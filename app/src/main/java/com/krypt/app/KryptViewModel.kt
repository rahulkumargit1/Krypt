package com.krypt.app

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.util.UUID

data class CallState(
    val isInCall: Boolean = false,
    val remoteUuid: String = "",
    val isIncoming: Boolean = false,
    val pendingOfferSdp: String = ""
)

data class UiState(
    val myUuid: String = "",
    val contacts: List<ContactEntity> = emptyList(),
    val messages: List<MessageEntity> = emptyList(),
    val statuses: List<StatusEntity> = emptyList(),
    val currentConversation: String = "",
    val isConnected: Boolean = false,
    val callState: CallState = CallState()
)

class KryptViewModel(private val context: Context) : ViewModel() {

    private val prefs: SharedPreferences = context.getSharedPreferences("krypt_prefs", Context.MODE_PRIVATE)
    private val db = KryptDatabase.getInstance(context)
    private val gson = Gson()

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var myUuid: String = ""
    private var myPublicKey: String = ""
    private var myPrivateKey: String = ""

    var webRTCManager: WebRTCManager? = null

    // Reassembling incoming file chunks: key = "fromUuid_fileName"
    private val incomingChunks = mutableMapOf<String, MutableMap<Int, ByteArray>>()
    private val incomingChunkMeta = mutableMapOf<String, EncryptedFileChunk>()

    init {
        initializeIdentity()
        observeContacts()
        observeStatuses()
        observeIncomingMessages()
    }

    // ─── Identity ─────────────────────────────────────────────────────────────

    private fun initializeIdentity() {
        myUuid = prefs.getString("uuid", null) ?: run {
            val newUuid = UUID.randomUUID().toString()
            prefs.edit().putString("uuid", newUuid).apply()
            newUuid
        }

        val storedPub = prefs.getString("public_key", null)
        val storedPriv = prefs.getString("private_key", null)

        if (storedPub != null && storedPriv != null) {
            myPublicKey = storedPub
            myPrivateKey = storedPriv
        } else {
            val (pub, priv) = CryptoEngine.generateRSAKeyPair()
            myPublicKey = pub
            myPrivateKey = priv
            prefs.edit()
                .putString("public_key", pub)
                .putString("private_key", priv)
                .apply()
        }

        _uiState.update { it.copy(myUuid = myUuid) }
        NetworkClient.connect(myUuid, myPublicKey)
    }

    // ─── Contacts ─────────────────────────────────────────────────────────────

    private fun observeContacts() {
        viewModelScope.launch {
            db.contactDao().getAllContacts().collect { contacts ->
                _uiState.update { it.copy(contacts = contacts) }
            }
        }
    }

    fun addContact(uuid: String, nickname: String) {
        viewModelScope.launch(Dispatchers.IO) {
            NetworkClient.requestPublicKey(uuid)
            // Public key will arrive via incoming message handler
            // Temporarily store with empty key until server responds
            val contact = ContactEntity(uuid = uuid, publicKey = "", nickname = nickname)
            db.contactDao().insertContact(contact)
        }
    }

    // ─── Messages ─────────────────────────────────────────────────────────────

    fun openConversation(contactUuid: String) {
        _uiState.update { it.copy(currentConversation = contactUuid) }
        viewModelScope.launch {
            db.messageDao().getMessages(contactUuid).collect { messages ->
                _uiState.update { it.copy(messages = messages) }
            }
        }
    }

    fun sendTextMessage(to: String, text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val contact = db.contactDao().getContact(to) ?: return@launch
            if (contact.publicKey.isEmpty()) return@launch

            val payload = CryptoEngine.encryptMessage(text, contact.publicKey)
            NetworkClient.sendEncryptedMessage(to, payload)

            val msg = MessageEntity(
                conversationId = to,
                fromUuid = myUuid,
                content = text,
                contentType = "text",
                isSent = true
            )
            db.messageDao().insertMessage(msg)
        }
    }

    fun sendFile(to: String, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val contact = db.contactDao().getContact(to) ?: return@launch
            if (contact.publicKey.isEmpty()) return@launch

            val contentResolver = context.contentResolver
            val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
            val fileName = uri.lastPathSegment ?: "file_${System.currentTimeMillis()}"
            val bytes = contentResolver.openInputStream(uri)?.readBytes() ?: return@launch

            val chunks = CryptoEngine.encryptFileChunks(bytes, fileName, mimeType, contact.publicKey)
            chunks.forEach { chunk ->
                NetworkClient.sendFileChunk(to, chunk)
                Thread.sleep(50) // small delay to avoid flooding
            }

            val contentType = if (mimeType.startsWith("image")) "image" else "file"
            val msg = MessageEntity(
                conversationId = to,
                fromUuid = myUuid,
                content = "[sent: $fileName]",
                contentType = contentType,
                isSent = true
            )
            db.messageDao().insertMessage(msg)
        }
    }

    // ─── Status ───────────────────────────────────────────────────────────────

    private fun observeStatuses() {
        viewModelScope.launch {
            db.statusDao().getActiveStatuses().collect { statuses ->
                _uiState.update { it.copy(statuses = statuses) }
            }
        }
        // Purge expired statuses periodically
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                db.statusDao().deleteExpiredStatuses()
                Thread.sleep(60_000)
            }
        }
    }

    fun postStatus(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            // Broadcast to all contacts (simplified: broadcast to server)
            // For demo, we store locally and broadcast with self-encryption
            val payload = CryptoEngine.encryptMessage(text, myPublicKey)
            NetworkClient.sendStatus(payload)
            val status = StatusEntity(fromUuid = myUuid, content = text)
            db.statusDao().insertStatus(status)
        }
    }

    // ─── Incoming Message Handler ──────────────────────────────────────────────

    private fun observeIncomingMessages() {
        viewModelScope.launch {
            NetworkClient.incomingMessages.collect { raw ->
                handleIncoming(raw)
            }
        }
    }

    private suspend fun handleIncoming(raw: String) = withContext(Dispatchers.IO) {
        val json = NetworkClient.parseMessage(raw) ?: return@withContext
        when (val type = json.get("type")?.asString ?: return@withContext) {

            "message" -> {
                val from = json.get("from")?.asString ?: return@withContext
                val payloadObj = json.getAsJsonObject("payload") ?: return@withContext
                val payload = EncryptedPayload(
                    encryptedData = payloadObj.get("encryptedData").asString,
                    iv = payloadObj.get("iv").asString,
                    encryptedKey = payloadObj.get("encryptedKey").asString
                )
                try {
                    val text = CryptoEngine.decryptMessage(payload, myPrivateKey)
                    val msg = MessageEntity(
                        conversationId = from,
                        fromUuid = from,
                        content = text,
                        contentType = "text",
                        isSent = false
                    )
                    db.messageDao().insertMessage(msg)
                } catch (e: Exception) { /* decryption failed */ }
            }

            "file_chunk" -> {
                val from = json.get("from")?.asString ?: return@withContext
                val payloadObj = json.getAsJsonObject("payload") ?: return@withContext
                val chunk = gson.fromJson(payloadObj, EncryptedFileChunk::class.java)
                val key = "${from}_${chunk.fileName}"

                val chunkMap = incomingChunks.getOrPut(key) { mutableMapOf() }
                try {
                    val decryptedBytes = CryptoEngine.decryptFileChunk(chunk, myPrivateKey)
                    chunkMap[chunk.chunkIndex] = decryptedBytes
                    incomingChunkMeta[key] = chunk

                    if (chunkMap.size == chunk.totalChunks) {
                        // Reassemble file
                        val fullFile = (0 until chunk.totalChunks)
                            .flatMap { chunkMap[it]!!.toList() }
                            .toByteArray()
                        val dir = context.getExternalFilesDir(null)
                        val file = java.io.File(dir, chunk.fileName)
                        file.writeBytes(fullFile)

                        val contentType = if (chunk.mimeType.startsWith("image")) "image" else "file"
                        val msg = MessageEntity(
                            conversationId = from,
                            fromUuid = from,
                            content = "[received: ${chunk.fileName}]",
                            contentType = contentType,
                            filePath = file.absolutePath,
                            isSent = false
                        )
                        db.messageDao().insertMessage(msg)
                        incomingChunks.remove(key)
                        incomingChunkMeta.remove(key)
                    }
                } catch (e: Exception) { /* decryption failed */ }
            }

            "public_key_response" -> {
                val targetUuid = json.get("target")?.asString ?: return@withContext
                val publicKey = json.get("public_key")?.asString ?: return@withContext
                val existing = db.contactDao().getContact(targetUuid)
                if (existing != null) {
                    db.contactDao().insertContact(existing.copy(publicKey = publicKey))
                } else {
                    db.contactDao().insertContact(ContactEntity(uuid = targetUuid, publicKey = publicKey))
                }
            }

            "status" -> {
                val from = json.get("from")?.asString ?: return@withContext
                val payloadObj = json.getAsJsonObject("payload") ?: return@withContext
                val contact = db.contactDao().getContact(from) ?: return@withContext
                if (contact.publicKey.isEmpty()) return@withContext
                // We can't decrypt statuses not meant for us; skip or server should broadcast differently
                // For demo: store raw (in real app, server would fan-out encrypted copies per contact)
            }

            "webrtc_offer" -> {
                val from = json.get("from")?.asString ?: return@withContext
                val sdp = json.get("sdp")?.asString ?: return@withContext
                _uiState.update {
                    it.copy(callState = CallState(
                        isInCall = true,
                        remoteUuid = from,
                        isIncoming = true,
                        pendingOfferSdp = sdp
                    ))
                }
            }

            "webrtc_answer" -> {
                val sdp = json.get("sdp")?.asString ?: return@withContext
                val answer = SessionDescription(SessionDescription.Type.ANSWER, sdp)
                webRTCManager?.setRemoteAnswer(answer)
            }

            "webrtc_ice" -> {
                val candidate = json.get("candidate")?.asString ?: return@withContext
                val sdpMid = json.get("sdpMid")?.asString
                val sdpMLineIndex = json.get("sdpMLineIndex")?.asInt ?: 0
                val ice = IceCandidate(sdpMid, sdpMLineIndex, candidate)
                webRTCManager?.addIceCandidate(ice)
            }
        }
    }

    // ─── WebRTC ───────────────────────────────────────────────────────────────

    fun startCall(remoteUuid: String) {
        _uiState.update { it.copy(callState = CallState(isInCall = true, remoteUuid = remoteUuid)) }
        webRTCManager = WebRTCManager(
            context = context,
            localUuid = myUuid,
            remoteUuid = remoteUuid,
            onIceCandidate = { candidate ->
                NetworkClient.sendICECandidate(remoteUuid, candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
            },
            onLocalSdp = { sdp ->
                if (sdp.type == SessionDescription.Type.OFFER) {
                    NetworkClient.sendWebRTCOffer(remoteUuid, sdp.description)
                } else {
                    NetworkClient.sendWebRTCAnswer(remoteUuid, sdp.description)
                }
            },
            onTrack = { /* handled in CallScreen via manager */ },
            onCallEnded = { endCall() }
        )
        webRTCManager?.createOffer()
    }

    fun acceptCall() {
        val callState = _uiState.value.callState
        val remoteUuid = callState.remoteUuid
        val offerSdp = callState.pendingOfferSdp

        webRTCManager = WebRTCManager(
            context = context,
            localUuid = myUuid,
            remoteUuid = remoteUuid,
            onIceCandidate = { candidate ->
                NetworkClient.sendICECandidate(remoteUuid, candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
            },
            onLocalSdp = { sdp ->
                NetworkClient.sendWebRTCAnswer(remoteUuid, sdp.description)
            },
            onTrack = { /* handled in CallScreen */ },
            onCallEnded = { endCall() }
        )
        val offer = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
        webRTCManager?.createAnswer(offer)
        _uiState.update { it.copy(callState = callState.copy(isIncoming = false, pendingOfferSdp = "")) }
    }

    fun endCall() {
        webRTCManager?.endCall()
        webRTCManager = null
        _uiState.update { it.copy(callState = CallState()) }
    }

    // ─── Factory ──────────────────────────────────────────────────────────────

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return KryptViewModel(context.applicationContext) as T
        }
    }
}

val Gson = Gson()

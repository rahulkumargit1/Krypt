package com.krypt.app

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

data class KryptMessage(
    val type: String,           // "message", "file_chunk", "key_exchange", "webrtc_offer",
                                // "webrtc_answer", "webrtc_ice", "status"
    val from: String,
    val to: String,
    val payload: Any? = null
)

object NetworkClient {

    // Change this to your deployed server URL
    const val SERVER_URL = "ws://YOUR_SERVER_IP:8000/ws"

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO)
    private var webSocket: WebSocket? = null
    private var myUuid: String = ""

    private val _incomingMessages = MutableSharedFlow<String>(extraBufferCapacity = 100)
    val incomingMessages: SharedFlow<String> = _incomingMessages

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    fun connect(uuid: String, publicKeyB64: String) {
        myUuid = uuid
        val request = Request.Builder().url(SERVER_URL).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Register UUID and public key with server
                val registration = JsonObject().apply {
                    addProperty("type", "register")
                    addProperty("uuid", uuid)
                    addProperty("public_key", publicKeyB64)
                }
                webSocket.send(gson.toJson(registration))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch {
                    _incomingMessages.emit(text)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Reconnect after 3 seconds
                Thread.sleep(3000)
                connect(uuid, publicKeyB64)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                // Reconnect
                Thread.sleep(3000)
                connect(uuid, publicKeyB64)
            }
        })
    }

    fun sendEncryptedMessage(to: String, payload: EncryptedPayload) {
        val msg = JsonObject().apply {
            addProperty("type", "message")
            addProperty("from", myUuid)
            addProperty("to", to)
            add("payload", gson.toJsonTree(payload))
        }
        webSocket?.send(gson.toJson(msg))
    }

    fun sendFileChunk(to: String, chunk: EncryptedFileChunk) {
        val msg = JsonObject().apply {
            addProperty("type", "file_chunk")
            addProperty("from", myUuid)
            addProperty("to", to)
            add("payload", gson.toJsonTree(chunk))
        }
        webSocket?.send(gson.toJson(msg))
    }

    fun sendWebRTCOffer(to: String, sdp: String) {
        val msg = JsonObject().apply {
            addProperty("type", "webrtc_offer")
            addProperty("from", myUuid)
            addProperty("to", to)
            addProperty("sdp", sdp)
        }
        webSocket?.send(gson.toJson(msg))
    }

    fun sendWebRTCAnswer(to: String, sdp: String) {
        val msg = JsonObject().apply {
            addProperty("type", "webrtc_answer")
            addProperty("from", myUuid)
            addProperty("to", to)
            addProperty("sdp", sdp)
        }
        webSocket?.send(gson.toJson(msg))
    }

    fun sendICECandidate(to: String, candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
        val msg = JsonObject().apply {
            addProperty("type", "webrtc_ice")
            addProperty("from", myUuid)
            addProperty("to", to)
            addProperty("candidate", candidate)
            addProperty("sdpMid", sdpMid ?: "")
            addProperty("sdpMLineIndex", sdpMLineIndex)
        }
        webSocket?.send(gson.toJson(msg))
    }

    fun sendStatus(encryptedPayload: EncryptedPayload) {
        val msg = JsonObject().apply {
            addProperty("type", "status")
            addProperty("from", myUuid)
            add("payload", gson.toJsonTree(encryptedPayload))
        }
        webSocket?.send(gson.toJson(msg))
    }

    fun requestPublicKey(targetUuid: String) {
        val msg = JsonObject().apply {
            addProperty("type", "get_public_key")
            addProperty("from", myUuid)
            addProperty("target", targetUuid)
        }
        webSocket?.send(gson.toJson(msg))
    }

    fun disconnect() {
        webSocket?.close(1000, "User disconnected")
    }

    fun parseMessage(raw: String): JsonObject? {
        return try {
            JsonParser.parseString(raw).asJsonObject
        } catch (e: Exception) {
            null
        }
    }
}

package com.krypt.app

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// ─── Contacts Screen (Home) ───────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    viewModel: KryptViewModel,
    onOpenChat: (String) -> Unit,
    onOpenStatus: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var newUuid by remember { mutableStateOf("") }
    var newNick by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Krypt", color = KryptText, fontSize = 22.sp) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = KryptBlack),
                actions = {
                    IconButton(onClick = onOpenStatus) {
                        Icon(Icons.Default.Circle, contentDescription = "Status", tint = KryptAccent)
                    }
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.PersonAdd, contentDescription = "Add", tint = KryptAccent)
                    }
                }
            )
        },
        containerColor = KryptBlack,
        floatingActionButton = {}
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // My UUID card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                colors = CardDefaults.cardColors(containerColor = KryptCard),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Your ID", color = KryptSubtext, fontSize = 11.sp)
                    Text(
                        text = uiState.myUuid,
                        color = KryptAccent,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            if (uiState.contacts.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No contacts yet.\nTap + to add someone.", color = KryptSubtext, textAlign = TextAlign.Center)
                }
            } else {
                LazyColumn {
                    items(uiState.contacts) { contact ->
                        ContactRow(contact = contact, onClick = { onOpenChat(contact.uuid) })
                        HorizontalDivider(color = KryptCard, thickness = 0.5.dp)
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            containerColor = KryptCard,
            title = { Text("Add Contact", color = KryptText) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newUuid,
                        onValueChange = { newUuid = it },
                        label = { Text("UUID", color = KryptSubtext) },
                        colors = kryptTextFieldColors(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = newNick,
                        onValueChange = { newNick = it },
                        label = { Text("Nickname (optional)", color = KryptSubtext) },
                        colors = kryptTextFieldColors(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newUuid.isNotBlank()) {
                        viewModel.addContact(newUuid.trim(), newNick.trim())
                        newUuid = ""; newNick = ""
                        showAddDialog = false
                    }
                }) { Text("Add", color = KryptAccent) }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancel", color = KryptSubtext)
                }
            }
        )
    }
}

@Composable
fun ContactRow(contact: ContactEntity, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(KryptAccent.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = (contact.nickname.firstOrNull() ?: contact.uuid.first()).uppercaseChar().toString(),
                    color = KryptAccent,
                    fontSize = 18.sp
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = contact.nickname.ifBlank { contact.uuid.take(16) + "…" },
                    color = KryptText,
                    fontSize = 15.sp
                )
                Text(
                    text = contact.uuid.take(24) + "…",
                    color = KryptSubtext,
                    fontSize = 11.sp
                )
            }
        }
    }
}

// ─── Chat Screen ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: KryptViewModel,
    contactUuid: String,
    onStartCall: () -> Unit,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    var showCamera by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val contact = uiState.contacts.find { it.uuid == contactUuid }
    val displayName = contact?.nickname?.ifBlank { contactUuid.take(12) } ?: contactUuid.take(12)

    // File picker
    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.sendFile(contactUuid, it) }
    }

    // Camera permission
    val cameraPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showCamera = true
    }

    // Scroll to bottom when messages change
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    if (showCamera) {
        CameraScreen(
            onPhotoTaken = { uri ->
                showCamera = false
                viewModel.sendFile(contactUuid, uri)
            },
            onDismiss = { showCamera = false }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(displayName, color = KryptText, fontSize = 16.sp)
                        Text("E2EE Encrypted", color = KryptAccent, fontSize = 10.sp)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = KryptText)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = KryptDark),
                actions = {
                    IconButton(onClick = onStartCall) {
                        Icon(Icons.Default.VideoCall, contentDescription = "Call", tint = KryptAccent)
                    }
                }
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .background(KryptDark)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .navigationBarsPadding()
                    .imePadding(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED) {
                        showCamera = true
                    } else {
                        cameraPermLauncher.launch(Manifest.permission.CAMERA)
                    }
                }) {
                    Icon(Icons.Default.CameraAlt, contentDescription = "Camera", tint = KryptSubtext)
                }
                IconButton(onClick = { fileLauncher.launch("*/*") }) {
                    Icon(Icons.Default.AttachFile, contentDescription = "Attach", tint = KryptSubtext)
                }
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("Message…", color = KryptSubtext) },
                    modifier = Modifier.weight(1f),
                    colors = kryptTextFieldColors(),
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (inputText.isNotBlank()) {
                            viewModel.sendTextMessage(contactUuid, inputText.trim())
                            inputText = ""
                        }
                    })
                )
                IconButton(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            viewModel.sendTextMessage(contactUuid, inputText.trim())
                            inputText = ""
                        }
                    }
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send", tint = KryptAccent)
                }
            }
        },
        containerColor = KryptBlack
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(uiState.messages) { msg ->
                MessageBubble(message = msg, myUuid = uiState.myUuid)
            }
        }
    }
}

@Composable
fun MessageBubble(message: MessageEntity, myUuid: String) {
    val isMine = message.fromUuid == myUuid
    val bubbleColor = if (isMine) KryptAccent.copy(alpha = 0.15f) else KryptCard
    val textColor = if (isMine) KryptAccent else KryptText
    val alignment = if (isMine) Arrangement.End else Arrangement.Start

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalArrangement = alignment
    ) {
        Box(
            modifier = Modifier
                .clip(
                    RoundedCornerShape(
                        topStart = 18.dp, topEnd = 18.dp,
                        bottomStart = if (isMine) 18.dp else 4.dp,
                        bottomEnd = if (isMine) 4.dp else 18.dp
                    )
                )
                .background(bubbleColor)
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .widthIn(max = 280.dp)
        ) {
            Column {
                if (message.contentType != "text") {
                    Icon(
                        imageVector = if (message.contentType == "image") Icons.Default.Image else Icons.Default.InsertDriveFile,
                        contentDescription = null,
                        tint = textColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.height(2.dp))
                }
                Text(text = message.content, color = textColor, fontSize = 14.sp)
                Text(
                    text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp)),
                    color = KryptSubtext,
                    fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }
    }
}

// ─── CameraX Screen ───────────────────────────────────────────────────────────

@Composable
fun CameraScreen(onPhotoTaken: (Uri) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val imageCapture = remember { ImageCapture.Builder().build() }
    val previewView = remember { PreviewView(context) }

    LaunchedEffect(Unit) {
        val cameraProvider = ProcessCameraProvider.getInstance(context).get()
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageCapture)
    }

    Box(modifier = Modifier.fillMaxSize().background(KryptBlack)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Cancel", tint = KryptText, modifier = Modifier.size(32.dp))
            }
            // Shutter button
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(KryptText),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    onClick = {
                        val outputFile = File(context.getExternalFilesDir(null), "krypt_${System.currentTimeMillis()}.jpg")
                        val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()
                        imageCapture.takePicture(
                            outputOptions,
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                                    onPhotoTaken(Uri.fromFile(outputFile))
                                }
                                override fun onError(exception: ImageCaptureException) { onDismiss() }
                            }
                        )
                    },
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxSize()
                ) {}
            }
        }
    }
}

// ─── Status Screen ────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(viewModel: KryptViewModel, onBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    var statusText by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Status", color = KryptText) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = KryptText)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = KryptDark)
            )
        },
        containerColor = KryptBlack
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = statusText,
                onValueChange = { statusText = it },
                label = { Text("Post a status (expires in 24h)", color = KryptSubtext) },
                modifier = Modifier.fillMaxWidth(),
                colors = kryptTextFieldColors(),
                maxLines = 4
            )
            Button(
                onClick = {
                    if (statusText.isNotBlank()) {
                        viewModel.postStatus(statusText.trim())
                        statusText = ""
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = KryptAccent)
            ) {
                Text("Post Status", color = KryptBlack)
            }

            Text("Active Statuses", color = KryptSubtext, fontSize = 12.sp)

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(uiState.statuses) { status ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = KryptCard),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(status.fromUuid.take(16) + "…", color = KryptSubtext, fontSize = 10.sp)
                            Text(status.content, color = KryptText, fontSize = 14.sp)
                            Text(
                                "Expires: ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(status.expiresAt))}",
                                color = KryptSubtext,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

@Composable
fun kryptTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = KryptText,
    unfocusedTextColor = KryptText,
    focusedBorderColor = KryptAccent,
    unfocusedBorderColor = KryptCard,
    cursorColor = KryptAccent,
    focusedContainerColor = KryptDark,
    unfocusedContainerColor = KryptDark
)

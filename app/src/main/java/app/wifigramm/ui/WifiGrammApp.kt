package app.wifigramm.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.wifigramm.CrashLog
import app.wifigramm.RoomStore
import app.wifigramm.net.Crypto
import app.wifigramm.net.Protocol
import app.wifigramm.net.RoomEngine
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The whole app: the room is set up once, afterwards the chat and the calls
 * are the only things on the screen.
 */
@Composable
fun WifiGrammApp() {
    val context = LocalContext.current
    var room by remember { mutableStateOf(RoomStore.load(context)) }
    var crashText by remember { mutableStateOf(CrashLog.read(context)) }

    val saved = room
    if (saved == null) {
        SetupScreen(
            onJoin = { code, password, nickname, direct, host ->
                val newRoom = RoomStore.Room(code, password, nickname, direct, host)
                RoomStore.save(context, newRoom)
                room = newRoom
            }
        )
    } else {
        ChatScreen(
            room = saved,
            host = saved.host,
            onLeave = {
                RoomStore.clear(context)
                room = null
            }
        )
    }

    crashText?.let { text ->
        AlertDialog(
            onDismissRequest = { crashText = null },
            title = { Text("Помилка додатка") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    Text(text = text, fontSize = 11.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    crashText = null
                    CrashLog.clear(context)
                }) {
                    Text("Зрозуміло")
                }
            },
            dismissButton = {
                TextButton(onClick = { shareCrash(context, text) }) {
                    Text("Поділитися")
                }
            }
        )
    }
}

/** Sends the text of the error to another app, so it can be sent to the author. */
private fun shareCrash(context: android.content.Context, text: String) {
    try {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "WIFIGramm: помилка")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "Надіслати помилку"))
    } catch (_: Exception) {
    }
}

/** The first screen: the code, the password and the name of the phone. */
@Composable
private fun SetupScreen(
    onJoin: (code: String, password: String, nickname: String, direct: Boolean, host: Boolean) -> Unit
) {
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var direct by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf(true) }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Filled.Wifi,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(52.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "WIFIGramm",
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "Чат, файли та телефонія в одній мережі або напряму через WiFi Direct.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
            )

            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it },
                label = { Text("Ваше ім'я") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text("Код кімнати") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Пароль кімнати") },
                placeholder = { Text("обов'язково, 4 символи або більше") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !direct,
                    onClick = { direct = false },
                    label = { Text("Мережа") }
                )
                FilterChip(
                    selected = direct,
                    onClick = { direct = true },
                    label = { Text("WiFi Direct") }
                )
            }

            if (direct) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = host,
                        onClick = { host = true },
                        label = { Text("Створити") }
                    )
                    FilterChip(
                        selected = !host,
                        onClick = { host = false },
                        label = { Text("Приєднатися") }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = {
                    onJoin(
                        code.trim().lowercase(),
                        password,
                        nickname.ifBlank { "Телефон" },
                        direct,
                        host
                    )
                },
                enabled = code.isNotBlank() && nickname.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Увійти до кімнати")
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Усі повідомлення, файли та звук зашифровані паролем кімнати. " +
                    "Телефони з однаковим кодом і паролем бачать одне одного.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** The chat of the room. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(
    room: RoomStore.Room,
    host: Boolean,
    onLeave: () -> Unit
) {
    val context = LocalContext.current
    val crashState = remember { MutableStateFlow<String?>(null) }
    val crashText by crashState.collectAsState()

    // Every error of the background work is written down, the app does not
    // close because of a packet from the network.
    val scope = remember {
        CoroutineScope(
            SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, error ->
                CrashLog.save(context, error)
                crashState.value = CrashLog.read(context)
            }
        )
    }
    DisposableEffect(scope) {
        onDispose { scope.cancel() }
    }

    val engine = remember(room, host) {
        RoomEngine(
            context = context,
            scope = scope,
            code = room.code,
            password = room.password,
            nickname = room.nickname,
            direct = room.direct,
            host = host
        )
    }

    DisposableEffect(engine) {
        onDispose { engine.stop() }
    }
    LaunchedEffect(engine) {
        engine.start()
    }

    val messages by engine.messages.collectAsStateWithLifecycle()
    val peers by engine.peers.collectAsStateWithLifecycle()
    val status by engine.status.collectAsStateWithLifecycle()
    val safety by engine.safetyCode.collectAsStateWithLifecycle()
    val activeCall by engine.activeCall.collectAsStateWithLifecycle()

    var input by remember { mutableStateOf("") }
    var incomingCall by remember { mutableStateOf<Protocol.CallSignal?>(null) }
    var callPeer by remember { mutableStateOf<RoomEngine.Peer?>(null) }
    var callSeconds by remember { mutableStateOf(0) }

    LaunchedEffect(engine) {
        engine.callSignal.collect { signal ->
            if (signal.action == "ring") incomingCall = signal
        }
    }

    LaunchedEffect(activeCall) {
        callSeconds = 0
        if (activeCall == null) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(1000)
            callSeconds++
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            engine.sendFile(uri, mime)
        }
    }

    // The microphone is needed only for the calls
    var micGranted by remember { mutableStateOf(false) }
    val micPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        micGranted = granted
        if (granted) {
            val peer = callPeer
            callPeer = null
            if (peer != null) engine.startCall(peer.id)
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Кімната ${room.code}",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = buildString {
                                append("${peers.size} телефонів")
                                if (safety.isNotEmpty()) append(" · код ${safety}")
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onLeave) {
                        Icon(
                            Icons.Filled.Logout,
                            contentDescription = "Покинути кімнату",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (status.isNotEmpty() && status != "connected") {
                Text(
                    text = statusText(status),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    val mine = message.mine
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
                    ) {
                        Column(
                            modifier = Modifier
                                .widthIn(max = 280.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (mine) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable(enabled = message.filePath != null) {
                                    message.filePath?.let { openFile(context, it) }
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            if (!mine) {
                                Text(
                                    text = message.nickname,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            if (message.text.isNotEmpty()) {
                                Text(
                                    text = message.text,
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            if (message.fileName != null) {
                                Text(
                                    text = "📎 " + message.fileName,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = timeFormat.format(Date(message.timestamp)),
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            activeCall?.let { call ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = call.nickname,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = String.format(
                                    Locale.getDefault(),
                                    "%02d:%02d",
                                    callSeconds / 60,
                                    callSeconds % 60
                                ),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        IconButton(onClick = { engine.endCall() }) {
                            Icon(
                                Icons.Filled.CallEnd,
                                contentDescription = "Завершити дзвінок",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { filePicker.launch(arrayOf("*/*")) },
                    enabled = peers.isNotEmpty()
                ) {
                    Icon(
                        Icons.Filled.AttachFile,
                        contentDescription = "Прикріпити файл",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(
                    onClick = {
                        val peer = peers.firstOrNull() ?: return@IconButton
                        if (micGranted) {
                            engine.startCall(peer.id)
                        } else {
                            callPeer = peer
                            micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    enabled = peers.isNotEmpty() && activeCall == null
                ) {
                    Icon(
                        Icons.Filled.Call,
                        contentDescription = "Дзвінок",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Повідомлення") },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                    maxLines = 4
                )
                IconButton(
                    onClick = {
                        val text = input
                        input = ""
                        engine.sendText(text)
                    },
                    enabled = input.isNotBlank()
                ) {
                    Icon(
                        Icons.Filled.Send,
                        contentDescription = "Надіслати",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }

    incomingCall?.let { signal ->
        AlertDialog(
            onDismissRequest = {
                incomingCall = null
                engine.declineCall(signal)
            },
            title = { Text(signal.nickname) },
            text = { Text("Вхідний дзвінок") },
            confirmButton = {
                TextButton(onClick = {
                    incomingCall = null
                    engine.acceptCall(signal)
                }) {
                    Text("Відповісти")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    incomingCall = null
                    engine.declineCall(signal)
                }) {
                    Text("Відхилити")
                }
            }
        )
    }

    crashText?.let { text ->
        AlertDialog(
            onDismissRequest = { crashState.value = null },
            title = { Text("Помилка додатка") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    Text(text = text, fontSize = 11.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    crashState.value = null
                    CrashLog.clear(context)
                }) {
                    Text("Зрозуміло")
                }
            },
            dismissButton = {
                TextButton(onClick = { shareCrash(context, text) }) {
                    Text("Поділитися")
                }
            }
        )
    }

    callPeer?.let { peer ->
        AlertDialog(
            onDismissRequest = { callPeer = null },
            title = { Text(peer.nickname) },
            text = { Text("Почати дзвінок?") },
            confirmButton = {
                TextButton(onClick = {
                    callPeer = null
                    if (micGranted) {
                        engine.startCall(peer.id)
                    } else {
                        micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                    }
                }) {
                    Text("Дзвонити")
                }
            },
            dismissButton = {
                TextButton(onClick = { callPeer = null }) {
                    Text("Скасувати")
                }
            }
        )
    }
}

/** The status of the room in a readable form. */
private fun statusText(status: String): String = when (status) {
    "starting" -> "Підготовка..."
    "idle" -> ""
    "stopped" -> "Кімната зупинена"
    "server-failed" -> "Не вдалося відкрити порт для зв'язку"
    "start-failed" -> "Не вдалося запустити кімнату"
    "wifi-direct-group-ready" -> "Група WiFi Direct готова"
    "wifi-direct-connecting" -> "Підключення до групи WiFi Direct..."
    "wifi-direct-channel-lost" -> "Зв'язок WiFi Direct втрачено"
    "wifi-direct-group" -> "Створення групи WiFi Direct..."
    "wifi-direct-search" -> "Пошук кімнати WiFi Direct..."
    "wifi-direct-failed" -> "WiFi Direct не вдався"
    "wifi-direct-unavailable" -> "WiFi Direct недоступний на цьому телефоні"
    else -> status
}

/** Opens a file that was received in the room. */
private fun openFile(context: android.content.Context, path: String) {
    try {
        val file = File(path)
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
    }
}

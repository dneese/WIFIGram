package app.wifigram.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.wifigram.App
import app.wifigram.CallLogStore
import app.wifigram.Intercom
import app.wifigram.IntercomService
import app.wifigram.ProfileStore
import kotlinx.coroutines.delay

/**
 * The whole app: the station, the chats and the calls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifigramApp() {
    val context = LocalContext.current
    val engine = remember { App.engine(context) }
    val peers by engine.peers.collectAsStateWithLifecycle()
    val conversations by engine.conversations.collectAsStateWithLifecycle()
    val call by engine.call.collectAsStateWithLifecycle()

    var tab by remember { mutableIntStateOf(1) }
    var chatPeer by remember { mutableStateOf<String?>(null) }
    var nameDialog by remember { mutableStateOf(ProfileStore.name(context).isBlank()) }
    var modeDialog by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf(CallLogStore.list(context)) }
    var missed by remember { mutableIntStateOf(CallLogStore.missedCount(context)) }

    // The station lives in the background, so a call is heard with a closed screen
    LaunchedEffect(Unit) {
        IntercomService.start(context)
        while (true) {
            delay(2000)
            log = CallLogStore.list(context)
            missed = CallLogStore.missedCount(context)
        }
    }

    val peerName = chatPeer?.let { id ->
        peers.firstOrNull { it.id == id }?.name ?: "Телефон"
    } ?: "Телефон"

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val target = chatPeer
        if (uri != null && target != null) {
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            engine.sendFile(target, uri, mime)
        }
    }

    BackHandler(enabled = chatPeer != null) {
        chatPeer = null
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = when (tab) {
                                    0 -> "Дзвінки"
                                    2 -> "Повідомлення"
                                    else -> "Інтерком"
                                },
                                fontWeight = FontWeight.SemiBold
                            )
                            if (tab == 1) {
                                Text(
                                    text = "${peers.count { it.online }} у мережі",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { modeDialog = true }) {
                            Icon(
                                Icons.Filled.WifiTethering,
                                contentDescription = "Режим зв'язку"
                            )
                        }
                        IconButton(onClick = { nameDialog = true }) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = "Ім'я"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = {
                            BadgedBox(badge = {
                                if (missed > 0) Badge { Text(missed.toString()) }
                            }) {
                                Icon(Icons.Filled.History, contentDescription = null)
                            }
                        },
                        label = { Text("Дзвінки") }
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = { Icon(Icons.Filled.Call, contentDescription = null) },
                        label = { Text("Інтерком") }
                    )
                    NavigationBarItem(
                        selected = tab == 2,
                        onClick = { tab = 2 },
                        icon = { Icon(Icons.Filled.Chat, contentDescription = null) },
                        label = { Text("Чат") }
                    )
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                when (tab) {
                    0 -> CallsScreen(
                        entries = log,
                        onClear = {
                            CallLogStore.clear(context)
                            log = emptyList()
                            missed = 0
                        }
                    )

                    1 -> StationScreen(
                        engine = engine,
                        onCall = { id -> engine.startCall(id) },
                        onChat = { id ->
                            chatPeer = id
                            tab = 2
                        }
                    )

                    else -> ChatsScreen(
                        conversations = conversations,
                        onOpen = { id -> chatPeer = id }
                    )
                }

                if (chatPeer != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        ChatScreen(
                            engine = engine,
                            peerId = chatPeer!!,
                            peerName = peerName,
                            onBack = { chatPeer = null },
                            onPickFile = { filePicker.launch(arrayOf("*/*")) }
                        )
                    }
                }
            }
        }

        // The call covers the whole screen, as in the phone app
        if (call != null) {
            Box(modifier = Modifier.fillMaxSize()) {
                CallScreen(
                    engine = engine,
                    onAnswer = { engine.answerCall() },
                    onReject = { engine.rejectCall() },
                    onCancel = { engine.cancelCall() },
                    onEnd = { engine.endCall() },
                    onToggleMute = { engine.toggleMute() },
                    onToggleSpeaker = { engine.toggleSpeaker() },
                    onAdd = { id -> engine.addParticipant(id) }
                )
            }
        }
    }

    if (nameDialog) {
        NameDialog(
            initial = ProfileStore.name(context),
            onSave = { name ->
                ProfileStore.saveName(context, name)
                engine.rename(name)
                nameDialog = false
            }
        )
    }

    if (modeDialog) {
        ModeDialog(
            direct = ProfileStore.direct(context),
            host = ProfileStore.host(context),
            onSave = { direct, host ->
                ProfileStore.saveMode(context, direct, host)
                engine.setMode(direct, host)
                modeDialog = false
            }
        )
    }
}

package app.wifigram.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.wifigram.App
import app.wifigram.CallLogStore
import app.wifigram.Intercom
import app.wifigram.IntercomService
import app.wifigram.ProfileStore
import app.wifigram.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    var settingsDialog by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf(CallLogStore.list(context)) }
    var missed by remember { mutableIntStateOf(CallLogStore.missedCount(context)) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val offlineText = stringResource(R.string.calls_offline)

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
        peers.firstOrNull { it.id == id }?.name
            ?: conversations.firstOrNull { it.peerId == id }?.peerName
            ?: stringResource(R.string.phone_fallback)
    } ?: stringResource(R.string.phone_fallback)

    var pendingCall by remember { mutableStateOf<String?>(null) }
    val micPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val target = pendingCall
        pendingCall = null
        if (granted && target != null) engine.startCall(target)
    }

    val startCall: (String) -> Unit = { id ->
        val allowed = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (allowed) {
            engine.startCall(id)
        } else {
            pendingCall = id
            micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val target = chatPeer
        if (uri != null && target != null) {
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            engine.sendFile(target, uri, mime)
        }
    }

    val callFromLog: (CallLogStore.Entry) -> Unit = { entry ->
        val id = entry.peerId
            ?: peers.firstOrNull { it.online && it.name == entry.name }?.id
        if (id != null && peers.any { it.id == id && it.online }) {
            startCall(id)
        } else {
            scope.launch { snackbar.showSnackbar(offlineText) }
        }
    }

    BackHandler(enabled = chatPeer != null) {
        chatPeer = null
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                if (chatPeer == null) TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = when (tab) {
                                    0 -> stringResource(R.string.tab_calls)
                                    2 -> stringResource(R.string.tab_messages)
                                    else -> stringResource(R.string.tab_intercom)
                                },
                                fontWeight = FontWeight.SemiBold
                            )
                            if (tab == 1) {
                                Text(
                                    text = stringResource(R.string.peers_online, peers.count { it.online }),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { settingsDialog = true }) {
                            Icon(
                                Icons.Filled.Settings,
                                contentDescription = stringResource(R.string.action_settings)
                            )
                        }
                        IconButton(onClick = { modeDialog = true }) {
                            Icon(
                                Icons.Filled.WifiTethering,
                                contentDescription = stringResource(R.string.action_mode)
                            )
                        }
                        IconButton(onClick = { nameDialog = true }) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = stringResource(R.string.action_name)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (chatPeer == null) NavigationBar {
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
                        label = { Text(stringResource(R.string.tab_calls)) }
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = { Icon(Icons.Filled.Call, contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_intercom)) }
                    )
                    NavigationBarItem(
                        selected = tab == 2,
                        onClick = { tab = 2 },
                        icon = { Icon(Icons.Filled.Chat, contentDescription = null) },
                        label = { Text(stringResource(R.string.station_chat)) }
                    )
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
            ) {
                when (tab) {
                    0 -> CallsScreen(
                        entries = log,
                        onClear = {
                            CallLogStore.clear(context)
                            log = emptyList()
                            missed = 0
                        },
                        onCall = callFromLog
                    )

                    1 -> StationScreen(
                        engine = engine,
                        onCall = { id -> startCall(id) },
                        onChat = { id ->
                            chatPeer = id
                            tab = 2
                        }
                    )

                    else -> ChatsScreen(
                        conversations = conversations,
                        peers = peers,
                        onOpen = { id -> chatPeer = id }
                    )
                }

                val openPeer = chatPeer
                if (openPeer != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        ChatScreen(
                            engine = engine,
                            peerId = openPeer,
                            peerName = peerName,
                            peer = peers.firstOrNull { it.id == openPeer },
                            onBack = { chatPeer = null },
                            onPickFile = { filePicker.launch(arrayOf("*/*")) },
                            onCall = {
                                if (peers.any { it.id == openPeer && it.online }) {
                                    startCall(openPeer)
                                } else {
                                    scope.launch { snackbar.showSnackbar(offlineText) }
                                }
                            }
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

    if (settingsDialog) {
        SettingsDialog(
            denoise = ProfileStore.denoise(context),
            onSaveDenoise = { value -> ProfileStore.saveDenoise(context, value) },
            language = ProfileStore.language(context),
            onSaveLanguage = { value ->
                ProfileStore.saveLanguage(context, value)
                (context as? Activity)?.recreate()
            },
            onDismiss = { settingsDialog = false }
        )
    }
}

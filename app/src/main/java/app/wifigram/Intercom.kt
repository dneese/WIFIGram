package app.wifigram

import android.content.Context
import android.net.Uri
import app.wifigram.call.VoiceClient
import app.wifigram.call.VoiceEngine
import app.wifigram.call.VoiceTrace
import app.wifigram.net.DirectBridge
import app.wifigram.net.Discovery
import app.wifigram.net.PeerLink
import app.wifigram.net.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * The station of the phone.
 *
 * Every phone that runs WIFIGram in the same network appears here. The
 * station keeps the connections to them, carries the chat, the files and the
 * voice, and takes care that a call survives a lost WiFi.
 */
class Intercom(
    private val context: Context,
    private val scope: CoroutineScope,
    name: String,
    direct: Boolean,
    host: Boolean
) {

    // ── the data of the station ──

    data class Peer(
        val id: String,
        val name: String,
        val status: String,
        val online: Boolean,
        val lastSeen: Long = System.currentTimeMillis()
    )

    data class ChatMessage(
        val id: String,
        val peerId: String,
        val peerName: String,
        val text: String,
        val timestamp: Long,
        val mine: Boolean,
        val fileName: String? = null,
        val filePath: String? = null,
        val fileSize: Long = 0L,
        /** The other phone has said that the message came. */
        val delivered: Boolean = false,
        /** There was no connection, so the message waits for a new one. */
        val queued: Boolean = false
    )

    data class Conversation(
        val peerId: String,
        val peerName: String,
        val last: ChatMessage?,
        val unread: Int
    )

    enum class Phase { DIALING, INCOMING, WAITING, ACTIVE }

    data class Call(
        val callId: String,
        val phase: Phase,
        val peerId: String,
        val peerName: String,
        val startedAt: Long,
        val muted: Boolean = false,
        val speaker: Boolean = false,
        val signalLost: Boolean = false,
        val quality: Quality = Quality()
    )

    /**
     * The state of the connection during a call: how far the words of the
     * other phone go, how steady they are and how many of them are lost.
     */
    data class Quality(
        val rttMs: Int = -1,
        val jitterMs: Int = 0,
        val lossPercent: Int = 0,
        val bars: Int = 0,
        val engine: String = VoiceEngine.NAME,
        val codec: String = "",
        /** The last thing that went wrong, in the words of the protocol. */
        val diagnostic: String = ""
    )

    companion object {
        const val STATUS_FREE = "free"
        const val STATUS_BUSY = "busy"
        const val STATUS_CALLING = "calling"

        /** How long a call waits for the phone that went out of reach. */
        const val SIGNAL_TIMEOUT = 5 * 60 * 1000L

        /** How long a phone may be quiet before it leaves the station. */
        const val PEER_TTL = 30_000L

        /** How often the hello over UDP goes to the phones of the network. */
        const val HELLO_EVERY_MS = 3000L

        /** How long the answer to a hello waits before it is repeated. */
        const val HELLO_REPLY_AFTER_MS = 1500L

        /** How long the way over UDP is trusted after the last hello. */
        const val UDP_ALIVE_MS = 10_000L

        /** How often the answer that the voice came goes back. */
        const val ACK_EVERY_MS = 200L

        /** How many pieces may wait for their answer before the way is doubted. */
        const val UDP_LOST_FRAMES = 40
    }

    private val peerId: String = run {
        val prefs = context.getSharedPreferences("wifigram_identity", Context.MODE_PRIVATE)
        prefs.getString("peer_id", null) ?: java.util.UUID.randomUUID().toString().take(12).also {
            prefs.edit().putString("peer_id", it).apply()
        }
    }

    private var localName: String = name
    private var directMode: Boolean = direct
    private var hostMode: Boolean = host

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private val _messages = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    val messages: StateFlow<Map<String, List<ChatMessage>>> = _messages.asStateFlow()

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _call = MutableStateFlow<Call?>(null)
    val call: StateFlow<Call?> = _call.asStateFlow()

    private val _missed = MutableStateFlow(0)
    val missed: StateFlow<Int> = _missed.asStateFlow()

    /** The full report of the voice of the call, as the user may send it. */
    private val _voiceReport = MutableStateFlow("")
    val voiceReport: StateFlow<String> = _voiceReport.asStateFlow()

    /** What the phone tells about the connection, for example the direct one. */
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private val links = ConcurrentHashMap<String, PeerLink>()

    private val connecting = ConcurrentHashMap<String, Boolean>()
    private val known = ConcurrentHashMap<String, Peer>()
    private val seenMessages = ConcurrentHashMap<String, Boolean>()
    private val incomingFiles = ConcurrentHashMap<Long, IncomingFile>()

    /** Until what moment every conversation was read. */
    private var lastRead: Map<String, Long> = emptyMap()

    private class IncomingFile(
        val meta: Protocol.FileMeta,
        val file: File,
        var received: Long = 0L
    )

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var discovery: Discovery? = null
    private var directBridge: DirectBridge? = null
    private var voiceClient: VoiceClient? = null
    private var webRtcActive = false
    private var rtcQuality = Quality()
    private var running = false
    private var callStartedAt = 0L
    private var signalLostAt = 0L

    init {
        // The words of the old conversations are still here after a restart.
        _messages.value = ChatStore.load(context)
        lastRead = ChatStore.lastRead(context)
        refreshConversations()
    }

    // ── the life of the station ──

    fun start() {
        if (running) return
        running = true

        scope.launch(Dispatchers.IO) {
            try {
                val server = ServerSocket(Protocol.TCP_PORT)
                serverSocket = server
                acceptJob = scope.launch(Dispatchers.IO) { acceptLoop(server) }
            } catch (_: Exception) {
            }
        }

        // The voice of the call travels by WebRTC, so it needs no second
        // door of its own.

        scope.launch(Dispatchers.IO) { startDiscovery(sendEnabled = true) }

        if (directMode) startDirect()
    }

    fun stop() {
        running = false
        try { discovery?.stop() } catch (_: Exception) { }
        discovery = null
        try { directBridge?.stop() } catch (_: Exception) { }
        directBridge = null
        try { serverSocket?.close() } catch (_: Exception) { }
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
        links.values.forEach { link ->
            try { link.close() } catch (_: Exception) { }
        }
        links.clear()
        connecting.clear()
        stopVoice()
        _call.value = null
    }

    fun rename(value: String) {
        localName = value.trim().ifEmpty { defaultName() }
    }

    /** Switches between the plain network and the direct connection. */
    fun setMode(direct: Boolean, host: Boolean) {
        if (directMode == direct && hostMode == host) return
        directMode = direct
        hostMode = host
        try { directBridge?.stop() } catch (_: Exception) { }
        directBridge = null
        if (direct) startDirect()
    }

    private fun startDirect() {
        val bridge = DirectBridge(
            context = context,
            scope = scope,
            onReady = { isOwner ->
                scope.launch(Dispatchers.IO) { startDiscovery(sendEnabled = isOwner) }
            },
            onOwnerAddress = { address ->
                scope.launch(Dispatchers.IO) { connectToAddress(address, Protocol.TCP_PORT) }
            },
            onStatus = { value -> _status.value = value }
        )
        directBridge = bridge
        if (hostMode) bridge.startOwner() else bridge.startClient()
    }

    private suspend fun startDiscovery(sendEnabled: Boolean) {
        val beacon = Discovery(
            context = context,
            scope = scope,
            peerId = peerId,
            name = localName,
            statusProvider = { currentStatus() },
            onPeerFound = { id, peerNickname, address, port, status ->
                onPeerFound(id, peerNickname, address, port, status)
            }
        )
        discovery = beacon
        beacon.start()
    }

    /** The state of the phone that the others see. */
    private fun currentStatus(): String {
        val call = _call.value ?: return STATUS_FREE
        return when (call.phase) {
            Phase.ACTIVE -> STATUS_BUSY
            else -> STATUS_CALLING
        }
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running) {
            val socket: Socket = try {
                server.accept()
            } catch (_: Exception) {
                break
            }
            try { socket.tcpNoDelay = true } catch (_: Exception) { }
            scope.launch(Dispatchers.IO) {
                // The name of the caller is unknown until the hello, so the
                // link waits for it.
                val link = PeerLink(socket, peerId, localName, capabilities(), "", scope) { source, event ->
                    handleEvent(source, event)
                }
                link.start(initiator = false)
            }
        }
    }

    private fun connectToAddress(host: String, port: Int) {
        scope.launch(Dispatchers.IO) {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), 3000)
                socket.tcpNoDelay = true
                val link = PeerLink(socket, peerId, localName, capabilities(), "", scope) { source, event ->
                    handleEvent(source, event)
                }
                link.start(initiator = true)
            } catch (_: Exception) {
            }
        }
    }

    /** The name of a phone that has no name of its own. */
    private fun defaultName(): String = Locales.apply(context).getString(R.string.phone_fallback)

    /** A phone has said that it is here. */
    private fun onPeerFound(
        remoteId: String,
        remoteName: String,
        address: InetAddress,
        port: Int,
        status: String
    ) {
        if (remoteId == peerId) return
        val existing = known[remoteId]
        known[remoteId] = Peer(
            id = remoteId,
            name = remoteName.ifBlank { existing?.name ?: defaultName() },
            status = status,
            online = true,
            lastSeen = System.currentTimeMillis()
        )
        refreshPeers()

        if (links.containsKey(remoteId)) return
        if (!connecting.containsKey(remoteId)) {
            connecting[remoteId] = true
            scope.launch(Dispatchers.IO) {
                try {
                    val socket = Socket()
                    socket.connect(InetSocketAddress(address, port), 3000)
                    socket.tcpNoDelay = true
                    val link = PeerLink(
                        socket, peerId, localName, capabilities(), remoteId, scope
                    ) { source, event ->
                        handleEvent(source, event)
                    }
                    link.start(initiator = true)
                } catch (_: Exception) {
                    connecting.remove(remoteId)
                }
            }
        }
    }

    // ── the events of the connections ──

    private fun handleEvent(link: PeerLink, event: PeerLink.Event) {
        when (event) {
            is PeerLink.Event.Hello -> {
                val remoteId = link.remotePeerId
                if (remoteId.isNotBlank()) {
                    links[remoteId] = link
                    connecting.remove(remoteId)
                    val old = known[remoteId]
                    known[remoteId] = Peer(
                        id = remoteId,
                        name = event.name.ifBlank { old?.name ?: defaultName() },
                        status = old?.status ?: STATUS_FREE,
                        online = true,
                        lastSeen = System.currentTimeMillis()
                    )
                    refreshPeers()
                    // The messages that waited for this phone go now.
                    resendQueued(remoteId, link)
                    // A call that was interrupted by the lost WiFi continues
                    // when the phones see each other again.
                    resumeCallIfNeeded(remoteId)
                }
            }

            is PeerLink.Event.Chat -> {
                val remoteId = link.remotePeerId
                if (remoteId.isBlank()) return
                // The answer says that the message came, so the sender shows
                // the second tick. A repeated message gets its answer too.
                link.send(Protocol.TYPE_MESSAGE_ACK, Protocol.encodeChatAck(event.line.id))
                if (seenMessages.putIfAbsent(event.line.id, true) != null) return
                val peerName = known[remoteId]?.name ?: defaultName()
                appendMessage(
                    ChatMessage(
                        id = event.line.id,
                        peerId = remoteId,
                        peerName = peerName,
                        text = event.line.text,
                        timestamp = event.line.timestamp,
                        mine = false
                    )
                )
            }

            is PeerLink.Event.ChatAck -> {
                val remoteId = link.remotePeerId
                if (remoteId.isBlank()) return
                val list = _messages.value[remoteId] ?: return
                var changed = false
                val updated = list.map { message ->
                    if (message.mine && message.id == event.id && !message.delivered) {
                        changed = true
                        message.copy(delivered = true, queued = false)
                    } else {
                        message
                    }
                }
                if (changed) {
                    _messages.value = _messages.value + (remoteId to updated)
                    saveChat()
                }
            }

            is PeerLink.Event.FileMeta -> {
                val remoteId = link.remotePeerId
                val meta = event.meta
                if (incomingFiles.containsKey(meta.transferId)) return
                val folder = File(context.filesDir, "received").apply { mkdirs() }
                val part = File(folder, Protocol.clean(meta.name) + ".part")
                try { part.delete() } catch (_: Exception) { }
                incomingFiles[meta.transferId] = IncomingFile(meta, part)
            }

            is PeerLink.Event.FileChunk -> {
                val chunk = event.chunk
                val info = incomingFiles[chunk.transferId]
                if (info == null) return
                try {
                    FileOutputStream(info.file, true).use { output ->
                        output.write(chunk.data)
                    }
                    info.received += chunk.data.size
                    if (info.received >= info.meta.size) {
                        incomingFiles.remove(chunk.transferId)
                        val target = uniqueFile(info.file.parentFile, Protocol.clean(info.meta.name))
                        info.file.renameTo(target)
                        appendMessage(
                            ChatMessage(
                                id = java.util.UUID.randomUUID().toString(),
                                peerId = link.remotePeerId,
                                peerName = known[link.remotePeerId]?.name ?: defaultName(),
                                text = "",
                                timestamp = System.currentTimeMillis(),
                                mine = false,
                                fileName = info.meta.name,
                                filePath = target.absolutePath,
                                fileSize = info.meta.size
                            )
                        )
                    }
                } catch (_: Exception) {
                    incomingFiles.remove(chunk.transferId)
                }
            }

            is PeerLink.Event.Call -> {
                handleCallSignal(link, event.signal)
            }

            is PeerLink.Event.Rtc -> {
                handleRtcSignal(link.remotePeerId, event.signal)
            }

            is PeerLink.Event.Closed -> {
                val remoteId = link.remotePeerId
                if (remoteId.isNotBlank() && links[remoteId] === link) {
                    links.remove(remoteId)
                    connecting.remove(remoteId)
                    val old = known[remoteId]
                    if (old != null) {
                        known[remoteId] = old.copy(online = false)
                    }
                    refreshPeers()
                    markSignalLost(remoteId)
                }
            }
        }
    }

    // ── the chat ──

    private fun appendMessage(message: ChatMessage) {
        if (!message.mine) {
            // The phone says that a message has come.
            MessageSound.play()
        }
        val list = (_messages.value[message.peerId] ?: emptyList()) + message
        _messages.value = _messages.value + (message.peerId to list.takeLast(200))
        saveChat()
        refreshConversations()
    }

    /** Keeps the conversation in the settings of the phone. */
    private fun saveChat() {
        ChatStore.save(context, _messages.value)
    }

    /** Sends the words that were waiting for the connection. */
    private fun resendQueued(remoteId: String, link: PeerLink) {
        val list = _messages.value[remoteId] ?: return
        if (list.none { it.queued }) return
        val updated = list.map { message ->
            if (message.queued && message.fileName == null) {
                link.send(
                    Protocol.TYPE_MESSAGE,
                    Protocol.encodeChat(
                        Protocol.ChatLine(message.id, message.timestamp, message.text)
                    )
                )
                message.copy(queued = false)
            } else {
                message
            }
        }
        _messages.value = _messages.value + (remoteId to updated)
        saveChat()
    }

    /** Says that the conversation with this phone has been read. */
    fun markRead(peerId: String) {
        val last = _messages.value[peerId]?.lastOrNull { !it.mine }?.timestamp ?: return
        if (last <= (lastRead[peerId] ?: 0L)) return
        val now = System.currentTimeMillis()
        lastRead = lastRead + (peerId to now)
        ChatStore.markRead(context, peerId, now)
        refreshConversations()
    }

    private fun refreshConversations() {
        val peers = known.values.associateBy { it.id }
        val list = _messages.value.mapNotNull { (peerId, messages) ->
            val last = messages.lastOrNull() ?: return@mapNotNull null
            Conversation(
                peerId = peerId,
                peerName = peers[peerId]?.name ?: last.peerName,
                last = last,
                unread = messages.count { !it.mine && it.timestamp > (lastRead[peerId] ?: 0L) }
            )
        }.sortedByDescending { it.last?.timestamp ?: 0L }
        _conversations.value = list
    }

    fun sendText(peerId: String, text: String) {
        if (text.isBlank()) return
        val line = Protocol.ChatLine(
            id = java.util.UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            text = text.trim()
        )
        val link = links[peerId]
        link?.send(Protocol.TYPE_MESSAGE, Protocol.encodeChat(line))
        appendMessage(
            ChatMessage(
                id = line.id,
                peerId = peerId,
                peerName = known[peerId]?.name ?: defaultName(),
                text = line.text,
                timestamp = line.timestamp,
                mine = true,
                queued = link == null
            )
        )
    }

    fun sendFile(peerId: String, uri: Uri, mime: String) {
        val link = links[peerId] ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val name = queryName(uri) ?: "file"
                val cache = File(context.cacheDir, "outgoing").apply { mkdirs() }
                val copy = File(cache, System.currentTimeMillis().toString() + "_" + Protocol.clean(name))
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(copy).use { output ->
                        input.copyTo(output, 64 * 1024)
                    }
                } ?: return@launch
                val transferId = System.currentTimeMillis()
                link.sendFile(copy, mime, transferId)
                appendMessage(
                    ChatMessage(
                        id = java.util.UUID.randomUUID().toString(),
                        peerId = peerId,
                        peerName = known[peerId]?.name ?: defaultName(),
                        text = "",
                        timestamp = System.currentTimeMillis(),
                        mine = true,
                        fileName = name,
                        filePath = copy.absolutePath,
                        fileSize = copy.length()
                    )
                )
            } catch (_: Exception) {
            }
        }
    }

    private fun queryName(uri: Uri): String? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun uniqueFile(folder: File?, name: String): File {
        val base = folder ?: context.filesDir
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val suffix = if (dot > 0) name.substring(dot) else ""
        var candidate = File(base, name)
        var counter = 1
        while (candidate.exists()) {
            candidate = File(base, "${stem}_$counter$suffix")
            counter++
        }
        return candidate
    }

    // ── the calls ──

    /** Calls one phone of the station. */
    fun startCall(peerId: String): Boolean {
        val peer = known[peerId] ?: return false
        val link = links[peerId] ?: return false
        if (!supportsCalls(link)) {
            _status.value = "old-peer"
            return false
        }
        val callId = java.util.UUID.randomUUID().toString().take(8)
        callStartedAt = System.currentTimeMillis()
        signalLostAt = 0L
        rtcQuality = Quality()
        _call.value = Call(
            callId = callId,
            phase = Phase.DIALING,
            peerId = peerId,
            peerName = peer.name,
            startedAt = callStartedAt
        )
        link.send(
            Protocol.TYPE_CALL,
            Protocol.encodeCall(
                Protocol.CallSignal(Protocol.CALL_INVITE, callId, localName, capabilities())
            )
        )
        return true
    }

    /** Answers the incoming call. */
    fun answerCall() {
        val call = _call.value ?: return
        if (call.phase != Phase.INCOMING) return
        val link = links[call.peerId] ?: return
        _call.value = call.copy(phase = Phase.ACTIVE, startedAt = System.currentTimeMillis())
        callStartedAt = System.currentTimeMillis()
        rtcQuality = Quality()
        link.send(
            Protocol.TYPE_CALL,
            Protocol.encodeCall(
                Protocol.CallSignal(Protocol.CALL_ACCEPT, call.callId, localName, capabilities())
            )
        )
        startVoice(initiator = false)
    }

    /** Rejects the incoming call. */
    fun rejectCall() {
        val call = _call.value ?: return
        val link = links[call.peerId]
        link?.send(
            Protocol.TYPE_CALL,
            Protocol.encodeCall(Protocol.CallSignal(Protocol.CALL_REJECT, call.callId, localName, ""))
        )
        endCallLocally(rememberMissed = call.phase == Phase.INCOMING)
    }

    /** The caller gives up before the answer. */
    fun cancelCall() {
        val call = _call.value ?: return
        if (call.phase == Phase.DIALING || call.phase == Phase.WAITING) {
            links[call.peerId]?.send(
                Protocol.TYPE_CALL,
                Protocol.encodeCall(Protocol.CallSignal(Protocol.CALL_CANCEL, call.callId, localName, ""))
            )
        }
        endCallLocally(rememberMissed = false)
    }

    /** Ends the call. */
    fun endCall() {
        val call = _call.value ?: return
        links.values.forEach { link ->
            link.send(
                Protocol.TYPE_CALL,
                Protocol.encodeCall(Protocol.CallSignal(Protocol.CALL_END, call.callId, localName, ""))
            )
        }
        endCallLocally(rememberMissed = false)
    }

    fun toggleMute() {
        val call = _call.value ?: return
        val muted = !call.muted
        _call.value = call.copy(muted = muted)
        try { voiceClient?.setMuted(muted) } catch (_: Exception) { }
    }

    fun toggleSpeaker() {
        val call = _call.value ?: return
        val speaker = !call.speaker
        _call.value = call.copy(speaker = speaker)
        try { voiceClient?.setSpeaker(speaker) } catch (_: Exception) { }
    }

    private fun handleCallSignal(link: PeerLink, signal: Protocol.CallSignal) {
        val remoteId = link.remotePeerId
        when (signal.action) {
            Protocol.CALL_INVITE -> {
                val marks = Protocol.decodeCapabilities(signal.extra)
                val current = _call.value
                if (!marks.contains(Protocol.CAP_PROTOCOL_V2) ||
                    !marks.contains(Protocol.CAP_WEBRTC)
                ) {
                    // The other phone speaks an older language: there is no
                    // way to talk to it.
                    link.send(
                        Protocol.TYPE_CALL,
                        Protocol.encodeCall(
                            Protocol.CallSignal(
                                Protocol.CALL_REJECT,
                                signal.callId,
                                localName,
                                VoiceEngine.REASON_OLD_PEER
                            )
                        )
                    )
                    return
                }
                if (current != null && current.phase == Phase.ACTIVE) {
                    link.send(
                        Protocol.TYPE_CALL,
                        Protocol.encodeCall(
                            Protocol.CallSignal(Protocol.CALL_BUSY, signal.callId, localName, "")
                        )
                    )
                } else if (current == null || current.phase == Phase.INCOMING) {
                    if (ProfileStore.settings(context).incomingSound) MessageSound.play()
                    _call.value = Call(
                        callId = signal.callId,
                        phase = Phase.INCOMING,
                        peerId = remoteId,
                        peerName = signal.name.ifBlank { known[remoteId]?.name ?: defaultName() },
                        startedAt = System.currentTimeMillis()
                    )
                }
            }

            Protocol.CALL_ACCEPT -> {
                val call = _call.value ?: return
                if (call.callId != signal.callId) return
                val marks = Protocol.decodeCapabilities(signal.extra)
                if (!marks.contains(Protocol.CAP_WEBRTC)) {
                    endCallLocally(rememberMissed = true)
                    return
                }
                _call.value = call.copy(phase = Phase.ACTIVE, startedAt = System.currentTimeMillis())
                callStartedAt = System.currentTimeMillis()
                startVoice(initiator = true)
            }

            Protocol.CALL_REJECT -> {
                val call = _call.value ?: return
                if (call.callId != signal.callId) return
                val reason = signal.extra.ifBlank { "rejected" }
                rtcQuality = rtcQuality.copy(diagnostic = reason)
                endCallLocally(rememberMissed = true)
            }

            Protocol.CALL_CANCEL -> {
                val call = _call.value ?: return
                if (call.callId != signal.callId) return
                endCallLocally(rememberMissed = true)
            }

            Protocol.CALL_BUSY -> {
                val call = _call.value ?: return
                if (call.callId != signal.callId) return
                _call.value = call.copy(phase = Phase.WAITING)
            }

            Protocol.CALL_ADD -> {
                // One conversation is between two phones only.
                link.send(
                    Protocol.TYPE_CALL,
                    Protocol.encodeCall(
                        Protocol.CallSignal(
                            Protocol.CALL_REJECT,
                            signal.callId,
                            localName,
                            "one-to-one"
                        )
                    )
                )
            }

            Protocol.CALL_END -> {
                val call = _call.value ?: return
                if (call.callId != signal.callId) return
                endCallLocally(rememberMissed = false)
            }

            Protocol.CALL_RESUME -> {
                val call = _call.value ?: return
                if (call.phase != Phase.ACTIVE) return
                _call.value = call.copy(signalLost = false)
                signalLostAt = 0L
                restartIce()
            }
        }
    }

    /** The call continues after the phones see each other again. */
    private fun resumeCallIfNeeded(remoteId: String) {
        val call = _call.value ?: return
        if (call.phase != Phase.ACTIVE) return
        if (call.peerId != remoteId) return
        links[remoteId]?.send(
            Protocol.TYPE_CALL,
            Protocol.encodeCall(
                Protocol.CallSignal(
                    Protocol.CALL_RESUME,
                    call.callId,
                    localName,
                    capabilities()
                )
            )
        )
        _call.value = call.copy(signalLost = false)
        signalLostAt = 0L
        restartIce()
    }

    /** The phone went out of reach, the call waits for it. */
    private fun markSignalLost(remoteId: String) {
        val call = _call.value ?: return
        if (call.phase != Phase.ACTIVE) return
        if (call.peerId != remoteId) return
        if (!call.signalLost) {
            signalLostAt = System.currentTimeMillis()
            _call.value = call.copy(signalLost = true)
        }
    }

    private fun endCallLocally(rememberMissed: Boolean) {
        val call = _call.value
        val outgoing = call?.phase == Phase.DIALING || call?.phase == Phase.WAITING
        val lost = rtcQuality.lossPercent
        stopVoice()
        _call.value = null
        signalLostAt = 0L
        if (rememberMissed && call != null) {
            CallLogStore.add(
                context, call.peerName, outgoing, missed = true,
                dropped = lost, concealed = 0,
                peerId = call.peerId
            )
            _missed.value = _missed.value + 1
        } else if (call != null && call.phase == Phase.ACTIVE) {
            CallLogStore.add(
                context, call.peerName, outgoing, missed = false,
                dropped = lost, concealed = 0,
                peerId = call.peerId
            )
        }
    }

    /** The marks of what this phone can do. */
    private fun capabilities(): String =
        Protocol.encodeCapabilities(
            Protocol.CAP_PROTOCOL_V2,
            Protocol.CAP_WEBRTC,
            Protocol.CAP_CALL
        )

    /** True when the other phone speaks the language of this build. */
    private fun supportsCalls(link: PeerLink): Boolean {
        val marks = Protocol.decodeCapabilities(link.remoteCapabilities)
        return marks.contains(Protocol.CAP_PROTOCOL_V2) &&
            marks.contains(Protocol.CAP_WEBRTC) &&
            marks.contains(Protocol.CAP_CALL)
    }

    /**
     * Begins the voice of the call.
     *
     * The engine lives in a process of its own, so a fall of the native part
     * of WebRTC leaves the app alive and the journal says where it stopped.
     */
    private fun startVoice(initiator: Boolean) {
        val call = _call.value ?: return
        VoiceTrace.start(context, call.callId)
        rtcQuality = Quality()
        if (!startWebRtcVoice(call, initiator)) {
            failVoice(call.callId, VoiceEngine.REASON_START_FAILED)
        }
    }

    private fun startWebRtcVoice(call: Call, initiator: Boolean): Boolean {
        if (webRtcActive) return true
        val client = VoiceClient(context, object : VoiceClient.Callbacks {
            override fun onSignal(kind: String, payload: String) {
                sendRtcSignal(call.callId, kind, payload)
            }

            override fun onState(state: String) {
                val value = state.uppercase()
                if (value == "FAILED") {
                    if (ProfileStore.tuning(context).iceRestartOnFail) {
                        VoiceTrace.stage(context, "ice-failed-restart")
                        restartIce()
                    } else {
                        failVoice(call.callId, VoiceEngine.REASON_ICE_FAILED)
                    }
                } else if (value == "CLOSED") {
                    failVoice(call.callId, VoiceEngine.REASON_ICE_CLOSED)
                }
            }

            override fun onStats(rttMs: Int, jitterMs: Int, lossPercent: Int, codec: String) {
                rtcQuality = rtcQuality.copy(
                    rttMs = rttMs,
                    jitterMs = jitterMs,
                    lossPercent = lossPercent,
                    codec = codec,
                    engine = VoiceEngine.NAME
                )
            }

            override fun onError(text: String) {
                failVoice(call.callId, VoiceEngine.REASON_SIGNAL_ERROR, text)
            }

            override fun onDead(text: String) {
                failVoice(call.callId, VoiceEngine.REASON_PROCESS_DIED, text)
            }

            override fun onReport(text: String) {
                _voiceReport.value = text
            }
        })
        voiceClient = client
        webRtcActive = true
        try {
            client.connect()
            client.start(call.callId, initiator, ProfileStore.tuning(context))
            return true
        } catch (error: Exception) {
            webRtcActive = false
            voiceClient = null
            VoiceTrace.error(context, error.message ?: error.javaClass.simpleName)
            return false
        }
    }

    /** The voice did not rise: the call shows why it went quiet. */
    private fun failVoice(callId: String, reason: String, detail: String = "") {
        val call = _call.value ?: return
        if (call.callId != callId) return
        if (!webRtcActive) return
        webRtcActive = false
        try { voiceClient?.release() } catch (_: Exception) { }
        voiceClient = null
        VoiceTrace.error(context, "$reason $detail")
        val step = VoiceTrace.lastStep(context)
        rtcQuality = rtcQuality.copy(
            engine = VoiceEngine.NAME,
            diagnostic = if (step.isBlank()) reason else "$reason ($step)"
        )
    }

    /** Asks the engine to look for the way again after the WiFi came back. */
    private fun restartIce() {
        try { voiceClient?.restartIce() } catch (_: Exception) { }
    }

    /** Asks the voice process for the full report of the call. */
    fun requestVoiceReport() {
        try { voiceClient?.requestReport() } catch (_: Exception) { }
    }

    /** Sends the offer, the answer or the candidate to the other phone. */
    private fun sendRtcSignal(callId: String, kind: String, payload: String) {
        val call = _call.value ?: return
        if (call.callId != callId) return
        val frame = Protocol.encodeRtcSignal(
            Protocol.RtcSignal(kind, callId, Protocol.VERSION, payload)
        )
        links[call.peerId]?.send(Protocol.TYPE_RTC, frame)
    }

    /** Takes the offer, the answer or the candidate from the other phone. */
    private fun handleRtcSignal(remoteId: String, signal: Protocol.RtcSignal) {
        val call = _call.value ?: return
        if (call.callId != signal.callId) return
        if (remoteId != call.peerId) return
        val client = voiceClient ?: return
        when (signal.kind) {
            Protocol.RTC_OFFER -> client.handleDescription("offer", signal.payload)
            Protocol.RTC_ANSWER -> client.handleDescription("answer", signal.payload)
            Protocol.RTC_CANDIDATE -> {
                val parts = signal.payload.split("\n")
                val candidate = parts.getOrNull(0).orEmpty()
                val mid = parts.getOrNull(1).orEmpty()
                val index = parts.getOrNull(2)?.toIntOrNull() ?: 0
                if (candidate.isNotBlank()) client.handleCandidate(mid, index, candidate)
            }
        }
    }

    private fun stopVoice() {
        webRtcActive = false
        _voiceReport.value = ""
        try { voiceClient?.release() } catch (_: Exception) { }
        voiceClient = null
    }

    // ── the timer of the lost signal ──

    init {
        scope.launch(Dispatchers.Default) {
            while (true) {
                delay(5000)
                cleanOldPeers()
            }
        }
        scope.launch(Dispatchers.Default) {
            while (true) {
                delay(2000)
                updateQuality()
            }
        }
        scope.launch(Dispatchers.Default) {
            while (true) {
                delay(1000)
                val call = _call.value ?: continue
                if (call.phase != Phase.ACTIVE || !call.signalLost) continue
                if (signalLostAt == 0L) continue
                if (System.currentTimeMillis() - signalLostAt > SIGNAL_TIMEOUT) {
                    endCallLocally(rememberMissed = true)
                }
            }
        }
    }

    /**
     * Reads the state of the connection and puts it into the call, so the
     * screen can show how good the way is.
     */
    private fun updateQuality() {
        val call = _call.value ?: return
        if (call.phase != Phase.ACTIVE) return
        val live = rtcQuality
        val bars = when {
            live.rttMs < 0 -> 1
            live.lossPercent > 10 || live.rttMs > 400 -> 1
            live.lossPercent > 4 || live.rttMs > 250 -> 2
            live.lossPercent > 1 || live.rttMs > 120 -> 3
            else -> 4
        }
        val quality = live.copy(
            bars = bars,
            engine = VoiceEngine.NAME
        )
        if (call.quality != quality) _call.value = call.copy(quality = quality)
    }

    /** A phone that has not said anything for a long time leaves the station. */
    private fun cleanOldPeers() {
        val call = _call.value
        val now = System.currentTimeMillis()
        var changed = false
        known.entries.toList().forEach { (id, peer) ->
            if (links.containsKey(id)) {
                // The connection is alive, the phone stays in the station
                known[id] = peer.copy(lastSeen = now)
                return@forEach
            }
            if (now - peer.lastSeen < PEER_TTL) return@forEach
            if (call != null && call.peerId == id) return@forEach
            known.remove(id)
            changed = true
        }
        if (changed) refreshPeers()
    }

    private fun refreshPeers() {
        val list = known.values.sortedBy { it.name.lowercase() }
        _peers.value = list
    }
}

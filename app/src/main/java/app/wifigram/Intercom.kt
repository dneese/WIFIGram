package app.wifigram

import android.content.Context
import android.net.Uri
import app.wifigram.call.CallSession
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
        val online: Boolean
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
        val fileSize: Long = 0L
    )

    data class Conversation(
        val peerId: String,
        val peerName: String,
        val last: ChatMessage?,
        val unread: Int
    )

    enum class Phase { DIALING, INCOMING, WAITING, ACTIVE }

    data class Participant(
        val id: String,
        val name: String,
        val connected: Boolean
    )

    data class Call(
        val callId: String,
        val phase: Phase,
        val peerId: String,
        val peerName: String,
        val participants: List<Participant>,
        val startedAt: Long,
        val muted: Boolean = false,
        val speaker: Boolean = false,
        val signalLost: Boolean = false
    )

    companion object {
        const val STATUS_FREE = "free"
        const val STATUS_BUSY = "busy"
        const val STATUS_CALLING = "calling"

        /** How long a call waits for the phone that went out of reach. */
        const val SIGNAL_TIMEOUT = 5 * 60 * 1000L
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

    /** The callers that wait on the line of the phone that is busy. */
    private val _waiting = MutableStateFlow<List<Participant>>(emptyList())
    val waiting: StateFlow<List<Participant>> = _waiting.asStateFlow()

    private val _missed = MutableStateFlow(0)
    val missed: StateFlow<Int> = _missed.asStateFlow()

    private val links = ConcurrentHashMap<String, PeerLink>()
    private val connecting = ConcurrentHashMap<String, Boolean>()
    private val known = ConcurrentHashMap<String, Peer>()
    private val seenMessages = ConcurrentHashMap<String, Boolean>()
    private val lastPlayed = ConcurrentHashMap<String, Int>()
    private val incomingFiles = ConcurrentHashMap<Long, IncomingFile>()

    private class IncomingFile(
        val meta: Protocol.FileMeta,
        val file: File,
        var received: Long = 0L
    )

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var discovery: Discovery? = null
    private var directBridge: DirectBridge? = null
    private var voice: CallSession? = null
    private var running = false
    private var callStartedAt = 0L
    private var signalLostAt = 0L
    private var audioSequence = 0

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
        localName = value.trim().ifEmpty { "Телефон" }
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
            onStatus = { }
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
                val link = PeerLink(socket, peerId, localName, "", scope) { source, event ->
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
                val link = PeerLink(socket, peerId, localName, "", scope) { source, event ->
                    handleEvent(source, event)
                }
                link.start(initiator = true)
            } catch (_: Exception) {
            }
        }
    }

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
            name = remoteName.ifBlank { existing?.name ?: "Телефон" },
            status = status,
            online = true
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
                    val link = PeerLink(socket, peerId, localName, remoteId, scope) { source, event ->
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
                        name = event.name.ifBlank { old?.name ?: "Телефон" },
                        status = old?.status ?: STATUS_FREE,
                        online = true
                    )
                    refreshPeers()
                    // A call that was interrupted by the lost WiFi continues
                    // when the phones see each other again.
                    resumeCallIfNeeded(remoteId)
                }
            }

            is PeerLink.Event.Chat -> {
                val remoteId = link.remotePeerId
                if (remoteId.isBlank()) return
                if (seenMessages.putIfAbsent(event.line.id, true) != null) return
                val peerName = known[remoteId]?.name ?: "Телефон"
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
                                peerName = known[link.remotePeerId]?.name ?: "Телефон",
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

            is PeerLink.Event.Audio -> {
                val audio = event.audio
                val call = _call.value
                if (call != null && audio.callId == call.callId && audio.members.contains(peerId)) {
                    // The sound is for this phone
                    val last = lastPlayed[link.remotePeerId]
                    if (last != audio.sequence) {
                        lastPlayed[link.remotePeerId] = audio.sequence
                        voice?.play(audio.data)
                    }
                } else if (call != null && audio.callId == call.callId) {
                    // This phone is only a relay, the sound goes further
                    links.values.forEach { other ->
                        if (other !== link) {
                            other.send(
                                Protocol.TYPE_AUDIO,
                                Protocol.encodeAudio(
                                    audio.callId,
                                    audio.members,
                                    audio.sequence,
                                    audio.data,
                                    false
                                )
                            )
                        }
                    }
                }
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
        val list = (_messages.value[message.peerId] ?: emptyList()) + message
        _messages.value = _messages.value + (message.peerId to list.takeLast(200))
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
                unread = if (last.mine) 0 else 1
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
        links[peerId]?.send(Protocol.TYPE_MESSAGE, Protocol.encodeChat(line))
        appendMessage(
            ChatMessage(
                id = line.id,
                peerId = peerId,
                peerName = known[peerId]?.name ?: "Телефон",
                text = line.text,
                timestamp = line.timestamp,
                mine = true
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
                        peerName = known[peerId]?.name ?: "Телефон",
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
        val callId = java.util.UUID.randomUUID().toString().take(8)
        callStartedAt = System.currentTimeMillis()
        signalLostAt = 0L
        _call.value = Call(
            callId = callId,
            phase = Phase.DIALING,
            peerId = peerId,
            peerName = peer.name,
            participants = listOf(Participant(peerId, peer.name, true)),
            startedAt = callStartedAt
        )
        link.send(
            Protocol.TYPE_CALL,
            Protocol.encodeCall(Protocol.CallSignal(Protocol.CALL_INVITE, callId, localName, ""))
        )
        startVoice()
        return true
    }

    /** Answers the incoming call. */
    fun answerCall() {
        val call = _call.value ?: return
        if (call.phase != Phase.INCOMING) return
        val link = links[call.peerId] ?: return
        _call.value = call.copy(phase = Phase.ACTIVE, startedAt = System.currentTimeMillis())
        callStartedAt = System.currentTimeMillis()
        link.send(
            Protocol.TYPE_CALL,
            Protocol.encodeCall(Protocol.CallSignal(Protocol.CALL_ACCEPT, call.callId, localName, ""))
        )
        startVoice()
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

    /** Adds the caller that waits on the line to the running conversation. */
    fun addParticipant(participantId: String) {
        val call = _call.value ?: return
        val waiting = _waiting.value.firstOrNull { it.id == participantId } ?: return
        val link = links[participantId] ?: return
        val participants = call.participants + Participant(waiting.id, waiting.name, true)
        _call.value = call.copy(participants = participants)
        _waiting.value = _waiting.value.filterNot { it.id == participantId }
        link.send(
            Protocol.TYPE_CALL,
            Protocol.encodeCall(
                Protocol.CallSignal(
                    Protocol.CALL_ADD,
                    call.callId,
                    localName,
                    call.participants.joinToString(",") { it.name }
                )
            )
        )
    }

    fun toggleMute() {
        val call = _call.value ?: return
        val muted = !call.muted
        _call.value = call.copy(muted = muted)
        voice?.setMuted(muted)
    }

    fun toggleSpeaker() {
        val call = _call.value ?: return
        val speaker = !call.speaker
        _call.value = call.copy(speaker = speaker)
        voice?.setSpeaker(speaker)
    }

    private fun handleCallSignal(link: PeerLink, signal: Protocol.CallSignal) {
        val remoteId = link.remotePeerId
        when (signal.action) {
            Protocol.CALL_INVITE -> {
                val current = _call.value
                if (current != null && current.phase == Phase.ACTIVE) {
                    // The line is busy, the caller waits on the line
                    link.send(
                        Protocol.TYPE_CALL,
                        Protocol.encodeCall(
                            Protocol.CallSignal(Protocol.CALL_BUSY, signal.callId, localName, "")
                        )
                    )
                    if (!_waiting.value.any { it.id == remoteId }) {
                        _waiting.value = _waiting.value + Participant(
                            remoteId,
                            signal.name.ifBlank { known[remoteId]?.name ?: "Телефон" },
                            true
                        )
                    }
                } else if (current == null || current.phase == Phase.INCOMING) {
                    _call.value = Call(
                        callId = signal.callId,
                        phase = Phase.INCOMING,
                        peerId = remoteId,
                        peerName = signal.name.ifBlank { known[remoteId]?.name ?: "Телефон" },
                        participants = listOf(
                            Participant(remoteId, signal.name.ifBlank { "Телефон" }, true)
                        ),
                        startedAt = System.currentTimeMillis()
                    )
                }
            }

            Protocol.CALL_ACCEPT -> {
                val call = _call.value ?: return
                if (call.callId != signal.callId) return
                _call.value = call.copy(phase = Phase.ACTIVE, startedAt = System.currentTimeMillis())
                callStartedAt = System.currentTimeMillis()
                startVoice()
            }

            Protocol.CALL_REJECT -> {
                val call = _call.value ?: return
                if (call.callId != signal.callId) return
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
                // The phone was added to the conversation of the other one
                val call = _call.value
                if (call == null || call.phase == Phase.WAITING) {
                    _call.value = Call(
                        callId = signal.callId,
                        phase = Phase.ACTIVE,
                        peerId = remoteId,
                        peerName = signal.name.ifBlank { known[remoteId]?.name ?: "Телефон" },
                        participants = listOf(
                            Participant(remoteId, signal.name.ifBlank { "Телефон" }, true)
                        ),
                        startedAt = System.currentTimeMillis()
                    )
                    callStartedAt = System.currentTimeMillis()
                    startVoice()
                    link.send(
                        Protocol.TYPE_CALL,
                        Protocol.encodeCall(
                            Protocol.CallSignal(Protocol.CALL_ACCEPT, signal.callId, localName, "")
                        )
                    )
                }
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
                voice?.setSignalLost(false)
            }
        }
    }

    /** The call continues after the phones see each other again. */
    private fun resumeCallIfNeeded(remoteId: String) {
        val call = _call.value ?: return
        if (call.phase != Phase.ACTIVE) return
        val inCall = call.participants.any { it.id == remoteId }
        if (!inCall) return
        links[remoteId]?.send(
            Protocol.TYPE_CALL,
            Protocol.encodeCall(
                Protocol.CallSignal(
                    Protocol.CALL_RESUME,
                    call.callId,
                    localName,
                    call.participants.joinToString(",") { it.name }
                )
            )
        )
        _call.value = call.copy(signalLost = false)
        signalLostAt = 0L
        voice?.setSignalLost(false)
    }

    /** The phone went out of reach, the call waits for it. */
    private fun markSignalLost(remoteId: String) {
        val call = _call.value ?: return
        if (call.phase != Phase.ACTIVE) return
        if (!call.participants.any { it.id == remoteId }) return
        if (!call.signalLost) {
            signalLostAt = System.currentTimeMillis()
            _call.value = call.copy(signalLost = true)
            voice?.setSignalLost(true)
        }
    }

    private fun endCallLocally(rememberMissed: Boolean) {
        val call = _call.value
        stopVoice()
        _call.value = null
        _waiting.value = emptyList()
        signalLostAt = 0L
        if (rememberMissed && call != null) {
            CallLogStore.add(context, call.peerName, false)
            _missed.value = _missed.value + 1
        }
    }

    private fun startVoice() {
        if (voice != null) return
        val session = CallSession(scope) { frame ->
            val call = _call.value ?: return@CallSession
            // The list of the phones of the conversation, the sender is in it
            val members = call.participants.map { it.id } + peerId
            val sequence = audioSequence++
            links.values.forEach { link ->
                val direct = members.contains(link.remotePeerId)
                link.send(
                    Protocol.TYPE_AUDIO,
                    Protocol.encodeAudio(call.callId, members, sequence, frame, direct)
                )
            }
        }
        voice = session
        session.start()
    }

    private fun stopVoice() {
        try { voice?.stop() } catch (_: Exception) { }
        voice = null
    }

    // ── the timer of the lost signal ──

    init {
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

    private fun refreshPeers() {
        val list = known.values.sortedBy { it.name.lowercase() }
        _peers.value = list
    }
}

package app.wifigramm.net

import android.content.Context
import android.net.Uri
import app.wifigramm.call.VoiceCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * The room of the phone.
 *
 * The phones find each other in the local network or over WiFi Direct, they
 * greet each other with a proof that only a phone with the password of the
 * room can give, and from that moment everything is encrypted.
 */
class RoomEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    val code: String,
    val password: String,
    val nickname: String,
    val direct: Boolean,
    val host: Boolean
) {

    data class Peer(
        val id: String,
        val nickname: String,
        val address: String
    )

    data class ChatMessage(
        val id: String,
        val from: String,
        val nickname: String,
        val text: String,
        val timestamp: Long,
        val mine: Boolean,
        val fileName: String? = null,
        val filePath: String? = null,
        val fileSize: Long = 0L
    )

    data class CallInfo(
        val callId: String,
        val nickname: String,
        val outgoing: Boolean,
        val startedAt: Long
    )

    // The key is derived when the room starts, so a slow phone does not
    // freeze the screen and a failure can be caught.
    private val roomKey: ByteArray by lazy { Crypto.deriveRoomKey(code, password) }

    private val peerId: String = run {
        val prefs = context.getSharedPreferences("wifigramm_identity", Context.MODE_PRIVATE)
        prefs.getString("peer_id", null) ?: Crypto.randomId().also {
            prefs.edit().putString("peer_id", it).apply()
        }
    }

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _status = MutableStateFlow("idle")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _safetyCode = MutableStateFlow("")
    val safetyCode: StateFlow<String> = _safetyCode.asStateFlow()

    private val _callSignal = MutableSharedFlow<Protocol.CallSignal>(extraBufferCapacity = 8)
    val callSignal: SharedFlow<Protocol.CallSignal> = _callSignal.asSharedFlow()

    private val _activeCall = MutableStateFlow<CallInfo?>(null)
    val activeCall: StateFlow<CallInfo?> = _activeCall.asStateFlow()

    private val _transferProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val transferProgress: StateFlow<Map<String, Int>> = _transferProgress.asStateFlow()

    private val links = ConcurrentHashMap<String, PeerLink>()
    private val connecting = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val seenMessages = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val incomingFiles = ConcurrentHashMap<Long, IncomingFile>()

    private class IncomingFile(
        val meta: Protocol.FileMeta,
        val file: File,
        val digest: MessageDigest,
        var received: Long = 0L
    )

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var discovery: Discovery? = null
    private var directBridge: DirectBridge? = null
    private var voiceCall: VoiceCall? = null
    private var running = false

    fun start() {
        if (running) return
        running = true
        _status.value = "starting"

        scope.launch(Dispatchers.IO) {
            try {
                roomKey
            } catch (_: Exception) {
                _status.value = "start-failed"
                return@launch
            }
            try {
                val server = ServerSocket(Protocol.TCP_PORT)
                serverSocket = server
                acceptJob = scope.launch(Dispatchers.IO) { acceptLoop(server) }
            } catch (_: Exception) {
                _status.value = "server-failed"
            }
        }

        try {
            if (direct) {
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
                if (host) bridge.startOwner() else bridge.startClient()
            } else {
                scope.launch(Dispatchers.IO) { startDiscovery(sendEnabled = true) }
            }
        } catch (_: Exception) {
            _status.value = "start-failed"
        }
    }

    private suspend fun startDiscovery(sendEnabled: Boolean) {
        val beacon = Discovery(
            context = context,
            scope = scope,
            roomKey = roomKey,
            peerId = peerId,
            nickname = nickname,
            sendEnabled = sendEnabled,
            onPeerFound = { id, peerNickname, address, port ->
                onPeerFound(id, peerNickname, address, port)
            }
        )
        discovery = beacon
        beacon.start()
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
        _activeCall.value = null
        _status.value = "stopped"
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running) {
            val socket: Socket = try {
                server.accept()
            } catch (_: Exception) {
                break
            }
            try {
                socket.tcpNoDelay = true
            } catch (_: Exception) {
            }
            val link = PeerLink(socket, roomKey, peerId, nickname, scope, ::handleEvent)
            link.start(initiator = false)
        }
    }

    /** Connects to a phone whose address is already known. */
    private fun connectToAddress(host: String, port: Int) {
        scope.launch(Dispatchers.IO) {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), 3000)
                socket.tcpNoDelay = true
                val link = PeerLink(socket, roomKey, peerId, nickname, scope, ::handleEvent)
                link.start(initiator = true)
            } catch (_: Exception) {
            }
        }
    }

    private fun onPeerFound(remoteId: String, remoteNickname: String, address: InetAddress, port: Int) {
        if (remoteId == peerId) return
        if (links.containsKey(remoteId)) return
        if (!direct && remoteId >= peerId) return
        if (!connecting.add(remoteId)) return

        scope.launch(Dispatchers.IO) {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(address, port), 3000)
                socket.tcpNoDelay = true
                val link = PeerLink(socket, roomKey, peerId, nickname, scope, ::handleEvent)
                link.start(initiator = true)
            } catch (_: Exception) {
                connecting.remove(remoteId)
            }
        }
    }

    private fun handleEvent(link: PeerLink, event: PeerLink.Event) {
        when (event) {
            is PeerLink.Event.Handshake -> {
                connecting.remove(event.peerId)
                val existing = links[event.peerId]
                if (existing != null && existing !== link) {
                    link.close()
                    return
                }
                links[event.peerId] = link
                _safetyCode.value = event.safetyCode
                refreshPeers()
                _status.value = "connected"
            }

            is PeerLink.Event.Message -> {
                if (!seenMessages.add(event.message.id)) return
                addMessage(
                    ChatMessage(
                        id = event.message.id,
                        from = event.message.from,
                        nickname = event.message.nickname,
                        text = event.message.text,
                        timestamp = event.message.timestamp,
                        mine = event.message.from == peerId,
                        fileName = event.message.fileName,
                        fileSize = event.message.fileSize
                    )
                )
                relay(link, Protocol.TYPE_MESSAGE, event.payload)
            }

            is PeerLink.Event.FileMeta -> {
                val meta = event.meta
                if (incomingFiles.containsKey(meta.transferId)) return
                val folder = File(context.filesDir, "received").apply { mkdirs() }
                val part = File(folder, meta.name + ".part")
                try { part.delete() } catch (_: Exception) { }
                incomingFiles[meta.transferId] = IncomingFile(
                    meta = meta,
                    file = part,
                    digest = MessageDigest.getInstance("SHA-256")
                )
                relay(link, Protocol.TYPE_FILE_META, event.payload)
            }

            is PeerLink.Event.FileChunk -> {
                val chunk = event.chunk
                val info = incomingFiles[chunk.transferId]
                if (info == null) {
                    relay(link, Protocol.TYPE_FILE_CHUNK, event.payload)
                    return
                }
                try {
                    FileOutputStream(info.file, true).use { output ->
                        output.write(chunk.data)
                    }
                    info.digest.update(chunk.data)
                    info.received += chunk.data.size
                    val percent = if (info.meta.size > 0) {
                        ((info.received * 100) / info.meta.size).toInt().coerceIn(0, 100)
                    } else {
                        100
                    }
                    _transferProgress.value = _transferProgress.value +
                        (info.meta.transferId.toString() to percent)
                    if (info.received >= info.meta.size) {
                        val hash = info.digest.digest()
                        if (hash.contentEquals(info.meta.hash)) {
                            val target = uniqueFile(info.file.parentFile, info.meta.name)
                            info.file.renameTo(target)
                            addMessage(
                                ChatMessage(
                                    id = Crypto.randomId(),
                                    from = info.meta.from,
                                    nickname = info.meta.nickname,
                                    text = "",
                                    timestamp = System.currentTimeMillis(),
                                    mine = info.meta.from == peerId,
                                    fileName = info.meta.name,
                                    filePath = target.absolutePath,
                                    fileSize = info.meta.size
                                )
                            )
                        }
                        incomingFiles.remove(chunk.transferId)
                        _transferProgress.value = _transferProgress.value -
                            (info.meta.transferId.toString())
                    }
                } catch (_: Exception) {
                    incomingFiles.remove(chunk.transferId)
                }
                relay(link, Protocol.TYPE_FILE_CHUNK, event.payload)
            }

            is PeerLink.Event.Call -> {
                handleCallSignal(link, event.signal)
            }

            is PeerLink.Event.Closed -> {
                links.remove(link.remotePeerId)
                refreshPeers()
            }
        }
    }

    /** Sends the frame to the other phones of the room. */
    private fun relay(source: PeerLink, type: Int, payload: ByteArray) {
        links.values.forEach { link ->
            if (link !== source) link.send(type, payload)
        }
    }

    private fun refreshPeers() {
        val list = links.values.map { link ->
            Peer(
                id = link.remotePeerId,
                nickname = link.remoteNickname,
                address = link.remoteAddress?.hostAddress ?: ""
            )
        }
        _peers.value = list
    }

    private fun addMessage(message: ChatMessage) {
        val updated = (_messages.value + message).takeLast(300)
        _messages.value = updated
    }

    // MARK: - sending

    /** Sends a text to the whole room. */
    fun sendText(text: String): Boolean {
        if (text.isBlank()) return false
        val message = Protocol.TextMessage(
            id = Crypto.randomId(),
            from = peerId,
            nickname = nickname,
            text = text.trim(),
            timestamp = System.currentTimeMillis()
        )
        val payload = Protocol.encodeMessage(message)
        links.values.forEach { it.send(Protocol.TYPE_MESSAGE, payload) }
        addMessage(
            ChatMessage(
                id = message.id,
                from = peerId,
                nickname = nickname,
                text = message.text,
                timestamp = message.timestamp,
                mine = true
            )
        )
        return true
    }

    /** Sends a file to the whole room. */
    fun sendFile(uri: Uri, mime: String): Boolean {
        val targets = links.values.toList()
        if (targets.isEmpty()) return false
        scope.launch(Dispatchers.IO) {
            try {
                val name = queryName(uri) ?: "file"
                val cache = File(context.cacheDir, "outgoing").apply { mkdirs() }
                val copy = File(cache, System.currentTimeMillis().toString() + "_" + name)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(copy).use { output ->
                        input.copyTo(output, 64 * 1024)
                    }
                } ?: return@launch
                val transferId = System.currentTimeMillis()
                targets.forEach { link -> link.sendFile(copy, mime, transferId) }
                addMessage(
                    ChatMessage(
                        id = Crypto.randomId(),
                        from = peerId,
                        nickname = nickname,
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
        return true
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

    // MARK: - calls

    /** Calls one phone of the room. */
    fun startCall(peerId: String): Boolean {
        val link = links[peerId] ?: return false
        val callId = Crypto.randomId()
        val key = Crypto.deriveKey(roomKey, callId.toByteArray(), "wifigramm-call-v1")
        val call = VoiceCall(key, null, 0, scope)
        if (!call.start()) return false
        voiceCall = call
        val peerName = _peers.value.firstOrNull { it.id == peerId }?.nickname ?: "peer"
        _activeCall.value = CallInfo(callId, peerName, true, System.currentTimeMillis())
        val signal = Protocol.CallSignal("ring", callId, this.peerId, nickname, call.localPort)
        val payload = Protocol.encodeCallSignal(signal)
        link.send(Protocol.TYPE_CALL, payload)
        return true
    }

    /** Answers an incoming call. */
    fun acceptCall(signal: Protocol.CallSignal): Boolean {
        val link = links[signal.from] ?: return false
        val key = Crypto.deriveKey(roomKey, signal.callId.toByteArray(), "wifigramm-call-v1")
        val call = VoiceCall(key, link.remoteAddress, signal.port, scope)
        if (!call.start()) return false
        voiceCall = call
        _activeCall.value = CallInfo(signal.callId, signal.nickname, false, System.currentTimeMillis())
        val answer = Protocol.CallSignal("accept", signal.callId, peerId, nickname, call.localPort)
        link.send(Protocol.TYPE_CALL, Protocol.encodeCallSignal(answer))
        return true
    }

    /** Rejects an incoming call. */
    fun declineCall(signal: Protocol.CallSignal) {
        val link = links[signal.from] ?: return
        val answer = Protocol.CallSignal("decline", signal.callId, peerId, nickname, 0)
        link.send(Protocol.TYPE_CALL, Protocol.encodeCallSignal(answer))
        stopVoice()
        _activeCall.value = null
    }

    /** Ends the running call. */
    fun endCall() {
        val call = _activeCall.value
        if (call != null) {
            links.values.forEach { link ->
                val signal = Protocol.CallSignal("end", call.callId, peerId, nickname, 0)
                link.send(Protocol.TYPE_CALL, Protocol.encodeCallSignal(signal))
            }
        }
        stopVoice()
        _activeCall.value = null
    }

    private fun stopVoice() {
        try { voiceCall?.stop() } catch (_: Exception) { }
        voiceCall = null
    }

    private fun handleCallSignal(link: PeerLink, signal: Protocol.CallSignal) {
        when (signal.action) {
            "ring" -> _callSignal.tryEmit(signal)
            "accept" -> {
                val call = voiceCall ?: return
                call.setDestination(link.remoteAddress, signal.port)
            }
            "decline", "end" -> {
                stopVoice()
                _activeCall.value = null
            }
        }
    }

}

package app.wifigramm.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.Socket

/**
 * The connection to one other phone of the room.
 *
 * The phone that opens the socket says hello first, the other one answers.
 * Both sides check the HMAC of the hello, so only a phone that knows the
 * password of the room gets in. After that the traffic is encrypted with a
 * session key that both sides derive on their own.
 */
class PeerLink(
    private val socket: Socket,
    private val roomKey: ByteArray,
    private val localPeerId: String,
    private val localNickname: String,
    private val scope: CoroutineScope,
    private val onEvent: (PeerLink, Event) -> Unit
) {

    sealed class Event {
        data class Handshake(
            val peerId: String,
            val nickname: String,
            val safetyCode: String
        ) : Event()

        data class Message(
            val message: Protocol.TextMessage,
            val payload: ByteArray
        ) : Event()

        data class FileMeta(
            val meta: Protocol.FileMeta,
            val payload: ByteArray
        ) : Event()

        data class FileChunk(
            val chunk: Protocol.FileChunk,
            val payload: ByteArray
        ) : Event()

        data class Call(
            val signal: Protocol.CallSignal,
            val payload: ByteArray
        ) : Event()
        object Closed : Event()
    }

    private val input = BufferedInputStream(socket.getInputStream())
    private val output = BufferedOutputStream(socket.getOutputStream())
    private var sessionKey: ByteArray = roomKey
    private var readJob: Job? = null

    @Volatile
    var remotePeerId: String = ""

    @Volatile
    var remoteNickname: String = ""

    /** The address of the other phone, it is needed for the voice. */
    val remoteAddress: java.net.InetAddress?
        get() = try {
            (socket.remoteSocketAddress as? java.net.InetSocketAddress)?.address
        } catch (_: Exception) {
            null
        }

    fun start(initiator: Boolean) {
        readJob = scope.launch(Dispatchers.IO) {
            try {
                handshake(initiator)
                readLoop()
            } catch (_: Exception) {
                onEvent(this@PeerLink, Event.Closed)
            }
        }
    }

    private fun handshake(initiator: Boolean) {
        val localNonce = Crypto.randomBytes(16)
        val hello = Protocol.encodeHello(
            Protocol.Hello(localPeerId, localNickname, localNonce)
        )

        val salt: ByteArray
        val remote: Protocol.Hello
        if (initiator) {
            // The one who opened the socket says hello first
            Protocol.writeFrame(output, roomKey, Protocol.TYPE_HELLO, hello)
            val frame = Protocol.readFrame(input, roomKey)
                ?: throw IllegalStateException("no hello")
            if (frame.type != Protocol.TYPE_HELLO_ACK) throw IllegalStateException("wrong hello")
            remote = Protocol.decodeHello(frame.payload) ?: throw IllegalStateException("bad hello")
            salt = Crypto.concat(localNonce, remote.nonce)
        } else {
            // The other one answers
            val frame = Protocol.readFrame(input, roomKey)
                ?: throw IllegalStateException("no hello")
            if (frame.type != Protocol.TYPE_HELLO) throw IllegalStateException("wrong hello")
            remote = Protocol.decodeHello(frame.payload) ?: throw IllegalStateException("bad hello")
            Protocol.writeFrame(output, roomKey, Protocol.TYPE_HELLO_ACK, hello)
            salt = Crypto.concat(remote.nonce, localNonce)
        }

        sessionKey = Crypto.deriveKey(roomKey, salt, "wifigramm-session-v1")

        remotePeerId = remote.peerId
        remoteNickname = remote.nickname
        val safety = Crypto.toHex(Crypto.sha256(sessionKey).copyOf(3))
        onEvent(this@PeerLink, Event.Handshake(remote.peerId, remote.nickname, safety))
    }

    private fun readLoop() {
        while (scope.isActive) {
            val frame = Protocol.readFrame(input, sessionKey) ?: break
            when (frame.type) {
                Protocol.TYPE_MESSAGE -> {
                    val message = Protocol.decodeMessage(frame.payload)
                    if (message != null) onEvent(this@PeerLink, Event.Message(message, frame.payload))
                }
                Protocol.TYPE_FILE_META -> {
                    val meta = Protocol.decodeFileMeta(frame.payload)
                    if (meta != null) onEvent(this@PeerLink, Event.FileMeta(meta, frame.payload))
                }
                Protocol.TYPE_FILE_CHUNK -> {
                    val chunk = Protocol.decodeFileChunk(frame.payload)
                    if (chunk != null) onEvent(this@PeerLink, Event.FileChunk(chunk, frame.payload))
                }
                Protocol.TYPE_CALL -> {
                    val signal = Protocol.decodeCallSignal(frame.payload)
                    if (signal != null) onEvent(this@PeerLink, Event.Call(signal, frame.payload))
                }
                Protocol.TYPE_BYE -> break
            }
        }
        onEvent(this@PeerLink, Event.Closed)
    }

    /** Sends one frame of the session. */
    fun send(type: Int, payload: ByteArray) {
        try {
            Protocol.writeFrame(output, sessionKey, type, payload)
        } catch (_: Exception) {
            onEvent(this@PeerLink, Event.Closed)
        }
    }

    /** Sends a file in pieces, the other side writes it into its own folder. */
    fun sendFile(file: File, mime: String, transferId: Long) {
        scope.launch(Dispatchers.IO) {
            try {
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                var size = 0L
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        digest.update(buffer, 0, read)
                        size += read
                    }
                }
                val meta = Protocol.FileMeta(
                    transferId = transferId,
                    from = localPeerId,
                    nickname = localNickname,
                    name = file.name,
                    size = size,
                    mime = mime,
                    hash = digest.digest()
                )
                Protocol.writeFrame(
                    output, sessionKey, Protocol.TYPE_FILE_META,
                    Protocol.encodeFileMeta(meta)
                )

                var index = 0
                FileInputStream(file).use { input ->
                    val buffer = ByteArray(Protocol.CHUNK_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        val chunk = Protocol.FileChunk(transferId, index, buffer.copyOf(read))
                        Protocol.writeFrame(
                            output, sessionKey, Protocol.TYPE_FILE_CHUNK,
                            Protocol.encodeFileChunk(chunk)
                        )
                        index++
                    }
                }
            } catch (_: Exception) {
                onEvent(this@PeerLink, Event.Closed)
            }
        }
    }

    fun close() {
        try { send(Protocol.TYPE_BYE, ByteArray(0)) } catch (_: Exception) { }
        try { socket.close() } catch (_: Exception) { }
        readJob?.cancel()
    }
}

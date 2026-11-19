package app.wifigram.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.net.Socket

/**
 * The connection to one other phone.
 *
 * The phone that opens the socket says hello first, the other one answers.
 * After that the chat, the files, the signals of the calls and the voice go
 * through this one connection. Every three seconds a ping goes through the
 * connection, so a dead WiFi is noticed in a few seconds and the call can be
 * restored when the phones see each other again.
 */
class PeerLink(
    private val socket: Socket,
    private val localPeerId: String,
    private val localName: String,
    private val localCapabilities: String,
    remotePeerId: String,
    private val scope: CoroutineScope,
    private val onEvent: (PeerLink, Event) -> Unit
) {

    sealed class Event {
        data class Hello(val peerId: String, val name: String) : Event()
        data class Chat(val line: Protocol.ChatLine) : Event()
        data class ChatAck(val id: String) : Event()
        data class FileMeta(val meta: Protocol.FileMeta) : Event()
        data class FileChunk(val chunk: Protocol.FileChunk) : Event()
        data class Call(val signal: Protocol.CallSignal) : Event()
        data class Rtc(val signal: Protocol.RtcSignal) : Event()
        object Closed : Event()
    }

    private companion object {
        const val SILENCE_TIMEOUT = 12_000L
    }

    private val input = BufferedInputStream(socket.getInputStream())
    private val writer = FrameWriter(socket.getOutputStream())
    private var readJob: Job? = null
    private var pingJob: Job? = null

    @Volatile
    private var lastWord: Long = System.currentTimeMillis()

    @Volatile
    var remotePeerId: String = remotePeerId

    @Volatile
    var remoteName: String = ""

    /** What the other phone can do, in the words of the protocol. */
    @Volatile
    var remoteCapabilities: String = ""

    /** The revision of the protocol that the other phone speaks. */
    @Volatile
    var remoteVersion: Int = 0

    /** How long the way to the other phone takes, in milliseconds. */
    @Volatile
    var rttMs: Int = -1

    /** The address of the other phone. */
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
        pingJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(3000)
                if (!isActive) break
                // A WiFi that went out of range keeps the socket open for a
                // long time, so the silence itself says that the phone is gone.
                if (System.currentTimeMillis() - lastWord > SILENCE_TIMEOUT) {
                    close()
                    break
                }
                try {
                    val stamp = ByteArray(8)
                    val now = System.currentTimeMillis()
                    for (index in 0 until 8) {
                        stamp[index] = (now ushr ((7 - index) * 8)).toByte()
                    }
                    writer.write(Protocol.TYPE_PING, stamp)
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    private fun handshake(initiator: Boolean) {
        val hello = Protocol.encodeHello(localPeerId, localName, localCapabilities)
        if (initiator) {
            writer.write(Protocol.TYPE_HELLO, hello)
            val frame = Protocol.readFrame(input) ?: throw IllegalStateException("no hello")
            if (frame.type != Protocol.TYPE_HELLO) throw IllegalStateException("wrong hello")
            val remote = Protocol.decodeHello(frame.payload) ?: throw IllegalStateException("bad hello")
            remotePeerId = remote.peerId
            remoteName = remote.name
            remoteVersion = remote.version
            remoteCapabilities = remote.capabilities
        } else {
            val frame = Protocol.readFrame(input) ?: throw IllegalStateException("no hello")
            if (frame.type != Protocol.TYPE_HELLO) throw IllegalStateException("wrong hello")
            val remote = Protocol.decodeHello(frame.payload) ?: throw IllegalStateException("bad hello")
            remotePeerId = remote.peerId
            remoteName = remote.name
            remoteVersion = remote.version
            remoteCapabilities = remote.capabilities
            writer.write(Protocol.TYPE_HELLO, hello)
        }
        onEvent(this@PeerLink, Event.Hello(remotePeerId, remoteName))
    }

    private fun readLoop() {
        while (scope.isActive) {
            val frame = Protocol.readFrame(input) ?: break
            lastWord = System.currentTimeMillis()
            when (frame.type) {
                Protocol.TYPE_PING -> {
                    // The stamp of the ping comes back in the pong, so the
                    // phone learns how long the way takes.
                    try { writer.write(Protocol.TYPE_PONG, frame.payload) } catch (_: Exception) { }
                }
                Protocol.TYPE_PONG -> {
                    if (frame.payload.size == 8) {
                        var sent = 0L
                        for (index in 0 until 8) {
                            sent = (sent shl 8) or (frame.payload[index].toLong() and 0xff)
                        }
                        val round = System.currentTimeMillis() - sent
                        if (round in 0..5000) rttMs = round.toInt()
                    }
                }
                Protocol.TYPE_MESSAGE -> {
                    val line = Protocol.decodeChat(frame.payload)
                    if (line != null) onEvent(this@PeerLink, Event.Chat(line))
                }
                Protocol.TYPE_MESSAGE_ACK -> {
                    val id = Protocol.decodeChatAck(frame.payload)
                    if (id != null) onEvent(this@PeerLink, Event.ChatAck(id))
                }
                Protocol.TYPE_FILE_META -> {
                    val meta = Protocol.decodeFileMeta(frame.payload)
                    if (meta != null) onEvent(this@PeerLink, Event.FileMeta(meta))
                }
                Protocol.TYPE_FILE_CHUNK -> {
                    val chunk = Protocol.decodeFileChunk(frame.payload)
                    if (chunk != null) onEvent(this@PeerLink, Event.FileChunk(chunk))
                }
                Protocol.TYPE_CALL -> {
                    val signal = Protocol.decodeCall(frame.payload)
                    if (signal != null) onEvent(this@PeerLink, Event.Call(signal))
                }
                Protocol.TYPE_RTC -> {
                    val signal = Protocol.decodeRtcSignal(frame.payload)
                    if (signal != null) onEvent(this@PeerLink, Event.Rtc(signal))
                }
                Protocol.TYPE_BYE -> break
            }
        }
        onEvent(this@PeerLink, Event.Closed)
    }

    /** Sends one frame of the connection. */
    fun send(type: Int, payload: ByteArray) {
        try {
            writer.write(type, payload)
        } catch (_: Exception) {
            onEvent(this@PeerLink, Event.Closed)
        }
    }

    /** Sends a file in pieces, the other side writes it into its own folder. */
    fun sendFile(file: File, mime: String, transferId: Long) {
        scope.launch(Dispatchers.IO) {
            try {
                var size = 0L
                file.inputStream().use { input ->
                    val buffer = ByteArray(Protocol.CHUNK_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        size += read
                    }
                }
                val meta = Protocol.FileMeta(
                    transferId = transferId,
                    from = localName,
                    name = file.name,
                    size = size,
                    mime = mime
                )
                writer.write(
                    Protocol.TYPE_FILE_META,
                    Protocol.encodeFileMeta(meta)
                )

                var index = 0
                FileInputStream(file).use { input ->
                    val buffer = ByteArray(Protocol.CHUNK_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        val piece = buffer.copyOf(read)
                        writer.write(
                            Protocol.TYPE_FILE_CHUNK,
                            Protocol.encodeFileChunk(transferId, index, piece)
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
        pingJob?.cancel()
        readJob?.cancel()
    }
}

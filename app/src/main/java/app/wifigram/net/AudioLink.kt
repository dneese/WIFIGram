package app.wifigram.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.net.Socket

/**
 * The connection that carries only the voice of a call.
 *
 * The chat, the files and the signals of the calls stay on the usual
 * connection, so a picture that travels never stops the voice. This second
 * socket speaks the same frames, but it hears nothing else. A ping every
 * three seconds keeps it awake while the microphones are off.
 */
class AudioLink(
    private val socket: Socket,
    private val localPeerId: String,
    private val localName: String,
    remotePeerId: String,
    private val scope: CoroutineScope,
    private val onEvent: (AudioLink, Event) -> Unit
) {

    sealed class Event {
        data class Ready(val peerId: String) : Event()
        data class Audio(val audio: Protocol.Audio) : Event()
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
        private set

    /** True when this phone opened the socket. */
    @Volatile
    var initiator: Boolean = false
        private set

    @Volatile
    var closed: Boolean = false
        private set

    /** The address of the other phone. */
    val remoteAddress: java.net.InetAddress?
        get() = try {
            (socket.remoteSocketAddress as? java.net.InetSocketAddress)?.address
        } catch (_: Exception) {
            null
        }

    fun start(initiates: Boolean) {
        initiator = initiates
        readJob = scope.launch(Dispatchers.IO) {
            try {
                handshake(initiates)
                readLoop()
            } catch (_: Exception) {
            } finally {
                if (!closed) {
                    closed = true
                    onEvent(this@AudioLink, Event.Closed)
                }
            }
        }
        pingJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(3000)
                if (!isActive) break
                if (System.currentTimeMillis() - lastWord > SILENCE_TIMEOUT) {
                    close()
                    break
                }
                try {
                    writer.write(Protocol.TYPE_PING, ByteArray(0))
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    private fun handshake(initiatorSide: Boolean) {
        val hello = Protocol.encodeHello(localPeerId, localName)
        if (initiatorSide) {
            writer.write(Protocol.TYPE_HELLO, hello)
            val frame = Protocol.readFrame(input) ?: throw IllegalStateException("no hello")
            if (frame.type != Protocol.TYPE_HELLO) throw IllegalStateException("wrong hello")
            val remote = Protocol.decodeHello(frame.payload) ?: throw IllegalStateException("bad hello")
            remotePeerId = remote.peerId
        } else {
            val frame = Protocol.readFrame(input) ?: throw IllegalStateException("no hello")
            if (frame.type != Protocol.TYPE_HELLO) throw IllegalStateException("wrong hello")
            val remote = Protocol.decodeHello(frame.payload) ?: throw IllegalStateException("bad hello")
            remotePeerId = remote.peerId
            writer.write(Protocol.TYPE_HELLO, hello)
        }
        onEvent(this@AudioLink, Event.Ready(remotePeerId))
    }

    private fun readLoop() {
        while (scope.isActive) {
            val frame = Protocol.readFrame(input) ?: break
            lastWord = System.currentTimeMillis()
            when (frame.type) {
                Protocol.TYPE_PING -> {
                    try { writer.write(Protocol.TYPE_PONG, ByteArray(0)) } catch (_: Exception) { }
                }
                Protocol.TYPE_PONG -> Unit
                Protocol.TYPE_AUDIO -> {
                    val audio = Protocol.decodeAudio(frame.payload)
                    if (audio != null) onEvent(this@AudioLink, Event.Audio(audio))
                }
                Protocol.TYPE_BYE -> break
            }
        }
    }

    /** Sends one piece of the voice. */
    fun sendAudio(payload: ByteArray) {
        if (closed) return
        try {
            writer.write(Protocol.TYPE_AUDIO, payload)
        } catch (_: Exception) {
            close()
        }
    }

    fun close() {
        if (closed) return
        closed = true
        try { writer.write(Protocol.TYPE_BYE, ByteArray(0)) } catch (_: Exception) { }
        try { socket.close() } catch (_: Exception) { }
        pingJob?.cancel()
        readJob?.cancel()
        onEvent(this@AudioLink, Event.Closed)
    }
}

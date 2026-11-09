package app.wifigram.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * The voice over UDP.
 *
 * A stream of TCP waits for a lost piece before it lets the next one through,
 * so one lost word holds the whole speech back until the piece comes again.
 * The voice does not need such an order: every piece stands on its own, and
 * the late one is thrown away. Over UDP a lost piece costs one word, not the
 * whole sentence.
 *
 * The packets are small, so they never get split by the network. The phones
 * say hello over UDP as well: when the hello of the other phone comes, the
 * way is open and the voice goes this way. If the hello never comes, the
 * voice stays on the stream as it was, so nothing is lost by trying.
 */
class AudioDatagram(
    private val localPeerId: String,
    private val scope: CoroutineScope,
    private val onHello: (peerId: String, address: InetAddress, ack: Int) -> Unit,
    private val onAudio: (audio: Protocol.Audio, peerId: String, address: InetAddress) -> Unit
) {

    companion object {
        /** The door of the voice, the same number on every phone. */
        const val UDP_PORT = 45314

        private val MAGIC = byteArrayOf(0x57, 0x46, 0x47, 0x41)
        internal const val VERSION = 1
        internal const val TYPE_HELLO = 1
        internal const val TYPE_AUDIO = 2
        private const val MAX_PACKET = 4096
    }

    private var socket: DatagramSocket? = null
    private var readJob: Job? = null

    /** True when the door is open. */
    @Volatile
    var open: Boolean = false
        private set

    /** Opens the door. */
    fun start() {
        if (socket != null) return
        val sock = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(UDP_PORT))
            }
        } catch (_: Exception) {
            null
        } ?: return
        socket = sock
        open = true
        readJob = scope.launch(Dispatchers.IO) { readLoop(sock) }
    }

    private fun readLoop(sock: DatagramSocket) {
        val buffer = ByteArray(MAX_PACKET)
        while (scope.isActive && !sock.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            var got = false
            try {
                sock.receive(packet)
                got = true
            } catch (_: Exception) {
                got = false
            }
            if (!got) {
                if (sock.isClosed) break
                continue
            }
            val size = packet.length
            val bytes = packet.data.copyOf(size)
            val from = packet.address ?: continue
            val parsed = parse(bytes) ?: continue
            when (parsed.type) {
                TYPE_HELLO -> onHello(parsed.senderId, from, parsed.ack)
                TYPE_AUDIO -> {
                    val audio = Protocol.decodeAudio(parsed.payload) ?: continue
                    onAudio(audio, parsed.senderId, from)
                }
            }
        }
    }

    /**
     * Says hello to a phone: the answer proves that the way is open.
     *
     * The number of the last piece of the voice that came from that phone
     * travels with the hello, so the phone that talks knows that the words
     * arrive and can fall back to the stream when they do not.
     */
    fun sendHello(address: InetAddress, ack: Int = 0): Boolean {
        val packet = build(TYPE_HELLO, ByteArray(0), ack) ?: return false
        return send(packet, address)
    }

    /** Sends one piece of the voice. */
    fun sendAudio(address: InetAddress, payload: ByteArray): Boolean {
        val packet = build(TYPE_AUDIO, payload) ?: return false
        return send(packet, address)
    }

    private fun send(packet: ByteArray, address: InetAddress): Boolean {
        val sock = socket ?: return false
        return try {
            sock.send(DatagramPacket(packet, packet.size, address, UDP_PORT))
            true
        } catch (_: Exception) {
            false
        }
    }

    fun stop() {
        open = false
        readJob?.cancel()
        readJob = null
        try { socket?.close() } catch (_: Exception) { }
        socket = null
    }

    // ── the shape of the packet ──

    internal class Packet(
        val type: Int,
        val senderId: String,
        val ack: Int,
        val payload: ByteArray
    )

    /**
     * The packet says:
     * [magic(4)][version(1)][type(1)][sender][addressee][ack(4)][body].
     * The names are short, so the whole packet stays small. The answer is
     * the number of the last piece of the voice that came: the phone that
     * talks knows then that the way is open in both directions.
     */
    internal fun build(type: Int, payload: ByteArray, ack: Int = 0): ByteArray? {
        val sender = localPeerId.toByteArray(Charsets.UTF_8)
        if (sender.size > 255) return null
        val out = ByteArray(4 + 1 + 1 + 1 + sender.size + 1 + 4 + payload.size)
        var at = 0
        MAGIC.copyInto(out, at)
        at += 4
        out[at++] = VERSION.toByte()
        out[at++] = type.toByte()
        out[at++] = sender.size.toByte()
        sender.copyInto(out, at)
        at += sender.size
        out[at++] = 0
        out[at++] = (ack shr 24).toByte()
        out[at++] = (ack shr 16).toByte()
        out[at++] = (ack shr 8).toByte()
        out[at++] = ack.toByte()
        payload.copyInto(out, at)
        return out
    }

    internal fun parse(bytes: ByteArray): Packet? {
        if (bytes.size < 8) return null
        for (index in 0 until 4) if (bytes[index] != MAGIC[index]) return null
        var at = 4
        val version = bytes[at++].toInt() and 0xFF
        if (version != VERSION) return null
        val type = bytes[at++].toInt() and 0xFF
        val senderLength = bytes[at++].toInt() and 0xFF
        if (bytes.size < at + senderLength + 1) return null
        val senderId = String(bytes, at, senderLength, Charsets.UTF_8)
        at += senderLength
        val addresseeLength = bytes[at++].toInt() and 0xFF
        if (bytes.size < at + addresseeLength + 4) return null
        at += addresseeLength
        val ack = ((bytes[at].toInt() and 0xFF) shl 24) or
            ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or
            (bytes[at + 3].toInt() and 0xFF)
        at += 4
        return Packet(type, senderId, ack, bytes.copyOfRange(at, bytes.size))
    }
}

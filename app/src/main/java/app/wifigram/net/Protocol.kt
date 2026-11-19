package app.wifigram.net

import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import app.wifigram.call.VoiceEngine

/**
 * The writer of the frames of one connection.
 *
 * The voice, the chat, the ping that keeps the connection awake and the
 * pieces of a file may all want to speak at the same moment. One lock keeps
 * their bytes from mixing: without it a ping that leaves in the middle of a
 * voice frame breaks the whole stream, and the other phone hears rubbish
 * until the connection dies.
 */
class FrameWriter(output: OutputStream) {

    private val lock = Any()
    private val stream = DataOutputStream(BufferedOutputStream(output))

    /** Writes one frame and sends it at once. */
    fun write(type: Int, payload: ByteArray) {
        synchronized(lock) {
            stream.writeByte(type)
            stream.writeInt(payload.size)
            stream.write(payload)
            stream.flush()
        }
    }
}

/**
 * The wire format of the intercom.
 *
 * Every frame is plain: [type(1)][length(4)][payload]. The text parts are
 * written as lines where the fields are separated by a vertical bar, so the
 * traffic can be read in any network sniffer.
 */
object Protocol {

    const val DISCOVERY_PORT = 45311
    const val TCP_PORT = 45312

    const val TYPE_HELLO = 1
    const val TYPE_BYE = 2
    const val TYPE_MESSAGE = 3
    const val TYPE_FILE_META = 4
    const val TYPE_FILE_CHUNK = 5
    const val TYPE_CALL = 6
    const val TYPE_PING = 8
    const val TYPE_PONG = 9
    const val TYPE_RTC = 11

    /** The answer that a chat message has come. */
    const val TYPE_MESSAGE_ACK = 10

    const val MAX_FRAME = 2 * 1024 * 1024
    const val CHUNK_SIZE = 64 * 1024

    const val BEACON_TAG = "WIFIGRAM1"
    const val MULTICAST_GROUP = "239.50.40.0"

    /** The revision of the protocol that this build speaks. */
    const val VERSION = 2

    /** The new voice of the call: WebRTC over the local network. */
    const val CAP_WEBRTC = VoiceEngine.CAPABILITY

    /** The mark that says: this phone speaks the revision of the protocol. */
    const val CAP_PROTOCOL_V2 = "v2"

    /** The mark that says: this phone can carry a call. */
    const val CAP_CALL = "call"

    data class Frame(val type: Int, val payload: ByteArray)

    /** Writes one frame into the stream. */
    fun writeFrame(output: OutputStream, type: Int, payload: ByteArray) {
        val out = DataOutputStream(output)
        out.writeByte(type)
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
    }

    /** Reads one frame, returns null at the end of the stream. */
    fun readFrame(input: InputStream): Frame? {
        return try {
            val data = DataInputStream(input)
            val type = data.readByte().toInt() and 0xFF
            val length = data.readInt()
            if (length < 0 || length > MAX_FRAME) {
                null
            } else {
                val payload = ByteArray(length)
                data.readFully(payload)
                Frame(type, payload)
            }
        } catch (_: EOFException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    // ── the text parts ──

    fun text(payload: ByteArray): String = String(payload, Charsets.UTF_8)

    fun bytes(value: String): ByteArray = value.toByteArray(Charsets.UTF_8)

    /** A field must not break the line, so the bars and the breaks are cut. */
    fun clean(value: String): String = value.replace("|", "/").replace("\n", " ").replace("\r", " ").trim()

    // ── the beacon of the presence ──

    /** The packet that says "I am here, my name is such, my port is such". */
    fun encodeBeacon(peerId: String, name: String, port: Int, status: String): ByteArray =
        bytes("$BEACON_TAG|$peerId|${clean(name)}|$port|$status")

    data class Beacon(
        val peerId: String,
        val name: String,
        val port: Int,
        val status: String
    )

    fun decodeBeacon(payload: ByteArray): Beacon? {
        val parts = text(payload).split("|")
        if (parts.size < 5 || parts[0] != BEACON_TAG) return null
        val port = parts[3].toIntOrNull() ?: return null
        return Beacon(parts[1], parts[2], port, parts[4])
    }

    // ── the chat ──

    data class ChatLine(
        val id: String,
        val timestamp: Long,
        val text: String
    )

    fun encodeChat(line: ChatLine): ByteArray =
        bytes("${clean(line.id)}|${line.timestamp}|${line.text.replace("|", "/")}")

    fun decodeChat(payload: ByteArray): ChatLine? {
        val parts = text(payload).split("|", limit = 3)
        if (parts.size < 3) return null
        val timestamp = parts[1].toLongOrNull() ?: return null
        return ChatLine(parts[0], timestamp, parts[2])
    }

    /** The answer to a chat line: only the id of the line that came. */
    fun encodeChatAck(id: String): ByteArray = bytes(clean(id))

    fun decodeChatAck(payload: ByteArray): String? =
        text(payload).trim().ifBlank { null }

    // ── the files ──

    data class FileMeta(
        val transferId: Long,
        val from: String,
        val name: String,
        val size: Long,
        val mime: String
    )

    fun encodeFileMeta(meta: FileMeta): ByteArray =
        bytes("${meta.transferId}|${clean(meta.from)}|${clean(meta.name)}|${meta.size}|${clean(meta.mime)}")

    fun decodeFileMeta(payload: ByteArray): FileMeta? {
        val parts = text(payload).split("|")
        if (parts.size < 5) return null
        val transferId = parts[0].toLongOrNull() ?: return null
        val size = parts[3].toLongOrNull() ?: return null
        return FileMeta(transferId, parts[1], parts[2], size, parts[4])
    }

    /** The piece of a file: [transferId(8)][index(4)][bytes]. */
    fun encodeFileChunk(transferId: Long, index: Int, data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val stream = DataOutputStream(out)
        stream.writeLong(transferId)
        stream.writeInt(index)
        stream.writeInt(data.size)
        stream.write(data)
        stream.flush()
        return out.toByteArray()
    }

    data class FileChunk(
        val transferId: Long,
        val index: Int,
        val data: ByteArray
    )

    fun decodeFileChunk(payload: ByteArray): FileChunk? {
        return try {
            val data = DataInputStream(payload.inputStream())
            val transferId = data.readLong()
            val index = data.readInt()
            val size = data.readInt()
            if (size < 0 || size > CHUNK_SIZE * 2) return null
            val bytes = ByteArray(size)
            data.readFully(bytes)
            FileChunk(transferId, index, bytes)
        } catch (_: Exception) {
            null
        }
    }

    // ── the WebRTC signaling ──

    const val RTC_OFFER = "offer"
    const val RTC_ANSWER = "answer"
    const val RTC_CANDIDATE = "candidate"

    data class RtcSignal(
        val kind: String,
        val callId: String,
        val version: Int,
        val payload: String
    )

    fun encodeRtcSignal(signal: RtcSignal): ByteArray =
        bytes(
            "${clean(signal.kind)}|${clean(signal.callId)}|${signal.version}|" +
                opaque(signal.payload)
        )

    fun decodeRtcSignal(payload: ByteArray): RtcSignal? {
        val parts = text(payload).split("|", limit = 4)
        if (parts.size != 4) return null
        val kind = parts[0]
        if (kind != RTC_OFFER && kind != RTC_ANSWER && kind != RTC_CANDIDATE) return null
        val callId = parts[1]
        if (callId.isBlank()) return null
        val version = parts[2].toIntOrNull() ?: return null
        if (version != VERSION) return null
        val body = plain(parts[3]) ?: return null
        return RtcSignal(kind, callId, version, body)
    }


    private fun opaque(value: String): String =
        java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun plain(value: String): String? = try {
        String(java.util.Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        null
    }

    // ── the calls ──

    const val CALL_INVITE = "INVITE"
    const val CALL_ACCEPT = "ACCEPT"
    const val CALL_REJECT = "REJECT"
    const val CALL_BUSY = "BUSY"
    const val CALL_ADD = "ADD"
    const val CALL_CANCEL = "CANCEL"
    const val CALL_END = "END"
    const val CALL_RESUME = "RESUME"

    data class CallSignal(
        val action: String,
        val callId: String,
        val name: String,
        val extra: String
    )

    fun encodeCall(signal: CallSignal): ByteArray =
        bytes("${signal.action}|${clean(signal.callId)}|${clean(signal.name)}|${clean(signal.extra)}")

    fun decodeCall(payload: ByteArray): CallSignal? {
        val parts = text(payload).split("|", limit = 4)
        if (parts.size < 2) return null
        return CallSignal(
            action = parts[0],
            callId = parts[1],
            name = if (parts.size > 2) parts[2] else "",
            extra = if (parts.size > 3) parts[3] else ""
        )
    }

    // ── the greeting ──

    /** The hello says who is calling and what the phone can do. */
    fun encodeHello(peerId: String, name: String, capabilities: String): ByteArray =
        bytes("${clean(peerId)}|${clean(name)}|$VERSION|${clean(capabilities)}")

    data class Hello(
        val peerId: String,
        val name: String,
        val version: Int,
        val capabilities: String
    )

    fun decodeHello(payload: ByteArray): Hello? {
        val parts = text(payload).split("|", limit = 4)
        if (parts.size < 2) return null
        return Hello(
            peerId = parts[0],
            name = parts[1],
            version = parts.getOrNull(2)?.toIntOrNull() ?: 0,
            capabilities = parts.getOrNull(3).orEmpty()
        )
    }

    /** The marks of what a phone can do, one word each. */
    fun encodeCapabilities(vararg marks: String): String =
        marks.joinToString(" ") { clean(it) }

    fun decodeCapabilities(value: String): Set<String> =
        value.split(' ', '\t', '\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
}

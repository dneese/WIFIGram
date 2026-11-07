package app.wifigram.net

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

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
    const val TYPE_AUDIO = 7
    const val TYPE_PING = 8
    const val TYPE_PONG = 9

    const val MAX_FRAME = 2 * 1024 * 1024
    const val CHUNK_SIZE = 64 * 1024

    const val BEACON_TAG = "WIFIGRAM1"
    const val MULTICAST_GROUP = "239.50.40.0"

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

    /** The hello says who is calling: [peer id][name]. */
    fun encodeHello(peerId: String, name: String): ByteArray =
        bytes("${clean(peerId)}|${clean(name)}")

    data class Hello(
        val peerId: String,
        val name: String
    )

    fun decodeHello(payload: ByteArray): Hello? {
        val parts = text(payload).split("|", limit = 2)
        if (parts.size < 2) return null
        return Hello(parts[0], parts[1])
    }

    // ── the voice ──

    const val AUDIO_DIRECT = 1
    const val AUDIO_RELAY = 2

    /**
     * The piece of the voice.
     *
     * [flags(1)][call id][members][sequence(4)][samples]. The members are the
     * phones of the conversation, so a phone that is only a relay knows that
     * the sound is not for it and passes it further.
     */
    fun encodeAudio(
        callId: String,
        members: List<String>,
        sequence: Int,
        data: ByteArray,
        direct: Boolean
    ): ByteArray {
        val id = bytes(callId)
        val out = java.io.ByteArrayOutputStream()
        val stream = DataOutputStream(out)
        stream.writeByte(if (direct) AUDIO_DIRECT else AUDIO_RELAY)
        stream.writeByte(id.size)
        stream.write(id)
        stream.writeByte(members.size)
        for (member in members) {
            val value = bytes(member)
            stream.writeByte(value.size)
            stream.write(value)
        }
        stream.writeInt(sequence)
        stream.write(data)
        stream.flush()
        return out.toByteArray()
    }

    data class Audio(
        val callId: String,
        val members: List<String>,
        val sequence: Int,
        val data: ByteArray,
        val direct: Boolean
    )

    fun decodeAudio(payload: ByteArray): Audio? {
        return try {
            if (payload.size < 6) return null
            val flags = payload[0].toInt() and 0xFF
            var offset = 1
            val idLength = payload[offset].toInt() and 0xFF
            offset++
            if (idLength <= 0 || payload.size < offset + idLength + 1) return null
            val callId = String(payload, offset, idLength, Charsets.UTF_8)
            offset += idLength
            val memberCount = payload[offset].toInt() and 0xFF
            offset++
            val members = mutableListOf<String>()
            repeat(memberCount) {
                if (payload.size <= offset) return null
                val length = payload[offset].toInt() and 0xFF
                offset++
                if (length <= 0 || payload.size < offset + length) return null
                members.add(String(payload, offset, length, Charsets.UTF_8))
                offset += length
            }
            val stream = DataInputStream(payload.inputStream())
            stream.skipBytes(offset)
            val sequence = stream.readInt()
            Audio(callId, members, sequence, payload.copyOfRange(offset + 4, payload.size), flags == AUDIO_DIRECT)
        } catch (_: Exception) {
            null
        }
    }
}

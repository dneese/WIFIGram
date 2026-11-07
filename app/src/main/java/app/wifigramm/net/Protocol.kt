package app.wifigramm.net

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * The wire format of the room.
 *
 * Every frame is encrypted: [type(1)][nonce(12)][length(4)][ciphertext].
 * The type is the associated data of the cipher, so a frame of one kind
 * cannot be played back as a frame of another kind.
 */
object Protocol {

    const val DISCOVERY_PORT = 45311
    const val TCP_PORT = 45312
    const val VOICE_PORT = 45313
    const val MULTICAST_GROUP = "239.50.40.0"

    const val TYPE_BEACON = 1
    const val TYPE_HELLO = 2
    const val TYPE_HELLO_ACK = 3
    const val TYPE_MESSAGE = 4
    const val TYPE_FILE_META = 5
    const val TYPE_FILE_CHUNK = 6
    const val TYPE_CALL = 7
    const val TYPE_BYE = 8

    const val MAX_FRAME = 2 * 1024 * 1024
    const val CHUNK_SIZE = 64 * 1024

    data class Frame(val type: Int, val payload: ByteArray)

    /** Writes one encrypted frame into the stream. */
    fun writeFrame(
        output: OutputStream,
        key: ByteArray,
        type: Int,
        payload: ByteArray,
        associatedData: ByteArray = ByteArray(0)
    ) {
        val nonce = Crypto.randomBytes(12)
        val typeBytes = byteArrayOf(type.toByte())
        val cipher = Crypto.encrypt(key, nonce, payload, Crypto.concat(typeBytes, associatedData))
        val out = DataOutputStream(output)
        out.writeInt(type)
        out.write(nonce)
        out.writeInt(cipher.size)
        out.write(cipher)
        out.flush()
    }

    /** Reads one encrypted frame, returns null at the end of the stream. */
    fun readFrame(input: InputStream, key: ByteArray): Frame? {
        return try {
            val data = DataInputStream(input)
            val type = data.readInt()
            if (type !in TYPE_BEACON..TYPE_BYE) return null
            val nonce = ByteArray(12)
            data.readFully(nonce)
            val length = data.readInt()
            if (length < 0 || length > MAX_FRAME) return null
            val cipher = ByteArray(length)
            data.readFully(cipher)
            val payload = Crypto.decrypt(key, nonce, cipher, byteArrayOf(type.toByte())) ?: return null
            Frame(type, payload)
        } catch (_: EOFException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    // ── the payload of the frames ──

    data class Beacon(
        val peerId: String,
        val nickname: String,
        val port: Int,
        val address: String,
        val timestamp: Long
    )

    fun encodeBeacon(beacon: Beacon): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.writeByte(1)
        data.writeUTF(beacon.peerId)
        data.writeUTF(beacon.nickname)
        data.writeInt(beacon.port)
        data.writeUTF(beacon.address)
        data.writeLong(beacon.timestamp)
        data.flush()
        return out.toByteArray()
    }

    fun decodeBeacon(payload: ByteArray): Beacon? = try {
        val data = DataInputStream(payload.inputStream())
        val version = data.readByte()
        if (version.toInt() != 1) null else {
            Beacon(
                peerId = data.readUTF(),
                nickname = data.readUTF(),
                port = data.readInt(),
                address = data.readUTF(),
                timestamp = data.readLong()
            )
        }
    } catch (_: Exception) {
        null
    }

    data class Hello(
        val peerId: String,
        val nickname: String,
        val nonce: ByteArray
    )

    fun encodeHello(hello: Hello): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.writeByte(1)
        data.writeUTF(hello.peerId)
        data.writeUTF(hello.nickname)
        data.writeInt(hello.nonce.size)
        data.write(hello.nonce)
        data.flush()
        return out.toByteArray()
    }

    fun decodeHello(payload: ByteArray): Hello? = try {
        val data = DataInputStream(payload.inputStream())
        val version = data.readByte()
        if (version.toInt() != 1) null else {
            val peerId = data.readUTF()
            val nickname = data.readUTF()
            val size = data.readInt()
            val nonce = ByteArray(size)
            data.readFully(nonce)
            Hello(peerId, nickname, nonce)
        }
    } catch (_: Exception) {
        null
    }

    data class TextMessage(
        val id: String,
        val from: String,
        val nickname: String,
        val text: String,
        val timestamp: Long,
        val fileName: String? = null,
        val fileSize: Long = 0L
    )

    fun encodeMessage(message: TextMessage): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.writeByte(1)
        data.writeUTF(message.id)
        data.writeUTF(message.from)
        data.writeUTF(message.nickname)
        data.writeUTF(message.text)
        data.writeLong(message.timestamp)
        data.writeBoolean(message.fileName != null)
        data.writeUTF(message.fileName ?: "")
        data.writeLong(message.fileSize)
        data.flush()
        return out.toByteArray()
    }

    fun decodeMessage(payload: ByteArray): TextMessage? = try {
        val data = DataInputStream(payload.inputStream())
        val version = data.readByte()
        if (version.toInt() != 1) null else {
            val id = data.readUTF()
            val from = data.readUTF()
            val nickname = data.readUTF()
            val text = data.readUTF()
            val timestamp = data.readLong()
            val hasFile = data.readBoolean()
            val fileName = data.readUTF()
            val fileSize = data.readLong()
            TextMessage(
                id = id,
                from = from,
                nickname = nickname,
                text = text,
                timestamp = timestamp,
                fileName = if (hasFile && fileName.isNotEmpty()) fileName else null,
                fileSize = fileSize
            )
        }
    } catch (_: Exception) {
        null
    }

    data class FileMeta(
        val transferId: Long,
        val from: String,
        val nickname: String,
        val name: String,
        val size: Long,
        val mime: String,
        val hash: ByteArray
    )

    fun encodeFileMeta(meta: FileMeta): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.writeByte(1)
        data.writeLong(meta.transferId)
        data.writeUTF(meta.from)
        data.writeUTF(meta.nickname)
        data.writeUTF(meta.name)
        data.writeLong(meta.size)
        data.writeUTF(meta.mime)
        data.writeInt(meta.hash.size)
        data.write(meta.hash)
        data.flush()
        return out.toByteArray()
    }

    fun decodeFileMeta(payload: ByteArray): FileMeta? = try {
        val data = DataInputStream(payload.inputStream())
        val version = data.readByte()
        if (version.toInt() != 1) null else {
            val transferId = data.readLong()
            val from = data.readUTF()
            val nickname = data.readUTF()
            val name = data.readUTF()
            val size = data.readLong()
            val mime = data.readUTF()
            val hashSize = data.readInt()
            val hash = ByteArray(hashSize)
            data.readFully(hash)
            FileMeta(transferId, from, nickname, name, size, mime, hash)
        }
    } catch (_: Exception) {
        null
    }

    data class FileChunk(
        val transferId: Long,
        val index: Int,
        val data: ByteArray
    )

    fun encodeFileChunk(chunk: FileChunk): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.writeByte(1)
        data.writeLong(chunk.transferId)
        data.writeInt(chunk.index)
        data.writeInt(chunk.data.size)
        data.write(chunk.data)
        data.flush()
        return out.toByteArray()
    }

    fun decodeFileChunk(payload: ByteArray): FileChunk? = try {
        val data = DataInputStream(payload.inputStream())
        val version = data.readByte()
        if (version.toInt() != 1) null else {
            val transferId = data.readLong()
            val index = data.readInt()
            val size = data.readInt()
            val bytes = ByteArray(size)
            data.readFully(bytes)
            FileChunk(transferId, index, bytes)
        }
    } catch (_: Exception) {
        null
    }

    data class CallSignal(
        val action: String,
        val callId: String,
        val from: String,
        val nickname: String,
        val port: Int
    )

    fun encodeCallSignal(signal: CallSignal): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.writeByte(1)
        data.writeUTF(signal.action)
        data.writeUTF(signal.callId)
        data.writeUTF(signal.from)
        data.writeUTF(signal.nickname)
        data.writeInt(signal.port)
        data.flush()
        return out.toByteArray()
    }

    fun decodeCallSignal(payload: ByteArray): CallSignal? = try {
        val data = DataInputStream(payload.inputStream())
        val version = data.readByte()
        if (version.toInt() != 1) null else {
            CallSignal(
                action = data.readUTF(),
                callId = data.readUTF(),
                from = data.readUTF(),
                nickname = data.readUTF(),
                port = data.readInt()
            )
        }
    } catch (_: Exception) {
        null
    }
}

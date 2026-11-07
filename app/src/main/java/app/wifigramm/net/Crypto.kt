package app.wifigramm.net

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Crypto of the room.
 *
 * Everything that leaves the phone is encrypted with ChaCha20-Poly1305, the
 * key comes from the code and the password of the room. The key is never sent
 * over the network: both phones derive it on their own.
 *
 * Beside the encryption there is a proof: every phone shows an HMAC of its
 * own id with the room key. Only a phone that knows the password can produce
 * it, so a stranger cannot join the room even if he hears the beacons.
 */
object Crypto {

    private val random = SecureRandom()

    fun randomBytes(count: Int): ByteArray = ByteArray(count).also { random.nextBytes(it) }

    fun randomId(): String = toHex(randomBytes(8))

    /** Derives the key of the room from its code and its password. */
    fun deriveRoomKey(code: String, password: String): ByteArray {
        val salt = sha256("wifigramm-room-v1:" + code.trim().lowercase())
        // Some phones refuse an empty password, the code is the secret then
        val secret = password.ifEmpty { code.trim().lowercase() }
        val spec = PBEKeySpec(
            secret.toCharArray(),
            salt,
            120_000,
            256
        )
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    /** A key for one session or one call, so the room key is used less. */
    fun deriveKey(roomKey: ByteArray, salt: ByteArray, info: String, length: Int = 32): ByteArray {
        val prk = hmac(roomKey, salt)
        val out = ByteArrayOutputStream()
        var block = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(block)
            mac.update(info.toByteArray())
            mac.update(counter.toByte())
            block = mac.doFinal()
            out.write(block)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    fun sha256(text: String): ByteArray = sha256(text.toByteArray(Charsets.UTF_8))

    /** Encrypts with ChaCha20-Poly1305, the nonce has to be 12 bytes. */
    fun encrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        associatedData: ByteArray = ByteArray(0)
    ): ByteArray {
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        if (associatedData.isNotEmpty()) cipher.updateAAD(associatedData)
        return cipher.doFinal(plaintext)
    }

    /** Decrypts a packet, returns null when it is broken or forged. */
    fun decrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        associatedData: ByteArray = ByteArray(0)
    ): ByteArray? = try {
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        if (associatedData.isNotEmpty()) cipher.updateAAD(associatedData)
        cipher.doFinal(ciphertext)
    } catch (_: Exception) {
        null
    }

    fun concat(vararg parts: ByteArray): ByteArray {
        var size = 0
        for (part in parts) size += part.size
        val result = ByteArray(size)
        var offset = 0
        for (part in parts) {
            System.arraycopy(part, 0, result, offset, part.size)
            offset += part.size
        }
        return result
    }

    fun toHex(bytes: ByteArray): String {
        val builder = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            builder.append("0123456789abcdef"[value ushr 4])
            builder.append("0123456789abcdef"[value and 0x0F])
        }
        return builder.toString()
    }

    fun fromHex(text: String): ByteArray {
        val clean = text.trim()
        val result = ByteArray(clean.length / 2)
        for (index in result.indices) {
            val high = Character.digit(clean[index * 2], 16)
            val low = Character.digit(clean[index * 2 + 1], 16)
            if (high < 0 || low < 0) return ByteArray(0)
            result[index] = ((high shl 4) or low).toByte()
        }
        return result
    }
}

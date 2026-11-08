package app.wifigram.call

/**
 * The Opus voice codec of the call.
 *
 * The sound travels as Opus instead of plain samples, so a frame of twenty
 * milliseconds is a few dozen bytes instead of six hundred and forty. The
 * phone falls back to plain samples when the library is missing, and the
 * other side hears the difference from the flag in the frame.
 */
object OpusCodec {

    const val SAMPLE_RATE = 16000
    const val FRAME_SAMPLES = 320
    const val FRAME_BYTES = FRAME_SAMPLES * 2

    /** The room for one encoded frame. */
    private const val MAX_PACKET = 1500

    val available: Boolean

    private var encoder: Long = 0
    private var decoder: Long = 0

    init {
        available = try {
            System.loadLibrary("wifigram-opus")
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** One piece of 20 ms of sound into its Opus form. */
    @Synchronized
    fun encode(pcm: ByteArray): ByteArray? {
        if (!available) return null
        if (encoder == 0L) encoder = nativeCreateEncoder()
        if (encoder == 0L) return null
        val out = ByteArray(MAX_PACKET)
        val size = nativeEncode(encoder, pcm, out)
        if (size <= 0) return null
        return out.copyOf(size)
    }

    /** One piece of Opus back into sound. */
    @Synchronized
    fun decode(data: ByteArray): ByteArray? {
        if (!available) return null
        if (decoder == 0L) decoder = nativeCreateDecoder()
        if (decoder == 0L) return null
        val out = ByteArray(FRAME_BYTES)
        val samples = nativeDecode(decoder, data, out)
        if (samples <= 0) return null
        return out.copyOf(samples * 2)
    }

    /** The codec forgets the talk of the finished call. */
    @Synchronized
    fun reset() {
        if (encoder != 0L) {
            nativeDestroyEncoder(encoder)
            encoder = 0
        }
        if (decoder != 0L) {
            nativeDestroyDecoder(decoder)
            decoder = 0
        }
    }

    private external fun nativeCreateEncoder(): Long
    private external fun nativeCreateDecoder(): Long
    private external fun nativeEncode(handle: Long, pcm: ByteArray, out: ByteArray): Int
    private external fun nativeDecode(handle: Long, data: ByteArray, out: ByteArray): Int
    private external fun nativeDestroyEncoder(handle: Long)
    private external fun nativeDestroyDecoder(handle: Long)
}

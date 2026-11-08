package app.wifigram.call

/**
 * The catcher of the constant noise: the hum, the fan, the machines, the
 * engine of a car. The voice goes through, the steady noise stays behind.
 *
 * The settings keep the toggle, and the phone works without the catcher
 * when the library is missing — the sound is then as it came.
 */
object Denoiser {

    const val SAMPLE_RATE = 16000
    const val FRAME_SAMPLES = 320

    /** One piece of 20 ms of sound. */
    const val FRAME_BYTES = FRAME_SAMPLES * 2

    /** True when the native catcher is present on the phone. */
    val available: Boolean

    init {
        available = try {
            System.loadLibrary("wifigram-opus")
            nativeInit()
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * One frame in place: exactly 20 ms of sound, so the catcher is fed as
     * it needs. Everything else goes back as it came.
     */
    @Synchronized
    fun process(frame: ByteArray) {
        if (!available) return
        if (frame.size != FRAME_BYTES) return
        try {
            nativeProcess(frame)
        } catch (_: Throwable) {
        }
    }

    private external fun nativeInit(): Boolean
    private external fun nativeProcess(frame: ByteArray)
}

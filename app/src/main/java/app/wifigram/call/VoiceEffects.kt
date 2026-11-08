package app.wifigram.call

import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor

/**
 * The helpers of the microphone that every phone already carries inside.
 *
 * The platform knows how to take the hum of the machines away and how to
 * take the echo of the speaker away. Both are attached to the recording of
 * the call, and the settings turn them off with one finger.
 *
 * The phone may not have them at all — then the sound simply goes through
 * as it came.
 */
class VoiceEffects(private val record: AudioRecord) {

    private val noise: NoiseSuppressor? = if (NoiseSuppressor.isAvailable()) {
        try {
            NoiseSuppressor.create(record.audioSessionId)
        } catch (_: Exception) {
            null
        }
    } else {
        null
    }

    private val echo: AcousticEchoCanceler? = if (AcousticEchoCanceler.isAvailable()) {
        try {
            AcousticEchoCanceler.create(record.audioSessionId)
        } catch (_: Exception) {
            null
        }
    } else {
        null
    }

    /** True when the phone can take the hum away. */
    val noiseAvailable: Boolean get() = noise != null

    /** True when the phone can take the echo of the speaker away. */
    val echoAvailable: Boolean get() = echo != null

    /** The hum of the fan and the machines gets quieter. */
    fun setNoise(enabled: Boolean) {
        try {
            noise?.setEnabled(enabled)
        } catch (_: Exception) {
        }
    }

    /**
     * The voice of the speaker comes back into the microphone — this takes
     * it away, so the other phone does not hear you twice.
     */
    fun setEcho(enabled: Boolean) {
        try {
            echo?.setEnabled(enabled)
        } catch (_: Exception) {
        }
    }

    fun release() {
        try { noise?.release() } catch (_: Exception) { }
        try { echo?.release() } catch (_: Exception) { }
    }
}

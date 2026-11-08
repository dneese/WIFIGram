package app.wifigram

import android.media.AudioManager
import android.media.ToneGenerator

/**
 * The short tone that says "a message has come".
 *
 * The phone plays it by itself, without the notifications of Android: the
 * message is noticed right away, even when the screen is locked.
 */
object MessageSound {

    /** Two tones in a row are one tone. */
    private const val GAP = 400L

    /** The loudness of the tone, in percent. */
    private const val VOLUME = 80

    /** How long the tone sounds, in milliseconds. */
    private const val TONE_LENGTH = 150

    private var last = 0L

    fun play() {
        val now = System.currentTimeMillis()
        if (now - last < GAP) return
        last = now
        Thread {
            try {
                val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, VOLUME)
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, TONE_LENGTH)
                Thread.sleep(TONE_LENGTH.toLong() + 150L)
                tone.release()
            } catch (_: Exception) {
            }
        }.start()
    }
}

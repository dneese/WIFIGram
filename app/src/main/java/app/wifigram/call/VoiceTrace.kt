package app.wifigram.call

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The journal of the voice process.
 *
 * The native part of WebRTC may fall without a word, so every step of the
 * beginning of a call is written down before it happens. The last line of
 * the journal says where the phone stopped.
 */
object VoiceTrace {

    private const val FILE_NAME = "voice_trace.txt"
    private const val MAX_LINES = 200

    /** Begins the journal of one call. */
    fun start(context: Context, callId: String) {
        write(context, "call $callId begin")
    }

    /** Writes one step of the way. */
    fun stage(context: Context, tag: String) {
        write(context, tag)
    }

    /** Writes what went wrong. */
    fun error(context: Context, text: String) {
        write(context, "error $text")
    }

    /** The whole journal, the newest step last. */
    fun read(context: Context): String? {
        return try {
            val file = file(context)
            if (file.exists() && file.length() > 0) file.readText() else null
        } catch (_: Exception) {
            null
        }
    }

    /** The last step of the journal. */
    fun lastStep(context: Context): String {
        val text = read(context) ?: return ""
        return text.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.isNotBlank() }
            .orEmpty()
    }

    fun clear(context: Context) {
        try { file(context).delete() } catch (_: Exception) { }
    }

    private fun write(context: Context, line: String) {
        try {
            val file = file(context)
            val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            val old = if (file.exists()) file.readText() else ""
            val kept = (old + "$stamp $line\n").lines().takeLast(MAX_LINES).joinToString("\n")
            file.writeText(kept)
        } catch (_: Exception) {
        }
    }

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)
}

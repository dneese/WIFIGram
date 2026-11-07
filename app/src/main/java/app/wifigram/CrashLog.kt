package app.wifigram

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last error of the app.
 *
 * The phone shows the text of the crash inside the app, so the reason of a
 * failure can be read and sent without a computer.
 */
object CrashLog {

    private const val FILE_NAME = "crash.txt"

    /** Writes the error and the place where it happened. */
    fun save(context: Context, error: Throwable) {
        try {
            val text = buildString {
                append("Час: ")
                append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
                append("\n\n")
                append(error.toString())
                append("\n\n")
                error.stackTrace.take(40).forEach { frame ->
                    append("    at ").append(frame.toString()).append("\n")
                }
                var cause = error.cause
                var depth = 0
                while (cause != null && depth < 5) {
                    append("\nПричина: ").append(cause.toString()).append("\n")
                    cause.stackTrace.take(20).forEach { frame ->
                        append("    at ").append(frame.toString()).append("\n")
                    }
                    cause = cause.cause
                    depth++
                }
            }
            file(context).writeText(text)
        } catch (_: Exception) {
        }
    }

    /** The text of the last error, null when nothing failed. */
    fun read(context: Context): String? {
        return try {
            val file = file(context)
            if (file.exists() && file.length() > 0) file.readText() else null
        } catch (_: Exception) {
            null
        }
    }

    fun clear(context: Context) {
        try { file(context).delete() } catch (_: Exception) { }
    }

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)
}

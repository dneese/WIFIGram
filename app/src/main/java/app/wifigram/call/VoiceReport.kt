package app.wifigram.call

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The report of the voice, saved where the user can find it.
 *
 * The file goes to the common folder of the phone, so the user may send it
 * away or show it to the developer without any trouble.
 */
object VoiceReport {

    /** Writes the report and the journal into the common folder of the phone. */
    fun save(context: Context, text: String): Boolean {
        return try {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "wifigram-voice-$stamp.txt")
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri == null) return false
            resolver.openOutputStream(uri)?.use { stream ->
                stream.write(text.toByteArray(Charsets.UTF_8))
            } ?: return false
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (_: Exception) {
            false
        }
    }
}

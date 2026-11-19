package app.wifigram

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * The files that came in the talk.
 *
 * The phone keeps its own copy, so the file may be opened inside the app,
 * and puts another copy into the common folder of the phone, in the folder
 * "wifigram", where the user finds it without any trouble.
 */
object ReceivedFiles {

    /** The folder of the common storage where the files lie. */
    val folder: String = Environment.DIRECTORY_DOWNLOADS + "/wifigram"

    /** Puts a copy of the file into the common folder of the phone. */
    fun saveToDownloads(context: Context, file: File, name: String, mime: String): Boolean {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, folder)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri == null) return false
            val written = try {
                resolver.openOutputStream(uri)?.use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                    true
                } ?: false
            } catch (_: Exception) {
                false
            }
            if (!written) {
                try { resolver.delete(uri, null, null) } catch (_: Exception) { }
                return false
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (_: Exception) {
            false
        }
    }
}

package app.wifigram

import android.content.Context
import java.util.UUID

/**
 * The list of the last calls.
 *
 * The entries are kept in the settings of the phone as simple lines, so the
 * journal survives the restart of the app. Every line remembers the id of
 * the phone, so the journal can call it back with one touch.
 */
object CallLogStore {

    private const val PREFS = "wifigram_calls"
    private const val KEY_LIST = "entries"

    data class Entry(
        val id: String,
        /** The phone that the call was with, if it is known. */
        val peerId: String? = null,
        val name: String,
        val timestamp: Long,
        val outgoing: Boolean,
        val missed: Boolean,
        /** The frames of the voice that were too late and had to go. */
        val dropped: Int = 0,
        /** The holes of the network that were hidden with a fading voice. */
        val concealed: Int = 0
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Adds one line to the journal. */
    fun add(
        context: Context,
        name: String,
        outgoing: Boolean,
        missed: Boolean = false,
        dropped: Int = 0,
        concealed: Int = 0,
        peerId: String? = null
    ) {
        val entry = Entry(
            id = UUID.randomUUID().toString().take(8),
            peerId = peerId,
            name = name.replace("|", "/").trim()
                .ifEmpty { Locales.apply(context).getString(R.string.phone_fallback) },
            timestamp = System.currentTimeMillis(),
            outgoing = outgoing,
            missed = missed,
            dropped = dropped,
            concealed = concealed
        )
        val list = list(context) + entry
        save(context, list.take(100))
    }

    fun list(context: Context): List<Entry> {
        val raw = prefs(context).getString(KEY_LIST, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { decodeEntry(it) }
            .sortedByDescending { it.timestamp }
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_LIST).apply()
    }

    fun missedCount(context: Context): Int = list(context).count { it.missed }

    private fun save(context: Context, entries: List<Entry>) {
        val text = entries.joinToString("\n") { encodeEntry(it) }
        prefs(context).edit().putString(KEY_LIST, text).apply()
    }

    // ── the lines of the file ──

    internal fun encodeEntry(entry: Entry): String =
        "${entry.id}|${entry.timestamp}|${if (entry.outgoing) 1 else 0}|" +
            "${if (entry.missed) 1 else 0}|${entry.dropped}|${entry.concealed}|" +
            "${entry.name}|${entry.peerId ?: ""}"

    /** Reads both the old lines without a phone id and the new ones. */
    internal fun decodeEntry(line: String): Entry? {
        val parts = line.split("|", limit = 8)
        if (parts.size < 5) return null
        val timestamp = parts[1].toLongOrNull() ?: return null
        val old = parts.size < 7
        return Entry(
            id = parts[0],
            peerId = if (old) null else parts.getOrNull(7)?.ifBlank { null },
            name = if (old) parts[4] else parts[6],
            timestamp = timestamp,
            outgoing = parts[2] == "1",
            missed = parts[3] == "1",
            dropped = if (old) 0 else parts[4].toIntOrNull() ?: 0,
            concealed = if (old) 0 else parts[5].toIntOrNull() ?: 0
        )
    }
}

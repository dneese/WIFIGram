package app.wifigram

import android.content.Context
import java.util.UUID

/**
 * The list of the last calls.
 *
 * The entries are kept in the settings of the phone as simple lines, so the
 * journal survives the restart of the app.
 */
object CallLogStore {

    private const val PREFS = "wifigram_calls"
    private const val KEY_LIST = "entries"

    data class Entry(
        val id: String,
        val name: String,
        val timestamp: Long,
        val outgoing: Boolean,
        val missed: Boolean
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Adds one line to the journal. */
    fun add(context: Context, name: String, outgoing: Boolean, missed: Boolean = false) {
        val entry = Entry(
            id = UUID.randomUUID().toString().take(8),
            name = name.replace("|", "/").trim().ifEmpty { "Телефон" },
            timestamp = System.currentTimeMillis(),
            outgoing = outgoing,
            missed = missed
        )
        val list = list(context) + entry
        save(context, list.take(100))
    }

    fun list(context: Context): List<Entry> {
        val raw = prefs(context).getString(KEY_LIST, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { line ->
            val parts = line.split("|", limit = 5)
            if (parts.size < 5) return@mapNotNull null
            val timestamp = parts[1].toLongOrNull() ?: return@mapNotNull null
            Entry(
                id = parts[0],
                name = parts[4],
                timestamp = timestamp,
                outgoing = parts[2] == "1",
                missed = parts[3] == "1"
            )
        }.sortedByDescending { it.timestamp }
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_LIST).apply()
    }

    fun missedCount(context: Context): Int = list(context).count { it.missed }

    private fun save(context: Context, entries: List<Entry>) {
        val text = entries.joinToString("\n") { entry ->
            "${entry.id}|${entry.timestamp}|${if (entry.outgoing) 1 else 0}|${if (entry.missed) 1 else 0}|${entry.name}"
        }
        prefs(context).edit().putString(KEY_LIST, text).apply()
    }
}

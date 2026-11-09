package app.wifigram

import android.content.Context

/**
 * The history of the chats.
 *
 * The messages are kept in the settings of the phone, one line for a
 * message, so the conversation is still there after the app was closed. The
 * bars and the breaks inside a message are escaped, so one message never
 * becomes two lines in the file.
 */
object ChatStore {

    private const val PREFS = "wifigram_chat"
    private const val KEY_MESSAGES = "messages"
    private const val KEY_READ = "read"

    /** How many messages of one phone are remembered. */
    const val LIMIT = 200

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): Map<String, List<Intercom.ChatMessage>> {
        val raw = prefs(context).getString(KEY_MESSAGES, "") ?: ""
        if (raw.isBlank()) return emptyMap()
        return raw.split("\n")
            .mapNotNull { decode(it) }
            .groupBy { it.peerId }
            .mapValues { (_, list) -> list.takeLast(LIMIT) }
    }

    fun save(context: Context, messages: Map<String, List<Intercom.ChatMessage>>) {
        val text = messages.values
            .flatMap { list -> list.takeLast(LIMIT) }
            .joinToString("\n") { encode(it) }
        prefs(context).edit().putString(KEY_MESSAGES, text).apply()
    }

    /** The moment until which every conversation was read. */
    fun lastRead(context: Context): Map<String, Long> {
        val raw = prefs(context).getString(KEY_READ, "") ?: ""
        if (raw.isBlank()) return emptyMap()
        return raw.split("\n").mapNotNull { line ->
            val at = line.lastIndexOf('=')
            if (at <= 0) return@mapNotNull null
            val time = line.substring(at + 1).toLongOrNull() ?: return@mapNotNull null
            line.substring(0, at) to time
        }.toMap()
    }

    fun markRead(context: Context, peerId: String, time: Long = System.currentTimeMillis()) {
        val now = lastRead(context).toMutableMap()
        now[peerId] = time
        val text = now.entries.joinToString("\n") { (id, at) -> "$id=$at" }
        prefs(context).edit().putString(KEY_READ, text).apply()
    }

    // ── the lines of the file ──

    internal fun encode(message: Intercom.ChatMessage): String = listOf(
        message.id,
        message.peerId,
        message.peerName,
        message.timestamp.toString(),
        if (message.mine) "1" else "0",
        if (message.delivered) "1" else "0",
        message.fileSize.toString(),
        message.fileName ?: "",
        message.filePath ?: "",
        message.text
    ).joinToString("|") { escape(it) }

    internal fun decode(line: String): Intercom.ChatMessage? {
        val fields = splitFields(line)
        if (fields.size < 10) return null
        val timestamp = unescape(fields[3]).toLongOrNull() ?: return null
        return Intercom.ChatMessage(
            id = unescape(fields[0]),
            peerId = unescape(fields[1]),
            peerName = unescape(fields[2]),
            timestamp = timestamp,
            mine = unescape(fields[4]) == "1",
            delivered = unescape(fields[5]) == "1",
            fileSize = unescape(fields[6]).toLongOrNull() ?: 0L,
            fileName = unescape(fields[7]).ifBlank { null },
            filePath = unescape(fields[8]).ifBlank { null },
            text = unescape(fields[9])
        )
    }

    /** Cuts a field so it cannot break the line of the file. */
    internal fun escape(value: String): String {
        val out = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '\\' -> out.append("\\\\")
                '|' -> out.append("\\|")
                '\n' -> out.append("\\n")
                '\r' -> Unit
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    internal fun unescape(value: String): String {
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '\\' && i + 1 < value.length) {
                when (value[i + 1]) {
                    '\\' -> out.append('\\')
                    '|' -> out.append('|')
                    'n' -> out.append('\n')
                    else -> out.append(value[i + 1])
                }
                i += 2
            } else {
                out.append(ch)
                i++
            }
        }
        return out.toString()
    }

    /** Splits on the bars that separate the fields, but not on the escaped ones. */
    internal fun splitFields(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            if (ch == '\\' && i + 1 < line.length) {
                current.append(ch).append(line[i + 1])
                i += 2
            } else if (ch == '|') {
                fields.add(current.toString())
                current.setLength(0)
                i++
            } else {
                current.append(ch)
                i++
            }
        }
        fields.add(current.toString())
        return fields
    }
}

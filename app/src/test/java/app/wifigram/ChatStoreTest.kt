package app.wifigram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatStoreTest {

    private fun message(
        text: String = "привіт",
        fileName: String? = null,
        filePath: String? = null,
        fileSize: Long = 0L,
        delivered: Boolean = false
    ) = Intercom.ChatMessage(
        id = "id-1",
        peerId = "peer-1",
        peerName = "Телефон",
        text = text,
        timestamp = 1_760_000_000_000L,
        mine = true,
        fileName = fileName,
        filePath = filePath,
        fileSize = fileSize,
        delivered = delivered
    )

    @Test
    fun `a simple message stays the same`() {
        val original = message()
        val parsed = ChatStore.decode(ChatStore.encode(original))
        assertEquals(original, parsed)
    }

    @Test
    fun `the bars and the breaks of a message do not break the line`() {
        val original = message(text = "a|b\\c\nd")
        val line = ChatStore.encode(original)
        assertTrue("the line must stay one line", !line.contains("\n"))
        val parsed = ChatStore.decode(line)
        assertEquals(original, parsed)
    }

    @Test
    fun `the fields of a file message come back whole`() {
        val original = message(
            text = "",
            fileName = "пісня|1.mp3",
            filePath = "/data/x/y.mp3",
            fileSize = 123_456L,
            delivered = true
        )
        val parsed = ChatStore.decode(ChatStore.encode(original))
        assertEquals(original, parsed)
    }

    @Test
    fun `a broken line is thrown away`() {
        assertNull(ChatStore.decode("short|line"))
        assertNull(ChatStore.decode(""))
    }

    @Test
    fun `the escaped bars are not the ends of the fields`() {
        val fields = ChatStore.splitFields("a\\|b|c")
        assertEquals(listOf("a\\|b", "c"), fields)
        assertEquals("a|b", ChatStore.unescape(fields[0]))
    }

    @Test
    fun `the old plain text survives the escape`() {
        val text = "рядок\\nне є переносом"
        assertEquals(text, ChatStore.unescape(ChatStore.escape(text)))
    }
}

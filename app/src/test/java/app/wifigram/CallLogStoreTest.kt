package app.wifigram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallLogStoreTest {

    private fun entry(peerId: String? = "peer-1", name: String = "Телефон") =
        CallLogStore.Entry(
            id = "abc12345",
            peerId = peerId,
            name = name,
            timestamp = 1_760_000_000_000L,
            outgoing = true,
            missed = false,
            dropped = 3,
            concealed = 1
        )

    @Test
    fun `a new line keeps the phone id`() {
        val original = entry()
        assertEquals(original, CallLogStore.decodeEntry(CallLogStore.encodeEntry(original)))
    }

    @Test
    fun `a line without a phone id is still read`() {
        val original = entry(peerId = null)
        val parsed = CallLogStore.decodeEntry(CallLogStore.encodeEntry(original))
        assertEquals(original, parsed)
    }

    @Test
    fun `an old line without the numbers and the id is read`() {
        val line = "ab12|1760000000000|1|0|Телефон"
        val parsed = CallLogStore.decodeEntry(line)!!
        assertEquals("ab12", parsed.id)
        assertEquals("Телефон", parsed.name)
        assertEquals(true, parsed.outgoing)
        assertEquals(false, parsed.missed)
        assertEquals(0, parsed.dropped)
        assertEquals(0, parsed.concealed)
        assertNull(parsed.peerId)
    }

    @Test
    fun `a broken line is thrown away`() {
        assertNull(CallLogStore.decodeEntry(""))
        assertNull(CallLogStore.decodeEntry("only|one"))
        assertNull(CallLogStore.decodeEntry("id|not-a-number|1|0|name"))
    }
}

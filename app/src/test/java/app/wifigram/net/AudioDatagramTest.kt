package app.wifigram.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDatagramTest {

    private fun datagram(peerId: String = "phone-1"): AudioDatagram = AudioDatagram(
        localPeerId = peerId,
        scope = CoroutineScope(Dispatchers.Unconfined),
        onHello = { _, _, _ -> },
        onAudio = { _, _, _ -> }
    )

    @Test
    fun `the packet says who sends it and keeps the body`() {
        val datagram = datagram()
        val body = byteArrayOf(1, 2, 3, 4, 5)
        val packet = datagram.build(AudioDatagram.TYPE_AUDIO, body)
        assertTrue(packet != null)

        val parsed = datagram.parse(packet!!)
        assertTrue(parsed != null)
        assertEquals(AudioDatagram.TYPE_AUDIO, parsed!!.type)
        assertEquals("phone-1", parsed.senderId)
        assertEquals(0, parsed.ack)
        assertTrue(parsed.payload.contentEquals(body))
    }

    @Test
    fun `the answer travels with the hello`() {
        val datagram = datagram()
        val packet = datagram.build(AudioDatagram.TYPE_HELLO, ByteArray(0), 4321)!!
        val parsed = datagram.parse(packet)!!
        assertEquals(AudioDatagram.TYPE_HELLO, parsed.type)
        assertEquals(4321, parsed.ack)
    }

    @Test
    fun `a packet of another program is left alone`() {
        val datagram = datagram()
        assertNull(datagram.parse(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))
        assertNull(datagram.parse(ByteArray(3)))
    }

    @Test
    fun `the packet of another version is left alone`() {
        val datagram = datagram()
        val packet = datagram.build(AudioDatagram.TYPE_HELLO, ByteArray(0))!!
        packet[4] = (AudioDatagram.VERSION + 1).toByte()
        assertNull(datagram.parse(packet))
    }
}

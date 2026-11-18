package app.wifigram.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProtocolRtcTest {

    @Test
    fun `an offer keeps its description`() {
        val signal = Protocol.RtcSignal(
            kind = Protocol.RTC_OFFER,
            callId = "call-1",
            payload = "v=0\r\na=ice-ufrag:test\r\n"
        )

        val decoded = Protocol.decodeRtcSignal(Protocol.encodeRtcSignal(signal))

        assertEquals(signal, decoded)
    }

    @Test
    fun `a candidate keeps its media line`() {
        val candidate = Protocol.RtcCandidate(
            sdpMid = "0",
            sdpMLineIndex = 0,
            candidate = "candidate:1 1 udp 2130706431 192.168.1.2 5000 typ host"
        )

        val encoded = Protocol.encodeRtcCandidate(candidate)

        assertEquals(candidate, Protocol.decodeRtcCandidate(encoded))
    }

    @Test
    fun `an unknown signaling kind is left alone`() {
        val payload = Protocol.bytes("unknown|call-1|payload")

        assertNull(Protocol.decodeRtcSignal(payload))
    }
}

package app.wifigram.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayoutBufferTest {

    private fun frame(seed: Int): ByteArray = ByteArray(PlayoutBuffer.FRAME_BYTES) { seed.toByte() }

    private fun play(buffer: PlayoutBuffer): PlayoutBuffer.Action = buffer.nextFrame()

    /** The frames come one after another, as they do on a quiet network. */
    private fun fill(buffer: PlayoutBuffer, count: Int, from: Int = 1, arrival: Long = 1000L) {
        repeat(count) { index ->
            buffer.enqueue(frame(from + index), arrival + index * PlayoutBuffer.FRAME_MS)
        }
    }

    @Test
    fun `waits until the cushion is full`() {
        val buffer = PlayoutBuffer()
        assertTrue(buffer.nextFrame() is PlayoutBuffer.Action.Wait)
        buffer.enqueue(frame(1), 1000L)
        buffer.enqueue(frame(2), 1020L)
        assertTrue(buffer.nextFrame() is PlayoutBuffer.Action.Wait)
        buffer.enqueue(frame(3), 1040L)
        assertTrue(buffer.nextFrame() is PlayoutBuffer.Action.Play)
        assertTrue(buffer.startedPlayout)
    }

    @Test
    fun `plays the frames in the order they came`() {
        val buffer = PlayoutBuffer()
        fill(buffer, 4)
        val first = play(buffer)
        val second = play(buffer)
        val third = play(buffer)
        assertTrue(first is PlayoutBuffer.Action.Play)
        assertTrue(second is PlayoutBuffer.Action.Play)
        assertTrue(third is PlayoutBuffer.Action.Play)
        assertEquals(1, (first as PlayoutBuffer.Action.Play).data[0].toInt())
        assertEquals(2, (second as PlayoutBuffer.Action.Play).data[0].toInt())
        assertEquals(3, (third as PlayoutBuffer.Action.Play).data[0].toInt())
    }

    @Test
    fun `ignores a piece of the wrong size`() {
        val buffer = PlayoutBuffer()
        buffer.enqueue(ByteArray(10), 1000L)
        assertEquals(0, buffer.depthMs)
        assertTrue(buffer.nextFrame() is PlayoutBuffer.Action.Wait)
    }

    @Test
    fun `a thick cushion makes the sound run faster and loses no word`() {
        // Ten frames are two hundred milliseconds: above the line of the
        // catch-up, so the sound has to run a little faster.
        val buffer = PlayoutBuffer()
        fill(buffer, 10)

        val heard = mutableSetOf<Int>()
        repeat(12) {
            val action = play(buffer)
            if (action is PlayoutBuffer.Action.Play) {
                action.data.forEach { byte -> heard.add(byte.toInt() and 0xFF) }
            }
        }

        assertEquals("no frame may be thrown away", 0, buffer.droppedFrames)
        for (seed in 1..10) {
            assertTrue("the frame $seed must be heard", heard.contains(seed))
        }
    }

    @Test
    fun `the sound runs faster only while the cushion is thick`() {
        val buffer = PlayoutBuffer(
            catchUpMarginMs = 20,
            hardDropMarginMs = 200
        )
        fill(buffer, 6)

        // The cushion of a hundred milliseconds is above the line.
        play(buffer)
        assertTrue("the sound must hurry", buffer.currentSpeed > 1.0)
        assertEquals(0, buffer.droppedFrames)

        // The cushion empties: the hurry is over.
        repeat(8) { play(buffer) }
        assertTrue("the hurry must end", buffer.currentSpeed <= 1.0)
        assertEquals(0, buffer.droppedFrames)
    }

    @Test
    fun `a cushion far too thick lets the oldest sound go`() {
        val buffer = PlayoutBuffer(
            catchUpMarginMs = 20,
            hardDropMarginMs = 40
        )
        fill(buffer, 20)

        play(buffer)
        assertTrue("the oldest sound must go", buffer.droppedFrames > 0)
    }

    @Test
    fun `a hole in the network fades instead of stopping dead`() {
        val buffer = PlayoutBuffer()
        fill(buffer, 4, from = 60)
        repeat(4) { assertTrue(play(buffer) is PlayoutBuffer.Action.Play) }

        // The cushion is empty: the last sound fades away.
        val firstHole = play(buffer)
        assertTrue(firstHole is PlayoutBuffer.Action.Conceal)
        val faded = (firstHole as PlayoutBuffer.Action.Conceal).data
        assertFalse(faded.contentEquals(frame(60)))

        repeat(PlayoutBuffer.PLC_FRAMES - 1) {
            assertTrue(play(buffer) is PlayoutBuffer.Action.Conceal)
        }
        // After the long hole the speaker hears silence.
        repeat(3) {
            val quiet = play(buffer)
            assertTrue(quiet is PlayoutBuffer.Action.Conceal)
            val data = (quiet as PlayoutBuffer.Action.Conceal).data
            assertTrue(data.all { it == 0.toByte() })
        }
        assertTrue(buffer.concealedFrames > 0)
    }

    @Test
    fun `the sound that comes back after a hole fades into it`() {
        val buffer = PlayoutBuffer()
        fill(buffer, 4, from = 60)
        repeat(4) { play(buffer) }
        // The cushion runs dry and the last sound fades.
        val hole = play(buffer)
        assertTrue(hole is PlayoutBuffer.Action.Conceal)

        buffer.enqueue(frame(120), 2000L)
        val back = play(buffer)
        assertTrue(back is PlayoutBuffer.Action.Play)
        val data = (back as PlayoutBuffer.Action.Play).data
        // The first samples are mixed out of the faded sound, so the piece
        // itself does not come out untouched.
        assertFalse(data.contentEquals(frame(120)))
    }

    @Test
    fun `reset empties the cushion and starts anew`() {
        val buffer = PlayoutBuffer()
        fill(buffer, 6)
        play(buffer)
        assertTrue(buffer.startedPlayout)
        buffer.reset()
        assertEquals(0, buffer.depthMs)
        assertFalse(buffer.startedPlayout)
        assertTrue(buffer.nextFrame() is PlayoutBuffer.Action.Wait)
        assertEquals(0, buffer.droppedFrames)
        assertEquals(0, buffer.concealedFrames)
    }

    @Test
    fun `stale sound of two seconds is thrown away`() {
        val buffer = PlayoutBuffer(
            catchUpMarginMs = 100_000,
            hardDropMarginMs = 100_000
        )
        repeat(PlayoutBuffer.MAX_FRAMES + 50) {
            buffer.enqueue(frame(it), 1000L + it * PlayoutBuffer.FRAME_MS)
        }
        assertEquals(PlayoutBuffer.MAX_FRAMES * PlayoutBuffer.FRAME_MS, buffer.depthMs)
    }

    @Test
    fun `the cushion grows with the jitter of the network`() {
        val buffer = PlayoutBuffer()
        // The frames come in bursts: five at once, then a long pause.
        repeat(5) { buffer.enqueue(frame(it + 1), 1000L) }
        repeat(5) { buffer.enqueue(frame(it + 6), 1200L) }
        repeat(5) { buffer.enqueue(frame(it + 11), 1220L) }

        assertTrue("the network must be seen as unsteady", buffer.measuredJitterMs > 5.0)
        assertTrue(
            "the cushion must follow the network",
            buffer.targetDepthMs > PlayoutBuffer.BASE_TARGET_MS
        )
    }

    @Test
    fun `the cushion of the next call begins with what the last call learned`() {
        val buffer = PlayoutBuffer(seedJitterMs = 25.0)
        assertEquals(160, buffer.targetDepthMs)
    }

    @Test
    fun `the sound runs slower when the cushion is thin`() {
        // The network of the last call was unsteady: the cushion is deep.
        val buffer = PlayoutBuffer(seedJitterMs = 30.0)
        assertEquals(180, buffer.targetDepthMs)
        fill(buffer, 12)

        // The cushion is thinner than it should be: the sound slows down a
        // little, so the cushion grows back without a hole in the speech.
        var slowed = false
        repeat(10) {
            play(buffer)
            if (buffer.currentSpeed < 1.0) slowed = true
        }
        assertTrue("the sound must wait a little", slowed)
    }

    @Test
    fun `the cushion is never deeper than the limit`() {
        val buffer = PlayoutBuffer(seedJitterMs = 1000.0)
        assertEquals(PlayoutBuffer.MAX_TARGET_MS, buffer.targetDepthMs)
    }
}

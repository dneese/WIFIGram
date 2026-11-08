package app.wifigram.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayoutBufferTest {

    private fun frame(seed: Int): ByteArray = ByteArray(PlayoutBuffer.FRAME_BYTES) { seed.toByte() }

    private fun play(buffer: PlayoutBuffer): PlayoutBuffer.Action = buffer.nextFrame()

    @Test
    fun `waits until the cushion is full`() {
        val buffer = PlayoutBuffer()
        assertTrue(buffer.nextFrame() is PlayoutBuffer.Action.Wait)
        buffer.enqueue(frame(1))
        buffer.enqueue(frame(2))
        assertTrue(buffer.nextFrame() is PlayoutBuffer.Action.Wait)
        buffer.enqueue(frame(3))
        assertTrue(buffer.nextFrame() is PlayoutBuffer.Action.Play)
        assertTrue(buffer.startedPlayout)
    }

    @Test
    fun `plays the frames in the order they came`() {
        val buffer = PlayoutBuffer()
        repeat(4) { buffer.enqueue(frame(it + 1)) }
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
        buffer.enqueue(ByteArray(10))
        assertEquals(0, buffer.depthMs)
        assertTrue(buffer.nextFrame() is PlayoutBuffer.Action.Wait)
    }

    @Test
    fun `a thick cushion makes the sound run faster and loses no word`() {
        // Ten frames are two hundred milliseconds: above the line of the
        // catch-up, so the sound has to run a little faster.
        val buffer = PlayoutBuffer()
        repeat(10) { buffer.enqueue(frame(it + 1)) }

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
            prebufferFrames = 1,
            catchUpOverMs = 40,
            catchUpHardOverMs = 100_000,
            catchUpUntilMs = 20
        )
        buffer.enqueue(frame(1))
        buffer.enqueue(frame(2))
        buffer.enqueue(frame(3))

        // The cushion of sixty milliseconds is above the line of forty.
        play(buffer)
        assertTrue("the sound must hurry", buffer.currentSpeed > 1.0)
        assertEquals(0, buffer.droppedFrames)

        // The cushion empties: the normal speed comes back.
        repeat(6) { play(buffer) }
        assertEquals(1.0, buffer.currentSpeed, 0.0001)
        assertEquals(0, buffer.droppedFrames)
    }

    @Test
    fun `a cushion far too thick lets the oldest sound go`() {
        val buffer = PlayoutBuffer(
            prebufferFrames = 1,
            catchUpOverMs = 40,
            catchUpHardOverMs = 100,
            catchUpUntilMs = 20
        )
        repeat(20) { buffer.enqueue(frame(it + 1)) }

        play(buffer)
        assertTrue("the oldest sound must go", buffer.droppedFrames > 0)
    }

    @Test
    fun `a hole in the network fades instead of stopping dead`() {
        val buffer = PlayoutBuffer()
        repeat(4) { buffer.enqueue(frame(60)) }
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
        repeat(4) { buffer.enqueue(frame(60)) }
        repeat(4) { play(buffer) }
        // The cushion runs dry and the last sound fades.
        val hole = play(buffer)
        assertTrue(hole is PlayoutBuffer.Action.Conceal)

        buffer.enqueue(frame(120))
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
        repeat(6) { buffer.enqueue(frame(1)) }
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
            prebufferFrames = 1,
            catchUpOverMs = 100_000,
            catchUpUntilMs = 100_000
        )
        repeat(PlayoutBuffer.MAX_FRAMES + 50) { buffer.enqueue(frame(it)) }
        assertEquals(PlayoutBuffer.MAX_FRAMES * PlayoutBuffer.FRAME_MS, buffer.depthMs)
    }
}

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
        buffer.enqueue(frame(3))
        assertTrue(buffer.nextFrame() is PlayoutBuffer.Action.Wait)
        buffer.enqueue(frame(4))
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
    fun `a deep cushion drops frames until it is small again`() {
        // The cushion of twenty frames is four hundred milliseconds: well
        // above every line of the catch-up.
        let {
            val buffer = PlayoutBuffer()
            repeat(20) { buffer.enqueue(frame(it)) }

            var ticks = 0
            while (buffer.depthMs > PlayoutBuffer.CATCH_UP_UNTIL_MS && ticks < 200) {
                buffer.nextFrame()
                ticks++
            }

            assertTrue("the catch-up must drop frames", buffer.droppedFrames > 0)
            assertTrue(
                "the cushion must be small again",
                buffer.depthMs <= PlayoutBuffer.CATCH_UP_UNTIL_MS
            )

            // The last tick began above the lower line, so a few more
            // ticks are needed before the speed is the normal one.
            repeat(6) { buffer.nextFrame() }

            // Normal speed: the drops stop while the cushion stays low.
            val stopped = buffer.droppedFrames
            repeat(10) {
                buffer.enqueue(frame(it))
                buffer.nextFrame()
            }
            assertEquals(stopped, buffer.droppedFrames)
        }
    }

    @Test
    fun `drops exactly every second frame when asked to hurry`() {
        val buffer = PlayoutBuffer(
            prebufferFrames = 1,
            catchUpOverMs = 40,
            catchUpHardOverMs = 100_000,
            catchUpUntilMs = 20,
            dropEvery = 2,
            dropEveryHard = 2
        )
        buffer.enqueue(frame(1))
        buffer.enqueue(frame(2))
        buffer.enqueue(frame(3))

        // The cushion of sixty milliseconds is above the line of forty.
        val first = play(buffer)   // no drop yet: the counter is one
        assertEquals(0, buffer.droppedFrames)
        assertTrue(first is PlayoutBuffer.Action.Play)
        assertEquals(1, (first as PlayoutBuffer.Action.Play).data[0].toInt())

        // The second frame of the cushion goes by unheard, the third sounds.
        val second = play(buffer)
        assertTrue(second is PlayoutBuffer.Action.Play)
        val sounded = (second as PlayoutBuffer.Action.Play).data
        // The crossfade touches only the first five milliseconds, so the
        // end of the piece still says that the third frame is the one that
        // came out and the second one was dropped.
        assertEquals(3, sounded[sounded.size - 1].toInt())
        assertEquals(1, buffer.droppedFrames)
        assertEquals(0, buffer.depthMs)

        // The cushion is low again: the normal speed is back at once.
        buffer.enqueue(frame(4))
        val third = play(buffer)
        assertTrue(third is PlayoutBuffer.Action.Play)
        assertEquals(4, (third as PlayoutBuffer.Action.Play).data[0].toInt())
        assertEquals(1, buffer.droppedFrames)
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

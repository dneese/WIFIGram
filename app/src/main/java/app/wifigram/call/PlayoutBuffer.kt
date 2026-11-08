package app.wifigram.call

import java.util.ArrayDeque

/**
 * The cushion between the wire and the speaker.
 *
 * The network gives the sound in bursts: a quiet moment, then a rush of
 * frames. Played straight away, every rush adds its delay to the call and
 * the delay never goes away. This buffer keeps a small cushion of sound and
 * decides what the speaker hears:
 *
 * - the cushion is thin after a gap: the last sound fades away instead of
 *   stopping dead, so a hole in the network is felt less;
 * - the cushion grew too thick: the sound runs a little faster than life —
 *   the words are all there, the voice is a little quicker until the
 *   cushion is small again. Nothing is thrown away, because a thrown-away
 *   frame is a hole in the speech.
 *
 * The class knows nothing about Android, so every rule of it is tested.
 */
class PlayoutBuffer(
    private val frameBytes: Int = FRAME_BYTES,
    private val frameMs: Int = FRAME_MS,
    private val frameSamples: Int = FRAME_SAMPLES,
    private val prebufferFrames: Int = PREBUFFER_FRAMES,
    private val catchUpOverMs: Int = CATCH_UP_OVER_MS,
    private val catchUpHardOverMs: Int = CATCH_UP_HARD_OVER_MS,
    private val catchUpUntilMs: Int = CATCH_UP_UNTIL_MS,
    private val maxSpeed: Double = MAX_SPEED,
    private val plcFrames: Int = PLC_FRAMES
) {

    /** What the speaker does with one tick of the clock. */
    sealed class Action {
        /** These samples go to the speaker. */
        data class Play(val data: ByteArray) : Action()

        /** There is a hole: these samples pretend the sound went on. */
        data class Conceal(val data: ByteArray) : Action()

        /** Nothing has ever arrived: silence. */
        object Silence : Action()

        /** The cushion is still filling up. */
        object Wait : Action()
    }

    companion object {
        const val FRAME_BYTES = 640
        const val FRAME_MS = 20
        const val FRAME_SAMPLES = 320

        /** How much sound is kept before the speaker starts. */
        const val PREBUFFER_FRAMES = 3

        /** The cushion above which the sound starts to run faster. */
        const val CATCH_UP_OVER_MS = 140

        /** The cushion above which the oldest sound is late beyond saving. */
        const val CATCH_UP_HARD_OVER_MS = 340

        /** The cushion below which the normal speed comes back. */
        const val CATCH_UP_UNTIL_MS = 80

        /** How much faster the sound may run while the cushion is thick. */
        const val MAX_SPEED = 1.06

        /** How many frames of hole are pretended before the silence. */
        const val PLC_FRAMES = 5

        /** The length of the crossfade in samples: five milliseconds. */
        const val FADE_SAMPLES = 80

        /** The cushion never grows beyond this: two seconds of stale talk. */
        const val MAX_FRAMES = 100
    }

    private val queue = ArrayDeque<ByteArray>()

    /** The frame that is being played at the moment. */
    private var current: ByteArray? = null

    /** The place inside the current frame, in samples. */
    private var position: Double = 0.0

    /** How fast the sound runs right now. */
    private var speed: Double = 1.0

    private var started = false
    private var catchingUp = false
    private var sincePlc = 0
    private var crossfade = false
    private var lastPlayed: ByteArray? = null
    private var dropped = 0
    private var concealed = 0

    /** The sound that arrived and waits for the speaker. */
    val depthMs: Int
        get() = synchronized(this) {
            val rest = current?.let { frameSamples - position } ?: 0.0
            ((queue.size * frameMs) + (rest * frameMs / frameSamples)).toInt()
        }

    val startedPlayout: Boolean
        get() = synchronized(this) { started }

    /** Counters for the log of the call. */
    val droppedFrames: Int get() = synchronized(this) { dropped }
    val concealedFrames: Int get() = synchronized(this) { concealed }

    /** The speed of the sound right now: one is the speed of life. */
    val currentSpeed: Double get() = synchronized(this) { speed }

    /** One piece of sound arrived from the network. */
    fun enqueue(frame: ByteArray) {
        if (frame.size != frameBytes) return
        synchronized(this) {
            // A cushion that grew out of proportion means a long silence of
            // the network followed by a rush; the oldest sound is stale.
            while (queue.size >= MAX_FRAMES) {
                queue.pollFirst()
            }
            queue.addLast(frame)
        }
    }

    /** What the speaker does at this tick of the clock: every 20 ms. */
    fun nextFrame(): Action = synchronized(this) {
        if (!started) {
            if (queue.size < prebufferFrames) return Action.Wait
            started = true
            current = queue.pollFirst()
            position = 0.0
            sincePlc = 0
        } else if (current == null) {
            // The hole is over: the sound that came in the meantime is
            // taken and fades into the quiet.
            val next = queue.pollFirst()
            if (next != null) {
                current = next
                position = 0.0
            }
        }

        val depth = depthMs()
        speed = speedFor(depth)

        // A cushion that is far too thick holds sound that is late beyond
        // saving. The oldest frame goes, the rest is played.
        if (depth > catchUpHardOverMs && queue.size > 1) {
            queue.pollFirst()
            dropped++
            crossfade = true
        }

        val out = ByteArray(frameBytes)
        var index = 0
        var hole = false
        while (index + 1 < frameBytes) {
            val frame = current
            if (frame == null) {
                hole = true
                break
            }
            val value = sampleAt(frame, position)
            out[index] = (value.toInt() and 0xFF).toByte()
            out[index + 1] = ((value.toInt() shr 8) and 0xFF).toByte()
            index += 2
            position += speed
            while (position >= frameSamples) {
                position -= frameSamples
                val next = queue.pollFirst()
                if (next == null) {
                    current = null
                    position = 0.0
                    break
                }
                current = next
            }
        }

        if (hole) {
            return conceal(out, index)
        }

        if (crossfade) {
            val previous = lastPlayed
            if (previous != null) crossfadeInto(out, previous)
            crossfade = false
        }
        lastPlayed = out
        sincePlc = 0
        return Action.Play(out)
    }

    /** The hole of the network: the last sound fades instead of stopping. */
    private fun conceal(out: ByteArray, from: Int): Action {
        sincePlc++
        val last = lastPlayed
        if (last == null) return Action.Silence
        if (sincePlc > plcFrames) {
            // The long hole ended in plain silence; the sound that comes
            // back fades into it.
            val silence = ByteArray(frameBytes)
            if (!crossfade) {
                lastPlayed = silence
                crossfade = true
            }
            concealed++
            return Action.Conceal(silence)
        }
        val gain = 1.0 - sincePlc.toDouble() / (plcFrames + 1)
        var index = from
        while (index + 1 < frameBytes) {
            val sample = ((last[index].toInt() and 0xFF) or (last[index + 1].toInt() shl 8)).toShort()
            val faded = (sample.toInt() * gain).toInt().toShort()
            out[index] = (faded.toInt() and 0xFF).toByte()
            out[index + 1] = ((faded.toInt() shr 8) and 0xFF).toByte()
            index += 2
        }
        concealed++
        // The faded copy becomes the new base, so the next hole continues
        // the fade instead of jumping back up.
        lastPlayed = out
        crossfade = true
        return Action.Conceal(out)
    }

    /** The sound of the current frame at the given place, gently mixed. */
    private fun sampleAt(frame: ByteArray, at: Double): Double {
        val index = at.toInt().coerceIn(0, frameSamples - 1)
        val weight = at - index
        val first = sampleOf(frame, index)
        if (index + 1 >= frameSamples) return first
        val second = sampleOf(frame, index + 1)
        return first + (second - first) * weight
    }

    private fun sampleOf(frame: ByteArray, index: Int): Double {
        val at = index * 2
        if (at + 1 >= frame.size) return 0.0
        val low = frame[at].toInt() and 0xFF
        val high = frame[at + 1].toInt()
        return ((high shl 8) or low).toShort().toDouble()
    }

    /** The speed of the sound: the thicker the cushion, the quicker it runs. */
    private fun speedFor(depth: Int): Double {
        if (catchingUp) {
            // The catch-up has its own hysteresis: it ends below the lower
            // line, so the speed does not flicker around one number.
            if (depth <= catchUpUntilMs) catchingUp = false
        } else if (depth > catchUpOverMs) {
            catchingUp = true
        }
        if (!catchingUp) return 1.0
        val span = (catchUpHardOverMs - catchUpUntilMs).coerceAtLeast(1)
        val over = (depth - catchUpUntilMs).coerceAtLeast(0).coerceAtMost(span)
        return 1.0 + (over.toDouble() / span) * (maxSpeed - 1.0)
    }

    /** Mixes the start of the new frame out of the end of the old one. */
    private fun crossfadeInto(frame: ByteArray, previous: ByteArray) {
        val tail = previous.size - FADE_SAMPLES * 2
        for (index in 0 until FADE_SAMPLES) {
            val at = index * 2
            val from = tail + at
            if (at + 1 >= frame.size || from + 1 >= previous.size || from < 0) break
            val old = ((previous[from].toInt() and 0xFF) or (previous[from + 1].toInt() shl 8)).toShort()
            val fresh = ((frame[at].toInt() and 0xFF) or (frame[at + 1].toInt() shl 8)).toShort()
            val weight = index.toDouble() / FADE_SAMPLES
            val mixed = (old.toInt() * (1.0 - weight) + fresh.toInt() * weight).toInt().toShort()
            frame[at] = (mixed.toInt() and 0xFF).toByte()
            frame[at + 1] = ((mixed.toInt() shr 8) and 0xFF).toByte()
        }
    }

    /** The call begins with an empty cushion. */
    fun reset() {
        synchronized(this) {
            queue.clear()
            current = null
            position = 0.0
            speed = 1.0
            started = false
            catchingUp = false
            sincePlc = 0
            crossfade = false
            lastPlayed = null
            dropped = 0
            concealed = 0
        }
    }
}

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
 * - the cushion grew too thick: a frame out of every few is dropped with a
 *   crossfade, the sound runs a little faster than life until the cushion
 *   is small again, and then the speed is the normal one.
 *
 * The class knows nothing about Android, so every rule of it is tested.
 */
class PlayoutBuffer(
    private val frameBytes: Int = FRAME_BYTES,
    private val frameMs: Int = FRAME_MS,
    private val prebufferFrames: Int = PREBUFFER_FRAMES,
    private val catchUpOverMs: Int = CATCH_UP_OVER_MS,
    private val catchUpHardOverMs: Int = CATCH_UP_HARD_OVER_MS,
    private val catchUpUntilMs: Int = CATCH_UP_UNTIL_MS,
    private val dropEvery: Int = DROP_EVERY,
    private val dropEveryHard: Int = DROP_EVERY_HARD,
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

        /** How much sound is kept before the speaker starts. */
        const val PREBUFFER_FRAMES = 4

        /** The cushion above which the sound starts to run faster. */
        const val CATCH_UP_OVER_MS = 140

        /** The cushion above which the sound runs much faster. */
        const val CATCH_UP_HARD_OVER_MS = 340

        /** The cushion below which the normal speed comes back. */
        const val CATCH_UP_UNTIL_MS = 80

        /** One frame out of this many is dropped in the mild catch-up. */
        const val DROP_EVERY = 8

        /** One frame out of this many is dropped in the hard catch-up. */
        const val DROP_EVERY_HARD = 4

        /** How many frames of hole are pretended before the silence. */
        const val PLC_FRAMES = 5

        /** The length of the crossfade in samples: five milliseconds. */
        const val FADE_SAMPLES = 80

        /** The cushion never grows beyond this: two seconds of stale talk. */
        const val MAX_FRAMES = 100
    }

    private val queue = ArrayDeque<ByteArray>()

    private var started = false
    private var catchingUp = false
    private var sinceDrop = 0
    private var sincePlc = 0
    private var crossfade = false
    private var lastPlayed: ByteArray? = null
    private var dropped = 0
    private var concealed = 0

    /** The sound that arrived and waits for the speaker. */
    val depthMs: Int
        get() = synchronized(this) { queue.size * frameMs }

    val startedPlayout: Boolean
        get() = synchronized(this) { started }

    /** Counters for the log of the call. */
    val droppedFrames: Int get() = synchronized(this) { dropped }
    val concealedFrames: Int get() = synchronized(this) { concealed }

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
            sincePlc = 0
        }

        val depth = queue.size * frameMs
        // The catch-up has its own hysteresis: it begins above the upper
        // line and only ends below the lower one, so the speed does not
        // flicker around one number.
        if (depth > catchUpOverMs) catchingUp = true
        else if (depth <= catchUpUntilMs) catchingUp = false

        if (catchingUp) {
            val strength = if (depth > catchUpHardOverMs) dropEveryHard else dropEvery
            sinceDrop++
            if (sinceDrop >= strength) {
                sinceDrop = 0
                // One frame is let go unheard. The frame after it is the
                // one that sounds, and it fades out of the old one.
                if (queue.size > 1) {
                    queue.pollFirst()
                    dropped++
                    crossfade = true
                }
            }
        } else {
            sinceDrop = 0
        }

        val frame = queue.pollFirst() ?: return concealAction()

        val out = if (crossfade) {
            val previous = lastPlayed
            if (previous != null) crossfadeInto(frame, previous) else frame
        } else {
            frame
        }
        crossfade = false
        lastPlayed = out
        sincePlc = 0
        return Action.Play(out)
    }

    /** The hole of the network: the last sound fades instead of stopping. */
    private fun concealAction(): Action {
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
            return Action.Conceal(silence)
        }
        val gain = 1.0 - sincePlc.toDouble() / (plcFrames + 1)
        val out = ByteArray(frameBytes)
        var index = 0
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

    /** Mixes the start of the new frame out of the end of the old one. */
    private fun crossfadeInto(frame: ByteArray, previous: ByteArray): ByteArray {
        val out = frame.copyOf()
        val tail = previous.size - FADE_SAMPLES * 2
        for (index in 0 until FADE_SAMPLES) {
            val at = index * 2
            val from = tail + at
            if (at + 1 >= out.size || from + 1 >= previous.size || from < 0) break
            val old = ((previous[from].toInt() and 0xFF) or (previous[from + 1].toInt() shl 8)).toShort()
            val fresh = ((frame[at].toInt() and 0xFF) or (frame[at + 1].toInt() shl 8)).toShort()
            val weight = index.toDouble() / FADE_SAMPLES
            val mixed = (old.toInt() * (1.0 - weight) + fresh.toInt() * weight).toInt().toShort()
            out[at] = (mixed.toInt() and 0xFF).toByte()
            out[at + 1] = ((mixed.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** The call begins with an empty cushion. */
    fun reset() {
        synchronized(this) {
            queue.clear()
            started = false
            catchingUp = false
            sinceDrop = 0
            sincePlc = 0
            crossfade = false
            lastPlayed = null
            dropped = 0
            concealed = 0
        }
    }
}

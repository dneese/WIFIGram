package app.wifigram.call

import java.util.ArrayDeque
import kotlin.math.abs

/**
 * The cushion between the wire and the speaker.
 *
 * The network gives the sound in bursts: a quiet moment, then a rush of
 * frames. Played straight away, every rush adds its delay to the call and
 * the delay never goes away. This buffer keeps a cushion of sound and
 * decides what the speaker hears.
 *
 * The cushion is not a fixed number. The buffer watches how the frames come:
 * the difference between the way they were sent and the way they arrived
 * says how much the network stumbles. The bigger the stumble, the deeper the
 * cushion has to be, and the deeper it is, the fewer words are lost in the
 * holes. The cushion follows the network:
 *
 * - the cushion is thin after a gap: the last sound fades away instead of
 *   stopping dead, so a hole in the network is felt less;
 * - the cushion grew too thick: the sound runs a little faster than life —
 *   the words are all there, the voice is a little quicker until the
 *   cushion is small again. Nothing is thrown away, because a thrown-away
 *   frame is a hole in the speech;
 * - the cushion is too thin: the sound runs a little slower, so the cushion
 *   grows back without a hole in the speech.
 *
 * The class knows nothing about Android, so every rule of it is tested.
 */
class PlayoutBuffer(
    private val frameBytes: Int = FRAME_BYTES,
    private val frameMs: Int = FRAME_MS,
    private val frameSamples: Int = FRAME_SAMPLES,
    private val baseTargetMs: Int = BASE_TARGET_MS,
    private val minTargetMs: Int = MIN_TARGET_MS,
    private val maxTargetMs: Int = MAX_TARGET_MS,
    private val jitterFactor: Double = JITTER_FACTOR,
    private val catchUpMarginMs: Int = CATCH_UP_MARGIN_MS,
    private val slowDownMarginMs: Int = SLOW_DOWN_MARGIN_MS,
    private val hardDropMarginMs: Int = HARD_DROP_MARGIN_MS,
    private val maxSpeed: Double = MAX_SPEED,
    private val minSpeed: Double = MIN_SPEED,
    private val plcFrames: Int = PLC_FRAMES,
    private val seedJitterMs: Double = 0.0
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

        /** The cushion the program aims at: three frames of sound. */
        const val BASE_TARGET_MS = 60

        /** The cushion never gets thinner than this. */
        const val MIN_TARGET_MS = 60

        /** The cushion never gets thicker than this. */
        const val MAX_TARGET_MS = 320

        /** The cushion grows with the measured jitter of the network. */
        const val JITTER_FACTOR = 4.0

        /** Above the cushion, the sound starts to run faster. */
        const val CATCH_UP_MARGIN_MS = 40

        /** Below the cushion, the sound runs a little slower. */
        const val SLOW_DOWN_MARGIN_MS = 20

        /** Below the cushion, the normal speed comes back. */
        const val CATCH_UP_UNTIL_MARGIN_MS = 20

        /** Far above the cushion, the oldest sound is late beyond saving. */
        const val HARD_DROP_MARGIN_MS = 200

        /** How much faster the sound may run while the cushion is thick. */
        const val MAX_SPEED = 1.06

        /** How much slower the sound may run while the cushion is thin. */
        const val MIN_SPEED = 0.96

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

    /** How much the network stumbles, in milliseconds. */
    private var jitterMs: Double = seedJitterMs

    /** When the last frame came. */
    private var lastArrival: Long = -1L

    /** The sound that arrived and waits for the speaker. */
    val depthMs: Int
        get() = synchronized(this) {
            val rest = current?.let { frameSamples - position } ?: 0.0
            ((queue.size * frameMs) + (rest * frameMs / frameSamples)).toInt()
        }

    /**
     * The cushion the buffer aims at.
     *
     * It follows the network: the more the network stumbles, the deeper the
     * cushion has to be.
     */
    val targetDepthMs: Int
        get() = synchronized(this) {
            (baseTargetMs + jitterFactor * jitterMs).toInt().coerceIn(minTargetMs, maxTargetMs)
        }

    /** How much the network stumbles, in milliseconds. */
    val measuredJitterMs: Double get() = synchronized(this) { jitterMs }

    val startedPlayout: Boolean
        get() = synchronized(this) { started }

    /** Counters for the log of the call. */
    val droppedFrames: Int get() = synchronized(this) { dropped }
    val concealedFrames: Int get() = synchronized(this) { concealed }

    /** The speed of the sound right now: one is the speed of life. */
    val currentSpeed: Double get() = synchronized(this) { speed }

    /** One piece of sound arrived from the network. */
    fun enqueue(frame: ByteArray, arrivalMs: Long = System.currentTimeMillis()) {
        if (frame.size != frameBytes) return
        synchronized(this) {
            if (lastArrival >= 0) {
                // The difference between the way the frames came and the way
                // they were sent: the bigger it is, the more the network
                // stumbles. The number moves slowly, so one bad moment does
                // not make the cushion jump.
                val stumble = abs((arrivalMs - lastArrival) - frameMs)
                jitterMs += (stumble - jitterMs) / 16.0
            }
            lastArrival = arrivalMs
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
        val target = targetDepthMs
        if (!started) {
            // The speaker waits until the cushion is as deep as the network
            // needs it.
            if (depthMs < target) return Action.Wait
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

        val depth = depthMs
        speed = speedFor(depth, target)

        // A cushion that is far too thick holds sound that is late beyond
        // saving. The oldest frame goes, the rest is played.
        if (depth > target + hardDropMarginMs && queue.size > 1) {
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

    /**
     * The speed of the sound.
     *
     * The cushion is thick: the sound runs a little faster and the cushion
     * gets thin again. The cushion is thin: the sound runs a little slower
     * and the cushion grows back. Between the two lines the sound keeps the
     * speed of life.
     */
    private fun speedFor(depth: Int, target: Int): Double {
        if (catchingUp) {
            // The catch-up has its own hysteresis: it ends below the lower
            // line, so the speed does not flicker around one number.
            if (depth <= target - CATCH_UP_UNTIL_MARGIN_MS) catchingUp = false
        } else if (depth > target + catchUpMarginMs) {
            catchingUp = true
        }
        if (catchingUp) {
            val span = (hardDropMarginMs - CATCH_UP_UNTIL_MARGIN_MS).coerceAtLeast(1)
            val over = (depth - (target - CATCH_UP_UNTIL_MARGIN_MS)).coerceIn(0, span)
            return 1.0 + (over.toDouble() / span) * (maxSpeed - 1.0)
        }
        val under = (target - slowDownMarginMs - depth).coerceAtLeast(0)
        if (under <= 0) return 1.0
        val span = (target - minTargetMs).coerceAtLeast(1)
        val ratio = (under.toDouble() / span).coerceIn(0.0, 1.0)
        return 1.0 - ratio * (1.0 - minSpeed)
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
            jitterMs = seedJitterMs
            lastArrival = -1L
        }
    }
}

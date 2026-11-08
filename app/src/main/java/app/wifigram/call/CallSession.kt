package app.wifigram.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import app.wifigram.ProfileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/**
 * The voice of the conversation.
 *
 * The sound is captured in pieces of 20 milliseconds, coded as Opus and
 * handed to the station, which sends them to the phones of the
 * conversation. The sound that comes back does not go to the speaker at
 * once: it lands in a small cushion that smooths the bursts of the network
 * — when the cushion grows, the sound runs a little faster until it is
 * caught up, and when the network leaves a hole, the last sound fades
 * instead of stopping.
 *
 * While the connection is lost the phone says so with a quiet beep, the
 * same way the old phones did.
 */
@android.annotation.SuppressLint("MissingPermission")
class CallSession(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onFrame: (ByteArray) -> Unit
) {

    private companion object {
        const val SAMPLE_RATE = 16000
        const val FRAME_SAMPLES = 320      // 20 ms
        const val FRAME_BYTES = FRAME_SAMPLES * 2

        /** The cushion of the microphone: two frames, forty milliseconds. */
        const val CAPTURE_FRAMES = 2

        /** The cushion of the speaker: two frames, forty milliseconds. */
        const val PLAYOUT_FRAMES = 2

        /** The quiet signal that says "there is no connection". */
        const val BEEP_INTERVAL = 4000L
        const val BEEP_TONE = 400.0
        const val BEEP_LENGTH = 80

        /** One tick of the playout clock. */
        const val TICK_NS = 20_000_000L
    }

    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    private var effects: VoiceEffects? = null
    private var captureJob: Job? = null
    private var playoutJob: Job? = null
    private val playout = PlayoutBuffer()
    private val silence = ByteArray(FRAME_BYTES)

    @Volatile
    private var muted = false

    @Volatile
    private var speaker = false

    @Volatile
    private var signalLost = false

    /** The state of the noise catcher of the phone right now. */
    @Volatile
    private var noiseOn = true

    @Volatile
    var running: Boolean = false
        private set

    /** The microphone is off, the call goes on. */
    fun setMuted(value: Boolean) {
        muted = value
    }

    /** The frames of the voice that were too late and had to go. */
    val droppedFrames: Int get() = playout.droppedFrames

    /** The holes of the network that were hidden with a fading voice. */
    val concealedFrames: Int get() = playout.concealedFrames

    /** How thick the cushion of the sound was at the end. */
    val bufferDepthMs: Int get() = playout.depthMs

    /** The sound goes to the speaker of the phone or to the earpiece. */
    fun setSpeaker(value: Boolean) {
        speaker = value
        // The echo of the speaker is only a problem when the speaker is on.
        effects?.setEcho(value)
        applyMode()
        applyRoute()
    }

    /** The connection is gone, the phone beeps until it is back. */
    fun setSignalLost(value: Boolean) {
        signalLost = value
    }

    fun start(): Boolean {
        if (running) return true
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return false
        // The cushion of the microphone is two frames: the voice waits there
        // only as long as it must.
        val bufferSize = maxOf(minBuffer, FRAME_BYTES * CAPTURE_FRAMES)

        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (_: Exception) {
            null
        } ?: return false

        // The speaker is quick: the track asks the hardware for the fast
        // path and falls back to the ordinary one when the phone refuses.
        val trackBuffer = maxOf(minBuffer / 2, FRAME_BYTES * PLAYOUT_FRAMES)
        val audioTrack = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(trackBuffer)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (_: Exception) {
            try {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(trackBuffer)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } catch (_: Exception) {
                null
            }
        } ?: return false

        recorder = audioRecord
        track = audioTrack
        running = true
        playout.reset()

        // The helpers of the phone: the hum and the echo.
        val voiceEffects = VoiceEffects(audioRecord)
        effects = voiceEffects
        noiseOn = ProfileStore.denoise(context)
        voiceEffects.setNoise(noiseOn)
        voiceEffects.setEcho(speaker)

        // The mode first, the route second: changing the mode resets the
        // route, and the phone would quietly forget the speaker.
        applyMode()
        applyRoute()

        try {
            audioTrack.play()
            audioRecord.startRecording()
        } catch (_: Exception) {
        }

        captureJob = scope.launch(Dispatchers.IO) { captureLoop(audioRecord) }
        playoutJob = scope.launch(Dispatchers.IO) { playoutLoop(audioTrack) }
        return true
    }

    fun stop() {
        running = false
        captureJob?.cancel()
        captureJob = null
        playoutJob?.cancel()
        playoutJob = null
        try { recorder?.stop() } catch (_: Exception) { }
        try { recorder?.release() } catch (_: Exception) { }
        recorder = null
        try { effects?.release() } catch (_: Exception) { }
        effects = null
        try { track?.stop() } catch (_: Exception) { }
        try { track?.release() } catch (_: Exception) { }
        track = null
        playout.reset()
        try { OpusCodec.reset() } catch (_: Exception) { }
        try {
            val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            manager?.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {
        }
    }

    /** Plays the sound that came from the other phone. */
    fun play(data: ByteArray, opus: Boolean) {
        if (!running) return
        val pcm = if (opus) {
            OpusCodec.decode(data) ?: return
        } else {
            data
        }
        playout.enqueue(pcm)
    }

    private fun captureLoop(audioRecord: AudioRecord) {
        // The microphone does not always give a whole frame at a time, so
        // the pieces are gathered until twenty milliseconds are ready. A
        // short frame would be a hole in the speech: the codec would make
        // a piece of ten milliseconds and the other phone would drop it.
        val piece = ByteArray(FRAME_BYTES)
        val frame = ByteArray(FRAME_BYTES)
        var filled = 0
        while (running && scope.isActive) {
            val read = try {
                audioRecord.read(piece, 0, piece.size)
            } catch (_: Exception) {
                -1
            }
            if (read <= 0) continue
            if (muted) {
                // The microphone is off, the call goes on
                filled = 0
                continue
            }
            // The settings may turn the noise catcher while the call goes on.
            val wanted = ProfileStore.denoise(context)
            if (wanted != noiseOn) {
                noiseOn = wanted
                effects?.setNoise(wanted)
            }
            var offset = 0
            while (offset < read) {
                val take = minOf(FRAME_BYTES - filled, read - offset)
                System.arraycopy(piece, offset, frame, filled, take)
                filled += take
                offset += take
                if (filled < FRAME_BYTES) break
                filled = 0
                if (OpusCodec.available) {
                    val coded = OpusCodec.encode(frame) ?: continue
                    onFrame(coded)
                } else {
                    onFrame(frame.copyOf())
                }
            }
        }
    }

    /**
     * The clock of the speaker: one tick every twenty milliseconds.
     *
     * The tick asks the cushion what to do — the sound that waits, the
     * sound that runs a little faster, the fade over a hole — and the quiet
     * beep of the lost connection keeps its place here too.
     */
    private suspend fun playoutLoop(audioTrack: AudioTrack) {
        var next = System.nanoTime()
        var lastBeep = 0L
        while (running && scope.isActive) {
            val action = playout.nextFrame()
            val bytes = when (action) {
                is PlayoutBuffer.Action.Play -> action.data
                is PlayoutBuffer.Action.Conceal -> action.data
                PlayoutBuffer.Action.Wait -> silence
                PlayoutBuffer.Action.Silence -> silence
            }
            try {
                audioTrack.write(bytes, 0, bytes.size)
            } catch (_: Exception) {
            }

            if (signalLost) {
                val now = System.currentTimeMillis()
                if (lastBeep == 0L) {
                    lastBeep = now
                } else if (now - lastBeep >= BEEP_INTERVAL) {
                    lastBeep = now
                    val tone = makeTone()
                    try {
                        audioTrack.write(tone, 0, tone.size)
                    } catch (_: Exception) {
                    }
                }
            } else {
                lastBeep = 0L
            }

            next += TICK_NS
            val sleep = next - System.nanoTime()
            if (sleep > 0) {
                try {
                    // The plan of the ticks is absolute, so a millisecond
                    // that is rounded away here never piles up.
                    kotlinx.coroutines.delay(sleep / 1_000_000)
                } catch (_: Exception) {
                    break
                }
            } else {
                // The phone was busy and the clock fell behind; the next
                // tick starts from now instead of the old plan.
                next = System.nanoTime()
            }
        }
    }

    private fun makeTone(): ByteArray {
        val result = ByteArray(BEEP_LENGTH)
        for (index in 0 until BEEP_LENGTH) {
            val value = sin(2.0 * PI * BEEP_TONE * index / SAMPLE_RATE) * 2200.0
            result[index] = value.toInt().toByte()
        }
        return result
    }

    /** The phone talks as in a usual call while the conversation goes on. */
    private fun applyMode() {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try { manager.mode = AudioManager.MODE_IN_COMMUNICATION } catch (_: Exception) { }
    }

    /** Chooses the speaker of the phone or the earpiece. */
    private fun applyRoute() {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            @Suppress("DEPRECATION")
            manager.isSpeakerphoneOn = speaker
        } catch (_: Exception) {
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            try {
                val device = if (speaker) {
                    android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                } else {
                    android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                }
                val found = manager.availableCommunicationDevices.firstOrNull { it.type == device }
                if (found != null) manager.setCommunicationDevice(found)
            } catch (_: Exception) {
            }
        }
    }
}

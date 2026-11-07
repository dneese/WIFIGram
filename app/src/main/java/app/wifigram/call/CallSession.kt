package app.wifigram.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
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
 * The sound is captured in pieces of 20 milliseconds and handed to the
 * station, which sends them to the phones of the conversation. The other
 * side plays them right away.
 *
 * While the connection is lost the phone says so with a quiet beep, the
 * same way the old phones did.
 */
class CallSession(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onFrame: (ByteArray) -> Unit
) {

    private companion object {
        const val SAMPLE_RATE = 16000
        const val FRAME_SAMPLES = 320      // 20 ms
        const val FRAME_BYTES = FRAME_SAMPLES * 2

        /** The quiet signal that says "there is no connection". */
        const val BEEP_INTERVAL = 4000L
        const val BEEP_TONE = 400.0
        const val BEEP_LENGTH = 80
    }

    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    private var captureJob: Job? = null
    private var beepJob: Job? = null
    private var sequence = 0

    @Volatile
    private var muted = false

    @Volatile
    private var speaker = false

    @Volatile
    private var signalLost = false

    @Volatile
    var running: Boolean = false
        private set

    /** The microphone is off, the call goes on. */
    fun setMuted(value: Boolean) {
        muted = value
    }

    /** The sound goes to the speaker of the phone or to the earpiece. */
    fun setSpeaker(value: Boolean) {
        speaker = value
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
        val bufferSize = maxOf(minBuffer, FRAME_BYTES * 8)

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
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (_: Exception) {
            null
        } ?: return false

        recorder = audioRecord
        track = audioTrack
        running = true
        applyRoute()

        try {
            audioTrack.play()
            audioRecord.startRecording()
        } catch (_: Exception) {
        }

        captureJob = scope.launch(Dispatchers.IO) { captureLoop(audioRecord) }
        beepJob = scope.launch(Dispatchers.IO) { beepLoop() }
        return true
    }

    fun stop() {
        running = false
        captureJob?.cancel()
        captureJob = null
        beepJob?.cancel()
        beepJob = null
        try { recorder?.stop() } catch (_: Exception) { }
        try { recorder?.release() } catch (_: Exception) { }
        recorder = null
        try { track?.stop() } catch (_: Exception) { }
        try { track?.release() } catch (_: Exception) { }
        track = null
    }

    /** Plays the sound that came from the other phone. */
    fun play(data: ByteArray) {
        try {
            track?.write(data, 0, data.size)
        } catch (_: Exception) {
        }
    }

    private fun captureLoop(audioRecord: AudioRecord) {
        val buffer = ByteArray(FRAME_BYTES)
        while (running && scope.isActive) {
            val read = try {
                audioRecord.read(buffer, 0, buffer.size)
            } catch (_: Exception) {
                -1
            }
            if (read <= 0) continue
            if (muted) {
                // The microphone is off, the call goes on
                continue
            }
            val frame = buffer.copyOf(read)
            sequence++
            onFrame(frame)
        }
    }

    /** The quiet beep that says that the connection is gone. */
    private suspend fun beepLoop() {
        while (running && scope.isActive) {
            kotlinx.coroutines.delay(BEEP_INTERVAL)
            if (!running || !signalLost) continue
            val tone = makeTone()
            try {
                track?.write(tone, 0, tone.size)
            } catch (_: Exception) {
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
                manager.setCommunicationDevice(
                    manager.availableCommunicationDevices.firstOrNull { it.type == device }
                )
            } catch (_: Exception) {
            }
        }
    }
}

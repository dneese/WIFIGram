package app.wifigramm.call

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import app.wifigramm.net.Crypto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * The voice of the room.
 *
 * The sound is captured in small pieces of 20 milliseconds, every piece is
 * encrypted with the key of the call and sent over UDP. The other phone
 * decrypts it and plays it right away.
 */
class VoiceCall(
    private val callKey: ByteArray,
    destination: InetAddress?,
    destinationPort: Int,
    private val scope: CoroutineScope
) {

    @Volatile
    private var destination: InetAddress? = destination

    @Volatile
    private var destinationPort: Int = destinationPort

    private companion object {
        const val SAMPLE_RATE = 16000
        const val FRAME_SAMPLES = 320      // 20 ms
        const val FRAME_BYTES = FRAME_SAMPLES * 2
    }

    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    private var socket: DatagramSocket? = null
    private var captureJob: Job? = null
    private var receiveJob: Job? = null
    private var sequence = 0

    @Volatile
    var localPort: Int = 0
        private set

    @Volatile
    var running: Boolean = false
        private set

    /** The other phone answers, now the sound has a place to go. */
    fun setDestination(address: InetAddress?, port: Int) {
        destination = address
        destinationPort = port
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
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
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

        val datagramSocket = try {
            DatagramSocket()
        } catch (_: Exception) {
            null
        } ?: return false

        recorder = audioRecord
        track = audioTrack
        socket = datagramSocket
        localPort = datagramSocket.localPort
        running = true

        try {
            audioTrack.play()
            audioRecord.startRecording()
        } catch (_: Exception) {
        }

        captureJob = scope.launch(Dispatchers.IO) { captureLoop(audioRecord) }
        receiveJob = scope.launch(Dispatchers.IO) { receiveLoop(datagramSocket) }
        return true
    }

    fun stop() {
        running = false
        captureJob?.cancel()
        captureJob = null
        receiveJob?.cancel()
        receiveJob = null
        try { recorder?.stop() } catch (_: Exception) { }
        try { recorder?.release() } catch (_: Exception) { }
        recorder = null
        try { track?.stop() } catch (_: Exception) { }
        try { track?.release() } catch (_: Exception) { }
        track = null
        try { socket?.close() } catch (_: Exception) { }
        socket = null
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
            val plain = buffer.copyOf(read)
            val nonce = nonceFor(sequence++)
            val cipher = Crypto.encrypt(callKey, nonce, plain)
            val packet = Crypto.concat(
                sequenceBytes(sequence - 1),
                cipher
            )
            val target = destination
            if (target != null && destinationPort > 0) {
                try {
                    val datagram = DatagramPacket(packet, packet.size, target, destinationPort)
                    socket?.send(datagram)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun receiveLoop(datagramSocket: DatagramSocket) {
        val buffer = ByteArray(4096)
        while (running && scope.isActive) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                datagramSocket.receive(packet)
            } catch (_: Exception) {
                continue
            }
            if (packet.length <= 4) continue
            val sequence = readSequence(packet.data, packet.length)
            val nonce = nonceFor(sequence)
            val cipher = packet.data.copyOfRange(4, packet.length)
            val plain = Crypto.decrypt(callKey, nonce, cipher) ?: continue
            try {
                track?.write(plain, 0, plain.size)
            } catch (_: Exception) {
            }
        }
    }

    private fun nonceFor(sequence: Int): ByteArray {
        val nonce = ByteArray(12)
        val value = sequence.toLong() and 0xFFFFFFFFL
        nonce[0] = (value ushr 24).toByte()
        nonce[1] = (value ushr 16).toByte()
        nonce[2] = (value ushr 8).toByte()
        nonce[3] = value.toByte()
        return nonce
    }

    private fun sequenceBytes(sequence: Int): ByteArray {
        val bytes = ByteArray(4)
        val value = sequence.toLong() and 0xFFFFFFFFL
        bytes[0] = (value ushr 24).toByte()
        bytes[1] = (value ushr 16).toByte()
        bytes[2] = (value ushr 8).toByte()
        bytes[3] = value.toByte()
        return bytes
    }

    private fun readSequence(data: ByteArray, length: Int): Int {
        if (length < 4) return 0
        val value = ((data[0].toInt() and 0xFF) shl 24) or
            ((data[1].toInt() and 0xFF) shl 16) or
            ((data[2].toInt() and 0xFF) shl 8) or
            (data[3].toInt() and 0xFF)
        return value
    }
}

package app.wifigram.call

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.IceCandidateErrorEvent
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStats
import org.webrtc.RTCStatsCollectorCallback
import org.webrtc.RTCStatsReport
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * The voice of the conversation: WebRTC over the local network.
 *
 * The whole thing is one encrypted stream (DTLS-SRTP) with Opus inside, so
 * the sound cannot be mixed up and the words do not need their own way over
 * the network. Inside the stream the phone's own helpers work: the echo of
 * the speaker is taken away, the hum is taken away and the voice is kept at
 * the same loudness. The jitter buffer of NetEq smooths the bursts of the
 * WiFi and hides the holes with the voice of the last moment.
 *
 * Only the addresses of the local network are used: no server, no STUN,
 * no TURN, nothing leaves the WiFi.
 */
class WebRtcEngine(
    context: Context,
    private val callbacks: Callbacks
) {

    /** What the engine tells to the app. */
    interface Callbacks {
        fun onSignal(kind: String, payload: String)
        fun onState(state: String)
        fun onStats(rttMs: Int, jitterMs: Int, lossPercent: Int, codec: String)
        fun onError(text: String)
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val lock = Any()
    private val pendingCandidates = mutableListOf<IceCandidate>()

    private var audioDeviceModule: AudioDeviceModule? = null
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localTrack: AudioTrack? = null
    private var remoteDescriptionSet = false
    private var closed = false
    private var statsJob: Thread? = null
    private var initiator = false
    private var volumePercent = 100
    private var volumeBefore = -1

    /** Starts the engine. The initiator makes the offer. */
    fun start(initiator: Boolean) {
        synchronized(lock) {
            if (closed || peerConnection != null) return
        }
        this.initiator = initiator
        try {
            VoiceTrace.stage(appContext, "webrtc-create")
            createPeerConnection()
        } catch (error: Throwable) {
            VoiceTrace.error(appContext, "create ${error.message}")
            callbacks.onError("WebRTC: ${error.message ?: error.javaClass.simpleName}")
            return
        }
        VoiceTrace.stage(appContext, "webrtc-created")
        if (initiator) createOffer(iceRestart = false)
    }

    /** The description of the other phone arrives. */
    fun handleDescription(kind: String, sdp: String) {
        val type = if (kind == VoiceMessages.KIND_OFFER) {
            SessionDescription.Type.OFFER
        } else {
            SessionDescription.Type.ANSWER
        }
        val answer = kind == VoiceMessages.KIND_OFFER
        VoiceTrace.stage(appContext, "webrtc-remote-$kind")
        setRemoteDescription(sdp, type, answer)
    }

    /** One path of the ice arrives. */
    fun handleCandidate(sdpMid: String, sdpIndex: Int, candidate: String) {
        val ice = IceCandidate(sdpMid, sdpIndex, candidate)
        synchronized(lock) {
            if (closed) return
            if (!remoteDescriptionSet) {
                pendingCandidates.add(ice)
                return
            }
        }
        addCandidate(ice)
    }

    fun setMuted(muted: Boolean) {
        try {
            localTrack?.setEnabled(!muted)
            VoiceTrace.stage(appContext, if (muted) "mic-off" else "mic-on")
        } catch (_: Exception) {
        }
    }

    fun setSpeaker(speaker: Boolean) {
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val type = if (speaker) {
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                } else {
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                }
                val device = audioManager.availableCommunicationDevices
                    .firstOrNull { it.type == type }
                if (device != null) audioManager.setCommunicationDevice(device)
                else if (!speaker) audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = speaker
            }
        } catch (_: Exception) {
        }
    }

    /** The loudness of the voice, in percent. */
    fun setVolume(percent: Int) {
        volumePercent = percent.coerceIn(0, 100)
        applyVolume()
    }

    /** Looks for the way of the ice again after the WiFi came back. */
    fun restartIce() {
        val connection = peerConnection ?: return
        try {
            VoiceTrace.stage(appContext, "webrtc-restart-ice")
            connection.restartIce()
        } catch (error: Exception) {
            VoiceTrace.error(appContext, "restart ${error.message}")
        }
    }

    fun close() {
        val resources = synchronized(lock) {
            if (closed) return
            closed = true
            pendingCandidates.clear()
            val result = Triple(peerConnection, localTrack, audioSource)
            peerConnection = null
            localTrack = null
            audioSource = null
            result
        }
        statsJob?.interrupt()
        statsJob = null
        try { resources.first?.close() } catch (_: Exception) { }
        try { resources.first?.dispose() } catch (_: Exception) { }
        try { resources.second?.dispose() } catch (_: Exception) { }
        try { resources.third?.dispose() } catch (_: Exception) { }
        try { factory?.dispose() } catch (_: Exception) { }
        try { audioDeviceModule?.release() } catch (_: Exception) { }
        restoreVolume()
        try { audioManager.mode = AudioManager.MODE_NORMAL } catch (_: Exception) { }
        VoiceTrace.stage(appContext, "webrtc-closed")
    }

    // ── the building of the connection ──

    private fun createPeerConnection() {
        if (!factoryInitialized) {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(appContext)
                    .createInitializationOptions()
            )
            factoryInitialized = true
        }
        VoiceTrace.stage(appContext, "webrtc-factory-initialized")

        val audioDevice = JavaAudioDeviceModule.builder(appContext)
            .setSampleRate(SAMPLE_RATE)
            .setInputSampleRate(SAMPLE_RATE)
            .setOutputSampleRate(SAMPLE_RATE)
            .setAudioSource(android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setUseLowLatency(true)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .createAudioDeviceModule()
        audioDeviceModule = audioDevice
        VoiceTrace.stage(appContext, "webrtc-audio-device")

        val peerFactory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDevice)
            .createPeerConnectionFactory()
        factory = peerFactory
        VoiceTrace.stage(appContext, "webrtc-factory-created")

        val configuration = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceServers = emptyList()
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.ALL
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            audioJitterBufferMaxPackets = 20
            audioJitterBufferFastAccelerate = true
        }

        val connection = peerFactory.createPeerConnection(configuration, PeerObserver())
        if (connection == null) {
            callbacks.onError("WebRTC: the connection could not be made")
            return
        }
        peerConnection = connection
        VoiceTrace.stage(appContext, "webrtc-peer-created")

        val source = peerFactory.createAudioSource(audioConstraints())
        audioSource = source
        val track = peerFactory.createAudioTrack("audio0", source)
        localTrack = track
        connection.addTransceiver(
            track,
            RtpTransceiver.RtpTransceiverInit(
                RtpTransceiver.RtpTransceiverDirection.SEND_RECV
            )
        )
        VoiceTrace.stage(appContext, "webrtc-track-added")

        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        } catch (_: Exception) {
        }
        applyVolume()

        startStats()
    }

    private fun createOffer(iceRestart: Boolean) {
        val connection = peerConnection ?: return
        VoiceTrace.stage(appContext, if (iceRestart) "webrtc-offer-restart" else "webrtc-offer")
        connection.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(description: SessionDescription) {
                createdDescription = description
                connection.setLocalDescription(this, description)
            }

            override fun onSetSuccess() {
                emit(VoiceMessages.KIND_OFFER, createdDescription?.description.orEmpty())
            }
        }, sdpConstraints(iceRestart))
    }

    private fun createAnswer() {
        val connection = peerConnection ?: return
        VoiceTrace.stage(appContext, "webrtc-answer")
        connection.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(description: SessionDescription) {
                createdDescription = description
                connection.setLocalDescription(this, description)
            }

            override fun onSetSuccess() {
                emit(VoiceMessages.KIND_ANSWER, createdDescription?.description.orEmpty())
            }
        }, sdpConstraints(false))
    }

    private fun setRemoteDescription(
        sdp: String,
        type: SessionDescription.Type,
        makeAnswer: Boolean
    ) {
        val connection = peerConnection ?: return
        connection.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                synchronized(lock) {
                    remoteDescriptionSet = true
                }
                VoiceTrace.stage(appContext, "webrtc-remote-set")
                val candidates = synchronized(lock) {
                    pendingCandidates.toList().also { pendingCandidates.clear() }
                }
                candidates.forEach { addCandidate(it) }
                if (makeAnswer) createAnswer()
            }
        }, SessionDescription(type, sdp))
    }

    private fun addCandidate(candidate: IceCandidate) {
        val connection = peerConnection ?: return
        try {
            connection.addIceCandidate(candidate)
        } catch (_: Exception) {
        }
    }

    private fun emit(kind: String, payload: String) {
        callbacks.onSignal(kind, payload)
    }

    private fun audioConstraints(): MediaConstraints = MediaConstraints().apply {
        mandatory.add(constraint("googEchoCancellation", "true"))
        mandatory.add(constraint("googNoiseSuppression", "true"))
        mandatory.add(constraint("googAutoGainControl", "true"))
        mandatory.add(constraint("googHighpassFilter", "true"))
        mandatory.add(constraint("googAudioFling", "true"))
        mandatory.add(constraint("googMaxAverageBitrate", "32000"))
    }

    private fun sdpConstraints(iceRestart: Boolean): MediaConstraints = MediaConstraints().apply {
        if (iceRestart) mandatory.add(constraint("IceRestart", "true"))
    }

    private fun constraint(key: String, value: String): MediaConstraints.KeyValuePair =
        MediaConstraints.KeyValuePair(key, value)

    // ── the loudness ──

    private fun applyVolume() {
        try {
            if (volumeBefore < 0) {
                volumeBefore = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            }
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            val value = (max * volumePercent / 100.0).toInt().coerceIn(0, max)
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, value, 0)
        } catch (_: Exception) {
        }
    }

    private fun restoreVolume() {
        try {
            if (volumeBefore >= 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, volumeBefore, 0)
                volumeBefore = -1
            }
        } catch (_: Exception) {
        }
    }

    // ── the numbers of the way ──

    private fun startStats() {
        statsJob?.interrupt()
        val thread = Thread {
            while (!closed) {
                try {
                    Thread.sleep(2000)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                val connection = peerConnection ?: continue
                try {
                    connection.getStats(object : RTCStatsCollectorCallback {
                        override fun onStatsDelivered(report: RTCStatsReport) {
                            readStats(report)
                        }
                    })
                } catch (_: Exception) {
                }
            }
        }
        statsJob = thread
        thread.isDaemon = true
        thread.start()
    }

    private fun readStats(report: RTCStatsReport) {
        var rttMs = -1
        var jitterMs = 0
        var lost = 0L
        var received = 0L
        var codec = ""
        val codecs = HashMap<String, String>()
        for (stats in report.statsMap.values) {
            val members = stats.members
            if (stats.type == "codec") {
                val id = members["id"] as? String ?: continue
                val mime = members["mimeType"] as? String ?: continue
                codecs[id] = mime
            }
        }
        for (stats in report.statsMap.values) {
            val members = stats.members
            when (stats.type) {
                "outbound-rtp", "inbound-rtp" -> {
                    val jitter = number(members["jitter"])
                    if (jitter != null && jitter > jitterMs) jitterMs = (jitter * 1000).toInt()
                    val roundTrip = number(members["roundTripTime"])
                    if (roundTrip != null && roundTrip >= 0) {
                        rttMs = (roundTrip * 1000).toInt()
                    }
                    val packetsLost = long(members["packetsLost"])
                    if (packetsLost != null) lost += packetsLost
                    val packetsReceived = long(members["packetsReceived"])
                    if (packetsReceived != null) received += packetsReceived
                    val id = members["codecId"] as? String
                    val mime = id?.let { codecs[it] }
                    if (mime != null && stats.type == "inbound-rtp") codec = mime
                }
                "candidate-pair" -> {
                    if (members["selected"] == true || members["state"] == "succeeded") {
                        val current = number(members["currentRoundTripTime"])
                        if (current != null && current >= 0 && rttMs < 0) {
                            rttMs = (current * 1000).toInt()
                        }
                    }
                }
            }
        }
        val loss = if (received + lost > 0) {
            ((lost * 100) / (received + lost)).toInt().coerceIn(0, 100)
        } else {
            0
        }
        callbacks.onStats(rttMs, jitterMs, loss, codec)
    }

    private fun number(value: Any?): Double? = when (value) {
        is Double -> value
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }

    private fun long(value: Any?): Long? = when (value) {
        is Long -> value
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }

    // ── the listeners ──

    private inner class PeerObserver : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            VoiceTrace.stage(appContext, "ice-${state.name}")
            callbacks.onState(state.name)
            if (state == PeerConnection.IceConnectionState.FAILED) {
                callbacks.onError("WebRTC: the way did not open")
            }
        }

        override fun onStandardizedIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            VoiceTrace.stage(appContext, "peer-${state.name}")
            callbacks.onState(state.name)
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {
            callbacks.onState(if (receiving) "RECEIVING" else "WAITING")
        }

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit

        override fun onIceCandidate(candidate: IceCandidate) {
            callbacks.onSignal(
                VoiceMessages.KIND_CANDIDATE,
                candidate.sdp + "\n" + candidate.sdpMid + "\n" + candidate.sdpMLineIndex
            )
        }

        override fun onIceCandidateError(event: IceCandidateErrorEvent) {
            VoiceTrace.error(appContext, "candidate ${event.errorCode} ${event.errorText}")
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit

        override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) = Unit

        override fun onAddStream(stream: MediaStream) = Unit

        override fun onRemoveStream(stream: MediaStream) = Unit

        override fun onDataChannel(channel: DataChannel) = Unit

        override fun onRenegotiationNeeded() {
            val connection = peerConnection ?: return
            if (!initiator) return
            if (connection.signalingState() != PeerConnection.SignalingState.STABLE) return
            createOffer(iceRestart = true)
        }

        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) = Unit

        override fun onRemoveTrack(receiver: RtpReceiver) = Unit

        override fun onTrack(transceiver: RtpTransceiver) {
            VoiceTrace.stage(appContext, "webrtc-track-received")
        }
    }

    private inner abstract class SimpleSdpObserver : SdpObserver {
        protected var createdDescription: SessionDescription? = null

        override fun onCreateSuccess(description: SessionDescription) {
            createdDescription = description
        }

        override fun onSetSuccess() = Unit

        override fun onCreateFailure(error: String) {
            VoiceTrace.error(appContext, "sdp-create $error")
            callbacks.onError("WebRTC: $error")
        }

        override fun onSetFailure(error: String) {
            VoiceTrace.error(appContext, "sdp-set $error")
            callbacks.onError("WebRTC: $error")
        }
    }

    companion object {
        private const val SAMPLE_RATE = 48000

        @Volatile
        private var factoryInitialized = false
    }
}

package app.wifigram.call

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import app.wifigram.net.Protocol
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
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule

class WebRtcCall(
    context: Context,
    private val callId: String,
    private val onSignal: (Protocol.RtcSignal) -> Unit,
    private val onConnectionChanged: (Boolean) -> Unit
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val lock = Any()
    private val pendingCandidates = mutableListOf<Protocol.RtcCandidate>()

    private var audioDeviceModule: AudioDeviceModule? = null
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localTrack: AudioTrack? = null
    private var remoteDescriptionSet = false
    private var remoteDescription = ""
    private var offerHandled = false
    private var answerHandled = false
    private var closed = false
    private var everConnected = false

    fun start(initiator: Boolean) {
        synchronized(lock) {
            if (closed || peerConnection != null) return
            createPeerConnection()
        }
        if (initiator) createOffer()
    }

    fun handleOffer(sdp: String) {
        synchronized(lock) {
            if (closed || offerHandled) return
            offerHandled = true
        }
        setRemoteDescription(sdp, SessionDescription.Type.OFFER, createAnswer = true)
    }

    fun handleAnswer(sdp: String) {
        synchronized(lock) {
            if (closed || answerHandled) return
            answerHandled = true
        }
        setRemoteDescription(sdp, SessionDescription.Type.ANSWER, createAnswer = false)
    }

    fun handleCandidate(payload: String) {
        val candidate = Protocol.decodeRtcCandidate(payload) ?: return
        synchronized(lock) {
            if (closed) return
            if (!remoteDescriptionSet) {
                pendingCandidates.add(candidate)
                return
            }
        }
        addCandidate(candidate)
    }

    fun setMuted(muted: Boolean) {
        localTrack?.setEnabled(!muted)
    }

    fun setSpeaker(speaker: Boolean) {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val type = if (speaker) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            } else {
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            }
            audioManager.availableCommunicationDevices.firstOrNull { it.type == type }?.let {
                audioManager.setCommunicationDevice(it)
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = speaker
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
        try { resources.first?.close() } catch (_: Exception) { }
        try { resources.second?.dispose() } catch (_: Exception) { }
        try { resources.third?.dispose() } catch (_: Exception) { }
        try { factory?.dispose() } catch (_: Exception) { }
        try { audioDeviceModule?.release() } catch (_: Exception) { }
    }

    private fun createPeerConnection() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .createInitializationOptions()
        )
        val audioDevice = JavaAudioDeviceModule.builder(appContext)
            .setUseHardwareAcousticEchoCanceler(
                JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported()
            )
            .setUseHardwareNoiseSuppressor(
                JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported()
            )
            .setUseLowLatency(true)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .createAudioDeviceModule()
        audioDeviceModule = audioDevice
        val peerFactory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDevice)
            .createPeerConnectionFactory()
        factory = peerFactory

        val configuration = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceServers = emptyList()
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.LOW_COST
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
        }
        val connection = peerFactory.createPeerConnection(configuration, PeerObserver())
        if (connection == null) {
            onConnectionChanged(false)
            return
        }
        peerConnection = connection

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
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
    }

    private fun createOffer() {
        val connection = peerConnection ?: return
        connection.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(description: SessionDescription) {
                createdDescription = description
                connection.setLocalDescription(this, description)
            }

            override fun onSetSuccess() {
                emit(Protocol.RTC_OFFER, createdDescription?.description.orEmpty())
            }
        }, sdpConstraints())
    }

    private fun createAnswer() {
        val connection = peerConnection ?: return
        connection.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(description: SessionDescription) {
                createdDescription = description
                connection.setLocalDescription(this, description)
            }

            override fun onSetSuccess() {
                emit(Protocol.RTC_ANSWER, createdDescription?.description.orEmpty())
            }
        }, sdpConstraints())
    }

    private fun setRemoteDescription(
        sdp: String,
        type: SessionDescription.Type,
        createAnswer: Boolean
    ) {
        val connection = peerConnection ?: return
        val duplicate = synchronized(lock) {
            if (remoteDescriptionSet && remoteDescription == sdp) {
                true
            } else {
                remoteDescription = sdp
                false
            }
        }
        if (duplicate) return
        connection.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                synchronized(lock) {
                    remoteDescriptionSet = true
                }
                val candidates = synchronized(lock) {
                    pendingCandidates.toList().also { pendingCandidates.clear() }
                }
                candidates.forEach { addCandidate(it) }
                if (createAnswer) createAnswer()
            }
        }, SessionDescription(type, sdp))
    }

    private fun addCandidate(candidate: Protocol.RtcCandidate) {
        val connection = peerConnection ?: return
        connection.addIceCandidate(
            IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate)
        )
    }

    private fun emit(kind: String, payload: String) {
        onSignal(Protocol.RtcSignal(kind, callId, payload))
    }

    private fun audioConstraints(): MediaConstraints = MediaConstraints().apply {
        mandatory.add(constraint("googEchoCancellation", "true"))
        mandatory.add(constraint("googNoiseSuppression", "true"))
        mandatory.add(constraint("googAutoGainControl", "true"))
        mandatory.add(constraint("googHighpassFilter", "true"))
    }

    private fun sdpConstraints(): MediaConstraints = MediaConstraints()

    private fun constraint(key: String, value: String): MediaConstraints.KeyValuePair =
        MediaConstraints.KeyValuePair(key, value)

    private inner class PeerObserver : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            val connected = state == PeerConnection.IceConnectionState.CONNECTED ||
                state == PeerConnection.IceConnectionState.COMPLETED
            if (connected) {
                everConnected = true
                onConnectionChanged(true)
            } else if (everConnected) {
                onConnectionChanged(false)
            }
        }

        override fun onStandardizedIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            if (state == PeerConnection.PeerConnectionState.FAILED ||
                state == PeerConnection.PeerConnectionState.DISCONNECTED
            ) {
                if (everConnected) onConnectionChanged(false)
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {
            if (receiving) onConnectionChanged(true)
        }

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit

        override fun onIceCandidate(candidate: IceCandidate) {
            emit(
                Protocol.RTC_CANDIDATE,
                Protocol.encodeRtcCandidate(
                    Protocol.RtcCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
                )
            )
        }

        override fun onIceCandidateError(event: IceCandidateErrorEvent) = Unit

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit

        override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) = Unit

        override fun onAddStream(stream: MediaStream) = Unit

        override fun onRemoveStream(stream: MediaStream) = Unit

        override fun onDataChannel(channel: DataChannel) = Unit

        override fun onRenegotiationNeeded() = Unit

        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) = Unit

        override fun onRemoveTrack(receiver: RtpReceiver) = Unit

        override fun onTrack(transceiver: RtpTransceiver) = Unit
    }

    private abstract class SimpleSdpObserver : SdpObserver {
        protected var createdDescription: SessionDescription? = null

        override fun onCreateSuccess(description: SessionDescription) {
            createdDescription = description
        }

        override fun onSetSuccess() = Unit

        override fun onCreateFailure(error: String) {
            onConnectionChanged(false)
        }

        override fun onSetFailure(error: String) {
            onConnectionChanged(false)
        }
    }
}

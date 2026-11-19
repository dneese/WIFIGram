package app.wifigram.call

import android.media.MediaRecorder
import org.json.JSONObject

/**
 * Every knob of the voice of the call.
 *
 * The settings are read when the call begins, so a change is seen in the
 * next call. Nothing here is a secret: each value is written to the report
 * of the voice, so the user can see what the phone really did.
 */
data class VoiceTuning(
    /** Where the sound of the microphone comes from. */
    val audioSource: Int = MediaRecorder.AudioSource.VOICE_COMMUNICATION,

    /** How often the voice is measured, in samples per second. */
    val sampleRate: Int = 48000,

    /** The phone tries to keep the voice as short as possible. */
    val useLowLatency: Boolean = true,

    /** The phone uses its own noise catcher of the hardware. */
    val hardwareNoiseSuppress: Boolean = false,

    /** The echo of the speaker is taken away by the software. */
    val softwareEcho: Boolean = true,

    /** The hum is taken away by the software. */
    val softwareNoise: Boolean = true,

    /** The voice keeps the same loudness. */
    val autoGain: Boolean = true,

    /** The deep sounds are cut off. */
    val highpass: Boolean = true,

    /** The widest stream of the voice, in kilobits per second. */
    val maxBitrate: Int = 32,

    /** Which ways of the ice may be used: all or relay only. */
    val iceTransport: String = ICE_ALL,

    /** May the ice use TCP. */
    val tcpCandidate: String = TCP_ENABLED,

    /** Which candidates are preferred: all or the cheap ones. */
    val candidatePolicy: String = CAND_ALL,

    /** The ice keeps looking for new ways even after the call began. */
    val gatherContinually: Boolean = true,

    /** One tube for everything or many. */
    val bundle: String = BUNDLE_MAX,

    /** The control and the voice travel in one tube. */
    val rtcpMux: Boolean = true,

    /** The old way of the description or the new one. */
    val sdpSemantics: String = SDP_UNIFIED,

    /** How many packets the buffer of the trembling keeps. */
    val jitterPackets: Int = 20,

    /** The buffer throws away the old packets quickly. */
    val fastAccelerate: Boolean = true,

    /** The paths of the ice go to the other phone as they come. */
    val trickle: Boolean = true,

    /** When the way breaks, the phone looks for it again by itself. */
    val iceRestartOnFail: Boolean = true,

    /** The report of the voice comes to the screen every few seconds. */
    val reportSeconds: Int = 2
) {

    /** The same knobs as a line of text, so it can be sent in a message. */
    fun toJson(): String {
        val json = JSONObject()
        json.put("audioSource", audioSource)
        json.put("sampleRate", sampleRate)
        json.put("useLowLatency", useLowLatency)
        json.put("hardwareNoiseSuppress", hardwareNoiseSuppress)
        json.put("softwareEcho", softwareEcho)
        json.put("softwareNoise", softwareNoise)
        json.put("autoGain", autoGain)
        json.put("highpass", highpass)
        json.put("maxBitrate", maxBitrate)
        json.put("iceTransport", iceTransport)
        json.put("tcpCandidate", tcpCandidate)
        json.put("candidatePolicy", candidatePolicy)
        json.put("gatherContinually", gatherContinually)
        json.put("bundle", bundle)
        json.put("rtcpMux", rtcpMux)
        json.put("sdpSemantics", sdpSemantics)
        json.put("jitterPackets", jitterPackets)
        json.put("fastAccelerate", fastAccelerate)
        json.put("trickle", trickle)
        json.put("iceRestartOnFail", iceRestartOnFail)
        json.put("reportSeconds", reportSeconds)
        return json.toString()
    }

    companion object {
        const val ICE_ALL = "all"
        const val ICE_RELAY = "relay"

        const val TCP_ENABLED = "enabled"
        const val TCP_DISABLED = "disabled"

        const val CAND_ALL = "all"
        const val CAND_LOW_COST = "low_cost"

        const val BUNDLE_MAX = "maxbundle"
        const val BUNDLE_BALANCED = "balanced"
        const val BUNDLE_COMPAT = "maxcompat"

        const val SDP_UNIFIED = "unified"
        const val SDP_PLAN_B = "plan_b"

        /** Reads the knobs from the line of text; broken parts keep the old value. */
        fun fromJson(value: String): VoiceTuning {
            val plain = VoiceTuning()
            if (value.isBlank()) return plain
            return try {
                val json = JSONObject(value)
                VoiceTuning(
                    audioSource = json.optInt("audioSource", plain.audioSource),
                    sampleRate = json.optInt("sampleRate", plain.sampleRate),
                    useLowLatency = json.optBoolean("useLowLatency", plain.useLowLatency),
                    hardwareNoiseSuppress = json.optBoolean(
                        "hardwareNoiseSuppress",
                        plain.hardwareNoiseSuppress
                    ),
                    softwareEcho = json.optBoolean("softwareEcho", plain.softwareEcho),
                    softwareNoise = json.optBoolean("softwareNoise", plain.softwareNoise),
                    autoGain = json.optBoolean("autoGain", plain.autoGain),
                    highpass = json.optBoolean("highpass", plain.highpass),
                    maxBitrate = json.optInt("maxBitrate", plain.maxBitrate),
                    iceTransport = json.optString("iceTransport", plain.iceTransport),
                    tcpCandidate = json.optString("tcpCandidate", plain.tcpCandidate),
                    candidatePolicy = json.optString("candidatePolicy", plain.candidatePolicy),
                    gatherContinually = json.optBoolean("gatherContinually", plain.gatherContinually),
                    bundle = json.optString("bundle", plain.bundle),
                    rtcpMux = json.optBoolean("rtcpMux", plain.rtcpMux),
                    sdpSemantics = json.optString("sdpSemantics", plain.sdpSemantics),
                    jitterPackets = json.optInt("jitterPackets", plain.jitterPackets),
                    fastAccelerate = json.optBoolean("fastAccelerate", plain.fastAccelerate),
                    trickle = json.optBoolean("trickle", plain.trickle),
                    iceRestartOnFail = json.optBoolean("iceRestartOnFail", plain.iceRestartOnFail),
                    reportSeconds = json.optInt("reportSeconds", plain.reportSeconds)
                )
            } catch (_: Exception) {
                plain
            }
        }
    }
}

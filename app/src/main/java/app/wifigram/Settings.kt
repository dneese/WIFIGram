package app.wifigram

/**
 * The tuning of the voice, of the sound and of the connection.
 *
 * Every knob here is checked by the phone itself: the changes take effect
 * during the next call, or right away when the call is already going.
 */
data class Settings(
    /** The echo of the speaker is taken away by the phone itself. */
    val echoCancel: Boolean = true,

    /** The hum of the fan and of the machines gets quieter. */
    val noiseSuppress: Boolean = true,

    /** The phone itself keeps the voice at the same loudness. */
    val autoGain: Boolean = false,

    /** The voice is coded with Opus: small and clear. */
    val opus: Boolean = true,

    /** How loud the other phone is heard, from 0 to 100. */
    val voiceVolume: Int = 80,

    /** The call begins with the speaker of the phone, not with the earpiece. */
    val speakerDefault: Boolean = false,

    /** The phone rings when the call comes. */
    val incomingSound: Boolean = true,

    /** The way the voice travels: UDP, TCP or the faster one of the two. */
    val transport: String = TRANSPORT_AUTO,

    /** The cushion of the sound, in milliseconds: more of it is steadier. */
    val jitterMs: Int = 80,

    /** How often the phone says that it is alive, in milliseconds. */
    val helloIntervalMs: Int = 3000,

    /** How often the answer to a piece of voice comes back, in milliseconds. */
    val ackIntervalMs: Int = 200
) {
    companion object {
        const val TRANSPORT_AUTO = "auto"
        const val TRANSPORT_UDP = "udp"
        const val TRANSPORT_TCP = "tcp"

        const val MIN_JITTER_MS = 20
        const val MAX_JITTER_MS = 400
        const val MIN_HELLO_MS = 1000
        const val MAX_HELLO_MS = 10000
        const val MIN_ACK_MS = 50
        const val MAX_ACK_MS = 1000
    }
}

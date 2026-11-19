package app.wifigram.call

/** The one engine of the voice: WebRTC over the local network. */
object VoiceEngine {

    /** The name of the engine as the phone shows it. */
    const val NAME = "webrtc"

    /** The mark that says: this phone speaks the new voice. */
    const val CAPABILITY = "rtc2"

    /** The marks of the reasons why the voice did not rise. */
    const val REASON_PROCESS_DIED = "voice-process-died"
    const val REASON_ICE_FAILED = "ice-failed"
    const val REASON_ICE_CLOSED = "ice-closed"
    const val REASON_SIGNAL_ERROR = "signal-error"
    const val REASON_START_FAILED = "start-failed"
    const val REASON_OLD_PEER = "peer-too-old"
}

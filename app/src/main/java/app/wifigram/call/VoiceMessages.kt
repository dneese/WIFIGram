package app.wifigram.call

import android.os.Bundle
import android.os.Message

/**
 * The words between the app and the voice process.
 *
 * The voice of the call lives in a process of its own. If the native part
 * of WebRTC ever falls, only that process dies — the app stays alive and
 * shows what went wrong.
 */
object VoiceMessages {

    /** The app asks the voice process to begin a call. */
    const val START = 1

    /** The app asks the voice process to end the call. */
    const val STOP = 2

    /** The description of the other phone (offer or answer). */
    const val DESCRIPTION = 3

    /** One path of the ice (the address of the other phone). */
    const val CANDIDATE = 4

    /** The microphone is off or on. */
    const val MUTE = 5

    /** The sound goes to the speaker of the phone or to the earpiece. */
    const val SPEAKER = 6

    /** The way of the ice must be looked for again. */
    const val RESTART_ICE = 7

    /** The voice process sends the description or the path back. */
    const val SIGNAL = 10

    /** The voice process sends the state of the connection. */
    const val STATE = 11

    /** The voice process sends the numbers of the way. */
    const val STATS = 12

    /** Something went wrong; the text says what. */
    const val ERROR = 13

    const val KEY_CALL_ID = "callId"
    const val KEY_INITIATOR = "initiator"
    const val KEY_KIND = "kind"
    const val KEY_PAYLOAD = "payload"
    const val KEY_SDP_MID = "sdpMid"
    const val KEY_SDP_INDEX = "sdpIndex"
    const val KEY_FLAG = "flag"
    const val KEY_STATE = "state"
    const val KEY_RTT = "rtt"
    const val KEY_JITTER = "jitter"
    const val KEY_LOSS = "loss"
    const val KEY_CODEC = "codec"

    const val KIND_OFFER = "offer"
    const val KIND_ANSWER = "answer"
    const val KIND_CANDIDATE = "candidate"

    fun bundleOf(vararg pairs: Pair<String, Any>): Bundle = Bundle().apply {
        pairs.forEach { (key, value) ->
            when (value) {
                is String -> putString(key, value)
                is Int -> putInt(key, value)
                is Long -> putLong(key, value)
                is Boolean -> putBoolean(key, value)
                is Double -> putDouble(key, value)
                else -> putString(key, value.toString())
            }
        }
    }

    fun message(what: Int, data: Bundle? = null): Message =
        Message.obtain(null, what).apply { data?.let { setData(it) } }
}

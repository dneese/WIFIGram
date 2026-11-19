package app.wifigram.call

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.core.content.ContextCompat

/**
 * The door to the voice process.
 *
 * The app never touches the native library of WebRTC itself: it speaks to
 * the voice process through a messenger. If that process falls, the app
 * hears about it and shows what went wrong.
 */
class VoiceClient(
    private val context: Context,
    private val callbacks: Callbacks
) {

    /** What the voice process tells to the app. */
    interface Callbacks {
        fun onSignal(kind: String, payload: String)
        fun onState(state: String)
        fun onStats(rttMs: Int, jitterMs: Int, lossPercent: Int, codec: String)
        fun onError(text: String)
        fun onDead(text: String)
    }

    private var service: Messenger? = null
    private var bound = false
    private var connecting = false
    private var pending: MutableList<Pair<Int, Bundle?>> = mutableListOf()

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            val data = message.data ?: Bundle()
            when (message.what) {
                VoiceMessages.SIGNAL -> callbacks.onSignal(
                    data.getString(VoiceMessages.KEY_KIND).orEmpty(),
                    data.getString(VoiceMessages.KEY_PAYLOAD).orEmpty()
                )

                VoiceMessages.STATE -> callbacks.onState(
                    data.getString(VoiceMessages.KEY_STATE).orEmpty()
                )

                VoiceMessages.STATS -> callbacks.onStats(
                    data.getInt(VoiceMessages.KEY_RTT, -1),
                    data.getInt(VoiceMessages.KEY_JITTER, 0),
                    data.getInt(VoiceMessages.KEY_LOSS, 0),
                    data.getString(VoiceMessages.KEY_CODEC).orEmpty()
                )

                VoiceMessages.ERROR -> callbacks.onError(
                    data.getString(VoiceMessages.KEY_PAYLOAD).orEmpty()
                )
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val messenger = Messenger(binder)
            service = messenger
            bound = true
            connecting = false
            val waiting = pending.toList()
            pending.clear()
            waiting.forEach { (what, data) -> sendNow(what, data) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            connecting = false
            callbacks.onDead("The voice process fell")
        }

        override fun onBindingDied(name: ComponentName?) {
            service = null
            bound = false
            connecting = false
            callbacks.onDead("The voice process is gone")
        }

        override fun onNullBinding(name: ComponentName?) {
            service = null
            bound = false
            connecting = false
            callbacks.onDead("The voice process did not answer")
        }
    }

    /** Opens the door to the voice process. */
    fun connect() {
        if (bound || connecting) return
        connecting = true
        try {
            val intent = Intent(context, VoiceService::class.java)
            intent.action = VoiceService.ACTION_START
            intent.putExtra(VoiceService.EXTRA_CLIENT, Messenger(handler))
            ContextCompat.startForegroundService(context, intent)
            val ok = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!ok) {
                connecting = false
                callbacks.onDead("The voice process did not open")
            }
        } catch (error: Exception) {
            connecting = false
            callbacks.onDead(error.message ?: "The voice process did not open")
        }
    }

    /** Begins a call in the voice process. */
    fun start(callId: String, initiator: Boolean) {
        val data = VoiceMessages.bundleOf(
            VoiceMessages.KEY_CALL_ID to callId,
            VoiceMessages.KEY_INITIATOR to initiator
        )
        if (!bound) {
            pending.clear()
            pending.add(VoiceMessages.START to data)
            connect()
            return
        }
        send(VoiceMessages.START, data)
    }

    fun handleDescription(kind: String, payload: String) {
        send(
            VoiceMessages.DESCRIPTION,
            VoiceMessages.bundleOf(
                VoiceMessages.KEY_KIND to kind,
                VoiceMessages.KEY_PAYLOAD to payload
            )
        )
    }

    fun handleCandidate(sdpMid: String, sdpIndex: Int, candidate: String) {
        send(
            VoiceMessages.CANDIDATE,
            VoiceMessages.bundleOf(
                VoiceMessages.KEY_SDP_MID to sdpMid,
                VoiceMessages.KEY_SDP_INDEX to sdpIndex,
                VoiceMessages.KEY_PAYLOAD to candidate
            )
        )
    }

    fun setMuted(muted: Boolean) {
        send(VoiceMessages.MUTE, VoiceMessages.bundleOf(VoiceMessages.KEY_FLAG to muted))
    }

    fun setSpeaker(speaker: Boolean) {
        send(VoiceMessages.SPEAKER, VoiceMessages.bundleOf(VoiceMessages.KEY_FLAG to speaker))
    }

    /** Looks for the way of the ice again after the WiFi came back. */
    fun restartIce() {
        send(VoiceMessages.RESTART_ICE, null)
    }

    /** Ends the call in the voice process. */
    fun stop() {
        if (bound) send(VoiceMessages.STOP, null)
        pending.clear()
    }

    /** Closes the door to the voice process. */
    fun release() {
        stop()
        try {
            if (bound) context.unbindService(connection)
        } catch (_: Exception) {
        }
        bound = false
        connecting = false
        service = null
        try { context.stopService(Intent(context, VoiceService::class.java)) } catch (_: Exception) { }
    }

    private fun send(what: Int, data: Bundle?) {
        if (service == null) {
            pending.add(what to data)
            return
        }
        sendNow(what, data)
    }

    private fun sendNow(what: Int, data: Bundle?) {
        val messenger = service ?: return
        try {
            messenger.send(VoiceMessages.message(what, data))
        } catch (_: Exception) {
        }
    }
}

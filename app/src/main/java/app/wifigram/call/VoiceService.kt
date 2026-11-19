package app.wifigram.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.core.app.NotificationCompat
import app.wifigram.CrashLog
import app.wifigram.Locales
import app.wifigram.ProfileStore
import app.wifigram.R
import java.util.concurrent.Executors

/**
 * The voice of the call lives in a process of its own.
 *
 * The native part of WebRTC is a big library of C++ and it may fall on
 * some phones. When it falls here, only this process dies: the app stays
 * alive, the call screen stays alive, and the journal says where the phone
 * stopped.
 */
class VoiceService : Service() {

    private var client: Messenger? = null
    private var engine: WebRtcEngine? = null
    private var callId: String = ""

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "wifigram-voice").apply { isDaemon = true }
    }

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            val data = message.data ?: Bundle()
            worker.execute { handleCommand(message.what, data) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification())
        installCrashHandler()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? {
        client = intent?.getParcelableExtra(EXTRA_CLIENT)
        return Messenger(handler)
    }

    override fun onDestroy() {
        val created = engine
        engine = null
        if (created != null) {
            try { worker.execute { created.close() } } catch (_: Exception) { }
        }
        try { worker.shutdown() } catch (_: Exception) { }
        super.onDestroy()
    }

    private fun handleCommand(what: Int, data: Bundle) {
        when (what) {
            VoiceMessages.START -> {
                callId = data.getString(VoiceMessages.KEY_CALL_ID).orEmpty()
                val initiator = data.getBoolean(VoiceMessages.KEY_INITIATOR, false)
                startEngine(initiator)
            }

            VoiceMessages.STOP -> stopEngine()

            VoiceMessages.DESCRIPTION -> {
                val kind = data.getString(VoiceMessages.KEY_KIND).orEmpty()
                val payload = data.getString(VoiceMessages.KEY_PAYLOAD).orEmpty()
                engine?.handleDescription(kind, payload)
            }

            VoiceMessages.CANDIDATE -> {
                val mid = data.getString(VoiceMessages.KEY_SDP_MID).orEmpty()
                val index = data.getInt(VoiceMessages.KEY_SDP_INDEX, 0)
                val candidate = data.getString(VoiceMessages.KEY_PAYLOAD).orEmpty()
                engine?.handleCandidate(mid, index, candidate)
            }

            VoiceMessages.MUTE -> engine?.setMuted(data.getBoolean(VoiceMessages.KEY_FLAG, false))

            VoiceMessages.SPEAKER -> engine?.setSpeaker(data.getBoolean(VoiceMessages.KEY_FLAG, false))

            VoiceMessages.RESTART_ICE -> engine?.restartIce()
        }
    }

    private fun startEngine(initiator: Boolean) {
        stopEngine()
        VoiceTrace.stage(this, "engine-create")
        val created = WebRtcEngine(this, object : WebRtcEngine.Callbacks {
            override fun onSignal(kind: String, payload: String) {
                reply(
                    VoiceMessages.SIGNAL,
                    VoiceMessages.bundleOf(
                        VoiceMessages.KEY_KIND to kind,
                        VoiceMessages.KEY_PAYLOAD to payload,
                        VoiceMessages.KEY_CALL_ID to callId
                    )
                )
            }

            override fun onState(state: String) {
                VoiceTrace.stage(this@VoiceService, "state-$state")
                reply(
                    VoiceMessages.STATE,
                    VoiceMessages.bundleOf(
                        VoiceMessages.KEY_STATE to state,
                        VoiceMessages.KEY_CALL_ID to callId
                    )
                )
            }

            override fun onStats(rttMs: Int, jitterMs: Int, lossPercent: Int, codec: String) {
                reply(
                    VoiceMessages.STATS,
                    VoiceMessages.bundleOf(
                        VoiceMessages.KEY_RTT to rttMs,
                        VoiceMessages.KEY_JITTER to jitterMs,
                        VoiceMessages.KEY_LOSS to lossPercent,
                        VoiceMessages.KEY_CODEC to codec,
                        VoiceMessages.KEY_CALL_ID to callId
                    )
                )
            }

            override fun onError(text: String) {
                VoiceTrace.error(this@VoiceService, text)
                reply(
                    VoiceMessages.ERROR,
                    VoiceMessages.bundleOf(
                        VoiceMessages.KEY_PAYLOAD to text,
                        VoiceMessages.KEY_CALL_ID to callId
                    )
                )
            }
        })
        engine = created
        VoiceTrace.stage(this, "engine-start")
        val settings = ProfileStore.settings(this)
        created.setVolume(settings.voiceVolume)
        created.setSpeaker(settings.speakerDefault)
        created.start(initiator)
    }

    private fun stopEngine() {
        try { engine?.close() } catch (_: Exception) { }
        engine = null
    }

    private fun reply(what: Int, data: Bundle) {
        try {
            client?.send(VoiceMessages.message(what, data))
        } catch (_: Exception) {
        }
    }

    /** A fall of the voice is written down before the process dies. */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                VoiceTrace.error(this, "uncaught ${error.javaClass.simpleName}: ${error.message}")
                CrashLog.save(this, error)
            } catch (_: Exception) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            text(R.string.voice_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, app.wifigram.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(text(R.string.app_name))
            .setContentText(text(R.string.voice_notification))
            .setSmallIcon(R.drawable.ic_stat_call)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .build()
    }

    private fun text(id: Int): String = Locales.apply(this).getString(id)

    companion object {
        const val ACTION_START = "app.wifigram.VOICE_START"
        const val EXTRA_CLIENT = "app.wifigram.VOICE_CLIENT"

        private const val CHANNEL_ID = "wifigram_voice"
        private const val NOTIFICATION_ID = 20
    }
}

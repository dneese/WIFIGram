package app.wifigram

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the station alive while the screen is closed.
 *
 * The service holds a small notification and watches the calls, so an
 * incoming call rings even when the app is in the background.
 */
class IntercomService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engine by lazy { App.engine(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, stationNotification())
        watchCalls()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ANSWER -> engine.answerCall()
            ACTION_REJECT -> engine.rejectCall()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** When a call comes, the phone rings with a full screen window. */
    private fun watchCalls() {
        scope.launch {
            engine.call.collect { call ->
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (call != null && call.phase == Intercom.Phase.INCOMING) {
                    manager.notify(CALL_NOTIFICATION_ID, incomingNotification(call))
                } else {
                    manager.cancel(CALL_NOTIFICATION_ID)
                    startForeground(NOTIFICATION_ID, stationNotification())
                }
            }
        }
    }

    /** The words of the service speak the chosen language. */
    private fun text(id: Int): String = Locales.apply(this).getString(id)

    private fun text(id: Int, count: Int): String = Locales.apply(this).getString(id, count)

    private fun stationNotification(): Notification {
        val peers = engine.peers.value.count { it.online }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(text(R.string.app_name))
            .setContentText(
                if (peers > 0) text(R.string.service_peers, peers)
                else text(R.string.service_waiting)
            )
            .setSmallIcon(R.drawable.ic_stat_call)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp())
            .build()
    }

    private fun incomingNotification(call: Intercom.Call): Notification {
        val full = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).apply {
                action = ACTION_SHOW
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val answer = PendingIntent.getService(
            this,
            2,
            Intent(this, IntercomService::class.java).apply { action = ACTION_ANSWER },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val reject = PendingIntent.getService(
            this,
            3,
            Intent(this, IntercomService::class.java).apply { action = ACTION_REJECT },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CALL_CHANNEL_ID)
            .setContentTitle(text(R.string.call_incoming))
            .setContentText(call.peerName)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .addAction(R.drawable.ic_stat_call, text(R.string.call_answer), answer)
            .addAction(R.drawable.ic_stat_call, text(R.string.call_reject), reject)
            .build()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val station = NotificationChannel(
            CHANNEL_ID,
            text(R.string.service_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
        }
        val call = NotificationChannel(
            CALL_CHANNEL_ID,
            text(R.string.call_channel),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            enableVibration(true)
            setSound(
                android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE),
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .build()
            )
        }
        manager.createNotificationChannel(station)
        manager.createNotificationChannel(call)
    }

    companion object {
        private const val CHANNEL_ID = "wifigram_station"
        private const val CALL_CHANNEL_ID = "wifigram_calls"
        private const val NOTIFICATION_ID = 10
        private const val CALL_NOTIFICATION_ID = 11

        const val ACTION_ANSWER = "app.wifigram.ANSWER"
        const val ACTION_REJECT = "app.wifigram.REJECT"
        const val ACTION_SHOW = "app.wifigram.SHOW"

        /** Starts the station in the background. */
        fun start(context: Context) {
            val intent = Intent(context, IntercomService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, IntercomService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}

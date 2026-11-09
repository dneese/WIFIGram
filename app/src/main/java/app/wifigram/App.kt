package app.wifigram

import android.content.Context
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The one station of the phone.
 *
 * The service and the screen use the same station, so a call is heard even
 * when the screen is closed.
 */
object App {

    @Volatile
    private var engine: Intercom? = null

    /** The station of this phone, it is started with the first call. */
    fun engine(context: Context): Intercom {
        engine?.let { return it }
        synchronized(this) {
            engine?.let { return it }
            val app = context.applicationContext
            val scope = CoroutineScope(
                SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error ->
                    CrashLog.save(app, error)
                }
            )
            val created = Intercom(
                context = app,
                scope = scope,
                name = ProfileStore.name(app)
                    .ifEmpty { Locales.apply(app).getString(R.string.phone_fallback) },
                direct = ProfileStore.direct(app),
                host = ProfileStore.host(app)
            )
            engine = created
            created.start()
            return created
        }
    }
}

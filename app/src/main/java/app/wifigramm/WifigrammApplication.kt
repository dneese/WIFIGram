package app.wifigramm

import android.app.Application

/**
 * The app catches every error that is not handled, writes it down and shows
 * it inside the app instead of closing without a word.
 */
class WifigrammApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            CrashLog.save(this, error)
            if (previous != null) {
                previous.uncaughtException(thread, error)
            }
        }
    }
}

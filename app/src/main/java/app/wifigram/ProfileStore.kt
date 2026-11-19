package app.wifigram

import android.content.Context
import android.content.SharedPreferences

/**
 * What the phone remembers about itself: the name and the mode of the
 * connection.
 */
object ProfileStore {

    private const val PREFS = "wifigram_profile"
    private const val KEY_NAME = "name"
    private const val KEY_DIRECT = "direct"
    private const val KEY_HOST = "host"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_VOLUME = "voice_volume"
    private const val KEY_SPEAKER = "speaker_default"
    private const val KEY_RING = "incoming_sound"

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The name of the phone, the other phones see it in the station. */
    fun name(context: Context): String =
        prefs(context).getString(KEY_NAME, "") ?: ""

    fun saveName(context: Context, name: String) {
        prefs(context).edit().putString(KEY_NAME, name.trim()).apply()
    }

    /** Is the station connected directly to the other phone or through a router? */
    fun direct(context: Context): Boolean = prefs(context).getBoolean(KEY_DIRECT, false)

    /** In the direct mode the phone either creates the group or joins it. */
    fun host(context: Context): Boolean = prefs(context).getBoolean(KEY_HOST, true)

    fun saveMode(context: Context, direct: Boolean, host: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_DIRECT, direct)
            .putBoolean(KEY_HOST, host)
            .apply()
    }

    /** All the tuning of the voice and of the sound. */
    fun settings(context: Context): Settings {
        val store = prefs(context)
        return Settings(
            voiceVolume = store.getInt(KEY_VOLUME, 80),
            speakerDefault = store.getBoolean(KEY_SPEAKER, false),
            incomingSound = store.getBoolean(KEY_RING, true)
        )
    }

    fun saveSettings(context: Context, value: Settings) {
        prefs(context).edit()
            .putInt(KEY_VOLUME, value.voiceVolume.coerceIn(0, 100))
            .putBoolean(KEY_SPEAKER, value.speakerDefault)
            .putBoolean(KEY_RING, value.incomingSound)
            .apply()
    }

    /** The language of the program: Ukrainian or English. */
    fun language(context: Context): String =
        prefs(context).getString(KEY_LANGUAGE, Locales.UKRAINIAN) ?: Locales.UKRAINIAN

    fun saveLanguage(context: Context, value: String) {
        prefs(context).edit().putString(KEY_LANGUAGE, value).apply()
    }
}

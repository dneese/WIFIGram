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
    private const val KEY_DENOISE = "denoise"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_ECHO = "echo_cancel"
    private const val KEY_NOISE = "noise_suppress"
    private const val KEY_GAIN = "auto_gain"
    private const val KEY_OPUS = "opus"
    private const val KEY_VOLUME = "voice_volume"
    private const val KEY_SPEAKER = "speaker_default"
    private const val KEY_RING = "incoming_sound"
    private const val KEY_TRANSPORT = "transport"
    private const val KEY_JITTER = "jitter_ms"
    private const val KEY_HELLO = "hello_interval_ms"
    private const val KEY_ACK = "ack_interval_ms"

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

    /** The noise catcher: the hum and the fan get quieter, the voice stays. */
    fun denoise(context: Context): Boolean = prefs(context).getBoolean(KEY_DENOISE, true)

    fun saveDenoise(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_DENOISE, value).apply()
    }

    /** All the tuning of the voice, of the sound and of the connection. */
    fun settings(context: Context): Settings {
        val store = prefs(context)
        return Settings(
            echoCancel = store.getBoolean(KEY_ECHO, true),
            noiseSuppress = store.getBoolean(KEY_NOISE, true),
            autoGain = store.getBoolean(KEY_GAIN, false),
            opus = store.getBoolean(KEY_OPUS, true),
            voiceVolume = store.getInt(KEY_VOLUME, 80),
            speakerDefault = store.getBoolean(KEY_SPEAKER, false),
            incomingSound = store.getBoolean(KEY_RING, true),
            transport = store.getString(KEY_TRANSPORT, Settings.TRANSPORT_AUTO)
                ?: Settings.TRANSPORT_AUTO,
            jitterMs = store.getInt(KEY_JITTER, 80),
            helloIntervalMs = store.getInt(KEY_HELLO, 3000),
            ackIntervalMs = store.getInt(KEY_ACK, 200)
        )
    }

    fun saveSettings(context: Context, value: Settings) {
        prefs(context).edit()
            .putBoolean(KEY_ECHO, value.echoCancel)
            .putBoolean(KEY_NOISE, value.noiseSuppress)
            .putBoolean(KEY_DENOISE, value.noiseSuppress)
            .putBoolean(KEY_GAIN, value.autoGain)
            .putBoolean(KEY_OPUS, value.opus)
            .putInt(KEY_VOLUME, value.voiceVolume.coerceIn(0, 100))
            .putBoolean(KEY_SPEAKER, value.speakerDefault)
            .putBoolean(KEY_RING, value.incomingSound)
            .putString(KEY_TRANSPORT, value.transport)
            .putInt(KEY_JITTER, value.jitterMs.coerceIn(Settings.MIN_JITTER_MS, Settings.MAX_JITTER_MS))
            .putInt(KEY_HELLO, value.helloIntervalMs.coerceIn(Settings.MIN_HELLO_MS, Settings.MAX_HELLO_MS))
            .putInt(KEY_ACK, value.ackIntervalMs.coerceIn(Settings.MIN_ACK_MS, Settings.MAX_ACK_MS))
            .apply()
    }

    /** The language of the program: Ukrainian or English. */
    fun language(context: Context): String =
        prefs(context).getString(KEY_LANGUAGE, Locales.UKRAINIAN) ?: Locales.UKRAINIAN

    fun saveLanguage(context: Context, value: String) {
        prefs(context).edit().putString(KEY_LANGUAGE, value).apply()
    }
}

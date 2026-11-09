package app.wifigram

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * The language of the program.
 *
 * The phone speaks Ukrainian or English, whatever the system speaks — the
 * choice of the settings is kept in the settings of the phone and is put
 * on the context, so every string of the program and of the service comes
 * in the chosen language.
 */
object Locales {

    const val UKRAINIAN = "uk"
    const val ENGLISH = "en"

    /** The context that speaks the chosen language. */
    fun apply(context: Context): Context {
        val language = ProfileStore.language(context)
        val locale = Locale(language)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return context.createConfigurationContext(config)
    }
}

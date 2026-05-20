package com.family.locationsender.util

import android.content.Context
import android.content.res.Configuration
import com.family.locationsender.data.Prefs
import java.util.Locale

object LocaleHelper {

    /** Apply currently-saved locale to a context. */
    fun applySaved(base: Context): Context {
        val lang = Prefs.get(base).language
        return wrap(base, lang)
    }

    fun setLanguage(context: Context, lang: String) {
        Prefs.get(context).language = lang
    }

    private fun wrap(context: Context, lang: String): Context {
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return context.createConfigurationContext(config)
    }
}

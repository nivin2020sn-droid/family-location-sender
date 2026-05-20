package com.family.locationsender.util

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import com.family.locationsender.data.Prefs
import java.util.Locale

object LocaleHelper {

    private const val TAG = "FLS-LocaleHelper"

    /** Apply currently-saved locale to a context. Safe against any failure. */
    fun applySaved(base: Context): Context {
        return try {
            val lang = Prefs.get(base).language
            wrap(base, lang)
        } catch (t: Throwable) {
            Log.e(TAG, "applySaved failed, using base context", t)
            base
        }
    }

    fun setLanguage(context: Context, lang: String) {
        try {
            Prefs.get(context).language = lang
        } catch (t: Throwable) {
            Log.e(TAG, "setLanguage failed", t)
        }
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

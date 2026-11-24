package com.nextgentool.nudgy.settings

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Sprint 6-2 — per-app UI language (spec §2 Q10=(a)).
 *
 * No AppCompat in this codebase (no appcompat dependency), so the
 * locale is applied the manual way: [wrap] returns a configuration
 * context carrying the stored language, MainActivity applies it in
 * attachBaseContext (so a fresh launch already renders in the
 * chosen language), and [applyAndRecreate] restarts the Activity
 * when the Settings choice changes it (a recreate is the only way
 * a new locale reaches already-inflated resources).
 *
 * Scope (spec §2 Q10): Bengali strings exist for the Settings
 * screen + the main screens (Today/Tasks/navigation) only; the
 * rest of the app still renders its hardcoded English strings
 * until a later sprint migrates them. The chat VOICE toggle
 * (Sprint 3-7) is voice input/output, not UI language — untouched.
 */
object AppLocale {

    /** "bn" → Bengali; anything else (incl. unknown codes) → English. */
    fun localeFor(code: String): Locale =
        if (code == SettingsPreferences.LANGUAGE_BENGALI) Locale("bn") else Locale.ENGLISH

    /** The stored language code ("en" default — the S6-0 store's default). */
    fun storedCode(context: Context): String =
        context.getSharedPreferences(SettingsPreferences.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(SettingsPreferences.KEY_LANGUAGE, SettingsPreferences.LANGUAGE_ENGLISH)
            ?: SettingsPreferences.LANGUAGE_ENGLISH

    /**
     * A copy of [base] whose resources resolve in [code]'s locale
     * (stringResource picks values-bn when code is "bn"). Pure on
     * the configuration — no Activity, no restart.
     */
    fun wrap(base: Context, code: String): Context {
        val config = Configuration(base.resources.configuration)
        config.setLocale(localeFor(code))
        return base.createConfigurationContext(config)
    }

    /**
     * Restarts [activity] when its current locale differs from
     * [code]'s — a no-op when already on it (e.g. tapping the
     * already-selected language). The caller writes the preference
     * first (SettingsViewModel.setLanguage); this only restarts.
     */
    fun applyAndRecreate(activity: Activity, code: String) {
        val current = activity.resources.configuration.locales.get(0)
        if (current.language != localeFor(code).language) {
            activity.recreate()
        }
    }
}

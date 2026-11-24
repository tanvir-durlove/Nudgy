package com.nextgentool.nudgy.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Sprint 6-0 — the Settings preference store (foundation).
 *
 * All Sprint 6 settings live in the v1 SharedPreferences file
 * "nudgy_settings" (v1 MainActivity/YourNudgySheet precedent — the
 * BYOK key already lives there in the rebuild, GeminiClient.kt:74).
 * Each value is a StateFlow seeded from the prefs at construction,
 * so a write in S6-2's Appearance/language UI recomposes every
 * collector (MainActivity → NudgyApp → NudgyTheme) with no restart.
 *
 * What this sub-PR wires: theme_mode read at app start (S6-2 adds
 * the Appearance UI). What it only exposes: the language preference
 * (English default; S6-2 adds the Bengali strings + UI) and the
 * onboarding flag (S6-3 reads it to show the tutorial once).
 * No Bengali strings, no language switching, no tutorial UI here.
 */
class SettingsPreferences(private val prefs: SharedPreferences) {

    // A stored value outside the known set (e.g. written by hand or
    // an older build) normalizes to "system" — the flow never carries
    // an invalid mode.
    private val _themeMode = MutableStateFlow(
        prefs.getString(KEY_THEME_MODE, THEME_SYSTEM)?.takeIf { it in THEME_MODES }
            ?: THEME_SYSTEM
    )

    /** "system" | "light" | "dark" — v1 key "theme_mode", default "system". */
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _language = MutableStateFlow(
        prefs.getString(KEY_LANGUAGE, LANGUAGE_ENGLISH) ?: LANGUAGE_ENGLISH
    )

    /** "en" default; "bn" when S6-2 wires the Bengali setting. */
    val language: StateFlow<String> = _language.asStateFlow()

    private val _hasSeenOnboarding = MutableStateFlow(
        prefs.getBoolean(KEY_HAS_SEEN_ONBOARDING, false)
    )

    /** False until the S6-3 tutorial is shown once. */
    val hasSeenOnboarding: StateFlow<Boolean> = _hasSeenOnboarding.asStateFlow()

    /**
     * Unknown values fall back to "system" (never a crash, never
     * stuck dark/light). The normalized value is always persisted —
     * no early return: setting "sepia" while already on "system"
     * must still write "system", so the stored value is the
     * effective value.
     */
    fun setThemeMode(mode: String) {
        val clean = if (mode in THEME_MODES) mode else THEME_SYSTEM
        _themeMode.value = clean
        prefs.edit().putString(KEY_THEME_MODE, clean).apply()
    }

    fun setLanguage(code: String) {
        if (code == _language.value) return
        _language.value = code
        prefs.edit().putString(KEY_LANGUAGE, code).apply()
    }

    fun setHasSeenOnboarding(seen: Boolean) {
        if (seen == _hasSeenOnboarding.value) return
        _hasSeenOnboarding.value = seen
        prefs.edit().putBoolean(KEY_HAS_SEEN_ONBOARDING, seen).apply()
    }

    companion object {
        const val PREFS_NAME = "nudgy_settings"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_LANGUAGE = "language"
        const val KEY_HAS_SEEN_ONBOARDING = "has_seen_onboarding"

        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        val THEME_MODES: Set<String> = setOf(THEME_SYSTEM, THEME_LIGHT, THEME_DARK)

        const val LANGUAGE_ENGLISH = "en"
        const val LANGUAGE_BENGALI = "bn"

        fun from(context: Context): SettingsPreferences =
            SettingsPreferences(
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            )

        /**
         * The v1 rule (origin/main MainActivity.kt:237): "light" is
         * never dark, "dark" is always dark, anything else follows
         * the system. Pure — unit-tested without Robolectric.
         */
        fun resolveDarkTheme(themeMode: String, isSystemDark: Boolean): Boolean =
            when (themeMode) {
                THEME_LIGHT -> false
                THEME_DARK -> true
                else -> isSystemDark
            }
    }
}

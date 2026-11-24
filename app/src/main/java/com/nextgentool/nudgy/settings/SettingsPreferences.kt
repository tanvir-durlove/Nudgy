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
 *
 * Sprint 6-2 — the notification toggles (spec §2 Q8=(a)): v1's
 * "Notification Preferences" dialog had exactly two Switches
 * ("Morning Brief Alert" + "Sound & Haptics") but NEITHER was
 * persisted — both were `remember { mutableStateOf(true) }` only,
 * resetting to ON every time the sheet opened. Here both persist
 * in the same "nudgy_settings" file, default ON (v1's initial
 * state). The Morning Brief worker and the reminder notification
 * path read them through the static readers below (a receiver /
 * worker has only a Context, not the @Singleton store).
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

    private val _morningBriefEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_MORNING_BRIEF, true)
    )

    /** Sprint 6-2 — the 8:00 AM daily summary. Default ON (v1's initial state). */
    val morningBriefEnabled: StateFlow<Boolean> = _morningBriefEnabled.asStateFlow()

    private val _soundEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_SOUND_ENABLED, true)
    )

    /** Sprint 6-2 — reminder sound + vibration. Default ON (v1's initial state). */
    val soundEnabled: StateFlow<Boolean> = _soundEnabled.asStateFlow()

    fun setMorningBriefEnabled(enabled: Boolean) {
        if (enabled == _morningBriefEnabled.value) return
        _morningBriefEnabled.value = enabled
        prefs.edit().putBoolean(KEY_MORNING_BRIEF, enabled).apply()
    }

    fun setSoundEnabled(enabled: Boolean) {
        if (enabled == _soundEnabled.value) return
        _soundEnabled.value = enabled
        prefs.edit().putBoolean(KEY_SOUND_ENABLED, enabled).apply()
    }

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
        const val KEY_MORNING_BRIEF = "morning_brief_enabled"
        const val KEY_SOUND_ENABLED = "sound_enabled"

        /**
         * Static readers for Context-only callers (ReminderReceiver,
         * MorningBriefWorker): the same keys, the same defaults —
         * never a second source of truth.
         */
        fun isMorningBriefEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_MORNING_BRIEF, true)

        fun isSoundEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_SOUND_ENABLED, true)

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

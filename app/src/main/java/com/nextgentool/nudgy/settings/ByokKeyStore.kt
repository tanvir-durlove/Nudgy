package com.nextgentool.nudgy.settings

import android.content.SharedPreferences

/**
 * Sprint 6-1 — the BYOK key store (spec §2 Q2, v1 behaviour).
 *
 * The v1 key lives in the "nudgy_settings" file under
 * "custom_gemini_api_key" (YourNudgySheet.kt:113, GeminiClient.kt:74
 * in the rebuild). The SettingsViewModel reads/writes through this
 * seam so tests can substitute an in-memory fake; production gets
 * the SharedPreferences file.
 *
 * A blank set removes the key — "Using Nudgy's built-in AI" (the
 * v1 dialog's Save-with-blank and Reset behaviour).
 */
interface ByokKeyStore {
    fun getKey(): String?
    fun setKey(key: String?)
}

class SharedPreferencesByokKeyStore(private val prefs: SharedPreferences) : ByokKeyStore {
    override fun getKey(): String? =
        prefs.getString(KEY_CUSTOM_GEMINI_API_KEY, null)?.takeIf { it.isNotBlank() }

    override fun setKey(key: String?) {
        val trimmed = key?.trim()
        if (trimmed.isNullOrBlank()) {
            prefs.edit().remove(KEY_CUSTOM_GEMINI_API_KEY).apply()
        } else {
            prefs.edit().putString(KEY_CUSTOM_GEMINI_API_KEY, trimmed).apply()
        }
    }

    companion object {
        const val KEY_CUSTOM_GEMINI_API_KEY = "custom_gemini_api_key"
    }
}

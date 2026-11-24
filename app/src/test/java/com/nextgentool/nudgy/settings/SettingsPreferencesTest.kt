package com.nextgentool.nudgy.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 6-0 — the Settings preference store (foundation).
 *
 * Uses a test-only SharedPreferences file so the tests never touch
 * the real "nudgy_settings" file (which also holds the BYOK key).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsPreferencesTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var store: SettingsPreferences

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences("test_s6_0_settings", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        store = SettingsPreferences(prefs)
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
    }

    @Test
    fun `defaults are system theme, English, onboarding not seen`() {
        assertEquals(SettingsPreferences.THEME_SYSTEM, store.themeMode.value)
        assertEquals(SettingsPreferences.LANGUAGE_ENGLISH, store.language.value)
        assertFalse(store.hasSeenOnboarding.value)
    }

    @Test
    fun `setThemeMode persists and emits, and a rebuilt store reads it back`() {
        store.setThemeMode(SettingsPreferences.THEME_DARK)
        assertEquals(SettingsPreferences.THEME_DARK, store.themeMode.value)
        assertEquals(SettingsPreferences.THEME_DARK, prefs.getString("theme_mode", null))
        assertEquals(
            SettingsPreferences.THEME_DARK,
            SettingsPreferences(prefs).themeMode.value
        )
    }

    @Test
    fun `unknown theme mode falls back to system and persists system`() {
        store.setThemeMode("sepia")
        assertEquals(SettingsPreferences.THEME_SYSTEM, store.themeMode.value)
        assertEquals(SettingsPreferences.THEME_SYSTEM, prefs.getString("theme_mode", null))
    }

    @Test
    fun `setLanguage persists and emits`() {
        store.setLanguage(SettingsPreferences.LANGUAGE_BENGALI)
        assertEquals(SettingsPreferences.LANGUAGE_BENGALI, store.language.value)
        assertEquals(SettingsPreferences.LANGUAGE_BENGALI, prefs.getString("language", null))
        assertEquals(
            SettingsPreferences.LANGUAGE_BENGALI,
            SettingsPreferences(prefs).language.value
        )
    }

    @Test
    fun `setHasSeenOnboarding persists and emits`() {
        store.setHasSeenOnboarding(true)
        assertTrue(store.hasSeenOnboarding.value)
        assertTrue(prefs.getBoolean("has_seen_onboarding", false))
        assertTrue(SettingsPreferences(prefs).hasSeenOnboarding.value)
    }
}

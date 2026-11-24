package com.nextgentool.nudgy.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sprint 6-0 — the theme-mode resolution rule (v1, origin/main
 * MainActivity.kt:237). Pure: no Robolectric, plain JUnit.
 */
class ThemeModeResolutionTest {

    @Test
    fun `light is never dark`() {
        assertFalse(SettingsPreferences.resolveDarkTheme("light", true))
        assertFalse(SettingsPreferences.resolveDarkTheme("light", false))
    }

    @Test
    fun `dark is always dark`() {
        assertTrue(SettingsPreferences.resolveDarkTheme("dark", true))
        assertTrue(SettingsPreferences.resolveDarkTheme("dark", false))
    }

    @Test
    fun `system follows the system`() {
        assertTrue(SettingsPreferences.resolveDarkTheme("system", true))
        assertFalse(SettingsPreferences.resolveDarkTheme("system", false))
    }

    @Test
    fun `unknown mode follows the system`() {
        assertTrue(SettingsPreferences.resolveDarkTheme("sepia", true))
        assertFalse(SettingsPreferences.resolveDarkTheme("sepia", false))
    }

    @Test
    fun `empty mode follows the system`() {
        assertTrue(SettingsPreferences.resolveDarkTheme("", true))
        assertFalse(SettingsPreferences.resolveDarkTheme("", false))
    }
}

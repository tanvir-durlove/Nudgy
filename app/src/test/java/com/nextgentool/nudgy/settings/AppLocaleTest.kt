package com.nextgentool.nudgy.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 6-2 — per-app UI language (spec §2 Q10=(a)).
 *
 * The locale helper is the whole switch: `wrap` returns a context
 * whose resources resolve in the chosen language (values-bn when
 * "bn"), and unknown codes fall back to English. The Activity
 * restart itself (AppLocale.applyAndRecreate) is a device-test
 * item (spec §10.6) — Robolectric cannot drive a real recreate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppLocaleTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `localeFor maps bn to Bengali and anything else to English`() {
        assertEquals("bn", AppLocale.localeFor("bn").language)
        assertEquals("en", AppLocale.localeFor("en").language)
        assertEquals("en", AppLocale.localeFor("xx").language)
        assertEquals("en", AppLocale.localeFor("").language)
    }

    @Test
    fun `wrapped context resolves the Settings title in Bengali`() {
        val bn = AppLocale.wrap(context, SettingsPreferences.LANGUAGE_BENGALI)
        assertEquals("সেটিংস", bn.resources.getString(R.string.settings_title))
        assertEquals("নোটিফিকেশন", bn.resources.getString(R.string.settings_section_notifications))
        assertEquals("আজ", bn.resources.getString(R.string.nav_today))
        assertEquals("আমার দিন", bn.resources.getString(R.string.today_my_day))
    }

    @Test
    fun `wrapped context resolves the Settings title in English`() {
        val en = AppLocale.wrap(context, SettingsPreferences.LANGUAGE_ENGLISH)
        assertEquals("Settings", en.resources.getString(R.string.settings_title))
        assertEquals("Notifications", en.resources.getString(R.string.settings_section_notifications))
        assertEquals("Today", en.resources.getString(R.string.nav_today))
    }

    @Test
    fun `storedCode defaults to English and reads back a written Bengali choice`() {
        val prefs = context.getSharedPreferences(
            SettingsPreferences.PREFS_NAME, Context.MODE_PRIVATE
        )
        prefs.edit().remove(SettingsPreferences.KEY_LANGUAGE).commit()
        assertEquals("en", AppLocale.storedCode(context))
        prefs.edit().putString(SettingsPreferences.KEY_LANGUAGE, "bn").commit()
        assertEquals("bn", AppLocale.storedCode(context))
        prefs.edit().remove(SettingsPreferences.KEY_LANGUAGE).commit()
    }
}

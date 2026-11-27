package com.nextgentool.nudgy.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.nextgentool.nudgy.auth.NudgyUser
import com.nextgentool.nudgy.data.local.entity.NudgyMemoryEntity
import com.nextgentool.nudgy.ui.theme.NudgyTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 6-1 — the Settings rows (spec §4–§5).
 *
 * The shell renders one row per section (S6-0 contract); the wired
 * rows carry the same `settings_row_<slug>` tags. Below-fold rows
 * are scrolled into view first (performScrollTo — Compose 1.7
 * top-level import, S6-0 lesson).
 *
 * The fake-example guard: with zero memories the dialog shows the
 * honest empty state and the v1 hardcoded sentence ("You prefer
 * focus time in the morning...") must NOT exist anywhere.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsRowsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val signedInUser = NudgyUser(
        uid = "user-1",
        displayName = "Tanvir Ahmed",
        email = "tanvir@example.com"
    )

    private fun setGuestShell(
        onSignInClick: () -> Unit = {},
        onPrivacyClick: () -> Unit = {},
        onHelpFeedbackClick: () -> Unit = {}
    ) {
        composeRule.setContent {
            NudgyTheme {
                SettingsShell(
                    onSignInClick = onSignInClick,
                    onPrivacyClick = onPrivacyClick,
                    onHelpFeedbackClick = onHelpFeedbackClick
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `guest profile shows the guest prompt and the sign-in button fires`() {
        var signInClicks = 0
        setGuestShell(onSignInClick = { signInClicks++ })

        composeRule.onNodeWithText("Guest User").assertIsDisplayed()
        composeRule.onNodeWithText("Sign in with Google to sync your data").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_sign_in_button").performScrollTo().performClick()
        assertEquals(1, signInClicks)
    }

    @Test
    fun `signed-in profile shows name and email and sign-out fires`() {
        var signOutClicks = 0
        composeRule.setContent {
            NudgyTheme {
                SettingsShell(
                    user = signedInUser,
                    onSignOutClick = { signOutClicks++ }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Tanvir Ahmed").assertIsDisplayed()
        composeRule.onNodeWithText("tanvir@example.com").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_row_sign_in").performScrollTo().performClick()
        assertEquals(1, signOutClicks)
    }

    @Test
    fun `memories dialog shows the honest empty state and never the v1 fake example`() {
        setGuestShell()
        composeRule.onNodeWithTag("settings_row_memories").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("settings_memories_dialog").assertIsDisplayed()
        composeRule.onNodeWithText(
            "No memories stored yet. Add one above or tell Nudgy in chat: 'Remember that...'"
        ).assertIsDisplayed()
        // The v1 bug: a hardcoded sentence shown as if remembered.
        composeRule.onNodeWithText("You prefer focus time in the morning and studying at 7 PM.")
            .assertDoesNotExist()
    }

    @Test
    fun `memories dialog add calls back with the trimmed text`() {
        val added = mutableListOf<String>()
        composeRule.setContent {
            NudgyTheme {
                SettingsShell(onAddMemory = { added += it })
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_row_memories").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("settings_memory_input").performTextInput("  Likes morning focus  ")
        composeRule.onNodeWithText("Add").performClick()
        assertEquals(listOf("Likes morning focus"), added)
    }

    @Test
    fun `memories dialog lists memories and delete fires with the id`() {
        val memory = NudgyMemoryEntity(
            id = "mem-1",
            userId = "user-1",
            content = "Prefers evening study"
        )
        val deleted = mutableListOf<String>()
        composeRule.setContent {
            NudgyTheme {
                SettingsShell(
                    user = signedInUser,
                    memories = listOf(memory),
                    onDeleteMemory = { deleted += it }
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_row_memories").performScrollTo()
        composeRule.onNodeWithText("1 facts").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_row_memories").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Prefers evening study").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Delete memory").performClick()
        assertEquals(listOf("mem-1"), deleted)
    }

    @Test
    fun `byok dialog save and reset call back`() {
        val saved = mutableListOf<String>()
        var resets = 0
        composeRule.setContent {
            NudgyTheme {
                SettingsShell(
                    onSaveByokKey = { saved += it },
                    onResetByokKey = { resets++ }
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_row_gemini_key").performScrollTo()
        composeRule.onNodeWithText("Using Nudgy's built-in AI").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_row_gemini_key").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("settings_byok_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_byok_input").performTextInput("AIzaSy-test")
        composeRule.onNodeWithText("Save").performClick()
        assertEquals(listOf("AIzaSy-test"), saved)

        // Reopen and reset.
        composeRule.onNodeWithTag("settings_row_gemini_key").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Reset").performClick()
        assertEquals(1, resets)
    }

    @Test
    fun `delete dialog cancel does nothing and confirm calls back`() {
        var deletes = 0
        composeRule.setContent {
            NudgyTheme {
                SettingsShell(onDeleteAccountClick = { deletes++ })
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_row_delete_account").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("settings_delete_dialog").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.waitForIdle()
        assertEquals(0, deletes)

        composeRule.onNodeWithTag("settings_row_delete_account").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Delete Forever").performClick()
        assertEquals(1, deletes)
    }

    @Test
    fun `privacy and help rows fire their callbacks`() {
        var privacyClicks = 0
        var helpClicks = 0
        setGuestShell(
            onPrivacyClick = { privacyClicks++ },
            onHelpFeedbackClick = { helpClicks++ }
        )
        composeRule.onNodeWithTag("settings_row_privacy").performScrollTo().performClick()
        composeRule.onNodeWithTag("settings_row_help_feedback").performScrollTo().performClick()
        assertEquals(1, privacyClicks)
        assertEquals(1, helpClicks)
    }

    @Test
    fun `calendar row offers connect when disconnected and sync when connected`() {
        var connects = 0
        var syncs = 0
        // One setContent per test (the rule throws on a second call —
        // PlanScreenTest): the connected state flips through a
        // mutableState instead.
        var connected by mutableStateOf(false)
        composeRule.setContent {
            NudgyTheme {
                SettingsShell(
                    calendarConnected = connected,
                    onCalendarConnectClick = { connects++ },
                    onCalendarSyncClick = { syncs++ }
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_row_calendar").performScrollTo()
        composeRule.onNodeWithText("Bring your calendar in").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_calendar_connect_button").performClick()
        assertEquals(1, connects)

        composeRule.runOnIdle { connected = true }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_row_calendar").performScrollTo()
        composeRule.onNodeWithText("Calendar synced").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_calendar_sync_button").performClick()
        assertEquals(1, syncs)
    }

    @Test
    fun `notifications appearance and language rows are wired, no Soon remains`() {
        setGuestShell()
        // Sprint 6-2 — the three rows are real now: no "Soon" text
        // anywhere on the screen, and all three row tags exist.
        assertEquals(
            0,
            composeRule.onAllNodesWithText("Soon").fetchSemanticsNodes().size
        )
        for (slug in listOf("notifications", "appearance", "language")) {
            assertEquals(
                1,
                composeRule.onAllNodesWithTag("settings_row_$slug").fetchSemanticsNodes().size
            )
        }
        composeRule.onNodeWithTag("settings_row_notifications").performScrollTo()
        composeRule.onNodeWithText("Morning Brief Alert").assertIsDisplayed()
        composeRule.onNodeWithText("Sound & Haptics").assertIsDisplayed()
    }

    @Test
    fun `notification toggles fire their callbacks with the flipped value`() {
        val briefChanges = mutableListOf<Boolean>()
        val soundChanges = mutableListOf<Boolean>()
        composeRule.setContent {
            NudgyTheme {
                SettingsShell(
                    onMorningBriefChange = { briefChanges += it },
                    onSoundChange = { soundChanges += it }
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_morning_brief_switch").performScrollTo().performClick()
        composeRule.onNodeWithTag("settings_sound_switch").performScrollTo().performClick()
        assertEquals(listOf(false), briefChanges)
        assertEquals(listOf(false), soundChanges)
    }

    /**
     * Sprint 7 — the Appearance row is an inline Light/Dark/System
     * seg now (the S6-2 dialog is gone): the pills carry the same
     * `settings_theme_<mode>` tags the dialog rows had, and tapping
     * one writes the mode at once.
     */
    @Test
    fun `appearance seg shows the pills and tapping Dark calls back`() {
        val modes = mutableListOf<String>()
        composeRule.setContent {
            NudgyTheme {
                SettingsShell(onThemeModeChange = { modes += it })
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_row_appearance").performScrollTo()
        composeRule.onNodeWithText("Light is the default high-contrast theme").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_theme_light").assertExists()
        composeRule.onNodeWithTag("settings_theme_dark").assertExists()
        composeRule.onNodeWithTag("settings_theme_system").assertExists()
        // The dialog is gone: no appearance dialog anywhere.
        composeRule.onNodeWithTag("settings_appearance_dialog").assertDoesNotExist()
        composeRule.onNodeWithTag("settings_theme_dark").performClick()
        assertEquals(listOf("dark"), modes)
    }

    /** Sprint 7 — the Statistics row opens the Stats screen. */
    @Test
    fun `statistics row fires its callback`() {
        var clicks = 0
        composeRule.setContent {
            NudgyTheme {
                SettingsShell(onStatisticsClick = { clicks++ })
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_row_statistics").performScrollTo()
        composeRule.onNodeWithText("Completed, streaks, focus time").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_row_statistics").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun `language dialog opens and picking Bengali calls back`() {
        val codes = mutableListOf<String>()
        composeRule.setContent {
            NudgyTheme {
                SettingsShell(onLanguageChange = { codes += it })
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_row_language").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_language_dialog").assertIsDisplayed()
        composeRule.onNodeWithText("বাংলা").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_language_bn").performClick()
        assertEquals(listOf("bn"), codes)
    }

    /**
     * Sprint 6-6 (step 4d) — the App lock row (spec §2 Q6, §8).
     *
     * The switch reports the flip; the row's subtitle follows the
     * stored preference (OFF by default). The "App lock" title
     * repeats in the section header, so it is never asserted by
     * text — the unique subtitles carry the assertions (the
     * appearance/language duplicate-text lesson).
     */
    @Test
    fun `app lock row shows the switch and toggling fires with the flipped value`() {
        val changes = mutableListOf<Boolean>()
        var locked by mutableStateOf(false)
        composeRule.setContent {
            NudgyTheme {
                SettingsShell(
                    appLockEnabled = locked,
                    onAppLockChange = { changes += it }
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_row_app_lock").performScrollTo()
        composeRule.onNodeWithText("Off — your data opens without a check").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_app_lock_switch").performClick()
        assertEquals(listOf(true), changes)

        composeRule.runOnIdle { locked = true }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Locked — fingerprint, face or device PIN").assertIsDisplayed()
    }

    /**
     * Sprint 6-6 (step 4d) — the Backup & restore rows (spec §2 Q7,
     * §8).
     *
     * The two inner rows fire their own callbacks (the SAF pickers
     * themselves are device-gated — no file picker exists on the
     * JVM — so the rows only report the tap here).
     */
    @Test
    fun `backup and restore rows fire their callbacks`() {
        var exports = 0
        var restores = 0
        composeRule.setContent {
            NudgyTheme {
                SettingsShell(
                    onExportClick = { exports++ },
                    onRestoreClick = { restores++ }
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_export_row").performScrollTo()
        composeRule.onNodeWithText("Export my data").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_export_row").performClick()
        composeRule.onNodeWithTag("settings_restore_row").performScrollTo().performClick()
        assertEquals(1, exports)
        assertEquals(1, restores)
    }

    @Test
    fun `settings screen re-renders in Bengali when the locale context is Bengali`() {
        val context: android.content.Context =
            androidx.test.core.app.ApplicationProvider.getApplicationContext()
        val bnContext = com.nextgentool.nudgy.settings.AppLocale.wrap(
            context,
            com.nextgentool.nudgy.settings.SettingsPreferences.LANGUAGE_BENGALI
        )
        composeRule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalContext provides bnContext
            ) {
                NudgyTheme { SettingsShell() }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("সেটিংস").assertIsDisplayed()
        // Sprint 7 — the Statistics section + taller title/headers
        // pushed this header below the fold at scroll 0 (it was at
        // ~276dp, now ~414dp); scroll it into view first, like the
        // appearance/language header reads below.
        composeRule.onNodeWithText("নোটিফিকেশন").performScrollTo().assertIsDisplayed()
        // The appearance / language rows repeat their section title as
        // the row title, so the Bengali title text exists TWICE —
        // onNodeWithText would throw on the duplicate. Disambiguate by
        // the unique section-header tag and read the Bengali text off
        // the semantics node (the PlanScreenTest traversal pattern:
        // fetchSemanticsNode + SemanticsProperties.Text + containment —
        // assertTextEquals and the generic assert() do not exist on
        // this Compose 1.7 BOM). Below the fold: scroll into view first.
        val appearanceText = composeRule.onNodeWithTag("settings_section_appearance")
            .performScrollTo().fetchSemanticsNode()
            .config[SemanticsProperties.Text].toString()
        assertTrue(appearanceText.contains("থিম"))
        val languageText = composeRule.onNodeWithTag("settings_section_language")
            .performScrollTo().fetchSemanticsNode()
            .config[SemanticsProperties.Text].toString()
        assertTrue(languageText.contains("ভাষা"))
        composeRule.onNodeWithText("সকালের সারাংশ").performScrollTo().assertIsDisplayed()
    }
}

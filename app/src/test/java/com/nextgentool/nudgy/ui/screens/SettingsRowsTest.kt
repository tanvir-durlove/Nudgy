package com.nextgentool.nudgy.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

    @Test
    fun `appearance dialog opens and picking Dark calls back`() {
        val modes = mutableListOf<String>()
        composeRule.setContent {
            NudgyTheme {
                SettingsShell(onThemeModeChange = { modes += it })
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_row_appearance").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_appearance_dialog").assertIsDisplayed()
        composeRule.onNodeWithText("System default").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_theme_dark").performClick()
        assertEquals(listOf("dark"), modes)
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
        composeRule.onNodeWithText("নোটিফিকেশন").assertIsDisplayed()
        // The appearance / language sections sit below the fold in the
        // scrollable Settings column: scroll each into view first —
        // assertIsDisplayed fails for below-fold nodes (Compose 1.7).
        composeRule.onNodeWithText("থিম").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("ভাষা").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("সকালের সারাংশ").performScrollTo().assertIsDisplayed()
    }
}

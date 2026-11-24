package com.nextgentool.nudgy.ui.screens

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
        composeRule.setContent {
            NudgyTheme {
                SettingsShell(
                    calendarConnected = false,
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

        composeRule.setContent {
            NudgyTheme {
                SettingsShell(
                    calendarConnected = true,
                    onCalendarConnectClick = { connects++ },
                    onCalendarSyncClick = { syncs++ }
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_row_calendar").performScrollTo()
        composeRule.onNodeWithText("Calendar synced").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_calendar_sync_button").performClick()
        assertEquals(1, syncs)
    }

    @Test
    fun `notifications appearance and language rows stay inert stubs for S6-2`() {
        setGuestShell()
        // Exactly the three S6-2 rows still say "Soon" — the eight
        // S6-1 rows no longer do. (assertCountEquals does not exist
        // in Compose 1.7 — count the semantics nodes instead.)
        assertEquals(
            3,
            composeRule.onAllNodesWithText("Soon").fetchSemanticsNodes().size
        )
        for (slug in listOf("notifications", "appearance", "language")) {
            assertEquals(
                1,
                composeRule.onAllNodesWithTag("settings_row_$slug").fetchSemanticsNodes().size
            )
        }
    }
}

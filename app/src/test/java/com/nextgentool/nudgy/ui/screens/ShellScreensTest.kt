package com.nextgentool.nudgy.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import com.nextgentool.nudgy.ui.theme.NudgyTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 6-0 — the real Settings structure (spec §4): the three
 * static placeholder rows are gone; the screen renders the eleven
 * section headers + stub rows and the Weekly review row.
 *
 * The waitForIdle() IS the blink guard (S5 fix round): an infinite
 * transition keeps the test clock busy, so reintroducing a shimmer
 * here stalls the test instead of shipping a blinking screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ShellScreensTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `settings shell renders all section headers and the weekly review row, with no placeholder rows`() {
        composeRule.setContent {
            NudgyTheme { SettingsShell() }
        }
        composeRule.waitForIdle()
        // No placeholder rows anywhere on the Settings screen.
        assertEquals(
            0,
            composeRule.onAllNodesWithTag("settings_placeholder_row")
                .fetchSemanticsNodes().size
        )
        // One header + one stub row per spec §4 section, in order.
        val slugs = listOf(
            "profile",
            "sign_in",
            "notifications",
            "appearance",
            "language",
            "calendar",
            "gemini_key",
            "privacy",
            "help_feedback",
            "memories",
            "delete_account"
        )
        for (slug in slugs) {
            assertEquals(
                1,
                composeRule.onAllNodesWithTag("settings_section_$slug")
                    .fetchSemanticsNodes().size
            )
            assertEquals(
                1,
                composeRule.onAllNodesWithTag("settings_row_$slug")
                    .fetchSemanticsNodes().size
            )
        }
        // The Weekly review row (Sprint 4-4) is real, not a stub.
        assertEquals(
            1,
            composeRule.onAllNodesWithTag("settings_section_weekly_review")
                .fetchSemanticsNodes().size
        )
        assertEquals(
            1,
            composeRule.onAllNodesWithTag("settings_weekly_review")
                .fetchSemanticsNodes().size
        )
    }
}

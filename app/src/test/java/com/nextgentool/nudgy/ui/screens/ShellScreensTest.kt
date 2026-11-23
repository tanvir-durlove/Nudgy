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
 * Sprint 5 fix round — the Settings placeholder (device test on
 * ci-792: the shell's shimmer pulsed dim/bright forever, which reads
 * as a blinking light). The rows are static now; the real Settings
 * screen lands in Sprint 6.
 *
 * The waitForIdle() IS the blink guard: an infinite transition keeps
 * the test clock busy, so reintroducing a shimmer here stalls the
 * test instead of shipping a blinking screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ShellScreensTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `settings shell renders three static placeholder rows and reaches idle`() {
        composeRule.setContent {
            NudgyTheme { SettingsShell() }
        }
        composeRule.waitForIdle()
        assertEquals(
            3,
            composeRule.onAllNodesWithTag("settings_placeholder_row")
                .fetchSemanticsNodes().size
        )
    }
}

package com.nextgentool.nudgy.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.nextgentool.nudgy.ui.theme.NudgyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Probe — the top bar's brand mark (approved logo: one tilted coral
 * bar + two upright teal bars, no cream circle). The plain-text
 * "Nudgy" wordmark is gone from the top bar; the mark is what the
 * sign-in screen's top bar shows.
 *
 * NOTE: NudgyTopBar is rendered here directly — MainActivity shows
 * SignInScreen with no top bar of its own, and the bar is the shared
 * component the sign-in screen's header is built from.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NudgyLogoMarkTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `top bar shows the logo mark and no plain-text wordmark`() {
        composeRule.setContent {
            NudgyTheme {
                NudgyTopBar()
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Nudgy logo").assertIsDisplayed()
        composeRule.onNodeWithText("Nudgy").assertDoesNotExist()
    }
}

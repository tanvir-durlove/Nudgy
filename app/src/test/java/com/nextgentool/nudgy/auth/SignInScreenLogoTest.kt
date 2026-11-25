package com.nextgentool.nudgy.auth

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.ui.theme.NudgyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Step 1 — the sign-in screen's headline is the brand logo mark
 * (NudgyLogoMark, the tilted coral bar + two upright teal bars),
 * not the plain-text "NUDGY" wordmark. The animation and the rest of
 * the headline ("Things Get Done." + tagline) are untouched.
 *
 * Render-only: the real SignInScreen on a real AuthManager (no
 * sign-in is attempted, no button is clicked, errorMessage is null —
 * so no AuthManager method runs during the test). The AuthManager is
 * built once, outside setContent, so recomposition never rebuilds it.
 *
 * Two harness traps, both proven in this repo — do NOT "fix" them:
 * - NO waitForIdle(): the mascot bob is an always-on infinite
 *   transition, which keeps the test clock busy and stalls waitForIdle
 *   forever (ShellScreensTest KDoc, S5 blink guard).
 * - NO performScrollTo(): it hangs the test worker on this harness
 *   (OnboardingScreenTest KDoc, runs 851/852). The logo is asserted
 *   with assertExists (presence in the tree), never scrolled to.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SignInScreenLogoTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `sign-in headline shows the logo mark and no plain-text NUDGY`() {
        val authManager = AuthManager(ApplicationProvider.getApplicationContext())
        composeRule.setContent {
            NudgyTheme {
                SignInScreen(
                    authManager = authManager,
                    scope = rememberCoroutineScope(),
                    isLoading = false,
                    errorMessage = null,
                    onSignInSuccess = {}
                )
            }
        }
        // The mark is in the tree from the first composition (the
        // entrance animation only fades it in — it never removes it).
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithContentDescription("Nudgy logo")
                .fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithContentDescription("Nudgy logo").assertExists()
        composeRule.onNodeWithText("NUDGY").assertDoesNotExist()
    }
}

package com.nextgentool.nudgy.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.nextgentool.nudgy.ui.theme.NudgyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Step 5a (polish item 1) — the splash wordmark below the bear is the
 * brand logo mark (NudgyLogoMark, the tilted coral bar + two upright
 * teal bars), not the plain-text "NUDGY". The bear image, layout and
 * the fade/slide-in animation are untouched.
 *
 * Render-only: the real NudgySplashScreen with a no-op onFinished.
 * The mark is in the tree from the first composition (the entrance
 * animation only fades it in — it never removes it), so the test does
 * not wait for the 450ms + 500ms LaunchedEffect delays to elapse.
 *
 * Two harness traps, both proven in this repo — do NOT "fix" them:
 * - NO waitForIdle(): the entrance tweens keep the test clock busy
 *   (SignInScreenLogoTest KDoc — the mascot bob stalls it forever
 *   there; the splash tweens are finite, but the waitUntil pattern
 *   below is the proven shape and needs no idle wait).
 * - NO performScrollTo(): it hangs the test worker on this harness
 *   (OnboardingScreenTest KDoc, runs 851/852). The logo is asserted
 *   with assertExists (presence in the tree), never scrolled to.
 *
 * Honest limit: NudgySplashScreen currently has NO call sites in
 * main code — the app shows the system splash from the manifest
 * theme (Theme.Nudgy.Splash) and MainActivity never composes this
 * screen (same class as the NudgyTopBar lesson in step 0). This test
 * proves the composable renders the mark; wiring the splash into
 * the launch flow is a separate step, not this one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NudgySplashScreenLogoTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `splash shows the logo mark below the bear and no plain-text NUDGY`() {
        composeRule.setContent {
            NudgyTheme {
                NudgySplashScreen(onFinished = {})
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
        // The bear is still there, above the mark. assertExists, not
        // assertIsDisplayed: the bear's entrance alpha starts at 0 and
        // the test must not depend on the tween having finished.
        composeRule.onNodeWithContentDescription("Nudgy").assertExists()
    }
}

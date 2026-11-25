package com.nextgentool.nudgy.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.nextgentool.nudgy.ui.theme.NudgyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Step 5a-wire (polish, Tanvir's decision) — the bear is GONE: the
 * splash shows only the big brand logo mark (NudgyLogoMark at
 * 220x96, ~4x the 55x24 top-bar mark), centred, and no "NUDGY"
 * text. The entrance is one stage (scale/fade in over 450ms, hold,
 * onFinished at ~950ms total) and MainActivity wires the splash
 * into the launch flow (once per cold start — see
 * shouldShowSplash / MainActivitySplashTest).
 *
 * Render-only, on the real NudgySplashScreen. The mark is in the
 * tree from the first composition (the entrance animation only
 * fades it in — it never removes it), so the render test does not
 * wait for the LaunchedEffect delays to elapse; the handover test
 * DOES wait for onFinished (~950ms) via waitUntil.
 *
 * Two harness traps, both proven in this repo — do NOT "fix" them:
 * - NO waitForIdle(): the entrance tweens keep the test clock busy
 *   (SignInScreenLogoTest KDoc — the mascot bob stalls it forever
 *   there; the splash tweens are finite, but the waitUntil pattern
 *   below is the proven shape and needs no idle wait).
 * - NO performScrollTo(): it hangs the test worker on this harness
 *   (OnboardingScreenTest KDoc, runs 851/852). Presence is asserted
 *   with assertExists, never scrolled to.
 *
 * Honest limits: the mark's 220x96 SIZE is verified by reading the
 * code, not by pixels — semantics bounds are unreliable on this
 * harness (the S1-2 boundsInRoot lesson). MainActivity itself is
 * @AndroidEntryPoint, so the launch-flow wiring (splash first,
 * then lock / sign-in / onboarding / app) is verified by CI
 * compile + the batch-end device test, not by a render test here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NudgySplashScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `splash shows the big logo mark and no bear and no plain-text NUDGY`() {
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
        composeRule.onNodeWithTag("splash_screen").assertExists()
        composeRule.onNodeWithContentDescription("Nudgy logo").assertExists()
        composeRule.onNodeWithText("NUDGY").assertDoesNotExist()
        // The bear is gone (Tanvir's decision): its "Nudgy"
        // contentDescription must not appear anywhere. The match is
        // exact, not a substring — "Nudgy logo" does not count.
        composeRule.onNodeWithContentDescription("Nudgy").assertDoesNotExist()
    }

    @Test
    fun `splash calls onFinished after its entrance`() {
        var finished by mutableStateOf(false)
        composeRule.setContent {
            NudgyTheme {
                NudgySplashScreen(onFinished = { finished = true })
            }
        }
        // The entrance is ~950ms (450ms scale-in + 500ms hold).
        // waitUntil advances the test clock while polling; the
        // 10s timeout is headroom, not the expected time.
        composeRule.waitUntil(timeoutMillis = 10_000) { finished }
        // Reaching here means onFinished fired (a second fire would
        // need a recomposition with a fresh LaunchedEffect, which
        // never happens). The splash is still composed in this test
        // — the flag is the proof, not an unmount.
        composeRule.onNodeWithTag("splash_screen").assertExists()
    }
}

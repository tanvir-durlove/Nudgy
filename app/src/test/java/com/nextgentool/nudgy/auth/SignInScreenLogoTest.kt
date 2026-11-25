package com.nextgentool.nudgy.auth

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
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
 * S7-1 — the sign-in hero is the prototype hero (Sprint 7 spec §3.1):
 * the cascade logo mark + "Welcome to Nudgy" + sub-copy. The mascot
 * hero is gone: no bear, no floating chips, no "Things Get Done."
 * headline. (Step 1 had already replaced the plain-text "NUDGY"
 * headline with the logo mark; this sprint replaces the rest of the
 * hero around it.)
 *
 * Render-only: the real SignInScreen on a real AuthManager (no
 * sign-in is attempted, no button is clicked, errorMessage is null —
 * so no AuthManager method runs during the test). The AuthManager is
 * built once, outside setContent, so recomposition never rebuilds it.
 *
 * Two harness traps, both proven in this repo — do NOT "fix" them:
 * - NO performScrollTo(): it hangs the test worker on this harness
 *   (OnboardingScreenTest KDoc, runs 851/852). Nodes are asserted
 *   with assertExists (presence in the tree), never scrolled to.
 * - The mascot bob (the always-on infinite transition that used to
 *   stall waitForIdle forever on this screen) was removed with the
 *   hero in S7-1 — the test still keeps the proven waitUntil +
 *   assertExists pattern instead of waitForIdle.
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

    @Test
    fun `sign-in hero is the prototype welcome hero, not the mascot hero`() {
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
        // The heading is in the tree from the first composition (the
        // entrance animation only fades it in — it never removes it).
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Welcome to Nudgy")
                .fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithText("Welcome to Nudgy").assertExists()
        composeRule.onNodeWithText(
            "Sign in to sync your tasks across devices, or start as a guest — everything works offline."
        ).assertExists()
        // The old mascot hero is gone entirely: headline and chips.
        // (The mascot itself is a bare Canvas with no semantics, so
        // its absence is proven by the chip labels + headline that
        // only existed to surround it.)
        composeRule.onNodeWithText("Things Get Done.").assertDoesNotExist()
        composeRule.onNodeWithText("Reminders").assertDoesNotExist()
        composeRule.onNodeWithText("Calendar").assertDoesNotExist()
        composeRule.onNodeWithText("Focus time").assertDoesNotExist()
    }
}

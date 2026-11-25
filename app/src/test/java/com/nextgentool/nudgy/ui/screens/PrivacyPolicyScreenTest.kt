package com.nextgentool.nudgy.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import com.nextgentool.nudgy.ui.navigation.NudgyRoute
import com.nextgentool.nudgy.ui.theme.NudgyTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Step 5b (polish item 2) — the in-app Privacy Policy screen:
 * the full published policy (eleven sections) renders, the
 * last-updated line renders, the footer web link and the back
 * button fire their callbacks, and the route resolves.
 *
 * The screen is pure static content — no ViewModel, no Room —
 * so there is no test-hygiene burden here (no ViewModelStore,
 * no database). Harness rules, all proven in this repo — do NOT
 * "fix" them:
 * - NO performScrollTo(): it hangs the test worker on this harness
 *   (OnboardingScreenTest KDoc, runs 851/852). The screen is a
 *   plain Column with verticalScroll, so every section composes
 *   and assertExists works without scrolling.
 * - Rows are awaited with waitUntil, NOT waitForIdle() (the
 *   SearchScreenTest pattern).
 * - The NavHost wiring (Settings row tap → this route) is NOT
 *   covered here: the real NavHost cannot be rendered in a unit
 *   test (no Hilt test infra in this repo) — it is verified by
 *   reading + CI compile, same as the Search entry (2d) and the
 *   widget tap (3d). The route-resolution assertion below is the
 *   part that IS testable: the route must be in NudgyRoute.all
 *   or fromRoute falls back to Today.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivacyPolicyScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setContent(
        onBackClick: () -> Unit = {},
        onWebClick: () -> Unit = {},
    ) {
        composeRule.setContent {
            NudgyTheme {
                PrivacyPolicyScreen(
                    onBackClick = onBackClick,
                    onWebClick = onWebClick,
                )
            }
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("privacy_section_1")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `all eleven sections render with their headings`() {
        setContent()
        (1..PRIVACY_POLICY_SECTIONS.size).forEach { index ->
            composeRule.onNodeWithTag("privacy_section_$index").assertExists()
        }
        assertEquals(11, PRIVACY_POLICY_SECTIONS.size)
    }

    @Test
    fun `last updated line and header render`() {
        setContent()
        composeRule.onNodeWithTag("privacy_updated").assertExists()
        composeRule.onNodeWithTag("privacy_header").assertExists()
    }

    @Test
    fun `web link row fires its callback`() {
        // The footer row sits below the fold in the scrollable
        // Column, so a coordinate-based performClick cannot reach
        // it (the click lands outside the viewport); performScrollTo
        // hangs the test worker on this harness (OnboardingScreenTest
        // KDoc, runs 851/852). The semantics OnClick action invokes
        // the row's clickable directly, no coordinates involved.
        // (PR run 45622471005 failed exactly this test with
        // performClick; the row itself was fine.)
        var webClicked = false
        setContent(onWebClick = { webClicked = true })
        composeRule.onNodeWithTag("privacy_web_link")
            .performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(webClicked)
    }

    @Test
    fun `back button fires onBackClick`() {
        var backClicked = false
        setContent(onBackClick = { backClicked = true })
        composeRule.onNodeWithTag("privacy_back_button").performClick()
        assertTrue(backClicked)
    }

    @Test
    fun `privacy route resolves in NudgyRoute all`() {
        assertTrue(NudgyRoute.all.contains(NudgyRoute.PrivacyPolicy))
        assertEquals(NudgyRoute.PrivacyPolicy, NudgyRoute.fromRoute("privacy_policy"))
    }
}

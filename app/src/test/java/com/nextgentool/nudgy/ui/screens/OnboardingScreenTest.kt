package com.nextgentool.nudgy.ui.screens

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nextgentool.nudgy.ui.theme.NudgyTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 6-3 — the first-run tutorial (feature 40, Q3=(a), spec §6).
 *
 * The screen owns no data: the tests drive it through the onFinish
 * callback and assert the page flow (all four pages, Skip on every
 * page, the sample offer only on the last page of a first launch,
 * never in a re-view). The seeding itself is OnboardingViewModelTest's
 * job; this file proves the UI hands it the right choice.
 *
 * Compose 1.7 traps, all proven in this repo: assertTextEquals and
 * the generic assert(matcher) do NOT resolve on this BOM — the page
 * title is read via fetchSemanticsNode (a member) +
 * SemanticsProperties.Text + a containment match; duplicate text
 * breaks onNodeWithText, so the title is asserted by testTag.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OnboardingScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun pageTitle(): String {
        val node = composeRule.onNodeWithTag("onboarding_page_title").fetchSemanticsNode()
        return node.config[SemanticsProperties.Text].toString()
    }

    private fun render(isReView: Boolean = false, onFinish: (Boolean) -> Unit = {}) {
        composeRule.setContent {
            NudgyTheme {
                OnboardingScreen(isReView = isReView, onFinish = onFinish)
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `all four pages render in order with Skip on every page`() {
        render()
        val titles = listOf(
            "Capture tasks in seconds",
            "Plan My Day",
            "Reminders that respect you",
            "Plan together"
        )
        // Page 1 renders first; Skip is visible on it.
        assertEquals(true, pageTitle().contains(titles[0]))
        composeRule.onNodeWithTag("onboarding_skip").assertIsDisplayed()
        // Next walks through pages 2–4; Skip stays on every page.
        for (index in 1..3) {
            composeRule.onNodeWithTag("onboarding_next").performClick()
            composeRule.waitForIdle()
            assertEquals(true, pageTitle().contains(titles[index]))
            composeRule.onNodeWithTag("onboarding_skip").assertIsDisplayed()
        }
        // On the last page Next becomes Finish.
        composeRule.onNodeWithTag("onboarding_next").assertDoesNotExist()
        composeRule.onNodeWithTag("onboarding_finish").assertIsDisplayed()
    }

    @Test
    fun `sample offer appears only on the last page and is checked by default`() {
        render()
        // Pages 1–3: no sample offer.
        repeat(2) {
            composeRule.onNodeWithTag("onboarding_sample_offer").assertDoesNotExist()
            composeRule.onNodeWithTag("onboarding_next").performClick()
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithTag("onboarding_sample_offer").assertDoesNotExist()
        // Last page: the offer is there, checked by default.
        composeRule.onNodeWithTag("onboarding_next").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("onboarding_sample_offer").assertIsDisplayed()
        composeRule.onNodeWithText("Start with sample tasks").assertIsDisplayed()
        composeRule.onNodeWithText("3 clearly-marked sample tasks you can delete in one tap.")
            .assertIsDisplayed()
    }

    @Test
    fun `finish with the offer checked reports includeSamples true`() {
        var reported: Boolean? = null
        render(onFinish = { reported = it })
        repeat(3) {
            composeRule.onNodeWithTag("onboarding_next").performClick()
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithTag("onboarding_finish").performClick()
        assertEquals(true, reported)
    }

    @Test
    fun `unchecking the offer reports includeSamples false`() {
        var reported: Boolean? = null
        render(onFinish = { reported = it })
        repeat(3) {
            composeRule.onNodeWithTag("onboarding_next").performClick()
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithTag("onboarding_sample_checkbox").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("onboarding_finish").performClick()
        assertEquals(false, reported)
    }

    @Test
    fun `skip on the first page reports includeSamples false`() {
        var reported: Boolean? = null
        render(onFinish = { reported = it })
        composeRule.onNodeWithTag("onboarding_skip").performClick()
        assertEquals(false, reported)
    }

    @Test
    fun `re-view hides the sample offer and finish reports false`() {
        var reported: Boolean? = null
        render(isReView = true, onFinish = { reported = it })
        repeat(3) {
            composeRule.onNodeWithTag("onboarding_next").performClick()
            composeRule.waitForIdle()
        }
        // Last page in a re-view: no sample offer at all.
        composeRule.onNodeWithTag("onboarding_sample_offer").assertDoesNotExist()
        // Finish reads "Done" in a re-view, and reports false
        // (nothing to seed — re-viewing must never re-seed).
        composeRule.onNodeWithText("Done").assertIsDisplayed()
        composeRule.onNodeWithTag("onboarding_finish").performClick()
        assertEquals(false, reported)
    }
}

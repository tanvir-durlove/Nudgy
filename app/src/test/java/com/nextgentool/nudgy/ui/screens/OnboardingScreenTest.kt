package com.nextgentool.nudgy.ui.screens

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
 * Sprint 7 — the first-run tutorial rebuilt to the prototype
 * (scr-onboarding): hero (logo mark + "Nudgy" + tagline), one white
 * card holding one of 3 slides (pill "Step N of 3", h2 23sp,
 * illustration), dots, Next (teal) + a "Skip the tour" ghost button
 * at the bottom (the old top-right Skip is gone).
 *
 * The screen owns no data: the tests drive it through the onFinish
 * callback and assert the page flow (all three slides, Skip ghost on
 * every slide, the sample offer only on the last slide of a first
 * launch, never in a re-view). The seeding itself is
 * OnboardingViewModelTest's job; this file proves the UI hands it
 * the right choice.
 *
 * The column scrolls (slide 3 is taller than the small Robolectric
 * window), so anything below the hero is asserted with assertExists,
 * never assertIsDisplayed — and never performScrollTo: on this
 * harness it hangs the test worker (runs 851/852).
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

    /** Walks to the given slide (0-based) from the first render. */
    private fun goToSlide(index: Int) {
        repeat(index) {
            composeRule.onNodeWithTag("onboarding_next").performClick()
            composeRule.waitForIdle()
        }
    }

    @Test
    fun `all three slides render in order with Skip on every slide`() {
        render()
        val titles = listOf(
            "Start with Today",
            "Plan in the morning",
            "Just say it"
        )
        // Slide 1 renders first; the Skip ghost is on it.
        assertEquals(true, pageTitle().contains(titles[0]))
        composeRule.onNodeWithTag("onboarding_skip").assertExists()
        // Next walks through slides 2–3; Skip stays on every slide.
        for (index in 1..2) {
            composeRule.onNodeWithTag("onboarding_next").performClick()
            composeRule.waitForIdle()
            assertEquals(true, pageTitle().contains(titles[index]))
            composeRule.onNodeWithTag("onboarding_skip").assertExists()
        }
        // On the last slide Next becomes Finish.
        composeRule.onNodeWithTag("onboarding_next").assertDoesNotExist()
        composeRule.onNodeWithTag("onboarding_finish").assertExists()
    }

    @Test
    fun `hero shows the logo mark, the name and the tagline`() {
        render()
        composeRule.onNodeWithContentDescription("Nudgy logo").assertExists()
        composeRule.onNodeWithText("Nudgy").assertIsDisplayed()
        composeRule.onNodeWithText("Your day, gently kept on track.").assertExists()
    }

    @Test
    fun `slide one shows the pill, the sample task rows and the note`() {
        render()
        composeRule.onNodeWithText("Step 1 of 3 · Today").assertIsDisplayed()
        composeRule.onNodeWithText("Morning walk").assertExists()
        composeRule.onNodeWithText("7:00 AM · 30 min").assertExists()
        composeRule.onNodeWithText("Buy groceries").assertExists()
        composeRule.onNodeWithText("10:30 AM · 45 min").assertExists()
        composeRule.onNodeWithText(
            "Sample tasks are already in the app so you can try everything right away."
        ).assertExists()
    }

    @Test
    fun `slide two shows the pill and the carry banner`() {
        render()
        goToSlide(1)
        composeRule.onNodeWithText("Step 2 of 3 · Plan").assertIsDisplayed()
        composeRule.onNodeWithText("3 unfinished from yesterday").assertExists()
        composeRule.onNodeWithText(
            "Choose them in Plan — they are never moved automatically."
        ).assertExists()
    }

    @Test
    fun `slide three shows the pill and the chat bubbles`() {
        render()
        goToSlide(2)
        composeRule.onNodeWithText("Step 3 of 3 · Chat").assertIsDisplayed()
        composeRule.onNodeWithText(
            "কালকে সকাল ৭টায় হাঁটতে যাবো, আর Tasnim-কে ৫টায় ফোন করতে হবে"
        ).assertExists()
        composeRule.onNodeWithText("NUDGY").assertExists()
        composeRule.onNodeWithText(
            "Got it — I found 2 tasks. Review them below before I save anything."
        ).assertExists()
    }

    @Test
    fun `sample offer appears only on the last slide and is checked by default`() {
        render()
        // Slides 1–2: no sample offer.
        composeRule.onNodeWithTag("onboarding_sample_offer").assertDoesNotExist()
        composeRule.onNodeWithTag("onboarding_next").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("onboarding_sample_offer").assertDoesNotExist()
        // Last slide: the offer is there, checked by default.
        // It sits below the fold on the small Robolectric window —
        // assert existence only, never performScrollTo.
        composeRule.onNodeWithTag("onboarding_next").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("onboarding_sample_offer").assertExists()
        composeRule.onNodeWithText("Start with sample tasks").assertExists()
        composeRule.onNodeWithText("3 clearly-marked sample tasks you can delete in one tap.")
            .assertExists()
    }

    @Test
    fun `finish with the offer checked reports includeSamples true`() {
        var reported: Boolean? = null
        render(onFinish = { reported = it })
        goToSlide(2)
        composeRule.onNodeWithTag("onboarding_finish").performClick()
        assertEquals(true, reported)
    }

    @Test
    fun `unchecking the offer reports includeSamples false`() {
        var reported: Boolean? = null
        render(onFinish = { reported = it })
        goToSlide(2)
        composeRule.onNodeWithTag("onboarding_sample_checkbox").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("onboarding_finish").performClick()
        assertEquals(false, reported)
    }

    @Test
    fun `skip on the first slide reports includeSamples false`() {
        var reported: Boolean? = null
        render(onFinish = { reported = it })
        composeRule.onNodeWithTag("onboarding_skip").performClick()
        assertEquals(false, reported)
    }

    @Test
    fun `re-view hides the sample offer and finish reports false`() {
        var reported: Boolean? = null
        render(isReView = true, onFinish = { reported = it })
        goToSlide(2)
        // Last slide in a re-view: no sample offer at all.
        composeRule.onNodeWithTag("onboarding_sample_offer").assertDoesNotExist()
        // Finish reads "Done" in a re-view, and reports false
        // (nothing to seed — re-viewing must never re-seed).
        composeRule.onNodeWithText("Done").assertExists()
        composeRule.onNodeWithTag("onboarding_finish").performClick()
        assertEquals(false, reported)
    }
}

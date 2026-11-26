package com.nextgentool.nudgy.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nextgentool.nudgy.data.local.entity.HabitEntity
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.repository.HabitStreak
import com.nextgentool.nudgy.domain.usecase.WeeklyReview
import com.nextgentool.nudgy.domain.usecase.WeeklyReviewStats
import com.nextgentool.nudgy.domain.usecase.summarizeWeeklyStats
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 4-4 — the weekly review screen (spec §7, §10 items 10–11).
 *
 * Renders the pure [WeeklyReviewScreenContent] with pinned data (no
 * Hilt, the FocusScreen pattern): the summary line, the focus
 * minutes, the streaks, and the three lists — and nothing when the
 * stats are absent. The back button fires its callback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WeeklyReviewScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun review(finished: List<TaskEntity> = emptyList()): WeeklyReview =
        WeeklyReview(
            finished = finished,
            missed = emptyList(),
            slipped = emptyList(),
            weekStartMillis = 0L,
            weekEndMillis = 7 * 24 * 60 * 60 * 1000L,
            weekLabel = "Nov 16 – Nov 22"
        )

    private fun stats(): WeeklyReviewStats = summarizeWeeklyStats(
        focusMinutesThisWeek = 50,
        habits = listOf(
            HabitStreak(
                habit = HabitEntity(id = "h1", userId = "u1", title = "Walk"),
                currentStreak = 3,
                longestStreak = 5,
                checkedInToday = false
            )
        )
    )

    @Test
    fun `the screen shows the summary, focus minutes and streaks`() {
        val finished = TaskEntity(
            id = "t1",
            userId = "u1",
            title = "Finished task",
            actualMinutes = 45
        )
        composeRule.setContent {
            WeeklyReviewScreenContent(
                review = review(finished = listOf(finished)),
                stats = stats(),
                onBackClick = {}
            )
        }

        composeRule.onNodeWithTag("weekly_review_summary").assertIsDisplayed()
        composeRule.onNodeWithText("This week you finished 1 task.").assertIsDisplayed()
        composeRule.onNodeWithTag("weekly_review_focus_minutes").assertIsDisplayed()
        composeRule.onNodeWithText("50 min").assertIsDisplayed()
        composeRule.onNodeWithTag("weekly_review_current_streak").assertIsDisplayed()
        composeRule.onNodeWithText("3 days").assertIsDisplayed()
        composeRule.onNodeWithTag("weekly_review_best_streak").assertIsDisplayed()
        composeRule.onNodeWithText("5 days").assertIsDisplayed()
        composeRule.onNodeWithTag("weekly_review_row_t1").assertExists()
        // Feature 18 (Sprint 7-2a — moved here from the S15 smoke
        // test: the card no longer renders on Today): the hand-written
        // actual time surfaces on the finished row.
        composeRule.onNodeWithText("45 min").assertIsDisplayed()
    }

    @Test
    fun `without stats the lists still show and no stats rows appear`() {
        composeRule.setContent {
            WeeklyReviewScreenContent(
                review = review(),
                stats = null,
                onBackClick = {}
            )
        }

        // "Weekly review" appears twice (screen title + card title),
        // so pin the card by tag instead of a bare text match.
        composeRule.onNodeWithTag("weekly_review_card").assertExists()
        composeRule.onNodeWithText("Nothing finished yet").assertIsDisplayed()
        composeRule.onNodeWithTag("weekly_review_focus_minutes").assertDoesNotExist()
        composeRule.onNodeWithTag("weekly_review_current_streak").assertDoesNotExist()
        composeRule.onNodeWithTag("weekly_review_best_streak").assertDoesNotExist()
    }

    @Test
    fun `without a review the screen shows its loading line`() {
        composeRule.setContent {
            WeeklyReviewScreenContent(
                review = null,
                stats = null,
                onBackClick = {}
            )
        }

        composeRule.onNodeWithText("Loading this week's review…").assertIsDisplayed()
    }

    @Test
    fun `the back button fires its callback`() {
        var backClicked = false
        composeRule.setContent {
            WeeklyReviewScreenContent(
                review = review(),
                stats = stats(),
                onBackClick = { backClicked = true }
            )
        }

        composeRule.onNodeWithTag("weekly_review_back").performClick()

        assertTrue(backClicked)
    }
}

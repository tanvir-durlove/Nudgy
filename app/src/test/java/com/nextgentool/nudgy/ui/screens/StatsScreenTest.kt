package com.nextgentool.nudgy.ui.screens

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.nextgentool.nudgy.data.local.entity.HabitEntity
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.repository.HabitStreak
import com.nextgentool.nudgy.domain.dateStringOf
import com.nextgentool.nudgy.domain.startOfWeekMillis
import com.nextgentool.nudgy.domain.usecase.WeeklyReview
import com.nextgentool.nudgy.domain.usecase.WeeklyReviewStats
import com.nextgentool.nudgy.ui.theme.NudgyTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 7 — Statistics screen test (spec §4, prototype scr-stats).
 *
 * The screen is a thin wrapper over the existing WeeklyReview +
 * WeeklyReviewStats data, so what is pinned here is the WRAPPER: the
 * backbar + the four statboxes, the completed-per-day bars, the
 * reused WeeklyReviewCard, and the Streaks card. The stats data
 * itself (counts, streak rules) stays covered by
 * WeeklyReviewStatsTest / WeeklyReviewUseCase tests. Compose 1.7
 * member rules apply: assertExists / fetchSemanticsNode are members,
 * never top-level imports (FocusScreenTest pattern). Values are
 * asserted on their tagged nodes, and card contents are asserted
 * scoped to the card's subtree — a bare text search can match
 * several nodes (e.g. "12 days" also appears in the review card's
 * streak rows, and the week label appears in two cards).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StatsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val dayMillis = 24 * 60 * 60 * 1000L
    private val weekStart = startOfWeekMillis(System.currentTimeMillis())

    /** Finished Mon ×2, Tue ×1, Wed ×1 — the bar chart's fixture. */
    private val finished = listOf(
        TaskEntity(
            id = "f1", userId = "u1", title = "Submit assignment",
            isCompleted = true, completedAtEpochMillis = weekStart + 12 * 60 * 60 * 1000L
        ),
        TaskEntity(
            id = "f2", userId = "u1", title = "Reply to emails",
            isCompleted = true, completedAtEpochMillis = weekStart + 14 * 60 * 60 * 1000L
        ),
        TaskEntity(
            id = "f3", userId = "u1", title = "Go for a run",
            isCompleted = true,
            completedAtEpochMillis = weekStart + dayMillis + 12 * 60 * 60 * 1000L
        ),
        TaskEntity(
            id = "f4", userId = "u1", title = "Water the plants",
            isCompleted = true,
            completedAtEpochMillis = weekStart + 2 * dayMillis + 12 * 60 * 60 * 1000L
        )
    )
    private val missed = listOf(
        TaskEntity(
            id = "m1", userId = "u1", title = "Book train ticket",
            dueDateEpochMillis = weekStart + dayMillis
        )
    )
    private val slipped = listOf(
        TaskEntity(
            id = "s1", userId = "u1", title = "Pay electricity bill",
            carriedOverFromDate = dateStringOf(weekStart + dayMillis)
        ),
        TaskEntity(
            id = "s2", userId = "u1", title = "Call bank",
            carriedOverFromDate = dateStringOf(weekStart + 2 * dayMillis)
        )
    )
    private val review = WeeklyReview(
        finished = finished,
        missed = missed,
        slipped = slipped,
        weekStartMillis = weekStart,
        weekEndMillis = weekStart + 7 * dayMillis,
        weekLabel = "Nov 16 – Nov 22"
    )
    private val stats = WeeklyReviewStats(
        focusMinutesThisWeek = 380,
        bestCurrentStreak = 12,
        bestLongestStreak = 12,
        habits = listOf(
            HabitStreak(
                habit = HabitEntity(id = "h1", userId = "u1", title = "Read 20 minutes"),
                currentStreak = 12,
                longestStreak = 12
            ),
            HabitStreak(
                habit = HabitEntity(id = "h2", userId = "u1", title = "Morning walk"),
                currentStreak = 8,
                longestStreak = 8
            )
        )
    )

    private fun setStatsContent(review: WeeklyReview? = this.review, stats: WeeklyReviewStats? = this.stats) {
        composeRule.setContent {
            NudgyTheme {
                StatsScreenContent(
                    review = review,
                    stats = stats,
                    onBackClick = {}
                )
            }
        }
    }

    /** The text of the tagged value node contains [expected]. */
    private fun assertValue(tag: String, expected: String) {
        val node = composeRule.onNodeWithTag(tag).fetchSemanticsNode()
        assertTrue(node.config[SemanticsProperties.Text].toString().contains(expected))
    }

    /** True when [text] appears in the semantics Text of [node] or any
     * of its descendants (TodayMyDayNoSwitcherTest pattern). */
    private fun subtreeHasText(node: SemanticsNode, text: String): Boolean {
        if (node.config.contains(SemanticsProperties.Text) &&
            node.config[SemanticsProperties.Text].toString().contains(text)
        ) return true
        return node.children.any { subtreeHasText(it, text) }
    }

    @Test
    fun `backbar and statboxes render`() {
        setStatsContent()

        composeRule.onNodeWithTag("stats_backbar").assertExists()
        composeRule.onNodeWithText("Statistics").assertExists()
        // 4 finished, 1 missed, 2 carried; rate 4 / (4 + 1) = 80%.
        assertValue("stats_done_value", "4")
        assertValue("stats_missed_value", "1")
        assertValue("stats_carried_value", "2")
        assertValue("stats_rate_value", "80%")
    }

    @Test
    fun `bar chart shows completed per day`() {
        setStatsContent()

        composeRule.onNodeWithTag("stats_bar_chart").assertExists()
        composeRule.onNodeWithText("Completed per day").assertExists()
        composeRule.onNodeWithText("Mon · 2").assertExists()
        composeRule.onNodeWithText("Tue · 1").assertExists()
        composeRule.onNodeWithText("Wed · 1").assertExists()
        composeRule.onNodeWithText("Thu · –").assertExists()
    }

    @Test
    fun `weekly review card renders`() {
        setStatsContent()

        composeRule.onNodeWithTag("stats_weekly_review").assertExists()
        composeRule.onNodeWithTag("weekly_review_card").assertExists()
        composeRule.onNodeWithText("Finished").assertExists()
        composeRule.onNodeWithText("Submit assignment").assertExists()
    }

    @Test
    fun `streaks card lists habits`() {
        setStatsContent()

        // Scoped to the card: "12 days" also appears in the review
        // card's streak rows, so a bare global assertion would find
        // several nodes.
        val card = composeRule.onNodeWithTag("stats_streaks").fetchSemanticsNode()
        assertTrue(subtreeHasText(card, "Read 20 minutes"))
        assertTrue(subtreeHasText(card, "12 days"))
        assertTrue(subtreeHasText(card, "Morning walk"))
        assertTrue(subtreeHasText(card, "8 days"))
    }

    @Test
    fun `loading text shows when review is null`() {
        setStatsContent(review = null, stats = null)

        composeRule.onNodeWithText("Loading this week's statistics…").assertExists()
    }

    @Test
    fun `completedPerDay counts finished tasks by weekday`() {
        val tasks = listOf(
            TaskEntity(id = "a", userId = "u1", title = "A", completedAtEpochMillis = weekStart + 3600000L),
            TaskEntity(id = "b", userId = "u1", title = "B", completedAtEpochMillis = weekStart + 7200000L),
            TaskEntity(id = "c", userId = "u1", title = "C", completedAtEpochMillis = weekStart + 2 * dayMillis),
            // No completion time, and one completed next week: ignored.
            TaskEntity(id = "d", userId = "u1", title = "D"),
            TaskEntity(id = "e", userId = "u1", title = "E", completedAtEpochMillis = weekStart + 7 * dayMillis)
        )

        assertEquals(listOf(2, 0, 1, 0, 0, 0, 0), completedPerDay(tasks, weekStart))
    }
}

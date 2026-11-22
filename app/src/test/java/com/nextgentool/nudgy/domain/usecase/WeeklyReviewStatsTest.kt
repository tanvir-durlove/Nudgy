package com.nextgentool.nudgy.domain.usecase

import com.nextgentool.nudgy.data.local.entity.HabitEntity
import com.nextgentool.nudgy.data.repository.HabitStreak
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Sprint 4-4 — the review's numbers are a pure summary (spec §7).
 *
 * No Room, no Compose: summarizeWeeklyStats takes the week's focus
 * minutes and the habits' streak states and returns the three numbers
 * the review shows. The best current streak is the max across habits
 * (an "at risk" habit still counts its live streak), and the best
 * longest streak is the max longest streak.
 */
class WeeklyReviewStatsTest {

    private fun habit(id: String, current: Int, longest: Int, checkedInToday: Boolean) =
        HabitStreak(
            habit = HabitEntity(id = id, userId = "u1", title = id, createdAtEpochMillis = 0L),
            currentStreak = current,
            longestStreak = longest,
            checkedInToday = checkedInToday
        )

    @Test
    fun `no habits means zero streaks and the focus minutes pass through`() {
        val stats = summarizeWeeklyStats(focusMinutesThisWeek = 50, habits = emptyList())

        assertEquals(50, stats.focusMinutesThisWeek)
        assertEquals(0, stats.bestCurrentStreak)
        assertEquals(0, stats.bestLongestStreak)
        assertEquals(emptyList<HabitStreak>(), stats.habits)
    }

    @Test
    fun `best streaks are the max across habits`() {
        val stats = summarizeWeeklyStats(
            focusMinutesThisWeek = 25,
            habits = listOf(
                habit("walk", current = 3, longest = 7, checkedInToday = false),
                habit("read", current = 0, longest = 5, checkedInToday = false),
                habit("water", current = 1, longest = 1, checkedInToday = true)
            )
        )

        assertEquals(25, stats.focusMinutesThisWeek)
        assertEquals(3, stats.bestCurrentStreak)
        assertEquals(7, stats.bestLongestStreak)
    }

    @Test
    fun `an at-risk habit still counts its live current streak`() {
        // Checked in yesterday and the day before, not today yet: the
        // streak is at risk, not broken — it still counts as 2.
        val stats = summarizeWeeklyStats(
            focusMinutesThisWeek = 0,
            habits = listOf(habit("walk", current = 2, longest = 2, checkedInToday = false))
        )

        assertEquals(2, stats.bestCurrentStreak)
        assertEquals(2, stats.bestLongestStreak)
    }

    @Test
    fun `a single habit keeps its own streaks`() {
        val stats = summarizeWeeklyStats(
            focusMinutesThisWeek = 0,
            habits = listOf(habit("walk", current = 4, longest = 9, checkedInToday = true))
        )

        assertEquals(4, stats.bestCurrentStreak)
        assertEquals(9, stats.bestLongestStreak)
    }
}

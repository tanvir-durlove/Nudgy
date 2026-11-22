package com.nextgentool.nudgy.domain.usecase

import com.nextgentool.nudgy.data.repository.HabitStreak

/**
 * Sprint 4-4 — the numbers of the weekly review (spec §7, Q9=(a)).
 *
 * The weekly review (Feature 17, S1-5) is the ONE report: its three
 * lists (finished / missed / slipped) come from [WeeklyReviewUseCase],
 * and these numbers sit beside them. Everything here is computed from
 * real data — completed FOCUS sessions and HabitCheckIn rows — never a
 * cached counter. The week is the same Mon–Sun week the review
 * reports on: [com.nextgentool.nudgy.data.repository.FocusHabitRepository.getFocusMinutesForWeek] already
 * uses [com.nextgentool.nudgy.domain.startOfWeekMillis], so the caller
 * only has to pass the same `now` to both.
 *
 * Presentation (chosen for the review, documented in PR #91): the
 * review shows the focus minutes for the week, the best current
 * streak across all habits, and the best longest streak — plus the
 * per-habit streak lines that the Habits section already shows. A
 * habit "at risk" today still counts its live streak (the
 * HabitStreakUseCase rule), so the best current streak never drops
 * to 0 while the day is still open.
 */
data class WeeklyReviewStats(
    /** Completed FOCUS minutes started this Mon–Sun week. */
    val focusMinutesThisWeek: Int = 0,
    /** Best current streak across all habits (0 when there are none). */
    val bestCurrentStreak: Int = 0,
    /** Best longest streak across all habits (0 when there are none). */
    val bestLongestStreak: Int = 0,
    /** Every habit with its streak state (for the per-habit lines). */
    val habits: List<HabitStreak> = emptyList()
)

/**
 * Pure: summarises [focusMinutesThisWeek] and the habits' streaks
 * into the review's numbers. Unit-tested without Room or Compose.
 */
fun summarizeWeeklyStats(
    focusMinutesThisWeek: Int,
    habits: List<HabitStreak>
): WeeklyReviewStats = WeeklyReviewStats(
    focusMinutesThisWeek = focusMinutesThisWeek,
    bestCurrentStreak = habits.maxOfOrNull { it.currentStreak } ?: 0,
    bestLongestStreak = habits.maxOfOrNull { it.longestStreak } ?: 0,
    habits = habits
)

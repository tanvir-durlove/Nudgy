package com.nextgentool.nudgy.domain.usecase

import com.nextgentool.nudgy.domain.dateStringOf
import javax.inject.Inject

/**
 * Sprint 7 (Focus data) — pure Focus statistics math, no Room, no
 * clock of its own: every function takes its inputs as values and
 * [now] as a parameter, so tests pin dates instead of depending on
 * the real today (HabitStreakUseCase pattern).
 *
 * - Completion %: completed FOCUS sessions ÷ all FOCUS sessions
 *   ENDED in the week (completed + ended-but-not-completed). Null
 *   when nothing ended yet — the screen shows "–", never a
 *   fabricated 0% or 100% (Stats bar-chart precedent).
 * - Streak: consecutive days ending today-or-yesterday with ≥1
 *   completed FOCUS session. Reuses [HabitStreakUseCase.currentStreak]
 *   on the sessions' local start dates, so the "at risk, not broken,
 *   today" rule is exactly the habit rule (spec §5).
 */
class FocusStatsUseCase @Inject constructor(
    private val streakUseCase: HabitStreakUseCase = HabitStreakUseCase()
) {

    /** Null when [completed] + [endedNotCompleted] is 0 (empty week). */
    fun completionPercent(completed: Int, endedNotCompleted: Int): Int? {
        val total = completed + endedNotCompleted
        if (total <= 0) return null
        return completed * 100 / total
    }

    /**
     * Consecutive days ending today-or-yesterday with ≥1 completed
     * FOCUS session. [completedSessionStartsMillis] are the
     * startedAtEpochMillis values of completed FOCUS sessions (a
     * generous window — the repository passes the last 366 days).
     */
    fun focusStreakDays(completedSessionStartsMillis: List<Long>, now: Long): Int {
        val dates = completedSessionStartsMillis.map { dateStringOf(it) }.toSet()
        return streakUseCase.currentStreak(dates, dateStringOf(now))
    }
}

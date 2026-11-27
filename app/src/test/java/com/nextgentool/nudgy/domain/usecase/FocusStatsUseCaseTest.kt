package com.nextgentool.nudgy.domain.usecase

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Sprint 7 (Focus data) — pure Focus statistics math (no Room, no
 * clock of its own: dates are pinned, like HabitStreakUseCaseTest).
 */
class FocusStatsUseCaseTest {

    private val useCase = FocusStatsUseCase(HabitStreakUseCase())

    private fun millisOf(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        Calendar.getInstance().apply {
            set(year, month, day, hour, 0, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    // ---- Completion % ----

    @Test
    fun `completion percent is completed over all ended`() {
        assertEquals(75, useCase.completionPercent(3, 1))
        assertEquals(100, useCase.completionPercent(4, 0))
        assertEquals(0, useCase.completionPercent(0, 3))
        assertEquals(50, useCase.completionPercent(1, 1))
    }

    @Test
    fun `completion percent is null on an empty week`() {
        assertNull(useCase.completionPercent(0, 0))
    }

    @Test
    fun `completion percent rounds down`() {
        assertEquals(33, useCase.completionPercent(1, 2))
        assertEquals(66, useCase.completionPercent(2, 1))
    }

    // ---- Streak ----

    @Test
    fun `streak counts consecutive days ending today`() {
        val now = millisOf(2026, Calendar.NOVEMBER, 18, 15)
        val starts = listOf(
            millisOf(2026, Calendar.NOVEMBER, 18, 10),
            millisOf(2026, Calendar.NOVEMBER, 17, 10),
            millisOf(2026, Calendar.NOVEMBER, 16, 10)
        )
        assertEquals(3, useCase.focusStreakDays(starts, now))
    }

    @Test
    fun `streak starts at yesterday when today is not done yet`() {
        val now = millisOf(2026, Calendar.NOVEMBER, 18, 15)
        val starts = listOf(
            millisOf(2026, Calendar.NOVEMBER, 17, 10),
            millisOf(2026, Calendar.NOVEMBER, 16, 10)
        )
        assertEquals(2, useCase.focusStreakDays(starts, now))
    }

    @Test
    fun `streak is one with only today done`() {
        val now = millisOf(2026, Calendar.NOVEMBER, 18, 15)
        val starts = listOf(millisOf(2026, Calendar.NOVEMBER, 18, 10))
        assertEquals(1, useCase.focusStreakDays(starts, now))
    }

    @Test
    fun `streak is zero when yesterday is missing`() {
        val now = millisOf(2026, Calendar.NOVEMBER, 18, 15)
        // Only Nov 16 — yesterday (Nov 17) has no session, so the
        // run never starts, even though today is still open.
        val starts = listOf(millisOf(2026, Calendar.NOVEMBER, 16, 10))
        assertEquals(0, useCase.focusStreakDays(starts, now))
    }

    @Test
    fun `streak stops at the first missing day`() {
        val now = millisOf(2026, Calendar.NOVEMBER, 18, 15)
        val starts = listOf(
            millisOf(2026, Calendar.NOVEMBER, 18, 10),
            millisOf(2026, Calendar.NOVEMBER, 17, 10),
            // Nov 15 missing — the Nov 14 run does not count.
            millisOf(2026, Calendar.NOVEMBER, 14, 10)
        )
        assertEquals(2, useCase.focusStreakDays(starts, now))
    }

    @Test
    fun `streak is zero with no sessions`() {
        val now = millisOf(2026, Calendar.NOVEMBER, 18, 15)
        assertEquals(0, useCase.focusStreakDays(emptyList(), now))
    }

    @Test
    fun `multiple sessions on one day count once`() {
        val now = millisOf(2026, Calendar.NOVEMBER, 18, 15)
        val starts = listOf(
            millisOf(2026, Calendar.NOVEMBER, 18, 10),
            millisOf(2026, Calendar.NOVEMBER, 18, 11),
            millisOf(2026, Calendar.NOVEMBER, 17, 10)
        )
        assertEquals(2, useCase.focusStreakDays(starts, now))
    }
}

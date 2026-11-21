package com.nextgentool.nudgy.domain.usecase

import com.nextgentool.nudgy.domain.dateStringOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Sprint 4-0 — habit streak math (spec §5). Pure: the use case takes
 * local-date strings and a pinned "today", so every case is exact.
 *
 * Two timezone cases pin the default timezone and restore it in
 * tearDown: the streak boundary is the DEVICE-LOCAL midnight, so the
 * same instant is a different local date in Dhaka and in UTC — the
 * date conversion (not the streak walk) is what must be local.
 */
class HabitStreakUseCaseTest {

    private val useCase = HabitStreakUseCase()
    private var savedTimeZone: TimeZone? = null

    @Before
    fun setUp() {
        savedTimeZone = TimeZone.getDefault()
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(savedTimeZone)
    }

    // ---- current streak ----

    @Test
    fun `empty history has no streak`() {
        assertEquals(0, useCase.currentStreak(emptySet(), "2026-11-21"))
        assertEquals(0, useCase.longestStreak(emptySet()))
    }

    @Test
    fun `today only is a streak of one`() {
        val dates = setOf("2026-11-21")
        assertEquals(1, useCase.currentStreak(dates, "2026-11-21"))
        assertEquals(1, useCase.longestStreak(dates))
    }

    @Test
    fun `three consecutive days ending today is a streak of three`() {
        val dates = setOf("2026-11-19", "2026-11-20", "2026-11-21")
        assertEquals(3, useCase.currentStreak(dates, "2026-11-21"))
        assertEquals(3, useCase.longestStreak(dates))
    }

    @Test
    fun `today not checked in yet counts back from yesterday - at risk, not broken`() {
        // Checked in Nov 19 + 20, not yet today (Nov 21): the day is
        // not over, so the streak is 2, not 0 (spec §5).
        val dates = setOf("2026-11-19", "2026-11-20")
        assertEquals(2, useCase.currentStreak(dates, "2026-11-21"))
        assertEquals(2, useCase.longestStreak(dates))
    }

    @Test
    fun `yesterday missed means the streak is zero - no grace day`() {
        // Last check-in Nov 19, today Nov 21: Nov 20 was missed, so
        // the streak is 0 even though the history is not empty (Q6=(a)).
        val dates = setOf("2026-11-19")
        assertEquals(0, useCase.currentStreak(dates, "2026-11-21"))
        assertEquals(1, useCase.longestStreak(dates))
    }

    @Test
    fun `a gap splits the run - current is the tail, longest is the best run`() {
        // Nov 10–12 (3), gap, Nov 14–15 (2), today Nov 15.
        val dates = setOf("2026-11-10", "2026-11-11", "2026-11-12", "2026-11-14", "2026-11-15")
        assertEquals(2, useCase.currentStreak(dates, "2026-11-15"))
        assertEquals(3, useCase.longestStreak(dates))
    }

    @Test
    fun `an isolated check-in is a longest streak of one`() {
        val dates = setOf("2026-11-15")
        assertEquals(0, useCase.currentStreak(dates, "2026-11-21"))
        assertEquals(1, useCase.longestStreak(dates))
    }

    // ---- day stepping (DST-safe, Calendar.add) ----

    @Test
    fun `previous day steps back across a month boundary`() {
        assertEquals("2026-10-31", useCase.previousDayString("2026-11-01"))
    }

    @Test
    fun `next day steps forward across a year boundary`() {
        assertEquals("2027-01-01", useCase.nextDayString("2026-12-31"))
    }

    @Test
    fun `next day steps into the leap day`() {
        assertEquals("2028-02-29", useCase.nextDayString("2028-02-28"))
        assertEquals("2028-03-01", useCase.nextDayString("2028-02-29"))
    }

    @Test
    fun `an invalid date string steps to null and never crashes the streak`() {
        assertNull(useCase.previousDayString("not-a-date"))
        assertNull(useCase.nextDayString("2026-02-30"))
        assertEquals(0, useCase.currentStreak(setOf("2026-11-21"), "not-a-date"))
    }

    // ---- timezone: the local date of an instant, not the UTC date ----

    /** An instant built in the given zone, as epoch millis. */
    private fun atInZone(
        zoneId: String,
        year: Int, month: Int, day: Int, hour: Int, minute: Int = 0
    ): Long = Calendar.getInstance(TimeZone.getTimeZone(zoneId)).apply {
        set(Calendar.YEAR, year)
        set(Calendar.MONTH, month - 1)
        set(Calendar.DAY_OF_MONTH, day)
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun `the same instant is a different local date in Dhaka and in UTC`() {
        // 2026-11-20 00:30 in Dhaka is 2026-11-19 18:30 UTC — the
        // local date is Nov 20 in Dhaka, Nov 19 in UTC. The streak
        // boundary must follow the device-local date.
        val instant = atInZone("Asia/Dhaka", 2026, 11, 20, 0, 30)
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Dhaka"))
        assertEquals("2026-11-20", dateStringOf(instant))
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        assertEquals("2026-11-19", dateStringOf(instant))
    }

    @Test
    fun `a late-night check-in and the next morning are two different streak days`() {
        // 23:30 and 00:30 local (Dhaka) are consecutive local days.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Dhaka"))
        val lateNight = atInZone("Asia/Dhaka", 2026, 11, 20, 23, 30)
        val nextMorning = atInZone("Asia/Dhaka", 2026, 11, 21, 0, 30)
        val dates = setOf(dateStringOf(lateNight), dateStringOf(nextMorning))
        assertEquals(setOf("2026-11-20", "2026-11-21"), dates)
        assertEquals(2, useCase.currentStreak(dates, "2026-11-21"))
    }

    @Test
    fun `midnight-boundary streak holds in America Los Angeles too`() {
        // 2026-11-20 23:30 and 2026-11-21 00:30 in Los Angeles are two
        // consecutive local days — the rule is the device zone, not a
        // hard-coded one.
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
        val lateNight = atInZone("America/Los_Angeles", 2026, 11, 20, 23, 30)
        val nextMorning = atInZone("America/Los_Angeles", 2026, 11, 21, 0, 30)
        val dates = setOf(dateStringOf(lateNight), dateStringOf(nextMorning))
        assertEquals(setOf("2026-11-20", "2026-11-21"), dates)
        assertEquals(2, useCase.currentStreak(dates, "2026-11-21"))
    }
}

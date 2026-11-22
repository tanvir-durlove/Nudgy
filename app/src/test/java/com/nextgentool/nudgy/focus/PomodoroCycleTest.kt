package com.nextgentool.nudgy.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sprint 4-1 — the Pomodoro cycle, plain JUnit (spec §4, Q1=(a)).
 * No Android, no clock: every instant is passed in, so these run
 * on the bare JVM.
 */
class PomodoroCycleTest {

    // ---- Break rule: 5 minutes, 15 after every 4th completed session ----

    @Test
    fun `no completed session yet means a short break`() {
        assertEquals(5, PomodoroCycle.breakMinutesAfter(0))
    }

    @Test
    fun `one to three completed sessions mean a short break`() {
        assertEquals(5, PomodoroCycle.breakMinutesAfter(1))
        assertEquals(5, PomodoroCycle.breakMinutesAfter(2))
        assertEquals(5, PomodoroCycle.breakMinutesAfter(3))
    }

    @Test
    fun `the fourth completed session means a long break`() {
        assertEquals(15, PomodoroCycle.breakMinutesAfter(4))
    }

    @Test
    fun `the cycle repeats - fifth to seventh short, eighth long`() {
        assertEquals(5, PomodoroCycle.breakMinutesAfter(5))
        assertEquals(5, PomodoroCycle.breakMinutesAfter(6))
        assertEquals(5, PomodoroCycle.breakMinutesAfter(7))
        assertEquals(15, PomodoroCycle.breakMinutesAfter(8))
    }

    @Test
    fun `a negative count never means a long break`() {
        assertEquals(5, PomodoroCycle.breakMinutesAfter(-1))
    }

    // ---- Remaining / finished ----

    @Test
    fun `remaining is the gap to the planned end`() {
        assertEquals(25 * 60_000L, PomodoroCycle.remainingMillis(1_000_000L + 25 * 60_000L, 1_000_000L))
        assertEquals(60_000L, PomodoroCycle.remainingMillis(1_060_000L, 1_000_000L))
    }

    @Test
    fun `remaining never goes negative past the end`() {
        assertEquals(0L, PomodoroCycle.remainingMillis(1_000_000L, 1_000_001L))
    }

    @Test
    fun `finished exactly at the planned end and after it, not before`() {
        assertFalse(PomodoroCycle.isFinished(1_000_000L, 999_999L))
        assertTrue(PomodoroCycle.isFinished(1_000_000L, 1_000_000L))
        assertTrue(PomodoroCycle.isFinished(1_000_000L, 1_000_001L))
    }

    // ---- Formatting ----

    @Test
    fun `a full focus session formats as 25-00`() {
        assertEquals("25:00", PomodoroCycle.formatRemaining(25 * 60_000L))
    }

    @Test
    fun `seconds and minutes pad to two digits`() {
        assertEquals("05:00", PomodoroCycle.formatRemaining(5 * 60_000L))
        assertEquals("00:09", PomodoroCycle.formatRemaining(9_000L))
        assertEquals("00:00", PomodoroCycle.formatRemaining(0L))
        assertEquals("24:59", PomodoroCycle.formatRemaining(24 * 60_000L + 59_000L))
    }

    @Test
    fun `sub-second remainders round down to the shown second`() {
        assertEquals("00:00", PomodoroCycle.formatRemaining(999L))
        assertEquals("00:01", PomodoroCycle.formatRemaining(1_500L))
    }
}

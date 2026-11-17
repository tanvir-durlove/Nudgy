package com.nextgentool.nudgy.domain

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sprint 1-2 — DayDates helpers (pure, device-local).
 */
class DayDatesTest {

    @Test
    fun `todayDateString is yyyy-MM-dd`() {
        assertTrue(todayDateString().matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
    }

    @Test
    fun `startOfDayMillis zeroes the time`() {
        val at1530 = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 15)
            set(Calendar.MINUTE, 30)
            set(Calendar.SECOND, 45)
            set(Calendar.MILLISECOND, 123)
        }.timeInMillis
        val start = startOfDayMillis(at1530)
        val cal = Calendar.getInstance().apply { timeInMillis = start }
        assertEquals(0, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
        assertEquals(0, cal.get(Calendar.SECOND))
        assertEquals(0, cal.get(Calendar.MILLISECOND))
        assertEquals(startOfDayMillis(System.currentTimeMillis()), startOfDayMillis(at1530))
    }

    @Test
    fun `endOfDayMillis is the last millisecond of the day`() {
        val now = System.currentTimeMillis()
        assertEquals(
            startOfDayMillis(now) + 24 * 60 * 60 * 1000L - 1,
            endOfDayMillis(now)
        )
    }

    @Test
    fun `formatMinutes spells durations`() {
        assertEquals("45 min", formatMinutes(45))
        assertEquals("1h 0m", formatMinutes(60))
        assertEquals("1h 30m", formatMinutes(90))
        assertEquals("8h 0m", formatMinutes(480))
    }
}

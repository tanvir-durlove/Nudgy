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

    // ------------------------------------------------------- Sprint 2-4

    private fun millisOf(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        Calendar.getInstance().apply {
            set(year, month, day, hour, 0, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun `parseStrictDate accepts a real date and returns its start of day`() {
        val parsed = parseStrictDate("2026-11-20")
        assertEquals(startOfDayMillis(millisOf(2026, Calendar.NOVEMBER, 20)), parsed)
    }

    @Test
    fun `parseStrictDate rejects impossible and malformed dates`() {
        assertEquals(null, parseStrictDate("2026-13-01"))
        assertEquals(null, parseStrictDate("2026-02-30"))
        assertEquals(null, parseStrictDate("2026-11-31"))
        assertEquals(null, parseStrictDate("2026-2-3"))
        assertEquals(null, parseStrictDate("20-11-2026"))
        assertEquals(null, parseStrictDate("2026/11/20"))
        assertEquals(null, parseStrictDate(""))
        assertEquals(null, parseStrictDate("not a date"))
    }

    @Test
    fun `parseStrictDate accepts a leap day only in a leap year`() {
        assertEquals(
            startOfDayMillis(millisOf(2024, Calendar.FEBRUARY, 29)),
            parseStrictDate("2024-02-29")
        )
        assertEquals(null, parseStrictDate("2026-02-29"))
    }

    @Test
    fun `minutesOfTime parses HH mm and rejects the rest`() {
        assertEquals(0, minutesOfTime("00:00"))
        assertEquals(900, minutesOfTime("15:00"))
        assertEquals(1439, minutesOfTime("23:59"))
        assertEquals(null, minutesOfTime("24:00"))
        assertEquals(null, minutesOfTime("15:60"))
        assertEquals(null, minutesOfTime("3:00"))
        assertEquals(null, minutesOfTime("3pm"))
        assertEquals(null, minutesOfTime(""))
    }

    @Test
    fun `weekOffsetForDay counts whole weeks from the current week`() {
        // "Now" is Friday 2026-11-20 (week Mon Nov 16 – Sun Nov 22).
        val now = millisOf(2026, Calendar.NOVEMBER, 20)
        assertEquals(0, weekOffsetForDay(millisOf(2026, Calendar.NOVEMBER, 18), now))
        assertEquals(0, weekOffsetForDay(millisOf(2026, Calendar.NOVEMBER, 22), now))
        assertEquals(1, weekOffsetForDay(millisOf(2026, Calendar.NOVEMBER, 23), now))
        assertEquals(1, weekOffsetForDay(millisOf(2026, Calendar.NOVEMBER, 25), now))
        assertEquals(2, weekOffsetForDay(millisOf(2026, Calendar.NOVEMBER, 30), now))
        assertEquals(-1, weekOffsetForDay(millisOf(2026, Calendar.NOVEMBER, 15), now))
        assertEquals(-1, weekOffsetForDay(millisOf(2026, Calendar.NOVEMBER, 9), now))
        assertEquals(-2, weekOffsetForDay(millisOf(2026, Calendar.NOVEMBER, 2), now))
    }

    @Test
    fun `dayIndexInWeek is Monday 0 through Sunday 6`() {
        assertEquals(0, dayIndexInWeek(millisOf(2026, Calendar.NOVEMBER, 16)))
        assertEquals(2, dayIndexInWeek(millisOf(2026, Calendar.NOVEMBER, 18)))
        assertEquals(4, dayIndexInWeek(millisOf(2026, Calendar.NOVEMBER, 20)))
        assertEquals(5, dayIndexInWeek(millisOf(2026, Calendar.NOVEMBER, 21)))
        assertEquals(6, dayIndexInWeek(millisOf(2026, Calendar.NOVEMBER, 22)))
    }

    // ------------------------------------------------------- Sprint 7 (Focus data)

    @Test
    fun `recentDayLabel says Today for today`() {
        val now = millisOf(2026, Calendar.NOVEMBER, 18, 15)
        assertEquals("Today", recentDayLabel(millisOf(2026, Calendar.NOVEMBER, 18, 9), now))
    }

    @Test
    fun `recentDayLabel says Yesterday for yesterday`() {
        val now = millisOf(2026, Calendar.NOVEMBER, 18, 15)
        assertEquals("Yesterday", recentDayLabel(millisOf(2026, Calendar.NOVEMBER, 17, 9), now))
    }

    @Test
    fun `recentDayLabel falls back to the date label`() {
        val now = millisOf(2026, Calendar.NOVEMBER, 18, 15)
        // 2026-11-16 is a Monday (see the dayIndexInWeek test above).
        assertEquals("Mon, Nov 16", recentDayLabel(millisOf(2026, Calendar.NOVEMBER, 16, 9), now))
    }
}

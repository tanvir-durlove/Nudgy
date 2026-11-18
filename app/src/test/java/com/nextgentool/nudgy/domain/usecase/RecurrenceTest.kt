package com.nextgentool.nudgy.domain.usecase

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sprint 1-3a — unit tests for the recurrence engine (Feature 3).
 *
 * Pure JVM (java.util.Calendar, device-local zone): anchors and expected
 * values are built with the same Calendar convention, so the tests hold in
 * any zone CI runs in. Dates below are 2026 (Feb has 28 days).
 */
class RecurrenceTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int = 9, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    // ------------------------------------------------------------ parsing

    @Test
    fun `parse accepts all four formats`() {
        assertTrue(Recurrence.parse("DAILY") is Recurrence.Rule.Daily)
        assertTrue(Recurrence.parse("weekly:mon,wed") is Recurrence.Rule.Weekly)
        assertEquals(Recurrence.Rule.Monthly(15), Recurrence.parse("MONTHLY:15"))
        assertEquals(Recurrence.Rule.EveryNDays(3), Recurrence.parse("EVERY_N_DAYS:3"))
    }

    @Test
    fun `parse rejects invalid rules`() {
        assertNull(Recurrence.parse(null))
        assertNull(Recurrence.parse(""))
        assertNull(Recurrence.parse("HOURLY"))
        assertNull(Recurrence.parse("WEEKLY:"))
        assertNull(Recurrence.parse("WEEKLY:FUNDAY"))
        assertNull(Recurrence.parse("MONTHLY:0"))
        assertNull(Recurrence.parse("MONTHLY:32"))
        assertNull(Recurrence.parse("EVERY_N_DAYS:0"))
        assertNull(Recurrence.parse("EVERY_N_DAYS:-2"))
    }

    @Test
    fun `format round-trips through parse`() {
        listOf("DAILY", "WEEKLY:MON,WED", "MONTHLY:15", "EVERY_N_DAYS:3").forEach { rule ->
            assertEquals(rule, Recurrence.format(Recurrence.parse(rule)!!))
        }
    }

    // ------------------------------------------------------- next occurrence

    @Test
    fun `daily task completed on its day recurs tomorrow at the same time`() {
        // Due Mon 2026-11-16 09:00, completed Mon 10:00.
        val next = Recurrence.nextOccurrence("DAILY", at(2026, Calendar.NOVEMBER, 16), at(2026, Calendar.NOVEMBER, 16, 10))
        assertEquals(at(2026, Calendar.NOVEMBER, 17), next)
    }

    @Test
    fun `daily task completed late still lands in the future`() {
        // Due Mon 09:00, completed Thu 10:00 -> Fri 09:00 (not Tue, which is past).
        val next = Recurrence.nextOccurrence("DAILY", at(2026, Calendar.NOVEMBER, 16), at(2026, Calendar.NOVEMBER, 19, 10))
        assertEquals(at(2026, Calendar.NOVEMBER, 20), next)
    }

    @Test
    fun `weekly Mon Wed lands on the right days`() {
        // Due Mon 2026-11-16 09:00, completed Mon 10:00 -> Wed 09:00.
        val next = Recurrence.nextOccurrence("WEEKLY:MON,WED", at(2026, Calendar.NOVEMBER, 16), at(2026, Calendar.NOVEMBER, 16, 10))
        assertEquals(at(2026, Calendar.NOVEMBER, 18), next)
        // Due Wed 09:00, completed Wed 10:00 -> next Mon 09:00.
        val next2 = Recurrence.nextOccurrence("WEEKLY:MON,WED", at(2026, Calendar.NOVEMBER, 18), at(2026, Calendar.NOVEMBER, 18, 10))
        assertEquals(at(2026, Calendar.NOVEMBER, 23), next2)
    }

    @Test
    fun `completing early consumes the current occurrence`() {
        // Due Mon 09:00, completed Mon 08:00 (before due) -> next is Wed,
        // not another Mon 09:00 today.
        val next = Recurrence.nextOccurrence("WEEKLY:MON,WED", at(2026, Calendar.NOVEMBER, 16), at(2026, Calendar.NOVEMBER, 16, 8))
        assertEquals(at(2026, Calendar.NOVEMBER, 18), next)
    }

    @Test
    fun `monthly 15th steps to the next month`() {
        val next = Recurrence.nextOccurrence("MONTHLY:15", at(2026, Calendar.NOVEMBER, 15), at(2026, Calendar.NOVEMBER, 16, 10))
        assertEquals(at(2026, Calendar.DECEMBER, 15), next)
    }

    @Test
    fun `monthly 31st clamps to the last day of shorter months`() {
        // Due Jan 31, completed Feb 1 -> Feb 28 (2026 is not a leap year).
        val next = Recurrence.nextOccurrence("MONTHLY:31", at(2026, Calendar.JANUARY, 31), at(2026, Calendar.FEBRUARY, 1, 10))
        assertEquals(at(2026, Calendar.FEBRUARY, 28), next)
    }

    @Test
    fun `every 3 days steps by 3 and skips past occurrences`() {
        // Due Mon Nov 16 09:00, completed Tue 10:00 -> Thu Nov 19 09:00.
        val next = Recurrence.nextOccurrence("EVERY_N_DAYS:3", at(2026, Calendar.NOVEMBER, 16), at(2026, Calendar.NOVEMBER, 17, 10))
        assertEquals(at(2026, Calendar.NOVEMBER, 19), next)
        // Completed Fri Nov 20 10:00 -> Sun Nov 22 09:00 (Nov 19 is past).
        val late = Recurrence.nextOccurrence("EVERY_N_DAYS:3", at(2026, Calendar.NOVEMBER, 16), at(2026, Calendar.NOVEMBER, 20, 10))
        assertEquals(at(2026, Calendar.NOVEMBER, 22), late)
    }

    @Test
    fun `invalid rule has no next occurrence`() {
        assertNull(Recurrence.nextOccurrence("HOURLY", at(2026, Calendar.NOVEMBER, 16), at(2026, Calendar.NOVEMBER, 16, 10)))
    }

    // ------------------------------------------------------------- describe

    @Test
    fun `describe produces human labels`() {
        assertEquals("Every day", Recurrence.describe("DAILY"))
        assertEquals("Every Mon, Wed", Recurrence.describe("WEEKLY:MON,WED"))
        assertEquals("Monthly on the 15th", Recurrence.describe("MONTHLY:15"))
        assertEquals("Monthly on the 1st", Recurrence.describe("MONTHLY:1"))
        assertEquals("Every 3 days", Recurrence.describe("EVERY_N_DAYS:3"))
        assertNull(Recurrence.describe("NOPE"))
    }
}

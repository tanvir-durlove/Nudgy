package com.nextgentool.nudgy.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sprint 1-3a — unit tests for the reminder decision logic (Feature 6).
 *
 * Pure functions on ReminderManager's companion: repeat scheduling, the
 * "missed" state, and the snooze length. No Android framework needed.
 */
class ReminderLogicTest {

    private val now = 1_800_000_000_000L // a fixed "now" (2027-01-15)

    // ------------------------------------------------------------- repeat

    @Test
    fun `repeat fires at now plus the interval`() {
        assertEquals(now + 15 * 60_000L, ReminderManager.nextRepeatFireMillis(15, false, now))
        assertEquals(now + 60 * 60_000L, ReminderManager.nextRepeatFireMillis(60, false, now))
    }

    @Test
    fun `repeat never fires for a completed task`() {
        assertNull(ReminderManager.nextRepeatFireMillis(15, true, now))
    }

    @Test
    fun `repeat never fires without an interval`() {
        assertNull(ReminderManager.nextRepeatFireMillis(null, false, now))
        assertNull(ReminderManager.nextRepeatFireMillis(0, false, now))
        assertNull(ReminderManager.nextRepeatFireMillis(-5, false, now))
    }

    // ------------------------------------------------------------- missed

    @Test
    fun `missed only when exact alarms are off and the trigger has passed`() {
        // Trigger 1h ago, task open, exact alarms off -> missed.
        assertTrue(ReminderManager.isReminderMissed(true, now - 3_600_000L, false, false, now))
    }

    @Test
    fun `not missed when exact alarms are allowed`() {
        assertFalse(ReminderManager.isReminderMissed(true, now - 3_600_000L, false, true, now))
    }

    @Test
    fun `not missed when the trigger is still in the future`() {
        assertFalse(ReminderManager.isReminderMissed(true, now + 3_600_000L, false, false, now))
    }

    @Test
    fun `not missed when the task is completed or the reminder is off`() {
        assertFalse(ReminderManager.isReminderMissed(true, now - 3_600_000L, true, false, now))
        assertFalse(ReminderManager.isReminderMissed(false, now - 3_600_000L, false, false, now))
        assertFalse(ReminderManager.isReminderMissed(true, null, false, false, now))
    }

    // ------------------------------------------------------------- snooze

    @Test
    fun `snooze is 10 minutes`() {
        assertEquals(10 * 60 * 1000L, ReminderManager.SNOOZE_MILLIS)
    }
}

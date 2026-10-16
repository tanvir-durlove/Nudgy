package com.example.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ReminderManager.getRequestCode].
 *
 * The request code feeds PendingIntent identity for reminder notifications
 * and their Done/Snooze actions. It must be non-negative (AlarmManager /
 * NotificationManager requirement), deterministic for the same task, and
 * distinct across tasks so reminders never overwrite each other.
 * Pure integer math — no Android framework needed.
 */
class ReminderRequestCodeTest {

    @Test
    fun `request code is always non-negative`() {
        assertTrue(ReminderManager.getRequestCode("some-task-id") >= 0)
        assertTrue(ReminderManager.getRequestCode("") >= 0)
        // hashCode can be negative; the mask must clear the sign bit
        assertTrue(ReminderManager.getRequestCode("negative-hash-probe-xyz-123") >= 0)
    }

    @Test
    fun `request code is deterministic for the same task id`() {
        val taskId = "task-123-abc"
        assertEquals(
            ReminderManager.getRequestCode(taskId),
            ReminderManager.getRequestCode(taskId)
        )
    }

    @Test
    fun `different task ids produce different request codes`() {
        assertNotEquals(
            ReminderManager.getRequestCode("task-alpha"),
            ReminderManager.getRequestCode("task-beta")
        )
    }

    @Test
    fun `action request codes do not collide with the base code`() {
        val base = ReminderManager.getRequestCode("task-1")
        val done = ReminderManager.getRequestCode("task-1_done")
        val snooze = ReminderManager.getRequestCode("task-1_snooze")
        assertNotEquals(base, done)
        assertNotEquals(base, snooze)
        assertNotEquals(done, snooze)
    }
}

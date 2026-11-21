package com.nextgentool.nudgy.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Sprint 3-2 — [ChatDraft] edit helpers are pure: the edit dialog
 * moves a draft without touching the DB, so the shift rules are
 * pinned here (end and reminder move with the start by the same
 * delta; a reminder-at-due follows the start exactly).
 */
@RunWith(JUnit4::class)
class ChatDraftTest {

    private val base = ChatDraft(
        kind = DraftKind.TASK,
        title = "Call Tasnim",
        startEpochMillis = 1_700_000_000_000L,
        priority = com.nextgentool.nudgy.data.model.Priority.HIGH,
        reminderAtDue = false,
        reminderEpochMillis = 1_700_000_000_000L - 2 * 60 * 60 * 1000L
    )

    @Test
    fun `withTitle changes only the title`() {
        val edited = base.withTitle("Call Tasnim about the launch")
        assertEquals("Call Tasnim about the launch", edited.title)
        assertEquals(base.startEpochMillis, edited.startEpochMillis)
        assertEquals(base.reminderEpochMillis, edited.reminderEpochMillis)
    }

    @Test
    fun `withStartTime shifts end and reminder by the same delta`() {
        val oneHour = 60 * 60 * 1000L
        val schedule = base.copy(
            kind = DraftKind.SCHEDULE,
            endEpochMillis = base.startEpochMillis + 45 * 60 * 1000L
        )
        val edited = schedule.withStartTime(schedule.startEpochMillis + oneHour)
        assertEquals(schedule.startEpochMillis + oneHour, edited.startEpochMillis)
        assertEquals(schedule.endEpochMillis!! + oneHour, edited.endEpochMillis)
        assertEquals(schedule.reminderEpochMillis!! + oneHour, edited.reminderEpochMillis)
    }

    @Test
    fun `withStartTime moves a reminder-at-due onto the new start`() {
        val due = base.copy(reminderAtDue = true, reminderEpochMillis = base.startEpochMillis)
        val edited = due.withStartTime(due.startEpochMillis + 30 * 60 * 1000L)
        assertEquals(edited.startEpochMillis, edited.reminderEpochMillis)
    }

    @Test
    fun `withStartTime keeps a null reminder null`() {
        val noReminder = base.copy(reminderEpochMillis = null)
        val edited = noReminder.withStartTime(noReminder.startEpochMillis + 60 * 60 * 1000L)
        assertNull(edited.reminderEpochMillis)
        assertNull(edited.endEpochMillis)
    }
}

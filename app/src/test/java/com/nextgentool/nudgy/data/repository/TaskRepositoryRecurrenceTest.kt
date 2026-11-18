package com.nextgentool.nudgy.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 1-3a — repository tests for recurrence + snooze (Features 3, 6).
 *
 * Real in-memory Room + real TaskRepository (no Hilt): completing a
 * recurring task creates its next occurrence, and snoozing moves only the
 * reminder — never the due date. The next-occurrence date math itself is
 * covered by the pure RecurrenceTest; this pins the wiring.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TaskRepositoryRecurrenceTest {

    private lateinit var context: Context
    private lateinit var db: NudgyDatabase
    private lateinit var repository: TaskRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, NudgyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = TaskRepository(context, db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private val userId get() = repository.currentUserId()

    @Test
    fun `completing a daily task creates tomorrow's occurrence and keeps the old one`() = runTest {
        val due = System.currentTimeMillis() + 5 * 3_600_000L // 5h ahead: 2h lead applies
        val created = repository.createTask(
            userId = userId,
            title = "Gym",
            dueEpochMillis = due,
            reminderSet = true,
            recurrenceRule = "DAILY"
        )
        repository.toggleTaskCompletion(created)

        val all = repository.getAllTasksOnce(userId)
        assertEquals(2, all.size)
        val old = all.first { it.id == created.id }
        assertTrue(old.isCompleted)
        val next = all.first { it.id != created.id }
        assertEquals("Gym", next.title)
        assertEquals("DAILY", next.recurrenceRule)
        assertEquals(due + 24 * 3_600_000L, next.dueDateEpochMillis)
        assertTrue(next.reminderSet)
        // Reminder lead preserved: original was due - 2h, so is the next one.
        assertEquals(next.dueDateEpochMillis - 2 * 3_600_000L, next.reminderEpochMillis)
    }

    @Test
    fun `completing a non-recurring task creates nothing`() = runTest {
        val created = repository.createTask(
            userId = userId,
            title = "Once",
            dueEpochMillis = System.currentTimeMillis() - 3_600_000L
        )
        repository.toggleTaskCompletion(created)
        assertEquals(1, repository.getAllTasksOnce(userId).size)
    }

    @Test
    fun `stopping recurrence creates no new occurrence`() = runTest {
        val due = System.currentTimeMillis() - 3_600_000L
        val created = repository.createTask(
            userId = userId,
            title = "Gym",
            dueEpochMillis = due,
            recurrenceRule = "DAILY"
        )
        // Stop the recurrence first, then complete.
        val stopped = db.taskDao().getTaskById(created.id)!!
        repository.updateTask(stopped.copy(recurrenceRule = null))
        val updated = db.taskDao().getTaskById(created.id)!!
        repository.toggleTaskCompletion(updated)
        assertEquals(1, repository.getAllTasksOnce(userId).size)
    }

    @Test
    fun `completing via setTaskStatus also advances the recurrence`() = runTest {
        val due = System.currentTimeMillis() - 3_600_000L
        val created = repository.createTask(
            userId = userId,
            title = "Gym",
            dueEpochMillis = due,
            recurrenceRule = "DAILY"
        )
        repository.setTaskStatus(created, com.nextgentool.nudgy.data.model.TaskStatus.DONE)
        assertEquals(2, repository.getAllTasksOnce(userId).size)
    }

    @Test
    fun `snooze moves only the reminder, never the due date`() = runTest {
        val due = System.currentTimeMillis() + 5 * 3_600_000L
        val created = repository.createTask(
            userId = userId,
            title = "Dentist",
            dueEpochMillis = due,
            reminderSet = true
        )
        val snoozeTime = System.currentTimeMillis() + 10 * 60_000L
        repository.snoozeTask(created.id, snoozeTime)

        val after = db.taskDao().getTaskById(created.id)!!
        assertEquals(due, after.dueDateEpochMillis)
        assertEquals(snoozeTime, after.reminderEpochMillis)
        assertTrue(after.reminderSet)
    }

    @Test
    fun `snoozing a completed task does nothing`() = runTest {
        val due = System.currentTimeMillis() + 5 * 3_600_000L
        val created = repository.createTask(
            userId = userId,
            title = "Done already",
            dueEpochMillis = due,
            reminderSet = true
        )
        repository.toggleTaskCompletion(created)
        val before = db.taskDao().getTaskById(created.id)!!
        repository.snoozeTask(created.id, System.currentTimeMillis() + 10 * 60_000L)
        val after = db.taskDao().getTaskById(created.id)!!
        // Nothing changes: the stored reminder stays, no new alarm is set.
        assertEquals(before.reminderEpochMillis, after.reminderEpochMillis)
        assertEquals(before.dueDateEpochMillis, after.dueDateEpochMillis)
    }
}

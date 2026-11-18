package com.nextgentool.nudgy.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.ui.components.RecurrenceSection
import com.nextgentool.nudgy.ui.components.ReminderSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

/**
 * Sprint 1-3a — smoke tests for the detail sheet's Reminder + Repeat
 * sections (Features 6 and 3).
 *
 * The sections are rendered directly (not through the ModalBottomSheet)
 * so the test pins their behaviour without sheet-animation timing: the
 * chips render, tapping one calls onUpdateTask with the exact value, and
 * the "missed" state shows only when exact alarms are off and the
 * trigger has passed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TaskDetailReminderTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun task(
        dueOffsetHours: Long = 5,
        reminderSet: Boolean = false,
        reminderEpochMillis: Long? = null,
        reminderRepeatMinutes: Int? = null,
        recurrenceRule: String? = null,
        isCompleted: Boolean = false
    ): TaskEntity {
        val due = System.currentTimeMillis() + dueOffsetHours * 3_600_000L
        return TaskEntity(
            id = "t1",
            userId = "u1",
            title = "Dentist",
            dueDateEpochMillis = due,
            reminderSet = reminderSet,
            reminderEpochMillis = reminderEpochMillis,
            reminderRepeatMinutes = reminderRepeatMinutes,
            recurrenceRule = recurrenceRule,
            isCompleted = isCompleted
        )
    }

    private fun dueOf(t: TaskEntity) = t.dueDateEpochMillis

    @Test
    fun `reminder section renders and 30-min chip sets the exact trigger`() {
        val t = task()
        var updated: TaskEntity? = null
        composeRule.setContent {
            ReminderSection(
                task = t,
                exactAlarmsAllowed = true,
                enabled = true,
                onUpdateTask = { updated = it }
            )
        }
        composeRule.onNodeWithText("Reminder").assertIsDisplayed()
        composeRule.onNodeWithText("30 min before").assertIsDisplayed()
        composeRule.onNodeWithText("30 min before").performClick()
        val result = updated
        assertTrue(result != null)
        assertTrue(result!!.reminderSet)
        assertEquals(dueOf(t) - 30 * 60_000L, result!!.reminderEpochMillis)
    }

    @Test
    fun `repeat reminder chips set the interval`() {
        val t = task(reminderSet = true, reminderEpochMillis = System.currentTimeMillis() + 3_600_000L)
        var updated: TaskEntity? = null
        composeRule.setContent {
            ReminderSection(
                task = t,
                exactAlarmsAllowed = true,
                enabled = true,
                onUpdateTask = { updated = it }
            )
        }
        composeRule.onNodeWithText("Repeat reminder").assertIsDisplayed()
        composeRule.onNodeWithText("Every 30 min").performClick()
        assertEquals(30, updated?.reminderRepeatMinutes)
    }

    @Test
    fun `missed chip shows only when exact alarms are off and the trigger passed`() {
        val past = System.currentTimeMillis() - 3_600_000L
        val t = task(reminderSet = true, reminderEpochMillis = past)
        composeRule.setContent {
            ReminderSection(
                task = t,
                exactAlarmsAllowed = false,
                enabled = true,
                onUpdateTask = {}
            )
        }
        composeRule.onNodeWithText("Reminder missed").assertIsDisplayed()
    }

    @Test
    fun `no missed chip when exact alarms are allowed`() {
        val past = System.currentTimeMillis() - 3_600_000L
        val t = task(reminderSet = true, reminderEpochMillis = past)
        composeRule.setContent {
            ReminderSection(
                task = t,
                exactAlarmsAllowed = true,
                enabled = true,
                onUpdateTask = {}
            )
        }
        composeRule.onAllNodesWithText("Reminder missed").assertCountEquals(0)
    }

    @Test
    fun `recurrence section renders and Daily sets DAILY`() {
        val t = task()
        var updated: TaskEntity? = null
        composeRule.setContent {
            RecurrenceSection(
                task = t,
                enabled = true,
                onUpdateTask = { updated = it }
            )
        }
        composeRule.onNodeWithText("Repeat task").assertIsDisplayed()
        composeRule.onNodeWithText("Daily").performClick()
        assertEquals("DAILY", updated?.recurrenceRule)
    }

    @Test
    fun `recurrence section shows the next occurrence hint`() {
        // Due tomorrow 09:00, rule DAILY -> next is the day after, 09:00.
        val cal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val t = TaskEntity(
            id = "t1",
            userId = "u1",
            title = "Gym",
            dueDateEpochMillis = cal.timeInMillis,
            recurrenceRule = "DAILY"
        )
        composeRule.setContent {
            RecurrenceSection(task = t, enabled = true, onUpdateTask = {})
        }
        composeRule.onNodeWithText("Daily").assertIsDisplayed()
        // "Next: ..." hint renders for a valid rule with a future occurrence.
        composeRule.onAllNodesWithText("Next:", substring = true).assertCountEquals(1)
    }

    @Test
    fun `no-date task explains why reminder and repeat are disabled`() {
        val t = task().copy(dueDateEpochMillis = 0L)
        composeRule.setContent {
            ReminderSection(
                task = t,
                exactAlarmsAllowed = true,
                enabled = true,
                onUpdateTask = {}
            )
        }
        composeRule.onNodeWithText("Set a due date to add a reminder.").assertIsDisplayed()
        composeRule.setContent {
            RecurrenceSection(task = t, enabled = true, onUpdateTask = {})
        }
        composeRule.onNodeWithText("Set a due date to repeat this task.").assertIsDisplayed()
    }
}

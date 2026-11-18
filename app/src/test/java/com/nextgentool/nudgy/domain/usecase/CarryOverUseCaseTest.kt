package com.nextgentool.nudgy.domain.usecase

import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.model.TaskStatus
import com.nextgentool.nudgy.domain.startOfDayMillis
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sprint 1-2 — carry-over rules (Feature 16, Q2: never automatic).
 */
class CarryOverUseCaseTest {

    private val useCase = CarryOverUseCase()

    private fun at(dayOffset: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, dayOffset)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun task(
        id: String,
        due: Long,
        status: TaskStatus = TaskStatus.TODO,
        parentTaskId: String? = null,
        groupId: String? = null
    ) = TaskEntity(
        id = id,
        userId = "u1",
        title = id,
        dueDateEpochMillis = due,
        status = status,
        isCompleted = status == TaskStatus.DONE,
        parentTaskId = parentTaskId,
        groupId = groupId
    )

    @Test
    fun `overdue unfinished top-level tasks are carried over, oldest first`() {
        val today = startOfDayMillis(System.currentTimeMillis())
        val tasks = listOf(
            task("yesterday-3pm", at(-1, 15)),
            task("today-9am", at(0, 9)),
            task("three-days-ago", at(-3, 10))
        )
        val found = useCase.findCarryOver(tasks, today)
        assertEquals(listOf("three-days-ago", "yesterday-3pm"), found.map { it.id })
    }

    @Test
    fun `done tasks never appear in carry-over`() {
        val today = startOfDayMillis(System.currentTimeMillis())
        val tasks = listOf(task("done-yesterday", at(-1, 15), status = TaskStatus.DONE))
        assertTrue(useCase.findCarryOver(tasks, today).isEmpty())
    }

    @Test
    fun `no-date tasks and subtasks are excluded`() {
        val today = startOfDayMillis(System.currentTimeMillis())
        val tasks = listOf(
            task("no-date", 0L),
            task("subtask-overdue", at(-2, 11), parentTaskId = "parent-1")
        )
        assertTrue(useCase.findCarryOver(tasks, today).isEmpty())
    }

    @Test
    fun `group tasks are included in carry-over`() {
        val today = startOfDayMillis(System.currentTimeMillis())
        val tasks = listOf(task("group-overdue", at(-1, 12), groupId = "g1"))
        assertEquals(listOf("group-overdue"), useCase.findCarryOver(tasks, today).map { it.id })
    }

    @Test
    fun `moving to today keeps the time-of-day`() {
        val today = startOfDayMillis(System.currentTimeMillis())
        val moved = useCase.moveDueToToday(at(-1, 15, 30), today)
        val cal = Calendar.getInstance().apply { timeInMillis = moved }
        assertEquals(15, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, cal.get(Calendar.MINUTE))
        assertEquals(today, startOfDayMillis(moved))
    }

    @Test
    fun `carriedOverFromDate is the old due date`() {
        val due = at(-2, 11)
        assertEquals(
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(due),
            useCase.carriedOverFromDate(due)
        )
    }
}

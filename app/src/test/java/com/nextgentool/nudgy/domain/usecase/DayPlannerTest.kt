package com.nextgentool.nudgy.domain.usecase

import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.startOfDayMillis
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Sprint 3-6 (features 21 + 22) — the shared day-planning
 * computation is pure: unplaced-task filter, schedule order,
 * priority order. No Room, no repository.
 */
class DayPlannerTest {

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
        priority: Priority = Priority.MEDIUM,
        isCompleted: Boolean = false,
        plannedStart: Long? = null,
        parentTaskId: String? = null
    ) = TaskEntity(
        id = id,
        userId = "u1",
        title = id,
        dueDateEpochMillis = due,
        priority = priority,
        isCompleted = isCompleted,
        plannedStartEpochMillis = plannedStart,
        parentTaskId = parentTaskId
    )

    private val todayStart = startOfDayMillis(System.currentTimeMillis())
    private val todayEnd = todayStart + 24 * 60 * 60 * 1000L

    @Test
    fun `unplaced keeps overdue today-due and anytime, drops the rest`() {
        val placed = task("placed", at(0, 10), plannedStart = at(0, 10))
        val tasks = listOf(
            task("overdue", at(-1, 15)),
            task("today", at(0, 15)),
            task("anytime", TaskRepository.NO_DUE_DATE),
            task("tomorrow", at(1, 10)),
            task("done", at(0, 11), isCompleted = true),
            task("subtask", at(0, 12), parentTaskId = "p1"),
            placed
        )
        assertEquals(
            listOf("overdue", "today", "anytime"),
            DayPlanner.unplacedTasks(tasks, todayStart, todayEnd).map { it.id }
        )
    }

    @Test
    fun `schedule order is due time then HIGH then timeless last`() {
        val tasks = listOf(
            task("timeless-high", TaskRepository.NO_DUE_DATE, priority = Priority.HIGH),
            task("due-15-med", at(0, 15)),
            task("due-15-high", at(0, 15), priority = Priority.HIGH),
            task("due-9-low", at(0, 9), priority = Priority.LOW),
            task("midnight", at(0, 0))
        )
        assertEquals(
            listOf("due-9-low", "due-15-high", "due-15-med", "timeless-high", "midnight"),
            DayPlanner.scheduleOrder(tasks).map { it.id }
        )
    }

    @Test
    fun `priority order is overdue then due then HIGH then conflict last`() {
        val now = todayStart + 10 * 60 * 60 * 1000L // 10:00 today
        val tasks = listOf(
            task("timeless-low", TaskRepository.NO_DUE_DATE, priority = Priority.LOW),
            task("due-15-high-conflict", at(0, 15), priority = Priority.HIGH),
            task("due-15-med-free", at(0, 15)),
            task("due-11-low", at(0, 11), priority = Priority.LOW),
            task("overdue-9", at(0, 9)),
            task("overdue-yesterday-high", at(-1, 18), priority = Priority.HIGH)
        )
        val conflicts = setOf("due-15-high-conflict")
        // Keys in spec order: overdue, due time, HIGH, conflict last —
        // so the HIGH conflicting task still beats the MEDIUM free one
        // at the same due time; conflict only breaks same-priority ties.
        assertEquals(
            listOf(
                "overdue-yesterday-high",
                "overdue-9",
                "due-11-low",
                "due-15-high-conflict",
                "due-15-med-free",
                "timeless-low"
            ),
            DayPlanner.priorityOrder(tasks, now) { it.id in conflicts }.map { it.id }
        )
    }

    @Test
    fun `priority order puts a conflicting task below a free one at the same due time`() {
        val now = todayStart + 8 * 60 * 60 * 1000L // 08:00 today
        val tasks = listOf(
            task("a-conflict", at(0, 14)),
            task("b-free", at(0, 14))
        )
        assertEquals(
            listOf("b-free", "a-conflict"),
            DayPlanner.priorityOrder(tasks, now) { it.id == "a-conflict" }.map { it.id }
        )
    }
}

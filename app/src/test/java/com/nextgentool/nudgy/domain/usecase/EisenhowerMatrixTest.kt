package com.nextgentool.nudgy.domain.usecase

import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.endOfDayMillis
import com.nextgentool.nudgy.domain.startOfDayMillis
import com.nextgentool.nudgy.ui.viewmodel.TaskListItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sprint 4-3 — Eisenhower quadrant assignment + move mapping (spec §6).
 *
 * Pure function, no Compose: what is pinned here is that every task
 * lands in exactly one quadrant from its existing priority + due date
 * (Q7=(a) — derived, no new field), that completed tasks are excluded,
 * and that the move mapping depends only on the TARGET quadrant — so a
 * move round-trips in both directions. The drag itself is a UI gesture
 * (EisenhowerMatrixTest drives it); the ViewModel write is pinned in
 * EisenhowerMatrixViewModelTest.
 */
class EisenhowerMatrixTest {

    /** Noon on the local day [daysAhead] days from today (0 = today). */
    private fun noon(daysAhead: Int = 0): Long =
        startOfDayMillis(daysAhead, System.currentTimeMillis()) + 12 * 60 * 60 * 1000L

    private fun task(
        id: String,
        priority: Priority,
        due: Long,
        completed: Boolean = false
    ) = TaskListItem(
        task = TaskEntity(
            id = id,
            userId = "u1",
            title = id,
            dueDateEpochMillis = due,
            priority = priority,
            isCompleted = completed
        )
    )

    private val now = noon()

    // ---- Quadrant assignment ----

    @Test
    fun `HIGH due today is Q1`() {
        assertEquals(EisenhowerQuadrant.Q1, quadrantOf(task("a", Priority.HIGH, noon()).task, now))
    }

    @Test
    fun `HIGH due in three days is Q2`() {
        assertEquals(EisenhowerQuadrant.Q2, quadrantOf(task("a", Priority.HIGH, noon(3)).task, now))
    }

    @Test
    fun `MEDIUM due today is Q3`() {
        assertEquals(EisenhowerQuadrant.Q3, quadrantOf(task("a", Priority.MEDIUM, noon()).task, now))
    }

    @Test
    fun `LOW with no due date is Q4`() {
        assertEquals(
            EisenhowerQuadrant.Q4,
            quadrantOf(task("a", Priority.LOW, TaskRepository.NO_DUE_DATE).task, now)
        )
    }

    @Test
    fun `MEDIUM with no due date is Q4`() {
        assertEquals(
            EisenhowerQuadrant.Q4,
            quadrantOf(task("a", Priority.MEDIUM, TaskRepository.NO_DUE_DATE).task, now)
        )
    }

    @Test
    fun `LOW due today is Q3`() {
        assertEquals(EisenhowerQuadrant.Q3, quadrantOf(task("a", Priority.LOW, noon()).task, now))
    }

    @Test
    fun `no due date is never urgent`() {
        // Even HIGH with no due date lands in Q2 (important, not urgent).
        assertEquals(
            EisenhowerQuadrant.Q2,
            quadrantOf(task("a", Priority.HIGH, TaskRepository.NO_DUE_DATE).task, now)
        )
    }

    @Test
    fun `overdue is urgent`() {
        assertEquals(
            EisenhowerQuadrant.Q1,
            quadrantOf(task("a", Priority.HIGH, noon(-1)).task, now)
        )
        assertEquals(
            EisenhowerQuadrant.Q3,
            quadrantOf(task("a", Priority.LOW, noon(-2)).task, now)
        )
    }

    @Test
    fun `due within 24 hours is urgent, beyond it is not`() {
        val in23h = now + 23 * 60 * 60 * 1000L
        val in25h = now + 25 * 60 * 60 * 1000L
        assertEquals(EisenhowerQuadrant.Q3, quadrantOf(task("a", Priority.MEDIUM, in23h).task, now))
        assertEquals(EisenhowerQuadrant.Q4, quadrantOf(task("a", Priority.MEDIUM, in25h).task, now))
        assertEquals(EisenhowerQuadrant.Q1, quadrantOf(task("a", Priority.HIGH, in23h).task, now))
        assertEquals(EisenhowerQuadrant.Q2, quadrantOf(task("a", Priority.HIGH, in25h).task, now))
    }

    @Test
    fun `completed tasks have no quadrant`() {
        assertNull(quadrantOf(task("a", Priority.HIGH, noon(), completed = true).task, now))
    }

    @Test
    fun `grouping puts every item in exactly one quadrant and excludes completed`() {
        val items = listOf(
            task("q1", Priority.HIGH, noon()),
            task("q2", Priority.HIGH, noon(3)),
            task("q3", Priority.MEDIUM, noon()),
            task("q4", Priority.LOW, TaskRepository.NO_DUE_DATE),
            task("done", Priority.HIGH, noon(), completed = true)
        )
        val grouped = groupMatrixItems(items, now)
        assertEquals(listOf("q1"), grouped.getValue(EisenhowerQuadrant.Q1).map { it.task.id })
        assertEquals(listOf("q2"), grouped.getValue(EisenhowerQuadrant.Q2).map { it.task.id })
        assertEquals(listOf("q3"), grouped.getValue(EisenhowerQuadrant.Q3).map { it.task.id })
        assertEquals(listOf("q4"), grouped.getValue(EisenhowerQuadrant.Q4).map { it.task.id })
        assertEquals(4, grouped.values.sumOf { it.size })
    }

    @Test
    fun `empty list yields four empty quadrants`() {
        val grouped = groupMatrixItems(emptyList(), now)
        assertEquals(4, grouped.size)
        assertTrue(grouped.values.all { it.isEmpty() })
    }

    // ---- Move mapping ----

    @Test
    fun `move into Q1 sets HIGH and due today`() {
        val target = moveTargetFor(EisenhowerQuadrant.Q1, now)
        assertEquals(Priority.HIGH, target.priority)
        assertEquals(endOfDayMillis(now), target.dueDateEpochMillis)
    }

    @Test
    fun `move into Q2 sets HIGH and clears the due date`() {
        val target = moveTargetFor(EisenhowerQuadrant.Q2, now)
        assertEquals(Priority.HIGH, target.priority)
        assertEquals(TaskRepository.NO_DUE_DATE, target.dueDateEpochMillis)
    }

    @Test
    fun `move into Q3 sets MEDIUM and due today`() {
        val target = moveTargetFor(EisenhowerQuadrant.Q3, now)
        assertEquals(Priority.MEDIUM, target.priority)
        assertEquals(endOfDayMillis(now), target.dueDateEpochMillis)
    }

    @Test
    fun `move into Q4 sets LOW and clears the due date`() {
        val target = moveTargetFor(EisenhowerQuadrant.Q4, now)
        assertEquals(Priority.LOW, target.priority)
        assertEquals(TaskRepository.NO_DUE_DATE, target.dueDateEpochMillis)
    }

    @Test
    fun `a move round-trips in both directions for every quadrant`() {
        // Q1 -> Q4 and back: the mapping depends only on the target.
        val fromQ1 = task("a", Priority.HIGH, noon()).task
        val toQ4 = moveTargetFor(EisenhowerQuadrant.Q4, now)
        val inQ4 = fromQ1.copy(priority = toQ4.priority, dueDateEpochMillis = toQ4.dueDateEpochMillis)
        assertEquals(EisenhowerQuadrant.Q4, quadrantOf(inQ4, now))
        val toQ1 = moveTargetFor(EisenhowerQuadrant.Q1, now)
        val backInQ1 = inQ4.copy(priority = toQ1.priority, dueDateEpochMillis = toQ1.dueDateEpochMillis)
        assertEquals(EisenhowerQuadrant.Q1, quadrantOf(backInQ1, now))

        // Q2 -> Q3 and back.
        val fromQ2 = task("b", Priority.HIGH, noon(3)).task
        val toQ3 = moveTargetFor(EisenhowerQuadrant.Q3, now)
        val inQ3 = fromQ2.copy(priority = toQ3.priority, dueDateEpochMillis = toQ3.dueDateEpochMillis)
        assertEquals(EisenhowerQuadrant.Q3, quadrantOf(inQ3, now))
        val toQ2 = moveTargetFor(EisenhowerQuadrant.Q2, now)
        val backInQ2 = inQ3.copy(priority = toQ2.priority, dueDateEpochMillis = toQ2.dueDateEpochMillis)
        assertEquals(EisenhowerQuadrant.Q2, quadrantOf(backInQ2, now))
    }
}

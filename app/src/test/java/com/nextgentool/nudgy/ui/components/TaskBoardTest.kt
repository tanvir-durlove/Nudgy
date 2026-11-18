package com.nextgentool.nudgy.ui.components

import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.model.TaskStatus
import com.nextgentool.nudgy.ui.viewmodel.TaskListItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sprint 1-4 — Board grouping tests (Feature 5).
 *
 * Pure function, no Compose: the Board shows the SAME filtered items
 * as the List, grouped by the same `status` column. The drag itself
 * is a UI gesture (device test); what is pinned here is that every
 * item lands in exactly one column, and that the Done column holds
 * the completed tasks.
 */
class TaskBoardTest {

    private fun item(id: String, status: TaskStatus, completed: Boolean = false) =
        TaskListItem(
            task = TaskEntity(
                id = id,
                userId = "u1",
                title = id,
                status = status,
                isCompleted = completed
            )
        )

    @Test
    fun `items group into exactly one column each by status`() {
        val items = listOf(
            item("a", TaskStatus.TODO),
            item("b", TaskStatus.IN_PROGRESS),
            item("c", TaskStatus.DONE, completed = true),
            item("d", TaskStatus.TODO)
        )
        val grouped = groupBoardItems(items)
        assertEquals(listOf("a", "d"), grouped.getValue(TaskStatus.TODO).map { it.task.id })
        assertEquals(listOf("b"), grouped.getValue(TaskStatus.IN_PROGRESS).map { it.task.id })
        assertEquals(listOf("c"), grouped.getValue(TaskStatus.DONE).map { it.task.id })
        assertEquals(4, grouped.values.sumOf { it.size })
    }

    @Test
    fun `empty list yields three empty columns`() {
        val grouped = groupBoardItems(emptyList())
        assertEquals(3, grouped.size)
        assertTrue(grouped.values.all { it.isEmpty() })
    }

    @Test
    fun `a task ticked in the List appears in Done`() {
        // setTaskStatus(DONE) writes status DONE + isCompleted true
        // (repository test); the Board then groups it under Done.
        val grouped = groupBoardItems(listOf(item("x", TaskStatus.DONE, completed = true)))
        assertEquals(listOf("x"), grouped.getValue(TaskStatus.DONE).map { it.task.id })
        assertTrue(grouped.getValue(TaskStatus.TODO).isEmpty())
    }
}

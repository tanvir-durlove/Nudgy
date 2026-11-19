package com.nextgentool.nudgy.ui.components

import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.model.TaskStatus
import com.nextgentool.nudgy.ui.viewmodel.TaskListItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    // Sprint 1-4b — the drop path (Feature 5). The S1-4 tests only
    // covered grouping, so CI passed while the drop could never land:
    // the old onDrop looked the dragged id up in the TARGET column's
    // own items, and a card from another column is never there.

    @Test
    fun `cross-column drop resolves the task from the full list`() {
        val all = listOf(
            item("a", TaskStatus.TODO),
            item("b", TaskStatus.IN_PROGRESS),
            item("c", TaskStatus.DONE, completed = true)
        )
        assertEquals("a", resolveBoardDrop(all, "a", TaskStatus.IN_PROGRESS)?.id)
        assertEquals("a", resolveBoardDrop(all, "a", TaskStatus.DONE)?.id)
        assertEquals("b", resolveBoardDrop(all, "b", TaskStatus.DONE)?.id)
    }

    @Test
    fun `drop on the source column does nothing`() {
        val all = listOf(item("a", TaskStatus.TODO))
        assertNull(resolveBoardDrop(all, "a", TaskStatus.TODO))
    }

    @Test
    fun `drop of an unknown id does nothing`() {
        val all = listOf(item("a", TaskStatus.TODO))
        assertNull(resolveBoardDrop(all, "zzz", TaskStatus.DONE))
        assertNull(resolveBoardDrop(all, null, TaskStatus.DONE))
    }

    @Test
    fun `old lookup could never land a cross-column drop`() {
        // The S1-4 onDrop, replicated: the target column searched its
        // OWN items for the dragged id. A card from another column was
        // never there, so the drop returned false on every column
        // except its own — where the status check no-op'd. This test
        // fails on the old code by construction (the old lookup is
        // null) and passes on the fix (the full-list lookup finds it).
        val all = listOf(
            item("a", TaskStatus.TODO),
            item("b", TaskStatus.IN_PROGRESS)
        )
        val targetItems = all.filter { it.task.status == TaskStatus.IN_PROGRESS }
        val oldLookup = targetItems.firstOrNull { it.task.id == "a" }?.task
        assertNull(oldLookup)
        assertEquals("a", resolveBoardDrop(all, "a", TaskStatus.IN_PROGRESS)?.id)
    }

    @Test
    fun `boardColumnAt maps content x to columns`() {
        val pitch = 284f
        assertEquals(TaskStatus.TODO, boardColumnAt(0f, pitch))
        assertEquals(TaskStatus.TODO, boardColumnAt(283f, pitch))
        assertEquals(TaskStatus.IN_PROGRESS, boardColumnAt(284f, pitch))
        assertEquals(TaskStatus.IN_PROGRESS, boardColumnAt(567f, pitch))
        assertEquals(TaskStatus.DONE, boardColumnAt(568f, pitch))
        assertEquals(TaskStatus.DONE, boardColumnAt(5000f, pitch))
        assertEquals(TaskStatus.TODO, boardColumnAt(-10f, pitch))
    }
}

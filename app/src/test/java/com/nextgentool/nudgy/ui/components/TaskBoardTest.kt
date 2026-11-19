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

    // Sprint 1-4g — Board reorder (Feature 5). The drop resolves to a
    // POSITION now, not just a column; the index counts the target
    // column's OTHER cards (dragged card excluded).

    private fun orderedItem(id: String, status: TaskStatus, boardOrder: Int) =
        TaskListItem(
            task = TaskEntity(
                id = id,
                userId = "u1",
                title = id,
                status = status,
                boardOrder = boardOrder
            )
        )

    @Test
    fun `groupBoardItems sorts each column by boardOrder`() {
        val items = listOf(
            orderedItem("c", TaskStatus.TODO, 2),
            orderedItem("a", TaskStatus.TODO, 0),
            orderedItem("b", TaskStatus.TODO, 1),
            orderedItem("d", TaskStatus.IN_PROGRESS, 0)
        )
        val grouped = groupBoardItems(items)
        assertEquals(
            listOf("a", "b", "c"),
            grouped.getValue(TaskStatus.TODO).map { it.task.id }
        )
        assertEquals(listOf("d"), grouped.getValue(TaskStatus.IN_PROGRESS).map { it.task.id })
    }

    @Test
    fun `groupBoardItems keeps input order for tied boardOrder`() {
        // Ties (fresh rows before the migration backfill, or tests that
        // never set boardOrder) must not scramble — sortedWith is stable.
        val grouped = groupBoardItems(
            listOf(item("a", TaskStatus.TODO), item("b", TaskStatus.TODO))
        )
        assertEquals(
            listOf("a", "b"),
            grouped.getValue(TaskStatus.TODO).map { it.task.id }
        )
    }

    @Test
    fun `groupBoardItems with sortByBoardOrder false keeps input order`() {
        // My Day Board: plannedStart order is the display order, and
        // boardOrder must never re-sort it.
        val items = listOf(
            orderedItem("c", TaskStatus.TODO, 2),
            orderedItem("a", TaskStatus.TODO, 0),
            orderedItem("b", TaskStatus.TODO, 1)
        )
        val grouped = groupBoardItems(items, sortByBoardOrder = false)
        assertEquals(
            listOf("c", "a", "b"),
            grouped.getValue(TaskStatus.TODO).map { it.task.id }
        )
    }

    @Test
    fun `boardDropIndex counts centres above the pointer`() {
        val centres = listOf(100f, 200f, 300f)
        assertEquals(0, boardDropIndex(centres, 50f))
        assertEquals(1, boardDropIndex(centres, 150f))
        // Exactly on a centre counts as above it (strictly-above rule).
        assertEquals(1, boardDropIndex(centres, 200f))
        assertEquals(2, boardDropIndex(centres, 250f))
        assertEquals(3, boardDropIndex(centres, 350f))
        assertEquals(0, boardDropIndex(emptyList(), 150f))
    }

    @Test
    fun `same-column drop resolves to the drop index`() {
        val all = listOf(
            item("a", TaskStatus.TODO),
            item("b", TaskStatus.TODO),
            item("c", TaskStatus.TODO),
            item("d", TaskStatus.TODO)
        )
        // Card #1 dragged to position #3 (index 2 among the others).
        assertEquals(
            BoardDropTarget(all[0].task, TaskStatus.TODO, 2),
            resolveBoardDropPosition(all, "a", TaskStatus.TODO, 2)
        )
        // Header: above the first card → 0.
        assertEquals(
            BoardDropTarget(all[2].task, TaskStatus.TODO, 0),
            resolveBoardDropPosition(all, "c", TaskStatus.TODO, 0)
        )
    }

    @Test
    fun `cross-column drop resolves to the drop index`() {
        val all = listOf(
            item("a", TaskStatus.TODO),
            item("b", TaskStatus.IN_PROGRESS),
            item("c", TaskStatus.IN_PROGRESS)
        )
        // Into the middle of In progress (index 1 of 2).
        assertEquals(
            BoardDropTarget(all[0].task, TaskStatus.IN_PROGRESS, 1),
            resolveBoardDropPosition(all, "a", TaskStatus.IN_PROGRESS, 1)
        )
    }

    @Test
    fun `drop into an empty column resolves to index 0`() {
        val all = listOf(item("a", TaskStatus.TODO))
        assertEquals(
            BoardDropTarget(all[0].task, TaskStatus.DONE, 0),
            resolveBoardDropPosition(all, "a", TaskStatus.DONE, 0)
        )
    }

    @Test
    fun `drop below the last card clamps to append`() {
        val all = listOf(
            item("a", TaskStatus.TODO),
            item("b", TaskStatus.IN_PROGRESS)
        )
        // Index 5 in a 1-card column → 1 (append).
        assertEquals(
            BoardDropTarget(all[0].task, TaskStatus.IN_PROGRESS, 1),
            resolveBoardDropPosition(all, "a", TaskStatus.IN_PROGRESS, 5)
        )
        // Same column: index 5 in a 1-other-card column → 1.
        val same = listOf(item("a", TaskStatus.TODO), item("b", TaskStatus.TODO))
        assertEquals(
            BoardDropTarget(same[0].task, TaskStatus.TODO, 1),
            resolveBoardDropPosition(same, "a", TaskStatus.TODO, 5)
        )
    }

    @Test
    fun `same-column drop at the same position is a no-op`() {
        val all = listOf(
            item("a", TaskStatus.TODO),
            item("b", TaskStatus.TODO),
            item("c", TaskStatus.TODO)
        )
        // "b" sits at index 1; dropping at 1 among the others puts it
        // right back — no write.
        assertNull(resolveBoardDropPosition(all, "b", TaskStatus.TODO, 1))
        // But one slot down is a real move.
        assertEquals(
            BoardDropTarget(all[1].task, TaskStatus.TODO, 2),
            resolveBoardDropPosition(all, "b", TaskStatus.TODO, 2)
        )
    }

    @Test
    fun `drop position of an unknown id is null`() {
        val all = listOf(item("a", TaskStatus.TODO))
        assertNull(resolveBoardDropPosition(all, "zzz", TaskStatus.DONE, 0))
        assertNull(resolveBoardDropPosition(all, null, TaskStatus.DONE, 0))
    }
}

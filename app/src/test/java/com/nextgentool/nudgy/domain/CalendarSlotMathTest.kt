package com.nextgentool.nudgy.domain

import com.nextgentool.nudgy.data.local.entity.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

/**
 * Pure tests for the S2-2 slot math (CalendarSlotMath.kt).
 * No Android framework, no database — every mapping in here is pinned
 * so the gesture test can trust slotStartForY and the ViewModel test can
 * trust nextFreeSlot.
 *
 * The grid is 24 rows of 64 dp (1,536 dp tall), one hour per row.
 * Slots snap to the NEAREST 15 minutes.
 */
class CalendarSlotMathTest {

    private val hourPx = 64f
    private val dayStart: Long = run {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.timeInMillis
    }
    private val dayEnd: Long = dayStart + 24 * 60 * 60_000L - 1

    private fun task(
        id: String,
        due: Long = 0L,
        plannedStart: Long? = null,
        plannedDate: String? = null,
        estimate: Int? = null,
        completed: Boolean = false,
        parentId: String? = null,
        groupId: String? = null,
    ) = TaskEntity(
        id = id,
        userId = "test-user-1",
        title = id,
        dueDateEpochMillis = due,
        plannedStartEpochMillis = plannedStart,
        plannedDate = plannedDate,
        estimatedMinutes = estimate,
        isCompleted = completed,
        parentTaskId = parentId,
        groupId = groupId,
    )

    private fun todayAt(hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    // --- slotStartForY (nearest 15-minute snap) ---

    @Test
    fun `slotStartForY maps a y inside the first hour to the snapped slot`() {
        // 32 px into the first hour (half an hour) -> 00:30.
        assertEquals(dayStart + 30 * 60_000L, slotStartForY(32f, hourPx, dayStart))
    }

    @Test
    fun `slotStartForY snaps to the nearest 15-minute bucket`() {
        // 96 px = 1.5 hours -> 01:30 exactly.
        assertEquals(dayStart + 90 * 60_000L, slotStartForY(96f, hourPx, dayStart))
        // 100 px = 1.5625 hours = 01:33:45 -> nearest is 01:30.
        assertEquals(dayStart + 90 * 60_000L, slotStartForY(100f, hourPx, dayStart))
        // 105 px = 1.640625 hours = 01:38:26 -> nearest is 01:45, not 01:30.
        assertEquals(dayStart + 105 * 60_000L, slotStartForY(105f, hourPx, dayStart))
        // 112 px = 1.75 hours = 01:45 exactly.
        assertEquals(dayStart + 105 * 60_000L, slotStartForY(112f, hourPx, dayStart))
    }

    @Test
    fun `slotStartForY clamps a y above the grid to 23-45`() {
        // Way past the last row: the last 15-minute slot of the day.
        assertEquals(dayStart + (23 * 60 + 45) * 60_000L, slotStartForY(10_000f, hourPx, dayStart))
        // Exactly at the bottom edge (1,536 px = 24:00) clamps the same way.
        assertEquals(dayStart + (23 * 60 + 45) * 60_000L, slotStartForY(1_536f, hourPx, dayStart))
    }

    @Test
    fun `slotStartForY clamps a negative y to midnight`() {
        assertEquals(dayStart, slotStartForY(-20f, hourPx, dayStart))
    }

    @Test
    fun `slotStartForY with a zero hour height never divides by zero`() {
        assertEquals(dayStart, slotStartForY(100f, 0f, dayStart))
    }

    // --- stripTasksForDay (Q4(b)) ---

    @Test
    fun `strip keeps due-today unplanned tasks and Anytime tasks`() {
        val tasks = listOf(
            task("due-today", due = todayAt(15, 0)),
            task("anytime", due = 0L),
            task("planned", due = todayAt(10, 0), plannedStart = todayAt(10, 0), plannedDate = "today"),
            task("tomorrow", due = todayAt(9, 0) + 24 * 60 * 60_000L),
            task("done-today", due = todayAt(12, 0), completed = true),
            task("subtask", due = todayAt(12, 0), parentId = "due-today"),
            task("group", due = todayAt(12, 0), groupId = "g1"),
        )
        val strip = stripTasksForDay(tasks, dayStart, dayEnd)
        // Group tasks ARE in the strip (local-only reorder precedent, S1-4g f2);
        // completed tasks and subtasks are not. Order is due-ascending,
        // no-date last: group 12:00, due-today 15:00, anytime.
        assertEquals(
            listOf("group", "due-today", "anytime"),
            strip.map { it.id },
        )
    }

    @Test
    fun `strip orders due tasks by due time with no-date last`() {
        val tasks = listOf(
            task("late", due = todayAt(18, 0)),
            task("anytime", due = 0L),
            task("early", due = todayAt(9, 0)),
            task("mid", due = todayAt(12, 0)),
        )
        assertEquals(
            listOf("early", "mid", "late", "anytime"),
            stripTasksForDay(tasks, dayStart, dayEnd).map { it.id },
        )
    }

    // --- placedTasksForDay ---

    @Test
    fun `placed keeps only tasks planned on the selected day`() {
        val tasks = listOf(
            task("placed", plannedStart = todayAt(10, 0), plannedDate = "today"),
            task("other-day", plannedStart = todayAt(10, 0) + 24 * 60 * 60_000L, plannedDate = "2099-01-01"),
            task("no-plan", due = todayAt(10, 0)),
        )
        assertEquals(listOf("placed"), placedTasksForDay(tasks, dayStart, dayEnd).map { it.id })
    }

    // --- slotBlockMinutes ---

    @Test
    fun `slot block length is the estimate or 30 minutes`() {
        assertEquals(45, slotBlockMinutes(task("a", estimate = 45)))
        assertEquals(30, slotBlockMinutes(task("b", estimate = null)))
    }

    // --- nextFreeSlot (day limit = next midnight) ---

    @Test
    fun `nextFreeSlot returns the requested slot when it is free`() {
        val busy = listOf(todayAt(10, 0) to todayAt(11, 0))
        assertEquals(
            todayAt(9, 0),
            nextFreeSlot(todayAt(9, 0), 30, busy, dayStart + 24 * 60 * 60_000L),
        )
        // Abutting the busy interval is free (end == start is no overlap).
        assertEquals(
            todayAt(9, 30),
            nextFreeSlot(todayAt(9, 30), 30, busy, dayStart + 24 * 60 * 60_000L),
        )
    }

    @Test
    fun `nextFreeSlot jumps to the end of a conflicting event`() {
        val busy = listOf(todayAt(10, 0) to todayAt(11, 0))
        assertEquals(
            todayAt(11, 0),
            nextFreeSlot(todayAt(10, 30), 30, busy, dayStart + 24 * 60 * 60_000L),
        )
    }

    @Test
    fun `nextFreeSlot walks past chained overlaps`() {
        val busy = listOf(
            todayAt(10, 0) to todayAt(11, 0),
            todayAt(11, 0) to todayAt(12, 30),
        )
        assertEquals(
            todayAt(12, 30),
            nextFreeSlot(todayAt(10, 30), 30, busy, dayStart + 24 * 60 * 60_000L),
        )
    }

    @Test
    fun `nextFreeSlot returns null when nothing fits before midnight`() {
        val busy = listOf(dayStart to dayStart + 24 * 60 * 60_000L)
        assertNull(nextFreeSlot(todayAt(23, 0), 30, busy, dayStart + 24 * 60 * 60_000L))
    }
}

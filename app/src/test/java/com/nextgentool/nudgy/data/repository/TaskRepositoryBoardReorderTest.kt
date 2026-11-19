package com.nextgentool.nudgy.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.model.TaskStatus
import com.nextgentool.nudgy.domain.model.BuiltInTemplates
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar

/**
 * Sprint 1-4g — repository tests for Board reorder (Feature 5).
 *
 * Real in-memory Room + real TaskRepository (no Hilt), following the
 * S1-4 TaskRepositoryS14Test pattern. Covers: same-column and
 * cross-column moves, the no-op, dense renumbering of both columns,
 * (c1) top-of-Done / (d1) end-of-column, toggle placement, template
 * order, subtask rejection, local-only group rows, the filtered
 * (visible-subset → full-column) mapping, and the My Day plannedStart
 * re-slot (decision (e2)).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TaskRepositoryBoardReorderTest {

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

    /** Top-level tasks of one status column, in board order. */
    private suspend fun column(status: TaskStatus): List<TaskEntity> =
        db.taskDao().getAllTasksOnce(userId)
            .filter { it.parentTaskId == null && it.status == status }
            .sortedBy { it.boardOrder }

    private suspend fun columnIds(status: TaskStatus): List<String> =
        column(status).map { it.id }

    private suspend fun createTodo(title: String): TaskEntity =
        repository.createTask(userId = userId, title = title)

    private suspend fun fresh(id: String): TaskEntity =
        checkNotNull(db.taskDao().getTaskById(id))

    // ------------------------------------------------------------ same column

    @Test
    fun `same-column move renumbers the column dense`() = runTest {
        val a = createTodo("A")
        val b = createTodo("B")
        val c = createTodo("C")
        val d = createTodo("D")
        assertEquals(listOf("A", "B", "C", "D"), columnIds(TaskStatus.TODO))

        // #1 dragged to position #3 (visible index 2 among B, C, D).
        assertTrue(
            repository.moveTaskToPosition(a, TaskStatus.TODO, 2, listOf(b, c, d))
        )
        assertEquals(listOf("B", "C", "A", "D"), columnIds(TaskStatus.TODO))
        assertEquals(listOf(0, 1, 2, 3), column(TaskStatus.TODO).map { it.boardOrder })

        // Drag it back to the top.
        assertTrue(
            repository.moveTaskToPosition(a, TaskStatus.TODO, 0, listOf(b, c, d))
        )
        assertEquals(listOf("A", "B", "C", "D"), columnIds(TaskStatus.TODO))
        assertEquals(listOf(0, 1, 2, 3), column(TaskStatus.TODO).map { it.boardOrder })
    }

    @Test
    fun `same-column drop at the same position is a no-op`() = runTest {
        val a = createTodo("A")
        val b = createTodo("B")
        val c = createTodo("C")
        assertFalse(
            repository.moveTaskToPosition(b, TaskStatus.TODO, 1, listOf(a, c))
        )
        assertEquals(listOf("A", "B", "C"), columnIds(TaskStatus.TODO))
        // One slot down is a real move.
        assertTrue(
            repository.moveTaskToPosition(b, TaskStatus.TODO, 2, listOf(a, c))
        )
        assertEquals(listOf("A", "C", "B"), columnIds(TaskStatus.TODO))
    }

    @Test
    fun `move of a missing task and of a subtask both return false`() = runTest {
        val a = createTodo("A")
        val ghost = a.copy(id = "no-such-task")
        assertFalse(
            repository.moveTaskToPosition(ghost, TaskStatus.TODO, 0, emptyList())
        )
        val sub = repository.createSubtask(a.id, "Sub")
        assertFalse(
            repository.moveTaskToPosition(sub, TaskStatus.TODO, 0, listOf(a))
        )
        assertEquals(listOf("A"), columnIds(TaskStatus.TODO))
    }

    // ------------------------------------------------------------ cross column

    @Test
    fun `cross-column move renumbers both columns`() = runTest {
        val a = createTodo("A")
        val b = createTodo("B")
        val x = createTodo("X")
        repository.setTaskStatus(x, TaskStatus.IN_PROGRESS)
        assertEquals(listOf("X"), columnIds(TaskStatus.IN_PROGRESS))

        // A into the middle of In progress (index 1, below X).
        assertTrue(
            repository.moveTaskToPosition(a, TaskStatus.IN_PROGRESS, 1, listOf(x))
        )
        assertEquals(listOf("X", "A"), columnIds(TaskStatus.IN_PROGRESS))
        assertEquals(listOf(0, 1), column(TaskStatus.IN_PROGRESS).map { it.boardOrder })
        assertEquals(TaskStatus.IN_PROGRESS, fresh(a.id).status)
        // The source column closed its gap.
        assertEquals(listOf("B"), columnIds(TaskStatus.TODO))
        assertEquals(listOf(0), column(TaskStatus.TODO).map { it.boardOrder })
    }

    @Test
    fun `drop into an empty Done column completes at the top`() = runTest {
        val a = createTodo("A")
        assertTrue(
            repository.moveTaskToPosition(a, TaskStatus.DONE, 0, emptyList())
        )
        val done = fresh(a.id)
        assertEquals(TaskStatus.DONE, done.status)
        assertTrue(done.isCompleted)
        assertNotNull(done.completedAtEpochMillis)
        assertEquals(0, done.boardOrder)
        assertEquals(emptyList<String>(), columnIds(TaskStatus.TODO))
    }

    @Test
    fun `completing an already-completed task keeps its original completedAt`() =
        runTest {
            val a = createTodo("A")
            repository.setTaskStatus(a, TaskStatus.DONE)
            val firstCompletedAt = fresh(a.id).completedAtEpochMillis
            assertNotNull(firstCompletedAt)
            // Re-drop into Done (from Done itself, at the top).
            repository.moveTaskToPosition(fresh(a.id), TaskStatus.DONE, 0, emptyList())
            assertEquals(firstCompletedAt, fresh(a.id).completedAtEpochMillis)
        }

    // ------------------------------------------------------------ toggle / status

    @Test
    fun `toggle complete goes to top of Done, toggle back to end of To do`() =
        runTest {
            val a = createTodo("A")
            val b = createTodo("B")
            repository.toggleTaskCompletion(fresh(a.id))
            assertEquals(listOf("A"), columnIds(TaskStatus.DONE))
            assertEquals(0, fresh(a.id).boardOrder)
            assertTrue(fresh(a.id).isCompleted)
            // B closed the gap.
            assertEquals(listOf("B"), columnIds(TaskStatus.TODO))
            assertEquals(0, fresh(b.id).boardOrder)

            repository.toggleTaskCompletion(fresh(a.id))
            assertEquals(listOf("B", "A"), columnIds(TaskStatus.TODO))
            assertEquals(1, fresh(a.id).boardOrder)
            assertFalse(fresh(a.id).isCompleted)
            assertNull(fresh(a.id).completedAtEpochMillis)
        }

    @Test
    fun `setTaskStatus completes to top of Done and appends elsewhere`() = runTest {
        val a = createTodo("A")
        val b = createTodo("B")
        repository.setTaskStatus(b, TaskStatus.DONE)
        assertEquals(0, fresh(b.id).boardOrder)
        repository.setTaskStatus(a, TaskStatus.DONE)
        // Newly completed always lands on TOP (c1), pushing B down.
        assertEquals(listOf("A", "B"), columnIds(TaskStatus.DONE))
        // Moving out appends at the end of the target column.
        repository.setTaskStatus(a, TaskStatus.IN_PROGRESS)
        assertEquals(listOf("A"), columnIds(TaskStatus.IN_PROGRESS))
        assertEquals(0, fresh(a.id).boardOrder)
        assertEquals(listOf("B"), columnIds(TaskStatus.DONE))
    }

    // ------------------------------------------------------------ creation order

    @Test
    fun `new task appends at the end of its column`() = runTest {
        val a = createTodo("A")
        val b = createTodo("B")
        val c = createTodo("C")
        assertEquals(listOf(0, 1, 2), listOf(a, b, c).map { fresh(it.id).boardOrder })
    }

    @Test
    fun `template tasks take sequential positions in draft order`() = runTest {
        repository.seedBuiltInTemplates(userId)
        val template = repository.observeTemplates(userId).first()
            .first { it.id == BuiltInTemplates.DAILY_ROUTINE_ID }
        val due = repository.planMyDayDefaultDueEpochMillis(LocalDate.now())
        val created = repository.createTasksFromTemplate(userId, template, due)
        assertEquals(5, created.size)
        assertEquals(
            listOf(
                "Morning routine", "Exercise / walk", "Plan the day",
                "Family time", "Prepare tomorrow's list"
            ),
            created.map { it.title }
        )
        assertEquals(listOf(0, 1, 2, 3, 4), created.map { fresh(it.id).boardOrder })
        assertEquals(
            created.map { it.id },
            columnIds(TaskStatus.TODO)
        )
    }

    // ------------------------------------------------------------ group rows (f2)

    @Test
    fun `group row reorders locally without dirty-marking`() = runTest {
        val personal = createTodo("Personal")
        val group = TaskEntity(
            id = "group-task-1", userId = userId, title = "Group homework",
            groupId = "group-1", isSyncedToCloud = 1, boardOrder = 0
        )
        // The personal task took 0; shift the group row after it by hand
        // (the fixture is the pre-move state, not a createTask).
        db.taskDao().insertTask(group.copy(boardOrder = 1))
        assertEquals(listOf("Personal", "Group homework"), columnIds(TaskStatus.TODO))

        // Drag the group row to the top of the same column.
        assertTrue(
            repository.moveTaskToPosition(
                fresh("group-task-1"), TaskStatus.TODO, 0, listOf(personal)
            )
        )
        assertEquals(listOf("Group homework", "Personal"), columnIds(TaskStatus.TODO))
        val moved = fresh("group-task-1")
        assertEquals(0, moved.boardOrder)
        // Local-only: the row was never dirty-marked for sync (f2).
        assertEquals(1, moved.isSyncedToCloud)
    }

    // ------------------------------------------------------------ filtered mapping

    @Test
    fun `filtered mapping keeps hidden tasks in relative order`() = runTest {
        // Full TODO column: H1, B, H2, C. The Board shows only B and C
        // (H1/H2 are filtered out) — the drop counts visible cards only.
        val h1 = createTodo("H1")
        val b = createTodo("B")
        val h2 = createTodo("H2")
        val c = createTodo("C")
        assertEquals(listOf("H1", "B", "H2", "C"), columnIds(TaskStatus.TODO))

        // Drag C above B (visible index 0 among [B]).
        assertTrue(
            repository.moveTaskToPosition(c, TaskStatus.TODO, 0, listOf(b))
        )
        // Visible order is now C, B; hidden H1/H2 keep their relative order.
        assertEquals(listOf("H1", "C", "B", "H2"), columnIds(TaskStatus.TODO))
        assertEquals(listOf(0, 1, 2, 3), column(TaskStatus.TODO).map { it.boardOrder })

        // Drag B below the last visible card (visible index 1 among [C]).
        // B lands directly below C in the full column — after the last
        // visible card, NOT at the absolute end (H2 stays below B).
        assertTrue(
            repository.moveTaskToPosition(b, TaskStatus.TODO, 1, listOf(c))
        )
        assertEquals(listOf("H1", "C", "B", "H2"), columnIds(TaskStatus.TODO))
    }

    // ------------------------------------------------------------ My Day plannedStart (e2)

    @Test
    fun `My Day drop re-slots only the dragged cards plannedStart`() = runTest {
        val day = LocalDate.now()
        val zone = ZoneId.systemDefault()
        fun at(hour: Int): Long =
            day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        val a = TaskEntity(
            id = "myday-a", userId = userId, title = "A",
            status = TaskStatus.TODO, boardOrder = 0,
            plannedStartEpochMillis = at(9), plannedDate = day.toString()
        )
        val b = TaskEntity(
            id = "myday-b", userId = userId, title = "B",
            status = TaskStatus.TODO, boardOrder = 1,
            plannedStartEpochMillis = at(10), plannedDate = day.toString()
        )
        val c = TaskEntity(
            id = "myday-c", userId = userId, title = "C",
            status = TaskStatus.TODO, boardOrder = 2,
            plannedStartEpochMillis = at(11), plannedDate = day.toString()
        )
        db.taskDao().insertTask(a)
        db.taskDao().insertTask(b)
        db.taskDao().insertTask(c)

        // C dragged to the top of the day (visible index 0 among A, B).
        assertTrue(
            repository.moveTaskToPosition(
                c, TaskStatus.TODO, 0, listOf(a, b), updatePlannedStart = true
            )
        )
        // 30 min before A (09:00) → 08:30; A and B untouched.
        assertEquals(at(8) + 30 * 60_000L, fresh("myday-c").plannedStartEpochMillis)
        assertEquals(at(9), fresh("myday-a").plannedStartEpochMillis)
        assertEquals(at(10), fresh("myday-b").plannedStartEpochMillis)
        // The Tasks Board order follows too (same boardOrder field).
        assertEquals(
            listOf("myday-c", "myday-a", "myday-b"),
            columnIds(TaskStatus.TODO)
        )
    }

    @Test
    fun `Tasks Board drop never touches plannedStart`() = runTest {
        val a = createTodo("A")
        val b = createTodo("B")
        val withStart = fresh(a.id).copy(plannedStartEpochMillis = 12345L)
        db.taskDao().updateTask(withStart)
        repository.moveTaskToPosition(fresh(a.id), TaskStatus.TODO, 1, listOf(b))
        assertEquals(12345L, fresh(a.id).plannedStartEpochMillis)
    }

    // ------------------------------------------------------------ pure helpers

    @Test
    fun `mapVisibleIndexToFullIndex maps the anchor and the append cases`() {
        val without = listOf(
            TaskEntity(id = "h1", userId = "u", title = "H1"),
            TaskEntity(id = "b", userId = "u", title = "B"),
            TaskEntity(id = "h2", userId = "u", title = "H2"),
            TaskEntity(id = "c", userId = "u", title = "C")
        )
        val b = without[1]
        val c = without[3]
        // Visible [B, C]: index 0 → before B (full 1); index 1 → before C
        // (full 3); index 2 → past the last visible → after C (full 4).
        assertEquals(1, mapVisibleIndexToFullIndex(without, listOf(b, c), 0))
        assertEquals(3, mapVisibleIndexToFullIndex(without, listOf(b, c), 1))
        assertEquals(4, mapVisibleIndexToFullIndex(without, listOf(b, c), 2))
        // Empty visible list appends at the end.
        assertEquals(4, mapVisibleIndexToFullIndex(without, emptyList(), 0))
        // A stale visible id falls back to append.
        val stale = TaskEntity(id = "gone", userId = "u", title = "Gone")
        assertEquals(4, mapVisibleIndexToFullIndex(without, listOf(stale), 0))
    }

    @Test
    fun `resolvePlannedStartForDrop takes the midpoint and clamps to the day`() {
        val day = LocalDate.now()
        val zone = ZoneId.systemDefault()
        fun at(hour: Int, minute: Int = 0): Long =
            day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

        // Between 09:00 and 10:00 → 09:30.
        assertEquals(
            at(9, 30),
            resolvePlannedStartForDrop(
                listOf(at(9), at(10)), targetIndex = 1, fallback = null
            )
        )
        // Above the first (09:00) → 08:30.
        assertEquals(
            at(8, 30),
            resolvePlannedStartForDrop(
                listOf(at(9), at(10)), targetIndex = 0, fallback = null
            )
        )
        // Below the last (11:00) → 11:30.
        assertEquals(
            at(11, 30),
            resolvePlannedStartForDrop(
                listOf(at(9), at(11)), targetIndex = 2, fallback = null
            )
        )
        // No timed neighbour → keeps its own plannedStart.
        assertEquals(
            4242L,
            resolvePlannedStartForDrop(
                listOf(null, null), targetIndex = 1, fallback = 4242L
            )
        )
        // Above a 00:10 first card → clamped to the day's start, never
        // the previous day.
        val dayStart = startOfDayMillis(at(0, 10))
        assertEquals(
            dayStart,
            resolvePlannedStartForDrop(
                listOf(at(0, 10)), targetIndex = 0, fallback = null
            )
        )
        // Below a 23:50 last card → clamped to the day's end, never the
        // next day.
        val dayEnd = endOfDayMillis(at(23, 50))
        assertEquals(
            dayEnd,
            resolvePlannedStartForDrop(
                listOf(at(23, 50)), targetIndex = 1, fallback = null
            )
        )
    }

    @Test
    fun `startOfDay and endOfDay are the same days bounds`() {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        cal.timeInMillis = startOfDayMillis(now)
        assertEquals(0, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
        cal.timeInMillis = endOfDayMillis(now)
        assertEquals(23, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(59, cal.get(Calendar.MINUTE))
        assertEquals(59, cal.get(Calendar.SECOND))
    }
}

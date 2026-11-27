package com.nextgentool.nudgy.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.timeLabel
import com.nextgentool.nudgy.domain.todayDateString
import java.util.Calendar
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 6-5, step 3a — the Today widget's agenda rule.
 *
 * What this covers: the pure [todayAgendaItems] mapping (the My Day
 * rule — top-level tasks planned for today, planned-start order)
 * plus one Room round-trip proving `plannedDate` /
 * `plannedStartEpochMillis` survive the database and reach the
 * agenda. Real in-memory Room + real TaskRepository (no Hilt),
 * following the TaskRepositorySearchTest pattern; seeding via
 * `runBlocking`-free `runTest` on the repository.
 *
 * What it does NOT prove (honest limitation): the Glance rendering
 * itself. This harness cannot render a Glance widget — there is no
 * Glance test infra in this repo — so the render path
 * (TodayWidget.provideGlance → TodayWidgetContent) is verified by
 * CI compile + the batch-end device test, not by a test here.
 *
 * Sprint 7 — Widget restyle: the pure pieces of the restyle ARE
 * covered here — the priority carried through to the item (the row
 * pdot), the single-line row format, the header date label, the
 * 3-row cap, and the pdot color mapping.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TodayWidgetAgendaTest {

    private val today = "2026-11-24"
    private val otherDay = "2026-11-25"

    private fun task(
        title: String,
        plannedDate: String? = today,
        plannedStart: Long? = null,
        due: Long = 0L,
        created: Long = 0L,
        parentId: String? = null,
        completed: Boolean = false,
        priority: Priority = Priority.MEDIUM
    ) = TaskEntity(
        userId = "u1",
        title = title,
        plannedDate = plannedDate,
        plannedStartEpochMillis = plannedStart,
        dueDateEpochMillis = due,
        createdAtEpochMillis = created,
        parentTaskId = parentId,
        isCompleted = completed,
        priority = priority
    )

    @Test
    fun `only top-level tasks planned for today are shown`() {
        val items = todayAgendaItems(
            listOf(
                task("A", due = 100L, created = 1L),
                task("B", plannedDate = otherDay, due = 50L, created = 2L),
                task("C", plannedDate = null, due = 10L, created = 3L),
                task("D", parentId = "p1", due = 5L, created = 4L),
                task("E", due = 200L, created = 5L)
            ),
            today
        )
        assertEquals(listOf("A", "E"), items.map { it.title })
    }

    @Test
    fun `planned start orders first, tasks without a start go last`() {
        val items = todayAgendaItems(
            listOf(
                task("X", plannedStart = 1000L, due = 1L, created = 1L),
                task("Y", plannedStart = null, due = 1L, created = 2L),
                task("Z", plannedStart = 500L, due = 1L, created = 3L)
            ),
            today
        )
        assertEquals(listOf("Z", "X", "Y"), items.map { it.title })
    }

    @Test
    fun `due date breaks planned-start ties`() {
        val items = todayAgendaItems(
            listOf(
                task("P", plannedStart = 1000L, due = 300L, created = 1L),
                task("Q", plannedStart = 1000L, due = 100L, created = 2L)
            ),
            today
        )
        assertEquals(listOf("Q", "P"), items.map { it.title })
    }

    @Test
    fun `creation time breaks the remaining ties`() {
        val items = todayAgendaItems(
            listOf(
                task("M", plannedStart = 1000L, due = 100L, created = 5L),
                task("N", plannedStart = 1000L, due = 100L, created = 2L)
            ),
            today
        )
        assertEquals(listOf("N", "M"), items.map { it.title })
    }

    @Test
    fun `completed tasks keep their done state`() {
        val items = todayAgendaItems(
            listOf(
                task("Done", completed = true, due = 1L, created = 1L),
                task("Open", completed = false, due = 2L, created = 2L)
            ),
            today
        )
        assertEquals(listOf(true, false), items.map { it.isCompleted })
    }

    @Test
    fun `time label comes from the planned start, null without one`() {
        val start = 1_700_000_000_000L
        val items = todayAgendaItems(
            listOf(
                task("Slotted", plannedStart = start, due = 1L, created = 1L),
                task("Unslotted", plannedStart = null, due = 2L, created = 2L)
            ),
            today
        )
        assertEquals(timeLabel(start), items[0].timeLabel)
        assertNull(items[1].timeLabel)
    }

    // ------------------------------------------------------- Sprint 7 restyle

    @Test
    fun `priority is carried through to the widget item`() {
        val items = todayAgendaItems(
            listOf(
                task("High", priority = Priority.HIGH, due = 1L, created = 1L),
                task("Medium", priority = Priority.MEDIUM, due = 2L, created = 2L),
                task("Low", priority = Priority.LOW, due = 3L, created = 3L)
            ),
            today
        )
        assertEquals(
            listOf(Priority.HIGH, Priority.MEDIUM, Priority.LOW),
            items.map { it.priority }
        )
    }

    @Test
    fun `agenda row line joins time and title, title alone without a time`() {
        val start = 1_700_000_000_000L
        val items = todayAgendaItems(
            listOf(
                task("Slotted", plannedStart = start, due = 1L, created = 1L),
                task("Unslotted", plannedStart = null, due = 2L, created = 2L)
            ),
            today
        )
        assertEquals("${timeLabel(start)} · Slotted", widgetAgendaRowLine(items[0]))
        assertEquals("Unslotted", widgetAgendaRowLine(items[1]))
    }

    @Test
    fun `header date label uses the prototype shape`() {
        val november = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 16, 10, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals("Mon Nov 16", widgetHeaderDateLabel(november))
        val december = Calendar.getInstance().apply {
            set(2026, Calendar.DECEMBER, 25, 9, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals("Fri Dec 25", widgetHeaderDateLabel(december))
    }

    @Test
    fun `widget shows three agenda rows at most`() {
        assertEquals(3, TODAY_WIDGET_MAX_ITEMS)
    }

    @Test
    fun `priority dot colors match the prototype mapping`() {
        assertEquals(Color(0xFFF97360), widgetPriorityDotColor(Priority.HIGH))
        assertEquals(Color(0xFF0F766E), widgetPriorityDotColor(Priority.MEDIUM))
        assertEquals(Color(0xFF757575), widgetPriorityDotColor(Priority.LOW))
    }

    @Test
    fun `header shows the date only when the widget is wide enough`() {
        assertFalse(widgetShowsDateHeader(180.dp))
        assertFalse(widgetShowsDateHeader(299.dp))
        assertTrue(widgetShowsDateHeader(300.dp))
        assertTrue(widgetShowsDateHeader(360.dp))
    }

    @Test
    fun `header title joins today and the date, today alone when narrow`() {
        assertEquals("Today · Mon Nov 16", widgetHeaderTitle("Today", "Mon Nov 16", true))
        assertEquals("Today", widgetHeaderTitle("Today", "Mon Nov 16", false))
    }

    @Test
    fun `empty list when nothing is planned today`() {
        assertTrue(todayAgendaItems(emptyList(), today).isEmpty())
        assertTrue(
            todayAgendaItems(listOf(task("B", plannedDate = otherDay)), today).isEmpty()
        )
    }

    // ------------------------------------------------------- Room round-trip

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

    @Test
    fun `plannedDate survives Room and the agenda reads it`() = runTest {
        val userId = repository.currentUserId()
        val todayString = todayDateString()
        repository.createTask(userId = userId, title = "Walk")
        repository.createTask(userId = userId, title = "Read")
        val walk = repository.getAllTasksOnce(userId)
            .first { it.title == "Walk" }
        repository.updateTask(
            walk.copy(
                plannedDate = todayString,
                plannedStartEpochMillis = walk.dueDateEpochMillis
            )
        )

        val items = todayAgendaItems(
            repository.getAllTasksOnce(userId),
            todayString
        )
        assertEquals(listOf("Walk"), items.map { it.title })
    }
}

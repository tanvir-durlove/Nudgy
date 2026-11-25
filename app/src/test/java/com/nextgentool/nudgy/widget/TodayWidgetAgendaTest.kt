package com.nextgentool.nudgy.widget

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.timeLabel
import com.nextgentool.nudgy.domain.todayDateString
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
        completed: Boolean = false
    ) = TaskEntity(
        userId = "u1",
        title = title,
        plannedDate = plannedDate,
        plannedStartEpochMillis = plannedStart,
        dueDateEpochMillis = due,
        createdAtEpochMillis = created,
        parentTaskId = parentId,
        isCompleted = completed
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

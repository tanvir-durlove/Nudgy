package com.nextgentool.nudgy.ui.viewmodel

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.repository.CalendarRepository
import com.nextgentool.nudgy.data.repository.FakeCalendarEventSync
import com.nextgentool.nudgy.data.repository.FakeCalendarProviderGateway
import com.nextgentool.nudgy.data.repository.FakeGoogleEvent
import com.nextgentool.nudgy.data.repository.GroupRepository
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.startOfDayMillis
import com.nextgentool.nudgy.domain.usecase.GetTodayTimelineUseCase
import com.nextgentool.nudgy.domain.usecase.TimelineEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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
 * Sprint 2-2 — ViewModel tests for drag-to-slot (CalendarViewModel.dropTaskAtSlot).
 * Real in-memory Room + the fake calendar provider, same harness as
 * CalendarViewModelTest (no Hilt). The drop path is exercised at the
 * ViewModel level; the gesture itself is covered by CalendarDragTest.
 *
 * Every test asserts the drop writes plannedStart + plannedDate ONLY —
 * due, status, boardOrder, estimate and reminder are untouched — and
 * that a busy slot never writes until the user accepts the suggestion.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CalendarViewModelDropTest {

    private lateinit var context: Context
    private lateinit var db: NudgyDatabase
    private lateinit var provider: FakeCalendarProviderGateway
    private lateinit var calendarRepository: CalendarRepository
    private lateinit var taskRepository: TaskRepository
    private lateinit var groupRepository: GroupRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        org.robolectric.Shadows.shadowOf(context as android.app.Application)
            .grantPermissions(
                android.Manifest.permission.READ_CALENDAR,
                android.Manifest.permission.WRITE_CALENDAR
            )
        context.getSharedPreferences("nudgy_auth_prefs", Context.MODE_PRIVATE)
            .edit().putString("active_user_id", "test-user-1").apply()
        db = Room.inMemoryDatabaseBuilder(context, NudgyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        provider = FakeCalendarProviderGateway()
        calendarRepository = CalendarRepository(context, db, FakeCalendarEventSync(), provider)
        taskRepository = TaskRepository(context, db)
        groupRepository = GroupRepository(context, db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private val userId get() = taskRepository.currentUserId()

    private fun dayAt(daysAhead: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, daysAhead)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun todayString(): String = LocalDate.now(ZoneId.systemDefault()).toString()

    private fun tomorrowString(): String =
        LocalDate.now(ZoneId.systemDefault()).plusDays(1).toString()

    private suspend fun insertTask(
        id: String,
        due: Long,
        estimate: Int? = 60,
        reminderSet: Boolean = true,
    ): TaskEntity {
        val entity = TaskEntity(
            id = id,
            userId = "test-user-1",
            title = id,
            dueDateEpochMillis = due,
            estimatedMinutes = estimate,
            reminderSet = reminderSet,
            reminderEpochMillis = if (reminderSet) due - 2 * 60 * 60_000L else null,
        )
        db.taskDao().insertTask(entity)
        return entity
    }

    private fun viewModel(): CalendarViewModel =
        CalendarViewModel(calendarRepository, taskRepository, groupRepository)

    /** Waits until the strip contains the task (state has caught up). */
    private suspend fun waitForStrip(viewModel: CalendarViewModel, id: String) {
        viewModel.uiState.first { it.stripTasks.any { task -> task.id == id } }
    }

    @Test
    fun `free-slot drop writes plannedStart and plannedDate only`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            // Tomorrow, not today: the test also asserts the reminder
            // stays at due - 2h, and the product rule
            // (ReminderManager.dueReminderTriggerMillis) moves the
            // reminder to the due time once the 2h lead has passed. A
            // 15:00 due TODAY makes this test fail on any run after
            // 13:00 (proven by three red runs on 2026-11-20); tomorrow
            // keeps the lead in the future at any hour.
            val due = dayAt(1, 15)
            val task = insertTask("t1", due)
            val viewModel = viewModel()
            viewModel.selectDay(due)
            waitForStrip(viewModel, "t1")

            val slot = dayAt(1, 10, 30)
            viewModel.dropTaskAtSlot(task, slot)

            // The drop writes in viewModelScope, so wait for the state to
            // prove the write landed before reading the database.
            val state = viewModel.uiState.first { it.placedTasks.any { t -> t.id == "t1" } }
            val after = db.taskDao().getTaskById("t1")!!
            assertEquals(slot, after.plannedStartEpochMillis)
            assertEquals(tomorrowString(), after.plannedDate)
            // Nothing else moved: due, status, boardOrder, estimate, reminder.
            assertEquals(due, after.dueDateEpochMillis)
            assertEquals("TODO", after.status.name)
            assertEquals(0, after.boardOrder)
            assertEquals(60, after.estimatedMinutes)
            assertEquals(due - 2 * 60 * 60_000L, after.reminderEpochMillis)
            // The strip no longer lists it — it is placed now.
            assertTrue(state.stripTasks.none { it.id == "t1" })
            assertEquals(1, state.placedTasks.size)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `busy event drop warns with the event end and writes nothing`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val eventStart = dayAt(1, 10)
            provider.addEvent(
                FakeGoogleEvent(
                    id = 51L, title = "Design review",
                    start = eventStart, end = eventStart + 60 * 60_000L,
                )
            )
            calendarRepository.syncGoogleCalendar(userId)
            val task = insertTask("t1", dayAt(1, 15))
            val viewModel = viewModel()
            viewModel.selectDay(eventStart)
            waitForStrip(viewModel, "t1")

            // 10:30 overlaps the 10:00-11:00 event.
            viewModel.dropTaskAtSlot(task, dayAt(1, 10, 30))

            val state = viewModel.uiState.first { it.pendingConflict != null }
            assertEquals(dayAt(1, 10, 30), state.pendingConflict!!.requestedStartMillis)
            // checkConflict's suggestion is the first conflicting event's end.
            assertEquals(eventStart + 60 * 60_000L, state.pendingConflict!!.suggestedStartMillis)
            assertEquals("Design review", state.pendingConflict!!.conflictingTitle)
            // Nothing was written yet.
            val after = db.taskDao().getTaskById("t1")!!
            assertNull(after.plannedStartEpochMillis)
            assertNull(after.plannedDate)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `accepting the suggestion lands the task at the suggested slot`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val eventStart = dayAt(1, 10)
            provider.addEvent(
                FakeGoogleEvent(
                    id = 52L, title = "Design review",
                    start = eventStart, end = eventStart + 60 * 60_000L,
                )
            )
            calendarRepository.syncGoogleCalendar(userId)
            val task = insertTask("t1", dayAt(1, 15))
            val viewModel = viewModel()
            viewModel.selectDay(eventStart)
            waitForStrip(viewModel, "t1")

            viewModel.dropTaskAtSlot(task, dayAt(1, 10, 30))
            viewModel.uiState.first { it.pendingConflict != null }
            viewModel.acceptSuggestedSlot()

            // acceptSuggestedSlot clears the dialog and writes in the same
            // launch — wait for the placement, not just the dialog.
            viewModel.uiState.first {
                it.pendingConflict == null && it.placedTasks.any { t -> t.id == "t1" }
            }
            val after = db.taskDao().getTaskById("t1")!!
            assertEquals(eventStart + 60 * 60_000L, after.plannedStartEpochMillis)
            assertEquals(LocalDate.now(ZoneId.systemDefault()).plusDays(1).toString(), after.plannedDate)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `declining the suggestion leaves the task in the strip`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val eventStart = dayAt(1, 10)
            provider.addEvent(
                FakeGoogleEvent(
                    id = 53L, title = "Design review",
                    start = eventStart, end = eventStart + 60 * 60_000L,
                )
            )
            calendarRepository.syncGoogleCalendar(userId)
            val task = insertTask("t1", dayAt(1, 15))
            val viewModel = viewModel()
            viewModel.selectDay(eventStart)
            waitForStrip(viewModel, "t1")

            viewModel.dropTaskAtSlot(task, dayAt(1, 10, 30))
            viewModel.uiState.first { it.pendingConflict != null }
            viewModel.dismissConflict()

            val after = db.taskDao().getTaskById("t1")!!
            assertNull(after.plannedStartEpochMillis)
            assertNull(after.plannedDate)
            val state = viewModel.uiState.first {
                it.pendingConflict == null && it.stripTasks.any { t -> t.id == "t1" }
            }
            assertTrue(state.stripTasks.any { it.id == "t1" })
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `overlap with a placed task suggests the end of that task block`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val placed = insertTask("placed", dayAt(0, 9), estimate = 60)
            val task = insertTask("t1", dayAt(0, 15))
            val viewModel = viewModel()
            viewModel.selectDay(dayAt(0, 9))
            waitForStrip(viewModel, "t1")

            viewModel.dropTaskAtSlot(placed, dayAt(0, 10))
            viewModel.uiState.first { it.placedTasks.any { it.id == "placed" } }

            // 10:30 overlaps the placed 10:00-11:00 block.
            viewModel.dropTaskAtSlot(task, dayAt(0, 10, 30))
            val state = viewModel.uiState.first { it.pendingConflict != null }
            assertEquals(dayAt(0, 11), state.pendingConflict!!.suggestedStartMillis)
            assertEquals("placed", state.pendingConflict!!.conflictingTitle)
            assertNull(db.taskDao().getTaskById("t1")!!.plannedStartEpochMillis)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `all-day event does not block a drop`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val dayStart = startOfDayMillis(dayAt(1, 0))
            provider.addEvent(
                FakeGoogleEvent(
                    id = 54L, title = "Holiday",
                    start = dayStart, end = dayStart + 24 * 60 * 60_000L,
                    allDay = true,
                )
            )
            calendarRepository.syncGoogleCalendar(userId)
            val task = insertTask("t1", dayAt(1, 15))
            val viewModel = viewModel()
            viewModel.selectDay(dayAt(1, 10))
            waitForStrip(viewModel, "t1")
            // Non-vacuous: prove the all-day event is actually in state
            // before dropping — otherwise the drop would succeed trivially
            // by the event never having been imported.
            viewModel.uiState.first { it.events.any { event -> event.isAllDay } }

            viewModel.dropTaskAtSlot(task, dayAt(1, 10, 30))

            // The all-day event does not block the drop: wait for the
            // placement to prove the write landed, then assert no dialog
            // ever appeared.
            viewModel.uiState.first { it.placedTasks.any { t -> t.id == "t1" } }
            val after = db.taskDao().getTaskById("t1")!!
            assertEquals(dayAt(1, 10, 30), after.plannedStartEpochMillis)
            assertNull(viewModel.uiState.value.pendingConflict)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `drop near midnight clamps into the same day`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val task = insertTask("t1", dayAt(0, 15))
            val viewModel = viewModel()
            viewModel.selectDay(dayAt(0, 9))
            waitForStrip(viewModel, "t1")

            // 23:50 with a 60-minute estimate would end at 00:50 tomorrow;
            // the drop clamps the start to 23:00 so the block stays in the day.
            viewModel.dropTaskAtSlot(task, dayAt(0, 23, 50))
            viewModel.uiState.first { it.placedTasks.any { t -> t.id == "t1" } }
            val after = db.taskDao().getTaskById("t1")!!
            assertEquals(dayAt(0, 23), after.plannedStartEpochMillis)
            assertEquals(todayString(), after.plannedDate)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `no-estimate task drops fine and its estimate stays null`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val task = insertTask("t1", dayAt(0, 15), estimate = null)
            val viewModel = viewModel()
            viewModel.selectDay(dayAt(0, 9))
            waitForStrip(viewModel, "t1")

            viewModel.dropTaskAtSlot(task, dayAt(0, 10, 30))
            viewModel.uiState.first { it.placedTasks.any { t -> t.id == "t1" } }
            val after = db.taskDao().getTaskById("t1")!!
            assertEquals(dayAt(0, 10, 30), after.plannedStartEpochMillis)
            assertNull(after.estimatedMinutes)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `dropped estimated task appears as a Timeline block`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val task = insertTask("t1", dayAt(0, 15), estimate = 60)
            val viewModel = viewModel()
            viewModel.selectDay(dayAt(0, 9))
            waitForStrip(viewModel, "t1")

            viewModel.dropTaskAtSlot(task, dayAt(0, 10, 30))

            // Wait for the placement to prove the write landed, then read
            // the same Room rows the Timeline reads: the task now shows as
            // a block at its planned start (the S2-2 acceptance), and it
            // survives a fresh repository read.
            viewModel.uiState.first { it.placedTasks.any { t -> t.id == "t1" } }
            val fresh = taskRepository.observeAllTasks(userId).first()
            val result = GetTodayTimelineUseCase().buildTimeline(
                fresh,
                emptyList(),
                startOfDayMillis(System.currentTimeMillis()),
                System.currentTimeMillis(),
            )
            val block = result.entries.filterIsInstance<TimelineEntry.TaskBlock>()
                .singleOrNull { it.task.id == "t1" }
            assertTrue(block != null)
            assertEquals(dayAt(0, 10, 30), block!!.task.plannedStartEpochMillis)
        } finally {
            Dispatchers.resetMain()
        }
    }
}

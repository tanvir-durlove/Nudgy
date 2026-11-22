package com.nextgentool.nudgy.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.repository.CalendarRepository
import com.nextgentool.nudgy.data.repository.FakeCalendarEventSync
import com.nextgentool.nudgy.data.repository.FakeCalendarProviderGateway
import com.nextgentool.nudgy.data.repository.FakeGoogleEvent
import com.nextgentool.nudgy.data.repository.GroupRepository
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.startOfDayMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

/**
 * Sprint 2-1 — light ViewModel tests over real in-memory Room + the
 * fake calendar provider (no Hilt). The event is imported by an
 * explicit repository sync BEFORE the ViewModel is built, so the
 * tests never race the ViewModel's own init sync.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CalendarViewModelTest {

    private lateinit var context: Context
    private lateinit var db: NudgyDatabase
    private val viewModelStore = ViewModelStore()
    private lateinit var provider: FakeCalendarProviderGateway
    private lateinit var calendarRepository: CalendarRepository
    private lateinit var taskRepository: TaskRepository
    private lateinit var groupRepository: GroupRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Round 8 proved Robolectric does NOT grant calendar permissions
        // by default (even though the manifest declares them), so every
        // sync returned 0 at the permission check. Grant them explicitly
        // for these tests — the production permission checks are unchanged.
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
        // PR #86 rule: cancel the ViewModel scopes before the database
        // closes, so no viewModelScope coroutine can run Room work
        // against the closed DB and poison the next test's TestScope.
        viewModelStore.clear()
        // S4-1 round 7 — drain in-flight Room IO before closing the DB.
        // clear() cancels the ViewModels' scopes, but a Room call already
        // running on a real IO thread (a ViewModel init sync, or a
        // never-ending Room Flow collection re-querying after a write)
        // does not notice the cancellation until it returns; closing the
        // DB first makes that call throw IllegalStateException, which the
        // global coroutines-test ExceptionCollector buffers and replays
        // into the next test's runTest enter() as UncaughtExceptionsBeforeTest
        // (the compose rule wraps every test method in runTest, so the
        // victim is the next compose test — BoardDragTest). 500ms is ample
        // for in-memory Room calls.
        Thread.sleep(500)
        db.close()
    }

    private val userId get() = taskRepository.currentUserId()

    private fun dayAt(daysAhead: Int, hour: Int): Long =
        Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, daysAhead)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun `selected day shows its events and a repository-rule suggested slot`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val tomorrow = dayAt(1, 10)
            provider.addEvent(
                FakeGoogleEvent(id = 42L, title = "Design review", start = tomorrow, end = tomorrow + 60 * 60 * 1000L)
            )
            calendarRepository.syncGoogleCalendar(userId)
            val viewModel = CalendarViewModel(calendarRepository, taskRepository, groupRepository).also { viewModelStore.put("calendar", it) }
            viewModel.selectDay(tomorrow)
            // The suggested slot lands asynchronously (refreshSuggestedSlot
            // runs in the init collect), so wait for it too — asserting
            // right after the events emission races it.
            val state = viewModel.uiState.first {
                !it.isLoading && it.selectedDayMillis == startOfDayMillis(tomorrow) && it.events.size == 1 && it.suggestedSlot != null
            }
            assertEquals("Design review", state.events.single().title)
            // The suggested slot is the repository's rule (35-min gap),
            // not the screen's old 30-min inline calculation: a day with
            // one 10:00–11:00 event still has a free slot.
            assertNotNull(state.suggestedSlot)
            assertTrue(state.isCalendarConnected)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `syncNow imports a provider event into the state`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = CalendarViewModel(calendarRepository, taskRepository, groupRepository).also { viewModelStore.put("calendar", it) }
            // The init sync already ran against an empty provider; add
            // the event now and sync again via the ViewModel.
            val tomorrow = dayAt(1, 14)
            provider.addEvent(
                FakeGoogleEvent(id = 43L, title = "Team sync", start = tomorrow, end = tomorrow + 30 * 60 * 1000L)
            )
            viewModel.selectDay(tomorrow)
            viewModel.syncNow()
            val state = viewModel.uiState.first {
                !it.isLoading && it.selectedDayMillis == startOfDayMillis(tomorrow) && it.events.size == 1
            }
            assertEquals("Team sync", state.events.single().title)
        } finally {
            Dispatchers.resetMain()
        }
    }
}

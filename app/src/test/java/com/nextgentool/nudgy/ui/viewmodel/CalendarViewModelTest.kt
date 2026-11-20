package com.nextgentool.nudgy.ui.viewmodel

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.repository.CalendarRepository
import com.nextgentool.nudgy.data.repository.FakeCalendarEventSync
import com.nextgentool.nudgy.data.repository.FakeCalendarProvider
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
    private lateinit var provider: FakeCalendarProvider
    private lateinit var calendarRepository: CalendarRepository
    private lateinit var taskRepository: TaskRepository
    private lateinit var groupRepository: GroupRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("nudgy_auth_prefs", Context.MODE_PRIVATE)
            .edit().putString("active_user_id", "test-user-1").apply()
        db = Room.inMemoryDatabaseBuilder(context, NudgyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // The documented registration (see CalendarSyncDedupTest):
        // registerProviderInternal does not stick in this setup.
        val info = android.content.pm.ProviderInfo().apply {
            authority = "com.android.calendar"
        }
        provider = org.robolectric.Robolectric
            .buildContentProvider(FakeCalendarProvider::class.java)
            .create(info)
            .get()
        calendarRepository = CalendarRepository(context, db, FakeCalendarEventSync())
        taskRepository = TaskRepository(context, db)
        groupRepository = GroupRepository(context, db)
    }

    @After
    fun tearDown() {
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
            val viewModel = CalendarViewModel(calendarRepository, taskRepository, groupRepository)
            viewModel.selectDay(tomorrow)
            val state = viewModel.uiState.first {
                !it.isLoading && it.selectedDayMillis == startOfDayMillis(tomorrow) && it.events.size == 1
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
            val viewModel = CalendarViewModel(calendarRepository, taskRepository, groupRepository)
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

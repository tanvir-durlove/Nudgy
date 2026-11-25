package com.nextgentool.nudgy.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.local.entity.CalendarEventEntity
import com.nextgentool.nudgy.data.local.entity.GroupEntity
import com.nextgentool.nudgy.data.repository.CalendarRepository
import com.nextgentool.nudgy.data.repository.GroupRepository
import com.nextgentool.nudgy.data.repository.TaskRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 6-4, step 2b — the search ViewModel: the query drives the
 * Room LIKE results (title / description / tag name).
 *
 * Follows the SettingsViewModelTest pattern: the ViewModel is built
 * inside each test (never in setUp — the init coroutines need the
 * test Main), the ViewModel lives in a ViewModelStore cleared BEFORE
 * the database closes, and in-flight Room IO is drained first
 * (S4-1 round 7). [SearchViewModel.setQuery] is a plain StateFlow
 * set — it launches nothing, so calling it from the test is safe
 * (the S6-3 rule: never call a viewModelScope.launch method from a
 * plain-JUnit/Robolectric test). groupAuth is null here, so the init
 * launches nothing at all.
 *
 * Step 2c — the same file covers the remaining flows: schedules (the
 * calendar_events LIKE query), notes (description hits as their own
 * group) and groups (name filter). Those tests build the wired
 * ViewModel (calendar + group repositories); the step-2b tests keep
 * the task-only build, which still compiles and passes unchanged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SearchViewModelTest {

    private lateinit var context: Context
    private lateinit var db: NudgyDatabase
    private lateinit var taskRepository: TaskRepository
    private lateinit var calendarRepository: CalendarRepository
    private lateinit var groupRepository: GroupRepository
    private val store = ViewModelStore()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, NudgyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        taskRepository = TaskRepository(context, db)
        calendarRepository = CalendarRepository(context, db)
        groupRepository = GroupRepository(context, db)
    }

    @After
    fun tearDown() {
        store.clear()
        Thread.sleep(500)
        db.close()
    }

    /** runTest with Main on the test dispatcher (SettingsViewModelTest pattern). */
    private fun vmTest(block: suspend TestScope.() -> Unit) = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            block()
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun buildViewModel(): SearchViewModel =
        SearchViewModel(taskRepository).also { store.put("search", it) }

    /** Step 2c — the fully wired ViewModel (schedules + notes + groups). */
    private fun buildWiredViewModel(): SearchViewModel =
        SearchViewModel(
            taskRepository,
            calendarRepository = calendarRepository,
            groupRepository = groupRepository
        ).also { store.put("search-wired", it) }

    private val userId get() = taskRepository.currentUserId()

    /** Same seed as TaskRepositorySearchTest: title, description, tag. */
    private suspend fun seed() {
        taskRepository.createTask(
            userId = userId,
            title = "Buy groceries",
            description = "Weekly shop at the market"
        )
        taskRepository.createTask(
            userId = userId,
            title = "Call mom",
            description = "Ask about the garden"
        )
        taskRepository.createTask(
            userId = userId,
            title = "Read book",
            description = "Chapter on gardening"
        )
        val tag = taskRepository.createTag(userId, "shopping", "#0F766E")
        val groceries = taskRepository.getAllTasksOnce(userId)
            .first { it.title == "Buy groceries" }
        taskRepository.setTaskTags(groceries.id, listOf(tag.id))
    }

    @Test
    fun `blank query yields no task results`() = vmTest {
        val viewModel = buildViewModel()
        assertEquals("", viewModel.query.value)
        assertTrue(viewModel.taskResults.first { it.isEmpty() }.isEmpty())
    }

    @Test
    fun `typing a query surfaces the title match`() = vmTest {
        seed()
        val viewModel = buildViewModel()
        viewModel.setQuery("groceries")
        val results = viewModel.taskResults.first {
            it.any { task -> task.title == "Buy groceries" }
        }
        assertEquals(listOf("Buy groceries"), results.map { it.title })
    }

    @Test
    fun `typing a query surfaces description and tag matches`() = vmTest {
        seed()
        val viewModel = buildViewModel()
        viewModel.setQuery("garden")
        val described = viewModel.taskResults.first { it.size == 2 }
        assertEquals(listOf("Call mom", "Read book"), described.map { it.title }.sorted())
        viewModel.setQuery("shopping")
        val tagged = viewModel.taskResults.first {
            it.any { task -> task.title == "Buy groceries" }
        }
        assertEquals(listOf("Buy groceries"), tagged.map { it.title })
    }

    @Test
    fun `a query with no matches yields an empty list after a match`() = vmTest {
        seed()
        val viewModel = buildViewModel()
        viewModel.setQuery("groceries")
        viewModel.taskResults.first { it.size == 1 }
        viewModel.setQuery("zzz-no-such")
        // The previous emission had one row, so this empty emission
        // proves the search ran — it is not the initial value.
        assertTrue(viewModel.taskResults.first { it.isEmpty() }.isEmpty())
    }

    // ---- Sprint 6-4, step 2c: schedules, notes, groups ----

    @Test
    fun `blank query yields no schedule, note or group results`() = vmTest {
        val viewModel = buildWiredViewModel()
        assertTrue(viewModel.scheduleResults.first { it.isEmpty() }.isEmpty())
        assertTrue(viewModel.noteResults.first { it.isEmpty() }.isEmpty())
        assertTrue(viewModel.groupResults.first { it.isEmpty() }.isEmpty())
    }

    @Test
    fun `typing a query surfaces the schedule title match`() = vmTest {
        db.calendarDao().insertEvent(
            CalendarEventEntity(
                userId = userId,
                title = "Dentist appointment",
                startEpochMillis = 1_700_000_000_000,
                endEpochMillis = 1_700_000_600_000,
                locationOrLink = "City Dental Clinic"
            )
        )
        val viewModel = buildWiredViewModel()
        viewModel.setQuery("dentist")
        val results = viewModel.scheduleResults.first {
            it.any { event -> event.title == "Dentist appointment" }
        }
        assertEquals(listOf("Dentist appointment"), results.map { it.title })
    }

    @Test
    fun `typing a query surfaces the schedule location match`() = vmTest {
        db.calendarDao().insertEvent(
            CalendarEventEntity(
                userId = userId,
                title = "Team standup",
                startEpochMillis = 1_700_001_000_000,
                endEpochMillis = 1_700_001_300_000,
                locationOrLink = "Office Room 4"
            )
        )
        val viewModel = buildWiredViewModel()
        viewModel.setQuery("room")
        val results = viewModel.scheduleResults.first {
            it.any { event -> event.title == "Team standup" }
        }
        assertEquals(listOf("Team standup"), results.map { it.title })
    }

    @Test
    fun `another user's event never leaks into schedule results`() = vmTest {
        db.calendarDao().insertEvent(
            CalendarEventEntity(
                userId = userId,
                title = "Dentist appointment",
                startEpochMillis = 1_700_000_000_000,
                endEpochMillis = 1_700_000_600_000
            )
        )
        db.calendarDao().insertEvent(
            CalendarEventEntity(
                userId = "other-user",
                title = "Dentist checkup",
                startEpochMillis = 1_700_003_000_000,
                endEpochMillis = 1_700_003_600_000
            )
        )
        val viewModel = buildWiredViewModel()
        viewModel.setQuery("Dentist")
        val results = viewModel.scheduleResults.first {
            it.any { event -> event.title == "Dentist appointment" }
        }
        assertEquals(listOf("Dentist appointment"), results.map { it.title })
    }

    @Test
    fun `notes show description hits as their own group`() = vmTest {
        seed()
        val viewModel = buildWiredViewModel()
        // "market" matches only the Buy groceries description.
        viewModel.setQuery("market")
        val noted = viewModel.noteResults.first { it.size == 1 }
        assertEquals(listOf("Buy groceries"), noted.map { it.title })
        // "groceries" matches only the title — the Notes group stays
        // empty while the Tasks group still shows the row.
        viewModel.setQuery("groceries")
        assertTrue(viewModel.noteResults.first { it.isEmpty() }.isEmpty())
        assertEquals(
            listOf("Buy groceries"),
            viewModel.taskResults.first { it.size == 1 }.map { it.title }
        )
    }

    @Test
    fun `typing a query surfaces the group name match`() = vmTest {
        db.groupDao().insertGroup(
            GroupEntity(
                groupId = "g1",
                name = "Weekend Runners",
                code = "RUN123",
                creatorUid = userId
            )
        )
        db.groupDao().insertGroup(
            GroupEntity(
                groupId = "g2",
                name = "Book Club",
                code = "BOOK1",
                creatorUid = userId
            )
        )
        val viewModel = buildWiredViewModel()
        viewModel.setQuery("runners")
        val results = viewModel.groupResults.first {
            it.any { group -> group.name == "Weekend Runners" }
        }
        assertEquals(listOf("Weekend Runners"), results.map { it.name })
    }

    @Test
    fun `a query with no group matches yields an empty list after a match`() = vmTest {
        db.groupDao().insertGroup(
            GroupEntity(
                groupId = "g1",
                name = "Weekend Runners",
                code = "RUN123",
                creatorUid = userId
            )
        )
        val viewModel = buildWiredViewModel()
        viewModel.setQuery("runners")
        viewModel.groupResults.first { it.size == 1 }
        viewModel.setQuery("zzz-no-such")
        // The previous emission had one row, so this empty emission
        // proves the search ran — it is not the initial value.
        assertTrue(viewModel.groupResults.first { it.isEmpty() }.isEmpty())
    }
}

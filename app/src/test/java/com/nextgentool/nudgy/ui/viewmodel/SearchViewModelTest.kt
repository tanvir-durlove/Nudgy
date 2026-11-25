package com.nextgentool.nudgy.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.local.NudgyDatabase
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
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SearchViewModelTest {

    private lateinit var context: Context
    private lateinit var db: NudgyDatabase
    private lateinit var taskRepository: TaskRepository
    private val store = ViewModelStore()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, NudgyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        taskRepository = TaskRepository(context, db)
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
}

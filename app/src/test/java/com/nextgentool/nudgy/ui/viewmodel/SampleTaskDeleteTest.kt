package com.nextgentool.nudgy.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.usecase.AddSubtaskUseCase
import com.nextgentool.nudgy.domain.usecase.CreateTaskUseCase
import com.nextgentool.nudgy.domain.usecase.DeleteTaskUseCase
import com.nextgentool.nudgy.domain.usecase.SaveViewUseCase
import com.nextgentool.nudgy.domain.usecase.ToggleTaskCompletionUseCase
import com.nextgentool.nudgy.domain.usecase.UpdateTaskUseCase
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
 * Sprint 6-3 — the sample delete, proven WITHOUT compose (feature 40,
 * Q3=(a), spec §6).
 *
 * Round 5 split: the compose delete test in SampleTaskRowTest hung
 * the test step once it got past the chip wait (the bisect proved it
 * — with only that test @Ignore'd the suite went green, PR run 880
 * SUCCESS, push run 879 in 3m36s). The compose test now proves the
 * render only (chip + delete button); THIS test proves the delete
 * LOGIC: TasksViewModel.deleteTask(sample) removes exactly the
 * sample and leaves the ordinary task untouched. No compose rule,
 * no waitUntil, no semantics, no performClick — the await is the
 * repo's own flow-first pattern (CalendarViewModelDropTest /
 * GroupsViewModelTest.awaitGroup).
 *
 * Follows the GroupsViewModelTest pattern: the ViewModel is built
 * inside each test (never in setUp — the init coroutines need the
 * test Main), the ViewModel lives in a ViewModelStore cleared
 * BEFORE the database closes, and in-flight Room IO is drained
 * first (S4-1 round 7). The tap wiring itself (IconButton onClick
 * → viewModel.deleteTask) is NOT proven by this test — DEVICE-GATE
 * item for the sprint-end build.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SampleTaskDeleteTest {

    private lateinit var context: Context
    private lateinit var db: NudgyDatabase
    private lateinit var repository: TaskRepository
    private val store = ViewModelStore()

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
        // PR #86 rule: cancel the ViewModel scopes before the database
        // closes, then drain in-flight Room IO (S4-1 round 7).
        store.clear()
        Thread.sleep(500)
        db.close()
    }

    /** runTest with Main on the test dispatcher (CalendarViewModelTest pattern). */
    private fun vmTest(block: suspend TestScope.() -> Unit) = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            block()
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun buildViewModel(): TasksViewModel {
        val viewModel = TasksViewModel(
            repository = repository,
            createTaskUseCase = CreateTaskUseCase(repository),
            toggleCompletion = ToggleTaskCompletionUseCase(repository),
            updateTask = UpdateTaskUseCase(repository),
            deleteTask = DeleteTaskUseCase(repository),
            addSubtask = AddSubtaskUseCase(repository),
            saveView = SaveViewUseCase(repository)
        )
        store.put("tasks", viewModel)
        return viewModel
    }

    @Test
    fun `deleting a sample removes only it`() = vmTest {
        val userId = repository.currentUserId()
        val sample = repository.createTask(
            userId = userId,
            title = "Morning walk",
            dueEpochMillis = startOfTodayMillis() + 1 * 60 * 60 * 1000L,
            isSample = true
        )
        repository.createTask(
            userId = userId,
            title = "Real task",
            dueEpochMillis = startOfTodayMillis()
        )
        val tasksViewModel = buildViewModel()

        tasksViewModel.deleteTask(sample)

        // Await the delete through the repository flow itself — the
        // one-shot viewModelScope.launch runs on the test Main, the
        // Room flow re-emits once the row is gone.
        val remaining = repository.observeAllTasks(userId).first { it.size == 1 }
        assertEquals("Real task", remaining.single().title)
        assertTrue(remaining.none { it.id == sample.id })
        assertEquals(1, repository.getAllTasksOnce(userId).size)
    }
}

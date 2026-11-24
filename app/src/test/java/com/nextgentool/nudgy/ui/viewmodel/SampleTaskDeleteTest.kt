package com.nextgentool.nudgy.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.local.entity.TaskEntity
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
import kotlinx.coroutines.test.advanceUntilIdle
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
 * no waitUntil, no semantics, no performClick.
 *
 * Await pattern (round 5 fix): the FIRST shape awaited the delete
 * through the raw repository flow (`observeAllTasks(...).first`),
 * which threw UncompletedCoroutinesError on both runs 881/882.
 * This shape follows the EisenhowerMatrixViewModelTest pattern
 * instead — the SAME TasksViewModel, the SAME vmTest — awaiting
 * the ViewModel's own uiState (awaitLoaded, then a uiState
 * predicate on the surviving items). It still threw the same
 * error on runs 883/884, so the test now also clears the
 * ViewModelStore BEFORE the runTest body ends (see the
 * store.clear() at the end of the test): the uiState stateIn
 * (WhileSubscribed(5_000)) keeps its sharing coroutine alive on
 * the test Main after the last subscriber leaves, and runTest's
 * cleanup throws UncompletedCoroutinesError for it — the PR #86
 * store.clear() in tearDown runs too late for that check. That
 * still threw on runs 885/886, so the test now ALSO drains the
 * scheduler with advanceUntilIdle() before the clear: the suite
 * timing proves the body completes in seconds (green run 880's
 * test step took 3m20s; runs 882/884/885 took 3m12–3m29s), so
 * the error is runTest's cleanup check on a non-idle scheduler,
 * not the 60 s timeout — and a cancelled delay still counts as
 * a pending task, which is why the clear alone did not help.
 * The tap wiring itself (IconButton onClick → viewModel.deleteTask)
 * is NOT proven by this test — DEVICE-GATE item for the
 * sprint-end build.
 *
 * Follows the GroupsViewModelTest pattern: the ViewModel is built
 * inside each test (never in setUp — the init coroutines need the
 * test Main), the ViewModel lives in a ViewModelStore cleared
 * BEFORE the database closes, and in-flight Room IO is drained
 * first (S4-1 round 7).
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

    private suspend fun TasksViewModel.awaitLoaded(): TasksUiState =
        uiState.first { !it.isLoading }

    private suspend fun TasksViewModel.awaitTasks(
        predicate: (List<TaskEntity>) -> Boolean
    ): List<TaskEntity> =
        uiState.first { state ->
            predicate(state.items.map { it.task })
        }.items.map { it.task }

    @Test
    fun `deleting a sample removes only it`() = vmTest {
        val userId = repository.currentUserId()
        val tasksViewModel = buildViewModel()
        tasksViewModel.awaitLoaded()

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
        // Both rows are visible in the List before the delete.
        tasksViewModel.awaitTasks { tasks -> tasks.size == 2 }

        tasksViewModel.deleteTask(sample)

        // Await the delete through the ViewModel's own uiState (the
        // EisenhowerMatrixViewModelTest pattern): the sample is gone
        // and the ordinary task is the only row left.
        val remaining = tasksViewModel.awaitTasks { tasks ->
            tasks.size == 1 && tasks.single().title == "Real task"
        }
        assertEquals("Real task", remaining.single().title)
        assertTrue(remaining.none { it.id == sample.id })
        // The delete reached the DB, not just the List's state.
        assertEquals(1, repository.getAllTasksOnce(userId).size)

        // Drain the test scheduler BEFORE runTest ends: the uiState
        // stateIn (WhileSubscribed(5_000)) leaves its grace delay
        // as a pending task on the test Main scheduler after the
        // last subscriber leaves, and runTest's cleanup throws
        // UncompletedCoroutinesError while the scheduler is not
        // idle (runs 881–886 — the suite timing proves the body
        // itself completes in seconds, so this is the cleanup
        // check, not the 60 s timeout). advanceUntilIdle runs the
        // delay out so the sharing coroutine finishes.
        advanceUntilIdle()
        // Cancel the ViewModel's scope as well (see the KDoc): the
        // PR #86 store.clear() in tearDown runs too late for
        // runTest's cleanup check, so it happens here, after the
        // last assertion.
        store.clear()
    }
}

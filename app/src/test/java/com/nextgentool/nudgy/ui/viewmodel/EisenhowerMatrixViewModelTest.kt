package com.nextgentool.nudgy.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.endOfDayMillis
import com.nextgentool.nudgy.domain.startOfDayMillis
import com.nextgentool.nudgy.domain.usecase.AddSubtaskUseCase
import com.nextgentool.nudgy.domain.usecase.CreateTaskUseCase
import com.nextgentool.nudgy.domain.usecase.DeleteTaskUseCase
import com.nextgentool.nudgy.domain.usecase.EisenhowerQuadrant
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 4-3 — the Eisenhower move writes through the ViewModel (spec §6).
 *
 * Manually-constructed TasksViewModel over real in-memory Room (no Hilt),
 * following the HabitViewModelTest pattern: Main is set to the test
 * dispatcher inside each test, and the ViewModel is built inside the test
 * (never in setUp — the init coroutines need the test Main). TearDown
 * follows the PR #86 rule: the ViewModel lives in a ViewModelStore and
 * the store is cleared BEFORE the database closes, and in-flight Room IO
 * is drained first (S4-1 round 7).
 *
 * What is pinned here is the WRITE + the List's read-back: after
 * moveTaskToQuadrant, the List's own data (TasksViewModel.uiState) shows
 * the new priority + due date — the matrix is a view over the same
 * TaskEntity, not a second store.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EisenhowerMatrixViewModelTest {

    private lateinit var context: Context
    private lateinit var db: NudgyDatabase
    private lateinit var taskRepository: TaskRepository
    private val store = ViewModelStore()
    private val userId = "test-user-1"

    /** Noon on the local day [daysAhead] days from today (0 = today). */
    private fun noon(daysAhead: Int = 0): Long =
        startOfDayMillis(daysAhead, System.currentTimeMillis()) + 12 * 60 * 60 * 1000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("nudgy_auth_prefs", Context.MODE_PRIVATE)
            .edit().putString("active_user_id", userId).apply()
        db = Room.inMemoryDatabaseBuilder(context, NudgyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        taskRepository = TaskRepository(context, db)
    }

    @After
    fun tearDown() {
        // PR #86 rule: cancel the ViewModel scopes before the
        // database closes (same leak as DarkThemeTest).
        store.clear()
        // S4-1 round 7 — drain in-flight Room IO before closing the DB.
        Thread.sleep(500)
        db.close()
    }

    private fun buildViewModel(): TasksViewModel {
        val viewModel = TasksViewModel(
            repository = taskRepository,
            createTaskUseCase = CreateTaskUseCase(taskRepository),
            toggleCompletion = ToggleTaskCompletionUseCase(taskRepository),
            updateTask = UpdateTaskUseCase(taskRepository),
            deleteTask = DeleteTaskUseCase(taskRepository),
            addSubtask = AddSubtaskUseCase(taskRepository),
            saveView = SaveViewUseCase(taskRepository)
        )
        store.put("tasks", viewModel)
        return viewModel
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

    private suspend fun TasksViewModel.awaitLoaded(): TasksUiState =
        uiState.first { !it.isLoading }

    private suspend fun TasksViewModel.awaitTask(
        id: String,
        predicate: (TaskEntity) -> Boolean = { true }
    ): TaskEntity =
        uiState.first { state ->
            state.items.any { it.task.id == id && predicate(it.task) }
        }.items.first { it.task.id == id }.task

    @Test
    fun `moving a Q1 task to Q4 shows LOW and no due date in the List`() = vmTest {
        val viewModel = buildViewModel()
        viewModel.awaitLoaded()
        val created = taskRepository.createTask(
            userId, "Call the bank",
            dueEpochMillis = noon(), priority = Priority.HIGH
        )
        viewModel.awaitTask(created.id)

        viewModel.moveTaskToQuadrant(created, EisenhowerQuadrant.Q4)
        val moved = viewModel.awaitTask(created.id) {
            it.priority == Priority.LOW &&
                it.dueDateEpochMillis == TaskRepository.NO_DUE_DATE
        }

        assertEquals(Priority.LOW, moved.priority)
        assertEquals(TaskRepository.NO_DUE_DATE, moved.dueDateEpochMillis)
    }

    @Test
    fun `moving a Q4 task to Q1 shows HIGH and due today in the List`() = vmTest {
        val viewModel = buildViewModel()
        viewModel.awaitLoaded()
        val created = taskRepository.createTask(
            userId, "Read a chapter",
            dueEpochMillis = TaskRepository.NO_DUE_DATE, priority = Priority.LOW
        )
        viewModel.awaitTask(created.id)

        viewModel.moveTaskToQuadrant(created, EisenhowerQuadrant.Q1)
        val moved = viewModel.awaitTask(created.id) {
            it.priority == Priority.HIGH &&
                it.dueDateEpochMillis == endOfDayMillis(System.currentTimeMillis())
        }

        assertEquals(Priority.HIGH, moved.priority)
        assertEquals(endOfDayMillis(System.currentTimeMillis()), moved.dueDateEpochMillis)
    }

    @Test
    fun `moving to Q2 keeps HIGH and clears the due date`() = vmTest {
        val viewModel = buildViewModel()
        viewModel.awaitLoaded()
        val created = taskRepository.createTask(
            userId, "Plan the trip",
            dueEpochMillis = noon(3), priority = Priority.HIGH
        )
        viewModel.awaitTask(created.id)

        viewModel.moveTaskToQuadrant(created, EisenhowerQuadrant.Q2)
        val moved = viewModel.awaitTask(created.id) {
            it.priority == Priority.HIGH &&
                it.dueDateEpochMillis == TaskRepository.NO_DUE_DATE
        }

        assertEquals(Priority.HIGH, moved.priority)
        assertEquals(TaskRepository.NO_DUE_DATE, moved.dueDateEpochMillis)
    }

    @Test
    fun `moving to Q3 sets MEDIUM and due today`() = vmTest {
        val viewModel = buildViewModel()
        viewModel.awaitLoaded()
        val created = taskRepository.createTask(
            userId, "Reply to the landlord",
            dueEpochMillis = noon(3), priority = Priority.LOW
        )
        viewModel.awaitTask(created.id)

        viewModel.moveTaskToQuadrant(created, EisenhowerQuadrant.Q3)
        val moved = viewModel.awaitTask(created.id) {
            it.priority == Priority.MEDIUM &&
                it.dueDateEpochMillis == endOfDayMillis(System.currentTimeMillis())
        }

        assertEquals(Priority.MEDIUM, moved.priority)
        assertEquals(endOfDayMillis(System.currentTimeMillis()), moved.dueDateEpochMillis)
    }
}

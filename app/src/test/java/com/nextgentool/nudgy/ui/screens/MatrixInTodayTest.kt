package com.nextgentool.nudgy.ui.screens

import android.content.Context
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.repository.CalendarRepository
import com.nextgentool.nudgy.data.repository.FocusHabitRepository
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.startOfDayMillis
import com.nextgentool.nudgy.domain.todayDateString
import com.nextgentool.nudgy.domain.usecase.AddSubtaskUseCase
import com.nextgentool.nudgy.domain.usecase.CarryOverUseCase
import com.nextgentool.nudgy.domain.usecase.CreateTaskUseCase
import com.nextgentool.nudgy.domain.usecase.DeleteTaskUseCase
import com.nextgentool.nudgy.domain.usecase.GetTodayTimelineUseCase
import com.nextgentool.nudgy.domain.usecase.SaveViewUseCase
import com.nextgentool.nudgy.domain.usecase.ToggleTaskCompletionUseCase
import com.nextgentool.nudgy.domain.usecase.UpdateTaskUseCase
import com.nextgentool.nudgy.domain.usecase.WeeklyReviewUseCase
import com.nextgentool.nudgy.ui.viewmodel.HabitViewModel
import com.nextgentool.nudgy.ui.viewmodel.TasksViewModel
import com.nextgentool.nudgy.ui.viewmodel.TodayViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 4-3 — the Eisenhower Matrix renders inside Today as the third
 * My Day view (spec §6, Q10=(a)). Renders Today with
 * MANUALLY-constructed ViewModels (real in-memory Room repository, no
 * Hilt), copying the HabitsInTodayTest setup: the Matrix chip is in the
 * My Day toggle next to List / Board, and a Plan-picked task shows in
 * its quadrant when the Matrix view is selected.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MatrixInTodayTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var db: NudgyDatabase
    private lateinit var repository: TaskRepository
    private lateinit var focusRepository: FocusHabitRepository
    private lateinit var tasksViewModel: TasksViewModel
    private lateinit var todayViewModel: TodayViewModel
    private lateinit var habitViewModel: HabitViewModel
    // Owns the ViewModels so tearDown can cancel their scopes before
    // the DB closes (same leak as DarkThemeTest, 2026-11-21).
    private val viewModelStore = ViewModelStore()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NudgyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = TaskRepository(context, db)
        focusRepository = FocusHabitRepository(context, db)
        tasksViewModel = TasksViewModel(
            repository = repository,
            createTaskUseCase = CreateTaskUseCase(repository),
            toggleCompletion = ToggleTaskCompletionUseCase(repository),
            updateTask = UpdateTaskUseCase(repository),
            deleteTask = DeleteTaskUseCase(repository),
            addSubtask = AddSubtaskUseCase(repository),
            saveView = SaveViewUseCase(repository)
        )
        todayViewModel = TodayViewModel(
            repository = repository,
            calendarRepository = CalendarRepository(context, db),
            toggleCompletion = ToggleTaskCompletionUseCase(repository),
            carryOverUseCase = CarryOverUseCase(),
            timelineUseCase = GetTodayTimelineUseCase(),
            weeklyReviewUseCase = WeeklyReviewUseCase()
        )
        habitViewModel = HabitViewModel(focusRepository, repository)
        viewModelStore.put("tasks", tasksViewModel)
        viewModelStore.put("today", todayViewModel)
        viewModelStore.put("habits", habitViewModel)
    }

    @After
    fun tearDown() {
        // Order matters: cancel the ViewModel scopes FIRST so no
        // viewModelScope coroutine can run Room work against the
        // DB once it is closed (same leak as DarkThemeTest).
        viewModelStore.clear()
        // S4-1 round 7 — drain in-flight Room IO before closing the DB.
        Thread.sleep(500)
        db.close()
    }

    private fun setTodayContent() {
        composeRule.setContent {
            TodayScreen(
                onPlanClick = {},
                onAllTasksClick = {},
                tasksViewModel = tasksViewModel,
                todayViewModel = todayViewModel,
                habitViewModel = habitViewModel
            )
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Add task")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun selectMatrixView() {
        composeRule.onNodeWithTag("my_day_view_mode_matrix")
            .performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Do first")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `the Matrix chip sits in the My Day toggle next to List and Board`() {
        setTodayContent()

        composeRule.onNodeWithTag("my_day_view_mode_list").assertExists()
        composeRule.onNodeWithTag("my_day_view_mode_board").assertExists()
        composeRule.onNodeWithTag("my_day_view_mode_matrix").assertExists()

        selectMatrixView()
        composeRule.onNodeWithTag("eisenhower_matrix").assertExists()
    }

    @Test
    fun `a Plan-picked HIGH task due today shows in Q1 of the Matrix`() {
        val noonToday = startOfDayMillis(System.currentTimeMillis()) + 12 * 60 * 60 * 1000L
        val created = runBlocking {
            val task = repository.createTask(
                repository.currentUserId(), "Call the bank",
                dueEpochMillis = noonToday, priority = Priority.HIGH
            )
            repository.updateTask(task.copy(plannedDate = todayDateString()))
            task
        }
        setTodayContent()
        selectMatrixView()

        composeRule.onNodeWithTag("matrix_count_Q1").assertTextEquals("1")
        composeRule.onNodeWithTag("matrix_card_${created.id}").assertExists()
    }

    @Test
    fun `the Tasks screen toggle stays List and Board only`() {
        // The Matrix lives in Today only (spec §6/Q10) — the Tasks
        // screen must not gain a third chip.
        composeRule.setContent {
            TasksScreen(viewModel = tasksViewModel)
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Add task")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("view_mode_list").assertExists()
        composeRule.onNodeWithTag("view_mode_board").assertExists()
        composeRule.onNodeWithTag("view_mode_matrix").assertDoesNotExist()
    }
}

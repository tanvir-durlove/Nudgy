package com.nextgentool.nudgy.ui.screens

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.repository.CalendarRepository
import com.nextgentool.nudgy.data.repository.FocusHabitRepository
import com.nextgentool.nudgy.data.repository.TaskRepository
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
 * Sprint 4-2 — the Habits section renders inside Today (spec §5,
 * Q10=(a)). Renders Today with MANUALLY-constructed ViewModels (real
 * in-memory Room repository, no Hilt), copying the TodayScreenS12Test
 * setup and adding the HabitViewModel: the section is present on the
 * screen, and a habit created in the repository shows up in it with
 * its streak line.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HabitsInTodayTest {

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

    @Test
    fun `the Habits section renders inside Today`() {
        setTodayContent()

        // The empty text only appears after the HabitViewModel's first
        // refresh — wait for it instead of asserting immediately.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("No habits yet — add your first one above")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("habits_section").assertExists()
        composeRule.onNodeWithText("Habits").assertExists()
    }

    @Test
    fun `a created habit shows in the Today Habits section with its streak`() {
        val habit = runBlocking {
            focusRepository.createHabit(repository.currentUserId(), "Walk")
        }
        setTodayContent()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Walk")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("habit_row_${habit.id}").assertExists()
        composeRule.onNodeWithTag("habit_streak_${habit.id}").assertExists()
        composeRule.onNodeWithText("No current streak").assertExists()
    }

    /**
     * Sprint 7-2b — the Habits tile scrolls Today to the Habits
     * section (no Habits screen exists): the tile measures the
     * section's Y offset and animates the page's scroll state to
     * it. Proved by the habit row becoming DISPLAYED after the tap
     * — the row exists from the start (the Column composes every
     * child), so existence alone would prove nothing.
     */
    @Test
    fun `Habits tile scrolls Today to the Habits section`() {
        val habit = runBlocking {
            focusRepository.createHabit(repository.currentUserId(), "Walk")
        }
        setTodayContent()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Walk")
                .fetchSemanticsNodes().isNotEmpty()
        }
        // The guard: the row starts below the fold on Robolectric's
        // short viewport, so this test would fail loudly — not pass
        // vacuously — if the viewport ever grew tall enough to show
        // the whole screen at once.
        composeRule.onNodeWithTag("habit_row_${habit.id}").assertIsNotDisplayed()
        // Below the fold: a coordinate click cannot reach the tile —
        // click via its semantics action (Matrix lesson, PR #140).
        composeRule.onNodeWithTag("more_tile_habits")
            .performSemanticsAction(SemanticsActions.OnClick)
        // The scroll is an animation on the test clock: poll until
        // the row is actually displayed instead of asserting once.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("habit_row_${habit.id}").assertIsDisplayed()
            }.isSuccess
        }
    }
}

package com.nextgentool.nudgy.ui.screens

import android.content.Context
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 7-2a — My Day has NO List / Board / Matrix switcher on Today
 * (spec Q3(a)): Board lives on the Tasks screen, Matrix gets its own
 * screen later in the sprint. Renders Today with MANUALLY-constructed
 * ViewModels (real in-memory Room repository, no Hilt), copying the
 * HabitsInTodayTest setup: a Plan-picked task renders as a plain list
 * row, and no view-mode chip or matrix exists anywhere on the screen.
 *
 * This file was MatrixInTodayTest (Sprint 4-3: the Matrix as the third
 * My Day view). The Matrix component itself is untouched — only its
 * entry point on Today is removed; EisenhowerMatrixTest still covers
 * the component, and its own screen lands later in Sprint 7.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TodayMyDayNoSwitcherTest {

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

    /**
     * Sprint 7 Habits fix round — the Room Flow re-emission of the
     * seeded planned task can lag behind setTodayContent()'s "Add task"
     * wait, so a my_day_section fetch right after it can return an
     * empty subtree and the title assertion fails on a race (PR #139
     * run 1014: two failures on the same bare assertTrue). Wait for the
     * seeded title to be present anywhere first — My Day and the
     * Timeline render from the same state, so the section fetch after
     * this is stable. Assertions unchanged.
     */
    private fun waitForMyDayTask(title: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(title)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * True when [text] appears in the semantics Text of [node] or any
     * of its descendants. A Plan-picked task with a planned start
     * renders in BOTH My Day and the Timeline (correct product
     * behaviour), so a bare global text assertion finds two nodes —
     * scope to the section instead. SemanticsConfiguration has no
     * getOrNull in Compose 1.7: contains + [] below, as in
     * PlanScreenTest's traversalIndex.
     */
    private fun subtreeHasText(node: SemanticsNode, text: String): Boolean {
        if (node.config.contains(SemanticsProperties.Text) &&
            node.config[SemanticsProperties.Text].toString().contains(text)
        ) return true
        return node.children.any { subtreeHasText(it, text) }
    }

    /**
     * A Plan-picked task in My Day (HIGH, due today noon).
     * Returns the task id. Needed even for the no-switcher test: with an
     * empty My Day, Today shows "Nothing planned for today yet" instead
     * of the list — so the rows only render once a task is planned.
     */
    private fun seedMyDayTask(title: String): String = runBlocking {
        val noonToday = startOfDayMillis(System.currentTimeMillis()) + 12 * 60 * 60 * 1000L
        val task = repository.createTask(
            repository.currentUserId(), title,
            dueEpochMillis = noonToday, priority = Priority.HIGH
        )
        repository.updateTask(task.copy(plannedDate = todayDateString()))
        task.id
    }

    @Test
    fun `My Day has no List Board Matrix switcher`() {
        seedMyDayTask("Call the bank")
        setTodayContent()

        composeRule.onNodeWithTag("my_day_view_mode_list").assertDoesNotExist()
        composeRule.onNodeWithTag("my_day_view_mode_board").assertDoesNotExist()
        composeRule.onNodeWithTag("my_day_view_mode_matrix").assertDoesNotExist()
        composeRule.onNodeWithTag("eisenhower_matrix").assertDoesNotExist()
        // The task still renders — as a plain list row, not a quadrant.
        // Scoped to the My Day section: it also renders as a Timeline
        // block (correct product behaviour), so a bare global assertion
        // would find two nodes.
        waitForMyDayTask("Call the bank")
        val section = composeRule.onNodeWithTag("my_day_section").fetchSemanticsNode()
        assertTrue(subtreeHasText(section, "Call the bank"))
    }

    @Test
    fun `a Plan-picked task renders as a plain list row in My Day`() {
        val createdId = seedMyDayTask("Call the bank")
        setTodayContent()

        // Scoped to the My Day section (see the test above: the task
        // also renders as a Timeline block).
        waitForMyDayTask("Call the bank")
        val section = composeRule.onNodeWithTag("my_day_section").fetchSemanticsNode()
        assertTrue(subtreeHasText(section, "Call the bank"))
        composeRule.onNodeWithTag("matrix_count_Q1").assertDoesNotExist()
        composeRule.onNodeWithTag("matrix_card_$createdId").assertDoesNotExist()
    }

    @Test
    fun `the Tasks screen toggle stays List and Board only`() {
        // The Matrix has no entry point on Today (S7 Q3(a) — its own
        // screen lands later in Sprint 7) and must not gain one on
        // the Tasks screen either.
        composeRule.setContent {
            TasksScreen(viewModel = tasksViewModel)
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Search tasks")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("view_mode_list").assertExists()
        composeRule.onNodeWithTag("view_mode_board").assertExists()
        composeRule.onNodeWithTag("view_mode_matrix").assertDoesNotExist()
    }
}

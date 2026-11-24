package com.nextgentool.nudgy.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.repository.CalendarRepository
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
import com.nextgentool.nudgy.ui.components.TaskBoard
import com.nextgentool.nudgy.ui.viewmodel.TasksViewModel
import com.nextgentool.nudgy.ui.viewmodel.TodayViewModel
import com.nextgentool.nudgy.ui.viewmodel.startOfTodayMillis
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 6-3 — the sample row (feature 40, Q3=(a), spec §6).
 *
 * Renders with manually-constructed ViewModels (the
 * TodayScreenS12Test pattern: real in-memory Room repository, no
 * Hilt). The sample is seeded due today at 01:00 — the timeline has
 * no past filter, so it always lands as the first Today-tab
 * TaskPoint whatever hour the test runs at (a now-relative due would
 * cross midnight on a late-night run). The normal task is due at
 * 00:00, so it lands in the Anytime group on the same tab.
 *
 * The chip test stays on TodayScreen (it passes there). The DELETE
 * test renders the TaskBoard directly (the BoardDragTest pattern)
 * with items from the real TasksViewModel flow. Four surfaces were
 * tried before this one and each failed on a harness quirk, not on
 * the product code: TodayScreen (run 864 — programmatic scroll
 * does not re-place content in Robolectric, so the delete button
 * never became pressable); the 1000 dp tall viewport (run 866 —
 * hung the test step twice with zero output); the Tasks list by
 * index (runs 867/868 — after performScrollToIndex(6) the chip
 * never composed within 10 s); TasksScreen in board mode (run 870 —
 * after the S14-proven performScrollTo + click on view_mode_board
 * the chip still never appeared, most likely the tap landing on
 * stale bounds after the programmatic scroll — the repo's
 * documented lesson that semantics bounds do not update after a
 * programmatic scroll). The direct board render has no LazyColumn,
 * no toggle and no scrolling of any kind: the board's cards live in
 * plain Columns, so every card is composed eagerly, and the delete
 * path is identical (the same SampleDeleteButton component, the
 * same viewModel.deleteTask, items from the same ViewModel flow —
 * so the chip disappearing after the tap proves the flow-driven
 * refresh too). What this version does NOT prove is the
 * TasksScreen wiring of onDeleteSample into the board/list rows —
 * that is verified by code trace and by the sprint-end device
 * test (§10), not by this test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SampleTaskRowTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var db: NudgyDatabase
    private lateinit var repository: TaskRepository
    private lateinit var tasksViewModel: TasksViewModel
    private lateinit var todayViewModel: TodayViewModel
    private val viewModelStore = ViewModelStore()
    // The tasks must belong to the user the ViewModels actually
    // observe: constructed without a GroupAuth seam they fall back to
    // repository.currentUserId() (the TodayScreenS12Test pattern) —
    // a hardcoded uid would render zero rows on Today.

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NudgyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = TaskRepository(context, db)
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
        viewModelStore.put("tasks", tasksViewModel)
        viewModelStore.put("today", todayViewModel)
    }

    @After
    fun tearDown() {
        viewModelStore.clear()
        Thread.sleep(500)
        db.close()
    }

    @Test
    fun `sample task shows the Sample chip and one tap deletes only it`() {
        val sample = runBlocking {
            repository.createTask(
                userId = repository.currentUserId(),
                title = "Morning walk",
                dueEpochMillis = startOfTodayMillis() + 1 * 60 * 60 * 1000L,
                isSample = true
            )
        }
        runBlocking {
            repository.createTask(
                userId = repository.currentUserId(),
                title = "Real task",
                dueEpochMillis = startOfTodayMillis()
            )
        }

        // The board rendered directly (see the class comment): no
        // LazyColumn, no toggle, no scrolling — every card is composed
        // eagerly. Items come from the real TasksViewModel flow, so the
        // board follows the delete.
        composeRule.setContent {
            val uiState by tasksViewModel.uiState.collectAsState()
            Box(modifier = Modifier.fillMaxSize()) {
                TaskBoard(
                    items = uiState.items,
                    onMoveTask = { _, _, _, _ -> },
                    onToggle = { tasksViewModel.toggleTask(it) },
                    onTaskClick = {},
                    onDeleteSample = { tasksViewModel.deleteTask(it) }
                )
            }
        }
        // Wait for a task card's text — this proves the flow has
        // emitted and the cards are composed.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Real task")
                .fetchSemanticsNodes().isNotEmpty()
        }
        // The sample chip lands exactly once (the sample card);
        // the ordinary task next to it carries no chip.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("sample_chip")
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(
            1,
            composeRule.onAllNodesWithTag("sample_chip")
                .fetchSemanticsNodes().size
        )
        // One tap on the card's own delete button removes the sample.
        // No waitForIdle: the chip-disappearance waitUntil below is
        // the synchronisation, and it gives the delete coroutine time
        // to run.
        composeRule.onNodeWithTag("sample_delete_${sample.id}").assertIsDisplayed()
        composeRule.onNodeWithTag("sample_delete_${sample.id}")
            .performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("sample_chip")
                .fetchSemanticsNodes().isEmpty()
        }
        // The delete must have reached the DB — if this fails, the tap
        // missed or the ViewModel coroutine never ran.
        assertEquals(1, runBlocking { repository.getAllTasksOnce(repository.currentUserId()).size })
        // The ordinary task is untouched, and the sample never returns.
        composeRule.onNodeWithText("Real task").assertExists()
        composeRule.onNodeWithTag("sample_delete_${sample.id}").assertDoesNotExist()
    }

    @Test
    fun `ordinary task carries no chip and no delete button`() {
        runBlocking {
            repository.createTask(
                userId = repository.currentUserId(),
                title = "Real task",
                dueEpochMillis = startOfTodayMillis()
            )
        }

        composeRule.setContent {
            TodayScreen(
                onPlanClick = {},
                onAllTasksClick = {},
                tasksViewModel = tasksViewModel,
                todayViewModel = todayViewModel
            )
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Real task")
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(
            0,
            composeRule.onAllNodesWithTag("sample_chip")
                .fetchSemanticsNodes().size
        )
    }
}

package com.nextgentool.nudgy.ui.screens

import android.content.Context
import androidx.compose.foundation.ScrollState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
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
import com.nextgentool.nudgy.ui.viewmodel.TasksViewModel
import com.nextgentool.nudgy.ui.viewmodel.TodayViewModel
import com.nextgentool.nudgy.ui.viewmodel.startOfTodayMillis
import kotlinx.coroutines.runBlocking
import kotlin.math.roundToInt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 6-3 — the sample row on Today (feature 40, Q3=(a), spec §6).
 *
 * Renders Today with manually-constructed ViewModels (the
 * TodayScreenS12Test pattern: real in-memory Room repository, no
 * Hilt). The sample is seeded due today at 01:00 — the timeline has
 * no past filter, so it always lands as the first Today-tab
 * TaskPoint whatever hour the test runs at (a now-relative due would
 * cross midnight on a late-night run). The normal task is due at
 * 00:00, so it lands in the Anytime group on the same tab.
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
    // The Today screen's scroll state, owned by the test (the S1-4c
    // seam): the delete button sits below the fold, and the screen is
    // scrolled to it by offset — never performScrollTo, which hangs
    // this harness when a real scroll is required (runs 851/852 and
    // 857/858).
    private val todayScrollState = ScrollState(0)
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

        composeRule.setContent {
            TodayScreen(
                onPlanClick = {},
                onAllTasksClick = {},
                tasksViewModel = tasksViewModel,
                todayViewModel = todayViewModel,
                todayScrollState = todayScrollState
            )
        }
        // The sample chip lands exactly once (the Today-tab TaskPoint);
        // the ordinary task next to it carries no chip.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("sample_chip")
                .fetchSemanticsNodes().size == 1
        }
        // Never assert on the bare title: the 01:00 sample is already
        // past at test time, so the Weekly Review card lists it under
        // "Missed" too — "Morning walk" matches TWO nodes. The row's
        // own delete button tag is unique; the ordinary task is
        // date-only (midnight), so it is not missed and matches once.
        composeRule.onNodeWithTag("sample_delete_${sample.id}").assertExists()
        composeRule.onNodeWithText("Real task").assertExists()

        // One tap on the row's own delete button removes the sample.
        // The first timeline row sits below the fold (hero + quick-add
        // + My Day sit above it), so scroll it into view by offset
        // through the test-owned ScrollState: the button's root bounds
        // give its unscrolled Y, and scrollTo jumps there directly —
        // no gesture, no paused-clock animation. Then one tap.
        val deleteNode = composeRule.onNodeWithTag("sample_delete_${sample.id}")
            .fetchSemanticsNode()
        val targetPx = with(composeRule.density) {
            deleteNode.boundsInRoot.top - 100.dp.toPx()
        }
        runBlocking { todayScrollState.scrollTo(targetPx.roundToInt().coerceAtLeast(0)) }
        composeRule.waitForIdle()
        // The scroll must have revealed the button — if this fails,
        // the offset math (not the tap) is the problem.
        composeRule.onNodeWithTag("sample_delete_${sample.id}").assertIsDisplayed()
        composeRule.onNodeWithTag("sample_delete_${sample.id}")
            .performClick()
        composeRule.waitForIdle()
        // The delete must have reached the DB — if this fails, the tap
        // missed or the ViewModel coroutine never ran, even though the
        // button was on screen.
        assertEquals(1, runBlocking { repository.getAllTasksOnce(repository.currentUserId()).size })
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("sample_chip")
                .fetchSemanticsNodes().isEmpty()
        }
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

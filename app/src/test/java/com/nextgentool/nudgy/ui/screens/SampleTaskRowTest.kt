package com.nextgentool.nudgy.ui.screens

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
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
 * test lives on TasksScreen instead: TodayScreen has no LazyColumn
 * to scroll by index, and programmatic scrolling does not re-place
 * content in Robolectric (CalendarDragTest rounds 9–15 proved it:
 * scrollTo moves the value but does not re-place the content,
 * performScrollTo leaves the bounds stale) — run 864 failed the
 * delete tap on Today for exactly that reason. The 1000 dp tall
 * viewport tried next (run 866) hung the test step twice with zero
 * output, so that approach is reverted. TasksScreen is a LazyColumn
 * and scrolls by index (the S14-proven performScrollToIndex
 * pattern): with the two seeded tasks the list indices are 0
 * quick_add, 1 search, 2 saved_views, 3 filters, 4 view_mode,
 * 5 the real task (due 00:00, due-date sort first), 6 the sample
 * (due 01:00) — verified against TasksViewModel's sort
 * (compareBy isCompleted, thenBy dueSortKey) and the default
 * LIST view mode.
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

        // TasksScreen, not TodayScreen (see the class comment): the
        // list scrolls by index, so the delete button is composed and
        // pressable. The sample is list index 6, the real task 5.
        composeRule.setContent {
            TasksScreen(viewModel = tasksViewModel)
        }
        // The list arrives behind the loading skeleton — wait for the
        // quick-add field (the TasksScreenSmokeTest pattern) before
        // scrolling, or the scroll lands on an empty list.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Add task")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("tasks_list").performScrollToIndex(6)
        // The sample chip lands exactly once (the sample row);
        // the ordinary task next to it carries no chip.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("sample_chip")
                .fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithTag("sample_delete_${sample.id}").assertIsDisplayed()
        composeRule.onNodeWithText("Real task").assertExists()

        // One tap on the row's own delete button removes the sample.
        // No waitForIdle: the chip-disappearance waitUntil below is
        // the synchronisation, and it gives the delete coroutine time
        // to run.
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

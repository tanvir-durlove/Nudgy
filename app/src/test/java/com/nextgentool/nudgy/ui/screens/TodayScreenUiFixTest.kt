package com.nextgentool.nudgy.ui.screens

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
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
import java.util.Calendar
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 1-1 UI-FIX — Today screen usability (2026-11-16 device test).
 *
 * The device test showed Today unusable: no visible way to tell a task
 * was saved, a created task vanished from view (My Day / Timeline were
 * dead skeleton rows), and "All tasks" sat below the fold on a screen
 * that did not scroll. These tests render Today with MANUALLY-constructed
 * ViewModels (real in-memory Room repository, no Hilt) and pin the fixed
 * flows: create → appears and stays, "Saved" confirmation, All-tasks
 * entry visible and working, Timeline shown only with timed tasks.
 *
 * S1-2 UPDATE (2026-11-17): My Day is now Plan-picked ONLY (Q3), so a
 * quick-added task no longer lands in My Day — it lands in the
 * Timeline's "Anytime" group (due 00:00 = no time set). A timed task
 * now appears in the Timeline ONLY (count 1, not 2). The empty-day
 * words changed to "Nothing planned for today yet". The rest of the
 * pins (Saved card, All tasks, Timeline hidden on an empty day) are
 * unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TodayScreenUiFixTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var db: NudgyDatabase
    private lateinit var repository: TaskRepository
    private lateinit var tasksViewModel: TasksViewModel
    private lateinit var todayViewModel: TodayViewModel
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
        // Order matters: cancel the ViewModel scopes FIRST so no
        // viewModelScope coroutine can run Room work against the
        // DB once it is closed (same leak as DarkThemeTest).
        viewModelStore.clear()
        db.close()
    }

    private fun setTodayContent(onAllTasksClick: () -> Unit = {}) {
        composeRule.setContent {
            TodayScreen(
                onPlanClick = {},
                onAllTasksClick = onAllTasksClick,
                tasksViewModel = tasksViewModel,
                todayViewModel = todayViewModel
            )
        }
        // Wait for the first Flow emission (quick-add field appears).
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Add task")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `today quick-add creates a task due today and it appears in the Timeline Anytime group`() {
        setTodayContent()
        tasksViewModel.createTaskForToday("Buy milk")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Buy milk")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Buy milk").performScrollTo().assertIsDisplayed()
        // S1-2: My Day is Plan-picked only, so the new task is NOT there —
        // it is in the Timeline's Anytime group (due 00:00 = no time set).
        composeRule.onNodeWithText("Anytime").performScrollTo().assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodesWithText("Nothing planned for today yet")
                .fetchSemanticsNodes().isNotEmpty()
        )
        // A second task does not push the first one out of view.
        tasksViewModel.createTaskForToday("Call mum")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Call mum")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Buy milk").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Call mum").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `All tasks entry sits under the quick-add and opens the list`() {
        var opened = false
        setTodayContent(onAllTasksClick = { opened = true })
        // The nudge card sits above the quick-add now, so on Robolectric's
        // short viewport the row needs a scroll to be asserted displayed —
        // on a real phone it is on first paint (hero + nudge + quick-add
        // are ~450dp). What this pins is placement (directly under the
        // quick-add, never below the Timeline) + the tap working.
        composeRule.onNodeWithText("All tasks").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("all_tasks_row").performClick()
        assertTrue(opened)
    }

    @Test
    fun `quick-add UI shows Saved after creating`() {
        setTodayContent()
        composeRule.onNodeWithTag("quick_add_field").performScrollTo()
        composeRule.onNodeWithTag("quick_add_field").performTextInput("Buy milk")
        composeRule.onNodeWithContentDescription("Add task").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Saved: Buy milk")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Saved: Buy milk").performScrollTo().assertIsDisplayed()
        // The Done button sits at the bottom of the confirmation card,
        // below the fold in the scrollable column — scroll to it first.
        composeRule.onNodeWithText("Done").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `empty day shows words not blank boxes and Timeline stays hidden`() {
        setTodayContent()
        // Wait for the load to finish: the empty-day text only replaces
        // the loading skeleton after the first Flow emission, and the
        // "Add task" wait in setTodayContent does not cover that (the
        // field renders while loading). Without this wait the test is a
        // coin flip on emission timing (CI #363).
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Nothing planned for today yet")
                .fetchSemanticsNodes().isNotEmpty()
        }
        // Below the fold on Robolectric's short viewport (hero + nudge
        // + quick-add + All tasks + tabs sit above it) — scroll first;
        // on a real phone it is on first paint.
        composeRule.onNodeWithText("Nothing planned for today yet").performScrollTo().assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodesWithText("Timeline")
                .fetchSemanticsNodes().isEmpty()
        )
    }

    /**
     * Title matches outside the Weekly review card. A missed task's
     * title repeats in the review (S1-5b: missed = due time passed),
     * so a bare text count is clock-dependent — the review row is
     * pinned by its testTag instead and subtracted.
     */
    private fun nonReviewCount(text: String, taskId: String): Int {
        val total = composeRule.onAllNodesWithText(text).fetchSemanticsNodes().size
        val inReview = composeRule.onAllNodesWithTag("weekly_review_row_$taskId")
            .fetchSemanticsNodes().size
        return total - inReview
    }

    @Test
    fun `a timed task appears in Timeline`() {
        // Seed a task due today at 15:00 through the repository, then
        // render: the Timeline section must exist and list it.
        val due = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 15)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val dentist = runBlocking {
            repository.createTask(
                userId = repository.currentUserId(),
                title = "Dentist",
                dueEpochMillis = due
            )
        }
        setTodayContent()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Timeline")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Timeline").performScrollTo().assertIsDisplayed()
        // S1-2: My Day is Plan-picked only, so the timed task appears in
        // the Timeline ONLY (count 1, not 2 as in S1-1). S1-5b: once its
        // due time passes it also repeats in the review card's Missed
        // section, so count outside the review only. (No scroll-to-row
        // check here: Compose 1.7 has no onFirst() on the collection,
        // and onNodeWithText would throw once the review repeats it.)
        assertEquals(1, nonReviewCount("Dentist", dentist.id))
    }
}

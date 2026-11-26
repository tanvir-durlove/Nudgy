package com.nextgentool.nudgy.ui.screens

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.fetchSemanticsNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.model.TaskStatus
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
import com.nextgentool.nudgy.ui.components.TaskDetailSheet
import com.nextgentool.nudgy.ui.viewmodel.TaskDetailState
import com.nextgentool.nudgy.ui.viewmodel.TasksViewModel
import com.nextgentool.nudgy.ui.viewmodel.TodayViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
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
 * Sprint 1-5 — smoke tests for the This week section + the
 * estimate / actual editor (Features 17, 18).
 *
 * Manually-constructed ViewModels over real in-memory Room (no Hilt),
 * following the S1-2 / S1-4 patterns. The review tasks are seeded
 * relative to the CURRENT week (the ViewModel uses the real clock),
 * so the test holds on any day: a mid-week instant is always inside
 * [weekStart, now].
 *
 * Sprint 7-2a — Today shows the four This-week statboxes, not the
 * review card's task lists: the lists live on the WeeklyReview screen
 * (WeeklyReviewScreenTest), so these tests pin the counts here. The
 * Feature 18 actual-time display ("45 min" on a finished row) is
 * pinned on the WeeklyReview screen, where the card lives now.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class S15SmokeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var db: NudgyDatabase
    private lateinit var repository: TaskRepository
    private lateinit var tasksViewModel: TasksViewModel
    private lateinit var todayViewModel: TodayViewModel
    // Owns the ViewModels so tearDown can cancel their scopes before
    // the DB closes (same leak as DarkThemeTest, 2026-11-21).
    private val viewModelStore = ViewModelStore()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
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
        // S4-1 round 7 — drain in-flight Room IO before closing the DB.
        // clear() cancels the ViewModels' scopes, but a Room call already
        // running on a real IO thread (a ViewModel init sync, or a
        // never-ending Room Flow collection re-querying after a write)
        // does not notice the cancellation until it returns; closing the
        // DB first makes that call throw IllegalStateException, which the
        // global coroutines-test ExceptionCollector buffers and replays
        // into the next test's runTest enter() as UncaughtExceptionsBeforeTest
        // (the compose rule wraps every test method in runTest, so the
        // victim is the next compose test — BoardDragTest). 500ms is ample
        // for in-memory Room calls.
        Thread.sleep(500)
        db.close()
    }

    private val userId get() = repository.currentUserId()

    /** Monday 00:00 local of the current week. */
    private fun mondayOfThisWeek(): Long {
        val cal = Calendar.getInstance()
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        val diff = if (dow == Calendar.SUNDAY) -6 else Calendar.MONDAY - dow
        cal.add(Calendar.DAY_OF_MONTH, diff)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** A mid-week instant, always inside [weekStart, now]. */
    private fun midWeekInstant(): Long {
        val now = System.currentTimeMillis()
        val weekStart = mondayOfThisWeek()
        return minOf(weekStart + maxOf(1L, (now - weekStart) / 2), now)
    }

    /** The text of the node with this testTag, as "[text]". */
    private fun nodeText(tag: String): String {
        val node = composeRule.onNodeWithTag(tag).fetchSemanticsNode()
        return node.config[SemanticsProperties.Text].toString()
    }

    @Test
    fun `This week section shows the four statboxes with real tasks`() {
        val midWeek = midWeekInstant()
        val mondayDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(mondayOfThisWeek())
        // Missed needs a due day that is over: yesterday 09:00. On a
        // Monday there is no such day inside this week (the week just
        // started), so Missed stays 0 — the rate then reads 100%.
        val yesterdayNine = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, -1)
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val canMiss = yesterdayNine >= mondayOfThisWeek()
        runBlocking {
            val finished = repository.createTask(
                userId = userId,
                title = "Finished task",
                // No due date: it must not land in the Timeline or
                // Upcoming — the statboxes are the only place its
                // completion surfaces on Today.
                dueEpochMillis = TaskRepository.NO_DUE_DATE
            )
            repository.updateTask(
                finished.copy(
                    status = TaskStatus.DONE,
                    isCompleted = true,
                    completedAtEpochMillis = midWeek,
                    actualMinutes = 45
                )
            )
            if (canMiss) {
                repository.createTask(
                    userId = userId,
                    title = "Missed task",
                    dueEpochMillis = yesterdayNine
                )
            }
            val slipped = repository.createTask(
                userId = userId,
                title = "Slipped task",
                // No due date: the default due is "now", and under the
                // S1-5b missed rule (due time passed) a task due "now"
                // would also land in Missed. A slipped task needs no
                // due to slip.
                dueEpochMillis = TaskRepository.NO_DUE_DATE
            )
            repository.updateTask(slipped.copy(carriedOverFromDate = mondayDate))
        }
        var reviewClicked = false
        composeRule.setContent {
            TodayScreen(
                onPlanClick = {},
                onAllTasksClick = {},
                onWeeklyReviewClick = { reviewClicked = true },
                tasksViewModel = tasksViewModel,
                todayViewModel = todayViewModel
            )
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("this_week_section")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("this_week_section").assertExists()
        composeRule.onNodeWithText("This week").assertExists()
        assertTrue(nodeText("this_week_done_value").contains("[1]"))
        assertTrue(
            nodeText("this_week_missed_value").contains(if (canMiss) "[1]" else "[0]")
        )
        assertTrue(nodeText("this_week_carried_value").contains("[1]"))
        // Rate = finished / (finished + missed): 1/1 = 100% on a
        // Monday, 1/2 = 50% once a day of the week is over.
        assertTrue(
            nodeText("this_week_rate_value").contains(if (canMiss) "[50%]" else "[100%]")
        )
        // "See full statistics" opens the review screen.
        composeRule.onNodeWithTag("see_full_statistics")
            .performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(reviewClicked)
    }

    @Test
    fun `This week section shows zeros when nothing happened`() {
        composeRule.setContent {
            TodayScreen(
                onPlanClick = {},
                onAllTasksClick = {},
                tasksViewModel = tasksViewModel,
                todayViewModel = todayViewModel
            )
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("this_week_section")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("this_week_section").assertExists()
        composeRule.onNodeWithText("This week").assertExists()
        assertTrue(nodeText("this_week_done_value").contains("[0]"))
        assertTrue(nodeText("this_week_missed_value").contains("[0]"))
        assertTrue(nodeText("this_week_carried_value").contains("[0]"))
        assertTrue(nodeText("this_week_rate_value").contains("[0%]"))
        composeRule.onNodeWithTag("see_full_statistics").assertExists()
    }

    @Test
    fun `detail sheet estimate quick-pick and custom save call onUpdateTask`() {
        val task = TaskEntity(id = "t1", userId = "u1", title = "Timed task")
        val updates = mutableListOf<TaskEntity>()
        composeRule.setContent {
            TaskDetailSheet(
                detail = TaskDetailState(task = task),
                allTags = emptyList(),
                allProjects = emptyList(),
                onUpdateTask = { updates.add(it) },
                onDeleteTask = {},
                onSetTags = { _, _ -> },
                onCreateTag = { _, _ -> },
                onDeleteTag = {},
                onRenameTag = { _, _ -> },
                onCreateProject = { _, _ -> },
                onDeleteProject = {},
                onRenameProject = { _, _ -> },
                onAddSubtask = { _, _ -> },
                onToggleSubtask = {},
                onDeleteSubtask = {},
                onDismiss = {}
            )
        }
        // Sheet items for a top-level personal task: 0 header, 1 title,
        // 2 notes, 3 Due, 4 Reminder, 5 Recurrence, 6 Estimate, 7 Actual.
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("task_detail_sheet_list").performScrollToIndex(6)
        composeRule.onNodeWithText("Estimate").assertIsDisplayed()
        composeRule.onNodeWithText("15 min").assertIsDisplayed()
        composeRule.onNodeWithText("30 min").assertIsDisplayed()
        composeRule.onNodeWithText("60 min").assertIsDisplayed()
        composeRule.onNodeWithTag("estimate_pick_30").performClick()
        composeRule.waitForIdle()
        assertEquals(30, updates.last().estimatedMinutes)
        // Custom: 45 minutes via the number field.
        composeRule.onNodeWithTag("estimate_pick_custom").performClick()
        composeRule.onNodeWithTag("estimate_custom_field").performTextInput("45")
        composeRule.onNodeWithTag("estimate_custom_save").performClick()
        composeRule.waitForIdle()
        assertEquals(45, updates.last().estimatedMinutes)
    }

    @Test
    fun `detail sheet actual time saves the hand-written minutes`() {
        val task = TaskEntity(id = "t1", userId = "u1", title = "Timed task")
        val updates = mutableListOf<TaskEntity>()
        composeRule.setContent {
            TaskDetailSheet(
                detail = TaskDetailState(task = task),
                allTags = emptyList(),
                allProjects = emptyList(),
                onUpdateTask = { updates.add(it) },
                onDeleteTask = {},
                onSetTags = { _, _ -> },
                onCreateTag = { _, _ -> },
                onDeleteTag = {},
                onRenameTag = { _, _ -> },
                onCreateProject = { _, _ -> },
                onDeleteProject = {},
                onRenameProject = { _, _ -> },
                onAddSubtask = { _, _ -> },
                onToggleSubtask = {},
                onDeleteSubtask = {},
                onDismiss = {}
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("task_detail_sheet_list").performScrollToIndex(7)
        composeRule.onNodeWithText("Actual time").assertIsDisplayed()
        composeRule.onNodeWithTag("actual_minutes_field").performTextInput("45")
        composeRule.onNodeWithTag("actual_minutes_save").performClick()
        composeRule.waitForIdle()
        assertEquals(45, updates.last().actualMinutes)
    }
}

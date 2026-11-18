package com.nextgentool.nudgy.ui.screens

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 1-5 — smoke tests for the Weekly review card + the
 * estimate / actual editor (Features 17, 18).
 *
 * Manually-constructed ViewModels over real in-memory Room (no Hilt),
 * following the S1-2 / S1-4 patterns. The review tasks are seeded
 * relative to the CURRENT week (the ViewModel uses the real clock),
 * so the test holds on any day: a mid-week instant is always inside
 * [weekStart, now].
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
    }

    @After
    fun tearDown() {
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

    @Test
    fun `Weekly review card renders the three sections with real tasks`() {
        val midWeek = midWeekInstant()
        val mondayDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(mondayOfThisWeek())
        // Missed needs a due day that is over: yesterday 09:00. On a
        // Monday there is no such day inside this week (the week just
        // started), so the test then pins the empty state instead.
        val yesterdayNine = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, -1)
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val canMiss = yesterdayNine >= mondayOfThisWeek()
        val finished: TaskEntity
        val missed: TaskEntity?
        val slipped: TaskEntity
        runBlocking {
            finished = repository.createTask(
                userId = userId,
                title = "Finished task",
                // No due date: it must not land in the Timeline or
                // Upcoming too, or the title would match twice.
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
            missed = if (canMiss) {
                repository.createTask(
                    userId = userId,
                    title = "Missed task",
                    dueEpochMillis = yesterdayNine
                )
            } else {
                null
            }
            slipped = repository.createTask(userId, "Slipped task")
            repository.updateTask(slipped.copy(carriedOverFromDate = mondayDate))
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
            composeRule.onAllNodesWithText("Add task")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("weekly_review_card").performScrollTo()
        composeRule.onNodeWithText("Weekly review").assertIsDisplayed()
        composeRule.onNodeWithText("Finished").assertIsDisplayed()
        // Pin the rows by testTag: the titles can also appear in the
        // Timeline / carry-over banner, so a bare text match would
        // throw on duplicates.
        composeRule.onNodeWithTag("weekly_review_row_${finished.id}").assertExists()
        // Feature 18: the hand-written actual surfaces on the row.
        composeRule.onNodeWithText("45 min").assertIsDisplayed()
        composeRule.onNodeWithText("Missed").assertIsDisplayed()
        if (canMiss) {
            composeRule.onNodeWithTag("weekly_review_row_${missed!!.id}").assertExists()
        } else {
            // Monday: no day of this week is over yet, so nothing can
            // be missed — the empty state is the correct behaviour.
            composeRule.onNodeWithText("Nothing missed").assertIsDisplayed()
        }
        composeRule.onNodeWithText("Slipped").assertIsDisplayed()
        composeRule.onNodeWithTag("weekly_review_row_${slipped.id}").assertExists()
    }

    @Test
    fun `Weekly review card renders empty sections when nothing happened`() {
        composeRule.setContent {
            TodayScreen(
                onPlanClick = {},
                onAllTasksClick = {},
                tasksViewModel = tasksViewModel,
                todayViewModel = todayViewModel
            )
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Add task")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("weekly_review_card").performScrollTo()
        composeRule.onNodeWithText("Weekly review").assertIsDisplayed()
        composeRule.onNodeWithText("Nothing finished yet").assertIsDisplayed()
        composeRule.onNodeWithText("Nothing missed").assertIsDisplayed()
        composeRule.onNodeWithText("Nothing slipped").assertIsDisplayed()
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

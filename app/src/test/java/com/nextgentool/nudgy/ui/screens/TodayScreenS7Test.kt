package com.nextgentool.nudgy.ui.screens

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.local.NudgyDatabase
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
import com.nextgentool.nudgy.ui.viewmodel.TasksViewModel
import com.nextgentool.nudgy.ui.viewmodel.TodayViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
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
 * Sprint 7-2a — the Today rebuild (spec §4, prototype scr-today).
 *
 * Renders Today with MANUALLY-constructed ViewModels (real in-memory
 * Room repository, no Hilt), copying the MatrixInTodayTest setup, and
 * pins the prototype shape: hero (title + date line + 104px donut +
 * Plan / Focus / search), quick-add card (label + field + Save, no
 * "+" icon), All tasks / Templates ghost buttons, My Day as a plain
 * list (no List / Board / Matrix switcher), the Timeline rail (time
 * + pip + block, NOW pill, Anytime group) and the This week
 * statboxes (four counts + "See full statistics" to the review).
 *
 * Navigation wiring (Templates button → Templates route) is verified
 * by reading + CI compile, not by rendering the real NavHost — there
 * is no Hilt test infra and the NavHost calls hiltViewModel().
 * Below-fold nodes use assertExists (never assertIsDisplayed), and
 * the below-fold button is clicked via its semantics action (a
 * coordinate click cannot reach it on Robolectric's short viewport).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TodayScreenS7Test {

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
        // S4-1 round 7 — drain in-flight Room IO before closing the DB.
        Thread.sleep(500)
        db.close()
    }

    private fun setTodayContent(
        onWeeklyReviewClick: () -> Unit = {},
        onFocusClick: () -> Unit = {},
        onMatrixClick: () -> Unit = {}
    ) {
        composeRule.setContent {
            TodayScreen(
                onPlanClick = {},
                onAllTasksClick = {},
                onTemplatesClick = {},
                onFocusClick = onFocusClick,
                onMatrixClick = onMatrixClick,
                onWeeklyReviewClick = onWeeklyReviewClick,
                tasksViewModel = tasksViewModel,
                todayViewModel = todayViewModel
            )
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Add task")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** The text of the node with this testTag, as "[text]". */
    private fun nodeText(tag: String): String {
        val node = composeRule.onNodeWithTag(tag).fetchSemanticsNode()
        return node.config[SemanticsProperties.Text].toString()
    }

    /** Monday 00:00 local of the current week (S15 smoke test). */
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

    @Test
    fun `hero matches the prototype — title, date, donut, Plan my day, quick-add card, ghost buttons`() {
        setTodayContent()

        // Hero: title + date line + donut + actions.
        composeRule.onNodeWithText("Make today count.").assertExists()
        val todayLabel = SimpleDateFormat("EEEE, MMM d", Locale.getDefault())
            .format(Date())
        composeRule.onNodeWithText(todayLabel, substring = true).assertExists()
        composeRule.onNodeWithTag("progress_ring").assertExists()
        composeRule.onNodeWithText("Plan my day").assertExists()
        composeRule.onNodeWithText("Focus").assertExists()
        composeRule.onNodeWithTag("search_button").assertExists()
        // The ring shows 0% on an empty day, with the "of My Day" line.
        // Scoped to the donut: the This-week rate statbox also shows
        // "0%" on an empty day, so a bare global "0%" matches two nodes.
        assertTrue(nodeText("progress_ring").contains("0%"))
        composeRule.onNodeWithText("of My Day").assertExists()
        // Quick-add card: label + field + Save (no "+" icon here —
        // the icon belongs to the Tasks screen's variant).
        composeRule.onNodeWithText("QUICK ADD — DUE TODAY").assertExists()
        composeRule.onNodeWithTag("quick_add_field").assertExists()
        composeRule.onNodeWithTag("quick_add_save_button").assertExists()
        composeRule.onNodeWithContentDescription("Add task").assertDoesNotExist()
        // Ghost buttons.
        composeRule.onNodeWithTag("all_tasks_button").assertExists()
        composeRule.onNodeWithText("All tasks").assertExists()
        composeRule.onNodeWithTag("templates_button").assertExists()
        composeRule.onNodeWithText("Templates").assertExists()
        // My Day has no switcher: the section renders as a plain list.
        composeRule.onNodeWithTag("my_day_section").assertExists()
        composeRule.onNodeWithText("My Day").assertExists()
        composeRule.onNodeWithTag("my_day_view_mode_list").assertDoesNotExist()
        composeRule.onNodeWithTag("my_day_view_mode_board").assertDoesNotExist()
        composeRule.onNodeWithTag("my_day_view_mode_matrix").assertDoesNotExist()
        composeRule.onNodeWithTag("eisenhower_matrix").assertDoesNotExist()
        // Nudge shows while nothing is planned.
        composeRule.onNodeWithTag("plan_nudge_card").assertExists()
        composeRule.onNodeWithText("Plan your day?").assertExists()
    }

    @Test
    fun `This week section shows the four prototype statboxes from real review data`() {
        // 1 finished + 1 slipped this week (missed needs a past day
        // inside this week, so it stays 0 — same shape as the S15
        // smoke test's Monday branch). Rate = 1 / (1 + 0) = 100%.
        val mondayDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(mondayOfThisWeek())
        runBlocking {
            val finished = repository.createTask(
                userId = repository.currentUserId(),
                title = "Finished task",
                dueEpochMillis = TaskRepository.NO_DUE_DATE
            )
            repository.updateTask(
                finished.copy(
                    status = TaskStatus.DONE,
                    isCompleted = true,
                    completedAtEpochMillis = System.currentTimeMillis()
                )
            )
            val slipped = repository.createTask(
                userId = repository.currentUserId(),
                title = "Slipped task",
                dueEpochMillis = TaskRepository.NO_DUE_DATE
            )
            repository.updateTask(slipped.copy(carriedOverFromDate = mondayDate))
        }
        var reviewClicked = false
        setTodayContent(onWeeklyReviewClick = { reviewClicked = true })
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("this_week_section")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("this_week_section").assertExists()
        composeRule.onNodeWithText("This week").assertExists()
        assertTrue(nodeText("this_week_done_value").contains("[1]"))
        assertTrue(nodeText("this_week_missed_value").contains("[0]"))
        assertTrue(nodeText("this_week_carried_value").contains("[1]"))
        assertTrue(nodeText("this_week_rate_value").contains("[100%]"))
        // "See full statistics" opens the review screen.
        composeRule.onNodeWithTag("see_full_statistics")
            .performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(reviewClicked)
    }

    @Test
    fun `Timeline renders the prototype rail — time, block, NOW line, Anytime`() {
        // A timed task with an estimate (15:00, 45 min → block) + an
        // untimed task (Anytime group).
        val due = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 15)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val startOfToday = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        runBlocking {
            val timed = repository.createTask(
                userId = repository.currentUserId(),
                title = "Dentist",
                dueEpochMillis = due
            )
            repository.updateTask(
                timed.copy(
                    plannedStartEpochMillis = due,
                    estimatedMinutes = 45
                )
            )
            repository.createTask(
                userId = repository.currentUserId(),
                title = "Buy milk",
                dueEpochMillis = startOfToday
            )
        }
        setTodayContent()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Timeline")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("timeline_section").assertExists()
        composeRule.onNodeWithText("Timeline").assertExists()
        composeRule.onNodeWithText("Dentist").assertExists()
        // The block's meta line: range + duration.
        composeRule.onNodeWithText("3:00 PM – 3:45 PM · 45 min", substring = true)
            .assertExists()
        // The NOW line pill (uppercase, prototype .tl-now).
        composeRule.onNodeWithText("NOW ·", substring = true).assertExists()
        // Anytime group for the untimed task.
        composeRule.onNodeWithText("Anytime").assertExists()
        composeRule.onNodeWithText("Buy milk").assertExists()
    }

    /**
     * Sprint 7-2b — "More in Nudgy" (prototype scr-today): the three
     * tiles below the This-week card, with the prototype's icon +
     * title + subtitle on each. The tiles sit below the fold, so
     * assertExists (never assertIsDisplayed) pins them.
     */
    @Test
    fun `More in Nudgy shows the three prototype tiles`() {
        setTodayContent()

        composeRule.onNodeWithTag("more_in_nudgy_section").assertExists()
        composeRule.onNodeWithText("More in Nudgy").assertExists()
        composeRule.onNodeWithTag("more_tile_focus").assertExists()
        composeRule.onNodeWithText("Focus timer").assertExists()
        composeRule.onNodeWithText("25-minute Pomodoro").assertExists()
        composeRule.onNodeWithTag("more_tile_habits").assertExists()
        composeRule.onNodeWithText("Habits").assertExists()
        composeRule.onNodeWithText("Streaks that stick").assertExists()
        composeRule.onNodeWithTag("more_tile_matrix").assertExists()
        composeRule.onNodeWithText("Priority matrix").assertExists()
        composeRule.onNodeWithText("Urgent vs important").assertExists()
        // Disclosed deviations (decided by Tanvir 2026-11-27): the
        // Widget tile and the Share-preview button are NOT built —
        // neither has an honest destination.
        composeRule.onNodeWithTag("more_tile_widget").assertDoesNotExist()
        composeRule.onNodeWithText("Share to Nudgy", substring = true)
            .assertDoesNotExist()
    }

    /**
     * Sprint 7-2b — the Focus tile calls the same onFocusClick the
     * hero's Focus button uses. The NavHost wiring itself (callback
     * → Focus route) is verified by reading + CI compile, not by
     * rendering the real NavHost — same rule as the Templates button
     * (class KDoc).
     */
    @Test
    fun `Focus tile calls onFocusClick`() {
        var focused = false
        setTodayContent(onFocusClick = { focused = true })

        // Below the fold: a coordinate click cannot reach the tile
        // on Robolectric's short viewport — click via its semantics
        // action instead (Matrix lesson, PR #140).
        composeRule.onNodeWithTag("more_tile_focus")
            .performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(focused)
    }

    /**
     * Sprint 7-2b — the Matrix tile calls onMatrixClick, which the
     * NavHost wires to the Matrix route (PR #140). This is the first
     * button that reaches the Matrix screen at all.
     */
    @Test
    fun `Matrix tile calls onMatrixClick`() {
        var matrixOpened = false
        setTodayContent(onMatrixClick = { matrixOpened = true })

        // Below the fold: semantics action, not a coordinate click
        // (Matrix lesson, PR #140).
        composeRule.onNodeWithTag("more_tile_matrix")
            .performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(matrixOpened)
    }
}

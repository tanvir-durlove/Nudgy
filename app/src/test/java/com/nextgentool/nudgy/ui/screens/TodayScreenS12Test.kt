package com.nextgentool.nudgy.ui.screens

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.local.entity.CalendarEventEntity
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
 * Sprint 1-2 — Today + Upcoming + My Day + Timeline + Carry-over
 * (Features 4, 15, 16). Renders Today with MANUALLY-constructed
 * ViewModels (real in-memory Room repository, no Hilt) and pins the
 * acceptance criteria: My Day is Plan-picked only, the Timeline is
 * the day's full schedule (blocks / points / events + teal now-line),
 * Upcoming groups the next 7 days plus "No date", the carry-over
 * banner moves tasks to today, and the ring matches real completion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TodayScreenS12Test {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var db: NudgyDatabase
    private lateinit var repository: TaskRepository
    private lateinit var tasksViewModel: TasksViewModel
    private lateinit var todayViewModel: TodayViewModel
    // Owns the ViewModels so tearDown can cancel their scopes before
    // the DB closes (same leak as DarkThemeTest, 2026-11-21).
    private val viewModelStore = ViewModelStore()

    private fun at(dayOffset: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, dayOffset)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

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

    private fun setTodayContent() {
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
    }

    private fun createTask(title: String, due: Long) = runBlocking {
        repository.createTask(
            userId = repository.currentUserId(),
            title = title,
            dueEpochMillis = due
        )
    }

    private fun planTask(title: String, plannedStart: Long, estimatedMinutes: Int) =
        runBlocking {
            val created = repository.createTask(
                userId = repository.currentUserId(),
                title = title,
                // Date-only due (local midnight): the slot comes from
                // plannedStart, and a date-only today task is never
                // missed (S1-5b) — so its title stays exactly two
                // renderings (My Day + Timeline), never a third in
                // the review card, whatever time the test runs at.
                dueEpochMillis = startOfTodayMillis()
            )
            val today = java.text.SimpleDateFormat(
                "yyyy-MM-dd", java.util.Locale.getDefault()
            ).format(System.currentTimeMillis())
            repository.updateTask(
                created.copy(
                    plannedDate = today,
                    plannedStartEpochMillis = plannedStart,
                    estimatedMinutes = estimatedMinutes
                )
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
    fun `My Day shows planned tasks, Timeline shows timed tasks and events`() {
        planTask("Planned task", at(0, 9), 60)
        val timed = createTask("Timed task", at(0, 15))
        createTask("Untimed task", startOfTodayMillis())
        runBlocking {
            db.calendarDao().insertEvent(
                CalendarEventEntity(
                    userId = repository.currentUserId(),
                    title = "Team meeting",
                    startEpochMillis = at(0, 12),
                    endEpochMillis = at(0, 13)
                )
            )
        }
        setTodayContent()
        // The planned task is in My Day AND in the Timeline (as a block).
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Planned task")
                .fetchSemanticsNodes().size == 2
        }
        composeRule.onAllNodesWithText("Planned task").assertCountEquals(2)
        // The timed task is in the Timeline only (My Day is picked-only).
        // S1-5b: once its due time passes it also repeats in the review
        // card's Missed section, so count outside the review only.
        assertEquals(1, nonReviewCount("Timed task", timed.id))
        // The untimed task is in the Timeline's Anytime group.
        composeRule.onNodeWithText("Anytime").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("Untimed task").assertCountEquals(1)
        // The event and the now-line are on the Timeline.
        composeRule.onNodeWithText("Team meeting").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Now", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Timeline").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `carry-over banner shows the count and Move all moves tasks to today`() {
        val overdue = createTask("Overdue task", at(-1, 14))
        setTodayContent()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("1 task carried over")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("1 task carried over").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("carry_over_move_all").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("1 task carried over")
                .fetchSemanticsNodes().isEmpty()
        }
        val moved = runBlocking {
            repository.getAllTasksOnce(repository.currentUserId())
                .first { it.id == overdue.id }
        }
        assertTrue(moved.dueDateEpochMillis in startOfTodayMillis() until startOfTodayMillis() + 24 * 60 * 60 * 1000L)
        assertEquals(
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                .format(at(-1, 14)),
            moved.carriedOverFromDate
        )
    }

    @Test
    fun `Upcoming tab groups by day and lists no-date tasks`() {
        val todayTask = createTask("Today task", at(0, 11))
        createTask("Tomorrow task", at(1, 11))
        createTask("No-date task", TaskRepository.NO_DUE_DATE)
        setTodayContent()
        composeRule.onNodeWithTag("tab_upcoming").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Tomorrow")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Tomorrow").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Tomorrow task").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("No date").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("No-date task").performScrollTo().assertIsDisplayed()
        // Rule B: unpicked today-due tasks are ALSO listed in Upcoming
        // under "Today" — they never vanish from the app. "Today" is
        // two nodes (the Today tab + the day-group header), so count.
        composeRule.onAllNodesWithText("Today").assertCountEquals(2)
        // S1-5b: once its due time (11:00) passes, "Today task" also
        // repeats in the review card's Missed section, so a bare
        // single match would throw — count outside the review instead.
        // (No scroll-to-row check: Compose 1.7 has no onFirst(), and
        // onNodeWithText throws on two matches once the review
        // repeats it.)
        assertEquals(1, nonReviewCount("Today task", todayTask.id))
    }

    @Test
    fun `nudge card shows when nothing is planned and hides after planning`() {
        setTodayContent()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("আজকের দিন সাজাবে?")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("আজকের দিন সাজাবে?").assertIsDisplayed()
        planTask("Planned task", at(0, 9), 60)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("আজকের দিন সাজাবে?")
                .fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun `ring shows 0 percent on an empty day without crashing`() {
        setTodayContent()
        // "0%" is in the hero from the first composition (loading shows
        // 0% too), so it cannot prove the load finished — wait for the
        // empty-day text itself before asserting it is displayed.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Nothing planned for today yet")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("0%").assertIsDisplayed()
        // Below the fold on Robolectric's short viewport (hero + nudge
        // + quick-add + All tasks + tabs sit above it) — scroll first;
        // on a real phone it is on first paint.
        composeRule.onNodeWithText("Nothing planned for today yet").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `toggling a task updates the ring`() {
        val first = createTask("First", at(0, 9))
        createTask("Second", at(0, 10))
        setTodayContent()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("0 of 2 done")
                .fetchSemanticsNodes().isNotEmpty()
        }
        todayViewModel.toggleTask(first)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("1 of 2 done")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("1 of 2 done").performScrollTo().assertIsDisplayed()
    }
}

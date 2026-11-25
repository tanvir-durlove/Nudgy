package com.nextgentool.nudgy.ui.screens

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
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
import com.nextgentool.nudgy.ui.viewmodel.TasksViewModel
import com.nextgentool.nudgy.ui.viewmodel.TodayViewModel
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Step 2d — the global search entry point (S6-4, Q5): the magnifier in
 * the Today hero card's action row opens the search screen.
 *
 * Renders Today with MANUALLY-constructed ViewModels (real in-memory
 * Room repository, no Hilt — the TodayScreenS12Test pattern).
 *
 * What this test proves, and what it does NOT: tapping the magnifier
 * fires the onSearchClick callback the NavHost wires to
 * NudgyRoute.Search, and the hero card's existing actions ("Plan my
 * day", "Focus") still render. It does NOT render the real NudgyNavHost
 * — the NavHost calls hiltViewModel() and there is no Hilt test infra
 * in this repo — so the one-line NavHost wiring (plain navigate, same
 * as the sibling Plan/Focus/WeeklyReview entries in the same
 * destination) is verified by reading + CI compile, not by this test.
 *
 * Harness rules, all proven in this repo — do NOT "fix" them:
 * - NO performScrollTo(): it hangs the test worker on this harness
 *   (OnboardingScreenTest KDoc, runs 851/852). The magnifier sits in
 *   the hero card at the top, so no scrolling is needed.
 * - assertExists (not assertIsDisplayed): the safe choice on this
 *   harness for anything that can sit below the fold.
 * - The magnifier's testTag lives ON the IconButton itself — a
 *   testTag inside a clickable is invisible in the merged tree.
 * - Text is read the PlanScreenTest way: fetchSemanticsNode() is a
 *   rule MEMBER (not a top-level import) and the text is matched by
 *   containment on SemanticsProperties.Text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TodayScreenSearchEntryTest {

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

    private fun setTodayContent(onSearchClick: () -> Unit) {
        composeRule.setContent {
            TodayScreen(
                onPlanClick = {},
                onAllTasksClick = {},
                onSearchClick = onSearchClick,
                tasksViewModel = tasksViewModel,
                todayViewModel = todayViewModel
            )
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Add task")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `tapping the search magnifier fires onSearchClick and the header actions still render`() {
        var searchClicked = false
        setTodayContent(onSearchClick = { searchClicked = true })

        // The entry point itself (testTag ON the IconButton).
        composeRule.onNodeWithTag("search_button").assertExists()
        // Regression: the hero card's existing actions are untouched.
        composeRule.onNodeWithText("Plan my day").assertExists()
        composeRule.onNodeWithText("Focus").assertExists()

        composeRule.onNodeWithTag("search_button").performClick()
        assertTrue("tapping the magnifier should fire onSearchClick", searchClicked)
    }

    @Test
    fun `search magnifier carries the Search content description`() {
        setTodayContent(onSearchClick = {})

        val node = composeRule.onNodeWithTag("search_button").fetchSemanticsNode()
        val descriptions = node.config[SemanticsProperties.ContentDescription].toString()
        assertTrue(
            "magnifier should describe itself as Search, was: $descriptions",
            descriptions.contains("Search")
        )
    }
}

package com.nextgentool.nudgy.ui.screens

import android.content.Context
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.todayDateString
import com.nextgentool.nudgy.domain.usecase.CarryOverUseCase
import com.nextgentool.nudgy.domain.usecase.PlanDayUseCase
import com.nextgentool.nudgy.ui.viewmodel.PlanViewModel
import com.nextgentool.nudgy.ui.viewmodel.startOfTodayMillis
import java.util.Calendar
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Sprint 1-2 — Plan My Day (Feature 14, Q1 + Q2 + Q7 + Q8). Renders
 * Plan with a MANUALLY-constructed PlanViewModel (real in-memory Room
 * repository, no Hilt) and pins the acceptance criteria: the auto
 * draft is carry-over first within the 8h estimate budget, "Start my
 * day" stamps plannedDate + plannedStart (and moves carried-over tasks
 * to today), and doing Plan a second time shows the previous picks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlanScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var db: NudgyDatabase
    private lateinit var repository: TaskRepository
    private lateinit var viewModel: PlanViewModel

    private fun at(dayOffset: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, dayOffset)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun createTask(title: String, due: Long, estimatedMinutes: Int? = null) =
        runBlocking {
            repository.createTask(
                userId = repository.currentUserId(),
                title = title,
                dueEpochMillis = due,
                estimatedMinutes = estimatedMinutes
            )
        }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NudgyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = TaskRepository(context, db)
        viewModel = PlanViewModel(
            repository = repository,
            planDayUseCase = PlanDayUseCase(),
            carryOverUseCase = CarryOverUseCase()
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun setPlanContent(onBackClick: () -> Unit = {}) {
        composeRule.setContent {
            PlanScreen(onBackClick = onBackClick, viewModel = viewModel)
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Today's plan")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun topOf(text: String): Float =
        composeRule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    /**
     * Pump the (paused, in Robolectric) main looper until [condition]
     * holds or the timeout hits. viewModelScope work (startMyDay's Room
     * writes) resumes on Dispatchers.Main after its IO hops — and a
     * waitUntil whose condition never touches the Compose tree does not
     * pump the looper, so the coroutine stalls until the timeout (CI
     * #44183041137: the DB-polling waitUntils timed out while the
     * tree-polling ones passed). Idling the looper between short sleeps
     * drives it to completion.
     */
    private fun pumpMainLooperUntil(
        timeoutMillis: Long = 10_000,
        condition: () -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(50)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(condition())
    }

    /**
     * Wait until the draft has FULLY settled: the budget line AND the
     * No-est task's row are both present. The budget alone is not
     * enough — No-est carries no estimate, so it contributes 0 to the
     * budget and the line can appear before its row streams in. The
     * draft re-seeds per emission; starting the day or asserting order
     * on a half-streamed draft stamps / checks only the tasks seen so
     * far.
     */
    private fun waitForFullDraft() {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("3h 0m of 8h planned")
                .fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText("No-est task")
                    .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun seedPlanData() {
        // Carry-over (120 min) + today 60 min + today 500 min (over the
        // 8h budget once the first two are picked) + no-estimate task.
        createTask("Old report", at(-1, 14), estimatedMinutes = 120)
        createTask("Dentist", at(0, 15), estimatedMinutes = 60)
        createTask("Big project", at(0, 10), estimatedMinutes = 500)
        createTask("No-est task", startOfTodayMillis(), estimatedMinutes = null)
    }

    @Test
    fun `draft puts carry-over first and respects the 8h budget`() {
        seedPlanData()
        setPlanContent()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Old report")
                .fetchSemanticsNodes().isNotEmpty()
        }
        // Wait for the FULL draft, not just the first task (see
        // waitForFullDraft): asserting order while rows are still
        // streaming in is a coin flip.
        waitForFullDraft()
        // Carry-over first, then today-due in due order.
        assertTrue(topOf("Old report") < topOf("Dentist"))
        assertTrue(topOf("Dentist") < topOf("No-est task"))
        // 120 + 60 = 180 min picked; the 500-min task no longer fits.
        composeRule.onNodeWithText("3h 0m of 8h planned").assertIsDisplayed()
        composeRule.onNodeWithText("Big project").performScrollTo().assertIsDisplayed()
        // The over-budget task is present (in "Not planned"), not hidden.
        composeRule.onNodeWithText("Not planned").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `Start my day stamps the plan and navigates back`() {
        seedPlanData()
        var backCalled = false
        setPlanContent(onBackClick = { backCalled = true })
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Old report")
                .fetchSemanticsNodes().isNotEmpty()
        }
        // Full draft first (see waitForFullDraft): starting the day on a
        // half-streamed draft would stamp only the tasks seen so far.
        waitForFullDraft()
        composeRule.onNodeWithTag("plan_start_button").performScrollTo().performClick()
        // Drive the Main looper (Room writes + appliedFlow), then let
        // Compose settle (recomposition + the LaunchedEffect that fires
        // onBackClick) before asserting the navigation happened.
        pumpMainLooperUntil { viewModel.uiState.value.planApplied }
        composeRule.waitForIdle()
        assertTrue(backCalled)
        val tasks = runBlocking {
            repository.getAllTasksOnce(repository.currentUserId())
        }
        val today = todayDateString()
        val oldReport = tasks.first { it.title == "Old report" }
        val dentist = tasks.first { it.title == "Dentist" }
        val bigProject = tasks.first { it.title == "Big project" }
        val noEst = tasks.first { it.title == "No-est task" }
        // Picked tasks are planned for today.
        assertEquals(today, oldReport.plannedDate)
        assertEquals(today, dentist.plannedDate)
        assertEquals(today, noEst.plannedDate)
        // The carried-over task moved: due is today, origin recorded.
        assertTrue(
            oldReport.dueDateEpochMillis in startOfTodayMillis() until
                startOfTodayMillis() + 24 * 60 * 60 * 1000L
        )
        assertEquals(
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                .format(at(-1, 14)),
            oldReport.carriedOverFromDate
        )
        // The dentist keeps its due time as its planned start.
        assertEquals(at(0, 15), dentist.plannedStartEpochMillis)
        // The unpicked task is not planned.
        assertNull(bigProject.plannedDate)
        assertNull(bigProject.plannedStartEpochMillis)
    }

    @Test
    fun `second Plan shows the previous picks`() {
        seedPlanData()
        // One setContent per test (the rule throws on a second call):
        // the ViewModel is swapped through state instead.
        var currentViewModel by mutableStateOf(viewModel)
        composeRule.setContent {
            PlanScreen(onBackClick = {}, viewModel = currentViewModel)
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Old report")
                .fetchSemanticsNodes().isNotEmpty()
        }
        // Full draft first (see waitForFullDraft).
        waitForFullDraft()
        // Pick the over-budget task by hand, then start the day. A fresh
        // draft would never pick it (500 min does not fit the 8h budget
        // after the first two), so the second Plan proves the previous
        // picks are remembered — not re-derived from the budget.
        composeRule.onNodeWithTag("plan_row_Big project").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("11h 20m of 8h planned")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("plan_start_button").performScrollTo().performClick()
        // Wait for the apply to land: the Big project now has a plan.
        // (Pump the main looper explicitly — see pumpMainLooperUntil:
        // a DB-polling waitUntil does not drive viewModelScope work.)
        pumpMainLooperUntil {
            runBlocking {
                repository.getAllTasksOnce(repository.currentUserId())
                    .first { it.title == "Big project" }.plannedDate != null
            }
        }
        // A fresh ViewModel on the same repository: the previous picks
        // are seeded as picked again — the 500-min task stays picked
        // even though the budget is blown.
        val secondViewModel = PlanViewModel(
            repository = repository,
            planDayUseCase = PlanDayUseCase(),
            carryOverUseCase = CarryOverUseCase()
        )
        composeRule.runOnIdle { currentViewModel = secondViewModel }
        // Let the swap's recomposition settle (the new ViewModel starts
        // in loading) so the wait below cannot pass on the first
        // ViewModel's still-composed "11h 20m" line.
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("11h 20m of 8h planned")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("11h 20m of 8h planned").assertIsDisplayed()
        composeRule.onNodeWithText("Big project").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `move down reorders the plan`() {
        seedPlanData()
        setPlanContent()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Old report")
                .fetchSemanticsNodes().isNotEmpty()
        }
        // Full draft first (see waitForFullDraft).
        waitForFullDraft()
        assertTrue(topOf("Old report") < topOf("Dentist"))
        composeRule.onNodeWithContentDescription("Move Old report down")
            .performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            topOf("Dentist") < topOf("Old report")
        }
        assertTrue(topOf("Dentist") < topOf("Old report"))
    }
}

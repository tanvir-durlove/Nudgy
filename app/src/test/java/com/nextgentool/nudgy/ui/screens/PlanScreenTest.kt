package com.nextgentool.nudgy.ui.screens

import android.content.Context
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModelStore
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.dayLabel
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
 *
 * Sprint 7 — rebuilt to the prototype (scr-plan): intro line, cream
 * carry banner (live count, hidden when empty), Time-budget card
 * ("{Xh Ym} of 8h max" + 9px bar), "Draft — in order", "Start my day"
 * 52dp + the tiny note. Below-fold clicks use performSemanticsAction
 * (SemanticsActions.OnClick) — never coordinate performClick, and
 * never performScrollTo: on this harness it hangs the test worker
 * (runs 851/852); below-fold assertions use assertExists.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlanScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var db: NudgyDatabase
    private val viewModelStore = ViewModelStore()
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
        ).also { viewModelStore.put("plan", it) }
    }

    @After
    fun tearDown() {
        // PR #86 rule: cancel the ViewModel scopes before the database
        // closes, so no viewModelScope coroutine can run Room work
        // against the closed DB and poison the next test's TestScope.
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

    private fun setPlanContent(onBackClick: () -> Unit = {}) {
        composeRule.setContent {
            PlanScreen(onBackClick = onBackClick, viewModel = viewModel)
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Draft — in order")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Index of the first text node containing [title] in the semantics
     * tree's traversal order, which follows layout order. -1 if no such
     * node exists yet. (Merged nodes can concatenate a row's title and
     * detail into one text, so this matches by containment, not
     * equality. Note: onAllNodes is a member of the rule — it takes NO
     * import — and SemanticsConfiguration has no getOrNull in Compose
     * 1.7, hence contains + [] below.)
     */
    private fun traversalIndex(title: String): Int {
        val textNodes = SemanticsMatcher("node with text") { node ->
            node.config.contains(SemanticsProperties.Text)
        }
        val texts = composeRule.onAllNodes(textNodes).fetchSemanticsNodes()
            .map {
                if (it.config.contains(SemanticsProperties.Text)) {
                    // toString() on purpose: the Text property's value
                    // type differs across Compose versions (String vs
                    // AnnotatedString) — its string form is the text
                    // either way.
                    it.config[SemanticsProperties.Text].toString()
                } else {
                    ""
                }
            }
        return texts.indexOfFirst { it.contains(title) }
    }

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
            composeRule.onAllNodesWithText("3h 0m of 8h max")
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
        // Prove the ViewModel's order itself before asserting the
        // rendered order: the tree can only prove the rows exist, and a
        // half-streamed draft can show all four rows with the wrong
        // membership (No-est contributes 0 to the budget, so the budget
        // line cannot prove it is picked).
        pumpMainLooperUntil {
            val state = viewModel.uiState.value
            state.pickedRows.map { it.task.title } ==
                listOf("Old report", "Dentist", "No-est task") &&
                state.unpickedRows.map { it.task.title } == listOf("Big project")
        }
        // Wait for the COMPOSITION to catch up with the ViewModel: the
        // pump proves the state, but the tree can lag one emission
        // behind it, and asserting pixel tops on a stale tree throws
        // (row missing) or compares the wrong rows. Two stages, so a
        // failure names the stage: first each row exists exactly once,
        // then the rendered order matches the plan order.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Old report")
                .fetchSemanticsNodes().size == 1 &&
                composeRule.onAllNodesWithText("Dentist")
                    .fetchSemanticsNodes().size == 1 &&
                composeRule.onAllNodesWithText("No-est task")
                    .fetchSemanticsNodes().size == 1
        }
        // The rendered order, read from the semantics tree's traversal
        // order (layout order) instead of pixel tops: boundsInRoot kept
        // reporting the no-estimate row at-or-above the Dentist row in
        // CI (run 44198121044: the existence wait passed — one node
        // each — yet the Dentist < No-est top comparison timed out for
        // 10s), while the ViewModel order is proven correct and the
        // move-down test proves topOf tracks order changes. Traversal
        // order follows the picked Column's child order directly, so it
        // pins the same behaviour without the flaky measurement.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            traversalIndex("Old report") >= 0 &&
                traversalIndex("Dentist") >= 0 &&
                traversalIndex("No-est task") >= 0
        }
        // Carry-over first, then today-due in due order.
        assertTrue(traversalIndex("Old report") < traversalIndex("Dentist"))
        assertTrue(traversalIndex("Dentist") < traversalIndex("No-est task"))
        // 120 + 60 = 180 min picked; the 500-min task no longer fits.
        composeRule.onNodeWithText("3h 0m of 8h max").assertIsDisplayed()
        composeRule.onNodeWithText("Big project").assertExists()
        // The over-budget task is present (in "Not planned"), not hidden.
        composeRule.onNodeWithText("Not planned").assertExists()
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
        composeRule.onNodeWithTag("plan_start_button")
            .performSemanticsAction(SemanticsActions.OnClick)
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
        composeRule.onNodeWithTag("plan_row_Big project")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("11h 20m of 8h max")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("plan_start_button")
            .performSemanticsAction(SemanticsActions.OnClick)
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
        ).also { viewModelStore.put("plan-2", it) }
        composeRule.runOnIdle { currentViewModel = secondViewModel }
        // Let the swap's recomposition settle (the new ViewModel starts
        // in loading) so the wait below cannot pass on the first
        // ViewModel's still-composed "11h 20m" line.
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("11h 20m of 8h max")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("11h 20m of 8h max").assertIsDisplayed()
        composeRule.onNodeWithText("Big project").assertExists()
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
        // Traversal order, not pixel tops: boundsInRoot is unreliable
        // in Robolectric (S1-2 — the measurement was the liar, not the
        // product), and test 1 above already proves this order.
        assertTrue(traversalIndex("Old report") < traversalIndex("Dentist"))
        composeRule.onNodeWithContentDescription("Move Old report down")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            traversalIndex("Dentist") >= 0 &&
                traversalIndex("Old report") >= 0 &&
                traversalIndex("Dentist") < traversalIndex("Old report")
        }
        assertTrue(traversalIndex("Dentist") < traversalIndex("Old report"))
    }

    @Test
    fun `intro line shows the date and the draft copy`() {
        seedPlanData()
        setPlanContent()
        waitForFullDraft()
        // The date part is the live dayLabel, not the prototype's demo date.
        // The intro is ONE Text ("{date} · {body}"), so the date and the
        // body are substrings, not whole nodes — substring = true (the
        // ChatScreenSmokeTest pattern); the default exact match never fires.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(dayLabel(startOfTodayMillis()), substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Here is a draft of your day", substring = true)
            .assertExists()
        composeRule.onNodeWithText("your call, always", substring = true)
            .assertExists()
    }

    @Test
    fun `time-budget card shows the total, the bar and the note`() {
        seedPlanData()
        setPlanContent()
        waitForFullDraft()
        composeRule.onNodeWithTag("plan_time_budget_card").assertExists()
        composeRule.onNodeWithText("Time budget").assertExists()
        composeRule.onNodeWithText("3h 0m of 8h max").assertIsDisplayed()
        composeRule.onNodeWithTag("plan_time_budget_bar").assertExists()
        composeRule.onNodeWithText(
            "Estimates are optional. A task without one still joins My Day — " +
                "it just shows as a point on the timeline, not a block."
        ).assertExists()
    }

    @Test
    fun `carry banner shows the live count when rows are carried over`() {
        seedPlanData()
        setPlanContent()
        waitForFullDraft()
        // Exactly one seeded row is carried over (Old report, due yesterday).
        composeRule.onNodeWithTag("plan_carry_banner").assertExists()
        composeRule.onNodeWithText("1 unfinished from yesterday").assertExists()
        composeRule.onNodeWithText(
            "They are in the draft below, marked “Carried over”. " +
                "Remove any you do not want today."
        ).assertExists()
    }

    @Test
    fun `carry banner is hidden when nothing is carried over`() {
        // Today-only tasks: no row is a carry-over.
        createTask("Dentist", at(0, 15), estimatedMinutes = 60)
        createTask("No-est task", startOfTodayMillis(), estimatedMinutes = null)
        setPlanContent()
        // Wait for the FULL draft (both rows + the budget line) so the
        // absence below cannot pass on a half-streamed draft.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("1h 0m of 8h max")
                .fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText("No-est task")
                    .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("plan_carry_banner").assertDoesNotExist()
    }

    @Test
    fun `start my day carries the tiny note below it`() {
        seedPlanData()
        setPlanContent()
        waitForFullDraft()
        composeRule.onNodeWithTag("plan_start_button").assertExists()
        composeRule.onNodeWithText(
            "Start my day confirms these tasks into My Day on Today."
        ).assertExists()
    }
}

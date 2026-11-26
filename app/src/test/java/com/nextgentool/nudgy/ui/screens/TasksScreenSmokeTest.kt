package com.nextgentool.nudgy.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.usecase.AddSubtaskUseCase
import com.nextgentool.nudgy.domain.usecase.CreateTaskUseCase
import com.nextgentool.nudgy.domain.usecase.DeleteTaskUseCase
import com.nextgentool.nudgy.domain.usecase.SaveViewUseCase
import com.nextgentool.nudgy.domain.usecase.ToggleTaskCompletionUseCase
import com.nextgentool.nudgy.domain.usecase.UpdateTaskUseCase
import com.nextgentool.nudgy.ui.components.TaskDetailSheet
import com.nextgentool.nudgy.ui.viewmodel.TaskDetailState
import com.nextgentool.nudgy.ui.viewmodel.TasksViewModel
import kotlinx.coroutines.flow.first
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
 * Sprint 1-1 fix — TasksScreen render smoke test.
 *
 * The S1-1 crash (hiltViewModel() with no @AndroidEntryPoint on
 * MainActivity) could only be caught by rendering the screen. This test
 * renders TasksScreen with a MANUALLY-constructed TasksViewModel (real
 * in-memory Room repository, no Hilt) and asserts the screen composes
 * without crashing. It does NOT test Hilt injection itself — that is
 * covered by the MainActivityEntryPointTest guard + the device test.
 *
 * Sprint 7 — the screen was rebuilt to the prototype (scr-tasks):
 * "All tasks" backbar, MY FILTERS label, "Project: / Tag: / Status:"
 * chip labels, a two-pill List | Board seg, and the NEW TASK card as
 * the LAST list item. The quick-add field is therefore composed only
 * after scrolling — the "list loaded" wait uses the search field
 * ("Search tasks", top item), never "Add task". Below-fold assertions
 * use assertExists, and the bottom card is reached with
 * performScrollToIndex (the S14-proven LazyColumn pattern) — never
 * performScrollTo, which hangs this harness on non-lazy scrollables.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TasksScreenSmokeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var db: NudgyDatabase
    private lateinit var repository: TaskRepository
    private lateinit var viewModel: TasksViewModel
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
        viewModel = TasksViewModel(
            repository = repository,
            createTaskUseCase = CreateTaskUseCase(repository),
            toggleCompletion = ToggleTaskCompletionUseCase(repository),
            updateTask = UpdateTaskUseCase(repository),
            deleteTask = DeleteTaskUseCase(repository),
            addSubtask = AddSubtaskUseCase(repository),
            saveView = SaveViewUseCase(repository)
        )
        viewModelStore.put("tasks", viewModel)
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

    @Test
    fun `TasksScreen composes without crashing`() {
        composeRule.setContent {
            TasksScreen(viewModel = viewModel)
        }
        // Top bar renders immediately, even while the list is loading.
        composeRule.onNodeWithText("All tasks").assertIsDisplayed()
        // The list has loaded once the search field is up; wait for it
        // instead of asserting against the loading skeleton. (Sprint 7:
        // the quick-add moved to the bottom of the list, so "Add task"
        // no longer proves the list loaded — it is composed only after
        // scrolling to the last item.)
        waitForListLoaded()
        // The NEW TASK card is the last list item (index 5 with no
        // tasks: search, saved views, filters, seg, empty state, card).
        // Scroll to it by index and prove the field + Save are there.
        composeRule.onNodeWithTag("tasks_list").performScrollToIndex(5)
        composeRule.onNodeWithTag("quick_add_field").assertIsDisplayed()
        composeRule.onNodeWithTag("quick_add_save_button").assertIsDisplayed()
    }

    // ---- Sprint 7 — the prototype rebuild (scr-tasks) ----

    private val userId get() = repository.currentUserId()

    /** The list has loaded (the search field is up). */
    private fun waitForListLoaded() {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Search tasks")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun renderTasks() {
        composeRule.setContent {
            TasksScreen(viewModel = viewModel)
        }
        waitForListLoaded()
    }

    /**
     * Same as [renderTasks] but on a 1600dp-wide surface (test-only
     * layout; production code untouched). The filter chips live in a
     * LazyRow, which composes only what fits its viewport — on the
     * default narrow test surface the late chips ("Status: To do" and
     * friends, after All + project + tag + 3 priority chips) never
     * enter the semantics tree. The ReminderChipsTest pattern (step
     * 5c): widen the viewport so the whole row composes at once.
     * Used ONLY by the chip-prefix test; every other test keeps the
     * normal-width render.
     */
    private fun renderTasksWide() {
        composeRule.setContent {
            Box(Modifier.requiredWidth(1600.dp)) {
                TasksScreen(viewModel = viewModel)
            }
        }
        waitForListLoaded()
    }

    /** Seeds one task and returns its id. */
    private fun seedTask(title: String): String = runBlocking {
        repository.createTask(userId = userId, title = title)
        repository.getAllTasksOnce(userId).first { it.title == title }.id
    }

    @Test
    fun `backbar shows All tasks and the Templates soft button`() {
        renderTasks()
        composeRule.onNodeWithText("All tasks").assertIsDisplayed()
        composeRule.onNodeWithTag("templates_button").assertExists()
        composeRule.onNodeWithText("Templates").assertExists()
    }

    @Test
    fun `MY FILTERS label and the filters note render`() {
        renderTasks()
        composeRule.onNodeWithText("MY FILTERS").assertExists()
        composeRule.onNodeWithText(
            "“My filters” are views you saved. Tap a filter to narrow " +
                "the list — tap it again to clear it."
        ).assertExists()
    }

    @Test
    fun `filter chips carry Project Tag Status prefixes`() {
        runBlocking {
            repository.createProject(userId, "Home", "#0F766E")
            repository.createTag(userId, "Errands", "#0F766E")
        }
        // Wide render: "Status: To do" sits late in the filter
        // LazyRow and only composes on a wide viewport (see
        // renderTasksWide).
        renderTasksWide()
        composeRule.onNodeWithText("Project: Home").assertExists()
        composeRule.onNodeWithText("Tag: Errands").assertExists()
        composeRule.onNodeWithText("Status: To do").assertExists()
    }

    @Test
    fun `List Board seg has two pills and a mode hint`() {
        renderTasks()
        composeRule.onNodeWithTag("view_mode_list").assertExists()
        composeRule.onNodeWithTag("view_mode_board").assertExists()
        composeRule.onNodeWithText(
            "List view — every matching task in one list."
        ).assertExists()
        // Switch to Board (the S14-proven performClick on the pill):
        // the hint follows the mode.
        composeRule.onNodeWithTag("view_mode_board").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(
            "The Board shows the same tasks grouped by status — To do, " +
                "In progress, Done — so you can see at a glance what is " +
                "waiting and what is stuck."
        ).assertExists()
        composeRule.onNodeWithText(
            "List view — every matching task in one list."
        ).assertDoesNotExist()
    }

    @Test
    fun `NEW TASK card sits at the bottom with Save and the note`() {
        renderTasks()
        // Index 5 with no tasks: search, saved views, filters, seg,
        // empty state, card.
        composeRule.onNodeWithTag("tasks_list").performScrollToIndex(5)
        composeRule.onNodeWithText("NEW TASK").assertIsDisplayed()
        composeRule.onNodeWithTag("quick_add_field").assertIsDisplayed()
        composeRule.onNodeWithTag("quick_add_save_button").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Saved tasks get today’s date — change it any time in the " +
                "task details."
        ).assertExists()
    }

    @Test
    fun `empty state shows the prototype copy when a filter matches nothing`() {
        renderTasks()
        // A search that matches nothing: a plain StateFlow set, safe
        // from a test; the empty card arrives via the Flow.
        viewModel.setSearchText("zzz-no-match")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("No tasks match that view.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("No tasks match that view.").assertExists()
        composeRule.onNodeWithText(
            "Try a different filter, or clear the search."
        ).assertExists()
        composeRule.onNodeWithText("Clear filter").assertExists()
    }

    @Test
    fun `board note sits below the columns`() {
        seedTask("Board task one")
        renderTasks()
        composeRule.onNodeWithTag("view_mode_board").performClick()
        composeRule.waitForIdle()
        // Index 4 with one task: search, saved views, filters, seg,
        // board. The note is inside the board item, below the columns.
        composeRule.onNodeWithTag("tasks_list").performScrollToIndex(4)
        composeRule.onNodeWithTag("task_board").assertExists()
        composeRule.onNodeWithText(
            "Tap a card to open it. In the app, drag cards between " +
                "columns to change status."
        ).assertExists()
    }

    // ---- Sprint 3-5 — the detail sheet's "Break down" button (feature 20) ----

    private fun detailTask(parentTaskId: String? = null) = TaskEntity(
        id = "t1",
        userId = "u1",
        title = "Plan Diwali party",
        dueDateEpochMillis = System.currentTimeMillis() + 86_400_000L,
        parentTaskId = parentTaskId
    )

    private fun renderDetailSheet(task: TaskEntity, onSplitTask: (String) -> Unit) {
        composeRule.setContent {
            TaskDetailSheet(
                detail = TaskDetailState(task = task),
                allTags = emptyList(),
                allProjects = emptyList(),
                onUpdateTask = {},
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
                onDismiss = {},
                onSplitTask = onSplitTask
            )
        }
    }

    @Test
    fun `detail sheet Break down button calls onSplitTask with the task id`() {
        var splitId: String? = null
        renderDetailSheet(detailTask()) { splitId = it }
        // The button lives in the Subtasks section (LazyColumn item 12
        // for a top-level personal task: header, title, notes, Due,
        // Reminder, Recurrence, Estimate, Actual, Attachments,
        // Priority, Project, Tags, Subtasks) — below the fold, so its
        // row is not composed until the list is scrolled. Jump by
        // index (the S14SmokeTest pattern): deterministic, no swipe
        // physics.
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("task_detail_sheet_list").performScrollToIndex(12)
        composeRule.onNodeWithTag("split_task_button").assertIsDisplayed()
        composeRule.onNodeWithTag("split_task_button").performClick()
        assertEquals("t1", splitId)
    }

    @Test
    fun `detail sheet of a subtask shows no Break down button`() {
        // One level only: a subtask has no subtask section, so there
        // is nothing to split it into.
        renderDetailSheet(detailTask(parentTaskId = "parent-1")) {}
        composeRule.onNodeWithTag("split_task_button").assertDoesNotExist()
    }
}

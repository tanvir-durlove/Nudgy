package com.nextgentool.nudgy.ui.screens

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
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
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TasksScreenSmokeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var db: NudgyDatabase
    private lateinit var viewModel: TasksViewModel

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NudgyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repository = TaskRepository(context, db)
        viewModel = TasksViewModel(
            repository = repository,
            createTaskUseCase = CreateTaskUseCase(repository),
            toggleCompletion = ToggleTaskCompletionUseCase(repository),
            updateTask = UpdateTaskUseCase(repository),
            deleteTask = DeleteTaskUseCase(repository),
            addSubtask = AddSubtaskUseCase(repository),
            saveView = SaveViewUseCase(repository)
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `TasksScreen composes without crashing`() {
        composeRule.setContent {
            TasksScreen(viewModel = viewModel)
        }
        // Top bar renders immediately, even while the list is loading.
        composeRule.onNodeWithText("My tasks").assertIsDisplayed()
        // The quick-add field appears once the repository flows emit;
        // wait for it instead of asserting against the loading skeleton.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Add task")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Add task").assertIsDisplayed()
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

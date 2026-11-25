package com.nextgentool.nudgy.ui.screens

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.usecase.AddSubtaskUseCase
import com.nextgentool.nudgy.domain.usecase.CreateTaskUseCase
import com.nextgentool.nudgy.domain.usecase.DeleteTaskUseCase
import com.nextgentool.nudgy.domain.usecase.SaveViewUseCase
import com.nextgentool.nudgy.domain.usecase.ToggleTaskCompletionUseCase
import com.nextgentool.nudgy.domain.usecase.UpdateTaskUseCase
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
 * Sprint 6-4, step 2e — the search tap deep-link: a tap on a task
 * result in the global search opens that EXACT task's detail sheet,
 * not just the Tasks list.
 *
 * What this test proves: the TasksScreen honours the incoming
 * [TasksScreen.openTaskId] — with a real task id the detail sheet
 * (testTag `task_detail_sheet_list`) appears; with no id, or a stale
 * id, no sheet opens. The screen takes an explicit ViewModel here
 * (same TasksScreenSmokeTest pattern: real in-memory Room, manual
 * TasksViewModel, ViewModelStore cleared BEFORE the DB closes, 500 ms
 * Room-IO drain). Seeding uses runBlocking on the repository.
 *
 * What it does NOT prove (honest limitation, same as step 2d): the
 * real NudgyNavHost is not rendered here — there is no Hilt test
 * infra in this repo (no HiltAndroidTest anywhere) and the NavHost
 * calls hiltViewModel(). The NavHost wiring (the Search tap sets the
 * NavHost's searchOpenTaskId state; the Tasks destination passes it
 * as openTaskId) is verified by reading + CI compile, not by a
 * navigation test.
 *
 * Harness rules, all proven in this repo — do NOT "fix" them:
 * - NO performScrollTo(): it hangs the test worker on this harness.
 *   The sheet is asserted with assertExists (presence in the tree),
 *   never scrolled to.
 * - NO waitForIdle() for the sheet: the Room tasks stream in on
 *   Dispatchers.IO and waitForIdle does not wait for background IO.
 *   The sheet is awaited with waitUntil on the tree itself (the
 *   step-2c waitForRow pattern).
 * - selectTask is a plain StateFlow set (no viewModelScope launch),
 *   so the LaunchedEffect calling it from a plain-JUnit test is safe
 *   (the S6-3 hang was a viewModelScope.launch call from a test).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TasksScreenDeepLinkTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var db: NudgyDatabase
    private lateinit var repository: TaskRepository
    private lateinit var viewModel: TasksViewModel
    // Owns the ViewModel so tearDown can cancel its scope before the
    // DB closes (same leak as DarkThemeTest, 2026-11-21).
    private val viewModelStore = ViewModelStore()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
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
        // Order matters: cancel the ViewModel scope FIRST so no
        // viewModelScope coroutine can run Room work against the DB
        // once it is closed (same leak as DarkThemeTest).
        viewModelStore.clear()
        // S4-1 round 7 — drain in-flight Room IO before closing the DB.
        Thread.sleep(500)
        db.close()
    }

    private val userId get() = repository.currentUserId()

    /** Seeds one task and returns its id. */
    private fun seedTask(title: String): String = runBlocking {
        repository.createTask(userId = userId, title = title)
        repository.getAllTasksOnce(userId).first { it.title == title }.id
    }

    /** The list has loaded (the "Add task" quick-add field is up). */
    private fun waitForListLoaded() {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Add task")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForSheet() {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("task_detail_sheet_list")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `openTaskId opens that task's detail sheet`() {
        val taskId = seedTask("Buy groceries")
        composeRule.setContent {
            TasksScreen(viewModel = viewModel, openTaskId = taskId)
        }
        waitForSheet()
        composeRule.onNodeWithTag("task_detail_sheet_list").assertExists()
        // The sheet is for THIS task, not just any sheet: the
        // ViewModel's selected detail carries the deep-linked id.
        val selected = runBlocking {
            viewModel.uiState.first { it.selectedDetail?.task?.id == taskId }
        }
        assertEquals(taskId, selected.selectedDetail?.task?.id)
    }

    @Test
    fun `no openTaskId opens no detail sheet`() {
        seedTask("Buy groceries")
        composeRule.setContent {
            TasksScreen(viewModel = viewModel)
        }
        waitForListLoaded()
        composeRule.onNodeWithTag("task_detail_sheet_list").assertDoesNotExist()
    }

    @Test
    fun `stale openTaskId opens no detail sheet`() {
        seedTask("Buy groceries")
        composeRule.setContent {
            TasksScreen(viewModel = viewModel, openTaskId = "no-such-task")
        }
        waitForListLoaded()
        composeRule.onNodeWithTag("task_detail_sheet_list").assertDoesNotExist()
    }
}

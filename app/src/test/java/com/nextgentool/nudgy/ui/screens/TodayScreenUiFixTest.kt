package com.nextgentool.nudgy.ui.screens

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
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
import java.util.Calendar
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
 * Sprint 1-1 UI-FIX — Today screen usability (2026-11-16 device test).
 *
 * The device test showed Today unusable: no visible way to tell a task
 * was saved, a created task vanished from view (My Day / Timeline were
 * dead skeleton rows), and "All tasks" sat below the fold on a screen
 * that did not scroll. These tests render Today with a
 * MANUALLY-constructed TasksViewModel (real in-memory Room repository,
 * no Hilt) and pin the fixed flows: create → appears in My Day and
 * stays there, "Saved" confirmation, All-tasks entry visible and
 * working, Timeline shown only with timed tasks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TodayScreenUiFixTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var db: NudgyDatabase
    private lateinit var repository: TaskRepository
    private lateinit var viewModel: TasksViewModel

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
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun setTodayContent(onAllTasksClick: () -> Unit = {}) {
        composeRule.setContent {
            TodayScreen(
                onPlanClick = {},
                onAllTasksClick = onAllTasksClick,
                viewModel = viewModel
            )
        }
        // Wait for the first Flow emission (quick-add field appears).
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Add task")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `today quick-add creates a task due today and it appears in My Day`() {
        setTodayContent()
        viewModel.createTaskForToday("Buy milk")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Buy milk")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Buy milk").performScrollTo().assertIsDisplayed()
        // The empty-day text is gone — the task did not vanish.
        assertTrue(
            composeRule.onAllNodesWithText("No tasks yet — add one above")
                .fetchSemanticsNodes().isEmpty()
        )
        // A second task does not push the first one out of view.
        viewModel.createTaskForToday("Call mum")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Call mum")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Buy milk").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Call mum").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `All tasks entry is visible on first paint and opens the list`() {
        var opened = false
        setTodayContent(onAllTasksClick = { opened = true })
        composeRule.onNodeWithText("All tasks").assertIsDisplayed()
        composeRule.onNodeWithTag("all_tasks_row").performClick()
        assertTrue(opened)
    }

    @Test
    fun `quick-add UI shows Saved after creating`() {
        setTodayContent()
        composeRule.onNodeWithTag("quick_add_field").performTextInput("Buy milk")
        composeRule.onNodeWithContentDescription("Add task").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Saved: Buy milk")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Saved: Buy milk").assertIsDisplayed()
        // The Done button sits at the bottom of the confirmation card,
        // below the fold in the scrollable column — scroll to it first.
        composeRule.onNodeWithText("Done").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `empty day shows words not blank boxes and Timeline stays hidden`() {
        setTodayContent()
        composeRule.onNodeWithText("No tasks yet — add one above").assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodesWithText("Timeline")
                .fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun `a timed task appears in Timeline`() {
        // Seed a task due today at 15:00 through the repository, then
        // render: the Timeline section must exist and list it.
        val due = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 15)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        runBlocking {
            repository.createTask(
                userId = repository.currentUserId(),
                title = "Dentist",
                dueEpochMillis = due
            )
        }
        setTodayContent()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Timeline")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Timeline").performScrollTo().assertIsDisplayed()
        // A timed today-task appears in BOTH My Day and Timeline, so the
        // title exists twice — assert the count, then scroll to each.
        composeRule.onAllNodesWithText("Dentist").assertCountEquals(2)
        composeRule.onAllNodesWithText("Dentist")[0].performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("Dentist")[1].performScrollTo().assertIsDisplayed()
    }
}

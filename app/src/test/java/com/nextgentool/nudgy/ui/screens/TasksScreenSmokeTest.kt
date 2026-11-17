package com.nextgentool.nudgy.ui.screens

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
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
import org.junit.After
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
}

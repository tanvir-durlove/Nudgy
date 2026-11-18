package com.nextgentool.nudgy.ui.screens

import android.content.Context
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.local.entity.TaskAttachmentEntity
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
import com.nextgentool.nudgy.ui.viewmodel.TemplatesViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 1-4 — smoke tests for Board + Templates + detail-sheet
 * attachments (Features 5, 13, 10).
 *
 * Manually-constructed ViewModels over real in-memory Room (no Hilt),
 * following the S1-1 TasksScreenSmokeTest pattern. These pin that the
 * new screens compose without crashing and that the toggle, the
 * seeded templates and the attachment rows actually render. The drag
 * gesture itself is a device-test item.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class S14SmokeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var db: NudgyDatabase
    private lateinit var repository: TaskRepository
    private lateinit var tasksViewModel: TasksViewModel

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
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
    }

    @After
    fun tearDown() {
        db.close()
    }

    private val userId get() = repository.currentUserId()

    @Test
    fun `Board toggle renders the Kanban with three columns`() {
        runBlocking {
            repository.createTask(userId, "Board task one")
            repository.createTask(userId, "Board task two")
        }
        composeRule.setContent {
            TasksScreen(viewModel = tasksViewModel)
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Board task one")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("view_mode_board").performScrollTo()
        composeRule.onNodeWithTag("view_mode_board").performClick()
        composeRule.onNodeWithTag("task_board").assertExists()
        composeRule.onNodeWithText("To do").assertExists()
        composeRule.onNodeWithText("In progress").assertExists()
        composeRule.onNodeWithText("Done").assertExists()
        composeRule.onNodeWithText("Board task one").assertExists()
    }

    @Test
    fun `Templates screen renders the 3 seeded built-ins, read-only`() {
        runBlocking { repository.seedBuiltInTemplates(userId) }
        val templatesViewModel = TemplatesViewModel(repository)
        composeRule.setContent {
            TemplatesScreen(onBack = {}, viewModel = templatesViewModel)
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("দৈনিক রুটিন")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("দৈনিক রুটিন").assertIsDisplayed()
        composeRule.onNodeWithText("পড়াশোনা").assertIsDisplayed()
        composeRule.onNodeWithText("কাজ").assertIsDisplayed()
        composeRule.onNodeWithText("Morning routine").assertIsDisplayed()
        composeRule.onNodeWithTag("use_template_builtin-daily-routine").assertExists()
        // Built-ins are read-only: no edit/delete affordance on them.
        composeRule.onNodeWithContentDescription("Delete template").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Edit template").assertDoesNotExist()
    }

    @Test
    fun `detail sheet renders the attachments section with the file name`() {
        val task = TaskEntity(id = "t1", userId = "u1", title = "With file")
        val attachment = TaskAttachmentEntity(
            id = "a1",
            taskId = "t1",
            userId = "u1",
            fileName = "photo.jpg",
            mimeType = "image/jpeg",
            localPath = "/tmp/photo.jpg",
            sizeBytes = 12_345L
        )
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
                attachments = listOf(attachment)
            )
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("photo.jpg")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Attachments").assertIsDisplayed()
        composeRule.onNodeWithText("photo.jpg").assertIsDisplayed()
        composeRule.onNodeWithText("Add file").assertIsDisplayed()
    }
}

package com.nextgentool.nudgy.ui.screens

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
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
        // The column headers share their labels with the status filter
        // chips ("To do" / "In progress" / "Done"), so onNodeWithText
        // throws on multiple matches — pin the columns by testTag.
        composeRule.onNodeWithTag("board_column_TODO").assertExists()
        composeRule.onNodeWithTag("board_column_IN_PROGRESS").assertExists()
        composeRule.onNodeWithTag("board_column_DONE").assertExists()
        composeRule.onNodeWithText("Board task one").assertExists()
    }

    @Test
    fun `Templates screen renders the 3 seeded built-ins, read-only`() {
        runBlocking { repository.seedBuiltInTemplates(userId) }
        val templatesViewModel = TemplatesViewModel(repository)
        composeRule.setContent {
            TemplatesScreen(onBack = {}, viewModel = templatesViewModel)
        }
        // The DAO orders by name ASC (কাজ, দৈনিক রুটিন, পড়াশোনা), so
        // only the 1st card is visible at scroll 0 — check the cards
        // top to bottom, scrolling as each next one composes. Checking
        // a card after scrolling past it fails: LazyColumn uncomposes
        // items that leave the viewport.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("কাজ")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("কাজ").assertIsDisplayed()
        composeRule.onNodeWithText("দৈনিক রুটিন").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Morning routine").assertExists()
        composeRule.onNodeWithTag("use_template_builtin-daily-routine").assertExists()
        composeRule.onRoot().performTouchInput { swipeUp() }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("পড়াশোনা")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("পড়াশোনা").performScrollTo().assertIsDisplayed()
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
        // The Attachments section sits mid-sheet in the sheet's
        // LazyColumn — below the fold in the Robolectric viewport, so
        // its rows are not composed until the sheet is scrolled. Swipe
        // up on the sheet's own title (never onRoot — with a
        // ModalBottomSheet open there are TWO roots, main + popup, and
        // onRoot throws on multiple matches; never swipe down — down
        // dismisses the sheet). The whole AttachmentSection is one
        // LazyColumn item, so once its header is composed the file
        // rows are too.
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Task details").performTouchInput { swipeUp() }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Attachments")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Attachments").assertIsDisplayed()
        composeRule.onNodeWithText("photo.jpg").assertIsDisplayed()
        composeRule.onNodeWithText("Add file").assertIsDisplayed()
    }
}

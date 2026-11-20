package com.nextgentool.nudgy.ui.sheets

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.model.TaskCategory
import com.nextgentool.nudgy.data.repository.TaskRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 2-3 — share-to-task sheet prefill (Feature 12, Q5(a)).
 *
 * A share opens this sheet with its title/description already
 * filled and NO due date; confirming saves exactly that (due =
 * NO_DUE_DATE, reminder off). Tapping a day chip after "No date"
 * gives the task a real due date again, and the default sheet is
 * unchanged (empty title, save disabled).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CreateTaskSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private data class SavedArgs(
        val title: String,
        val description: String?,
        val dueEpochMillis: Long,
        val priority: Priority,
        val category: TaskCategory,
        val reminderSet: Boolean
    )

    private fun renderSheet(
        initialTitle: String = "",
        initialDescription: String = "",
        initialHasNoDueDate: Boolean = false,
        initialReminderSet: Boolean = true,
        saved: MutableList<SavedArgs>
    ) {
        composeTestRule.setContent {
            CreateTaskSheet(
                onDismiss = {},
                onSaveTask = { title, description, due, priority, category, reminderSet ->
                    saved.add(
                        SavedArgs(title, description, due, priority, category, reminderSet)
                    )
                },
                initialTitle = initialTitle,
                initialDescription = initialDescription,
                initialHasNoDueDate = initialHasNoDueDate,
                initialReminderSet = initialReminderSet
            )
        }
    }

    @Test
    fun `prefilled title is shown and prefilled description is shown after More options`() {
        val saved = mutableListOf<SavedArgs>()
        renderSheet(
            initialTitle = "Buy milk",
            initialDescription = "2% fat",
            initialHasNoDueDate = true,
            initialReminderSet = false,
            saved = saved
        )
        composeTestRule.onNodeWithText("Buy milk").assertExists()
        composeTestRule.onNodeWithText("More options")
            .performScrollTo()
            .performClick()
        composeTestRule.onNodeWithText("2% fat").assertExists()
    }

    @Test
    fun `confirm with No date saves NO_DUE_DATE and reminder off`() {
        val saved = mutableListOf<SavedArgs>()
        renderSheet(
            initialTitle = "Buy milk",
            initialDescription = "2% fat",
            initialHasNoDueDate = true,
            initialReminderSet = false,
            saved = saved
        )
        composeTestRule.onNodeWithText("Add Task")
            .performScrollTo()
            .performClick()
        assertEquals(1, saved.size)
        val args = saved.single()
        assertEquals("Buy milk", args.title)
        assertEquals("2% fat", args.description)
        assertEquals(TaskRepository.NO_DUE_DATE, args.dueEpochMillis)
        assertEquals(false, args.reminderSet)
    }

    @Test
    fun `tapping Today after No date saves a real due date`() {
        val saved = mutableListOf<SavedArgs>()
        renderSheet(
            initialTitle = "Buy milk",
            initialHasNoDueDate = true,
            initialReminderSet = false,
            saved = saved
        )
        composeTestRule.onNodeWithText("Today")
            .performScrollTo()
            .performClick()
        composeTestRule.onNodeWithText("Add Task")
            .performScrollTo()
            .performClick()
        assertEquals(1, saved.size)
        assertNotEquals(TaskRepository.NO_DUE_DATE, saved.single().dueEpochMillis)
    }

    @Test
    fun `tapping No date on the default sheet saves NO_DUE_DATE once a title is typed`() {
        // The default sheet still starts with a due date (today) —
        // "No date" is an explicit opt-out, not a new default.
        val saved = mutableListOf<SavedArgs>()
        renderSheet(saved = saved)
        composeTestRule.onNodeWithText("Add Task").assertIsNotEnabled()
        composeTestRule.onNodeWithText("What needs to get done?")
            .performTextInput("Buy milk")
        composeTestRule.onNodeWithText("No date")
            .performScrollTo()
            .performClick()
        composeTestRule.onNodeWithText("Add Task")
            .performScrollTo()
            .performClick()
        assertEquals(1, saved.size)
        assertEquals("Buy milk", saved.single().title)
        assertEquals(TaskRepository.NO_DUE_DATE, saved.single().dueEpochMillis)
    }
}

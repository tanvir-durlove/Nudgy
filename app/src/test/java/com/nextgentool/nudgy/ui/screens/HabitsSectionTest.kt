package com.nextgentool.nudgy.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.nextgentool.nudgy.data.local.entity.HabitEntity
import com.nextgentool.nudgy.data.repository.HabitStreak
import com.nextgentool.nudgy.ui.theme.NudgyTheme
import com.nextgentool.nudgy.ui.viewmodel.HabitUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 4-2 — smoke tests for the Habits section (spec §5).
 *
 * Renders the pure [HabitsSectionContent] with hand-built states (no
 * Hilt, no ViewModel): the empty state, the three streak display
 * states (done today / at risk / broken), and the add / rename /
 * delete callbacks. Compose 1.7 member rules apply: assertExists is
 * a member, never a top-level import.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HabitsSectionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun habit(
        id: String = "habit-1",
        title: String = "Drink water",
        currentStreak: Int = 3,
        longestStreak: Int = 5,
        checkedInToday: Boolean = true
    ) = HabitStreak(
        habit = HabitEntity(id = id, userId = "user-1", title = title),
        currentStreak = currentStreak,
        longestStreak = longestStreak,
        checkedInToday = checkedInToday
    )

    private fun setContent(
        state: HabitUiState,
        onNewTitleChange: (String) -> Unit = {},
        onCreateHabit: () -> Unit = {},
        onToggleCheckIn: (HabitStreak) -> Unit = {},
        onStartRename: (HabitStreak) -> Unit = {},
        onEditTitleChange: (String) -> Unit = {},
        onConfirmRename: () -> Unit = {},
        onCancelRename: () -> Unit = {},
        onRequestDelete: (HabitStreak) -> Unit = {},
        onConfirmDelete: () -> Unit = {},
        onCancelDelete: () -> Unit = {}
    ) {
        composeRule.setContent {
            NudgyTheme {
                HabitsSectionContent(
                    state = state,
                    onNewTitleChange = onNewTitleChange,
                    onCreateHabit = onCreateHabit,
                    onToggleCheckIn = onToggleCheckIn,
                    onStartRename = onStartRename,
                    onEditTitleChange = onEditTitleChange,
                    onConfirmRename = onConfirmRename,
                    onCancelRename = onCancelRename,
                    onRequestDelete = onRequestDelete,
                    onConfirmDelete = onConfirmDelete,
                    onCancelDelete = onCancelDelete
                )
            }
        }
    }

    private fun loadedState(habits: List<HabitStreak> = emptyList()) =
        HabitUiState(habits = habits, isLoading = false)

    @Test
    fun `empty state shows the add field and the no-habits text`() {
        setContent(loadedState())

        composeRule.onNodeWithText("Habits").assertIsDisplayed()
        composeRule.onNodeWithText("No habits yet — add your first one above").assertIsDisplayed()
        composeRule.onNodeWithTag("habit_new_title").assertIsDisplayed()
    }

    @Test
    fun `typing a title and tapping add calls back`() {
        var created = false
        var seenTitle = ""
        setContent(
            loadedState(),
            onNewTitleChange = { seenTitle = it },
            onCreateHabit = { created = true }
        )

        composeRule.onNodeWithTag("habit_new_title").performTextInput("Read")
        assertEquals("Read", seenTitle)
        composeRule.onNodeWithTag("habit_add").performClick()
        assertTrue(created)
    }

    @Test
    fun `a habit checked in today shows its streak and done today`() {
        setContent(loadedState(listOf(habit())))

        composeRule.onNodeWithText("Drink water").assertIsDisplayed()
        composeRule.onNodeWithTag("habit_streak_habit-1").assertIsDisplayed()
        composeRule.onNodeWithText("3-day streak · done today · Best 5").assertIsDisplayed()
    }

    @Test
    fun `a habit not checked in yet shows at risk, never zero`() {
        setContent(
            loadedState(
                listOf(habit(currentStreak = 2, longestStreak = 5, checkedInToday = false))
            )
        )

        composeRule.onNodeWithText("2-day streak · not done today yet · Best 5").assertIsDisplayed()
        composeRule.onNodeWithText("No current streak").assertDoesNotExist()
    }

    @Test
    fun `a broken streak shows no current streak but keeps the best`() {
        setContent(
            loadedState(
                listOf(habit(currentStreak = 0, longestStreak = 4, checkedInToday = false))
            )
        )

        composeRule.onNodeWithText("No current streak · Best 4").assertIsDisplayed()
    }

    @Test
    fun `tapping the checkbox calls back with the habit`() {
        var toggled: HabitStreak? = null
        setContent(loadedState(listOf(habit())), onToggleCheckIn = { toggled = it })

        composeRule.onNodeWithTag("habit_checkin_habit-1").performClick()
        assertEquals("habit-1", toggled?.habit?.id)
    }

    @Test
    fun `rename opens the edit field and saving calls back`() {
        var editSeen = ""
        var saved = false
        val state = loadedState(listOf(habit())).copy(
            editingHabitId = "habit-1",
            editTitle = "Drink water"
        )
        setContent(
            state,
            onEditTitleChange = { editSeen = it },
            onConfirmRename = { saved = true }
        )

        composeRule.onNodeWithTag("habit_edit_field_habit-1").assertIsDisplayed()
        composeRule.onNodeWithTag("habit_edit_field_habit-1").performTextInput(" more")
        assertTrue(editSeen.endsWith("more"))
        composeRule.onNodeWithTag("habit_edit_save_habit-1").performClick()
        assertTrue(saved)
    }

    @Test
    fun `tapping rename calls back with the habit`() {
        var renamed: HabitStreak? = null
        setContent(loadedState(listOf(habit())), onStartRename = { renamed = it })

        composeRule.onNodeWithTag("habit_rename_habit-1").performClick()
        assertEquals("habit-1", renamed?.habit?.id)
    }

    @Test
    fun `delete asks for confirmation before calling back`() {
        var requested: HabitStreak? = null
        var confirmed = false
        val state = loadedState(listOf(habit())).copy(confirmingDeleteHabitId = "habit-1")
        setContent(
            state,
            onRequestDelete = { requested = it },
            onConfirmDelete = { confirmed = true }
        )

        composeRule.onNodeWithText("Delete this habit? Its check-ins go too.").assertIsDisplayed()
        composeRule.onNodeWithTag("habit_delete_confirm_habit-1").performClick()
        assertTrue(confirmed)
        assertEquals(null, requested)
    }

    @Test
    fun `tapping delete calls back with the habit`() {
        var requested: HabitStreak? = null
        setContent(loadedState(listOf(habit())), onRequestDelete = { requested = it })

        composeRule.onNodeWithTag("habit_delete_habit-1").performClick()
        assertEquals("habit-1", requested?.habit?.id)
    }
}

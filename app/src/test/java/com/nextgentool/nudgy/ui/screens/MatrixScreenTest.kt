package com.nextgentool.nudgy.ui.screens

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.startOfDayMillis
import com.nextgentool.nudgy.ui.viewmodel.TaskListItem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 7 — Matrix screen test (spec §4, prototype scr-matrix).
 *
 * The screen is a thin wrapper (backbar + intro + the existing
 * EisenhowerMatrix), so what is pinned here is the WRAPPER: the
 * backbar + the prototype-verbatim intro render, all four quadrants
 * render with one task each, and tapping a card calls onTaskClick
 * with that task (the NavHost wires it to the Tasks deep-link). The
 * matrix behaviour itself (quadrant rules, drag, toggle) stays
 * covered by EisenhowerMatrixTest / EisenhowerMatrixViewModelTest.
 *
 * Compose 1.7 member rules apply: assertExists is a member, never a
 * top-level import (FocusScreenTest pattern). The content scrolls,
 * but a Column + verticalScroll composes every child (unlike Lazy
 * layouts), so assertExists works for all four quadrants. The tap
 * test uses performSemanticsAction(OnClick), NOT performClick: the
 * Q1 card sits below the fold in the test viewport (backbar + intro
 * + the 560dp matrix inside nested scrolls), so a coordinate click
 * lands outside the window and silently never fires (run 1017:
 * expected [a] but was []) — the semantics action is the
 * established remedy for a below-fold click.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MatrixScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Noon on the local day [daysAhead] days from today (0 = today). */
    private fun noon(daysAhead: Int = 0): Long =
        startOfDayMillis(daysAhead, System.currentTimeMillis()) + 12 * 60 * 60 * 1000L

    private fun item(id: String, title: String, priority: Priority, due: Long) =
        TaskListItem(
            task = TaskEntity(
                id = id,
                userId = "u1",
                title = title,
                dueDateEpochMillis = due,
                priority = priority
            )
        )

    /** One task per quadrant (same fixture as EisenhowerMatrixTest):
     * a → Q1, b → Q2, c → Q3, d → Q4. Titles are the prototype's own
     * example tasks. */
    private val items = listOf(
        item("a", "Submit assignment", Priority.HIGH, noon()),
        item("b", "Morning walk", Priority.HIGH, noon(3)),
        item("c", "Reply to client emails", Priority.MEDIUM, noon()),
        item("d", "Water the plants", Priority.LOW, TaskRepository.NO_DUE_DATE)
    )

    private fun setMatrixContent(clicked: MutableList<String> = mutableListOf()) {
        composeRule.setContent {
            MatrixScreenContent(
                items = items,
                onBackClick = {},
                onMoveTask = { _, _ -> },
                onToggle = {},
                onTaskClick = { clicked.add(it.id) }
            )
        }
    }

    @Test
    fun `backbar and prototype intro render`() {
        setMatrixContent()

        composeRule.onNodeWithTag("matrix_backbar").assertExists()
        composeRule.onNodeWithText("Priority matrix").assertExists()
        composeRule.onNodeWithText(
            "The Eisenhower view of the same tasks — nothing new to maintain. Tap a task to open it."
        ).assertExists()
    }

    @Test
    fun `all four quadrants render with one task each`() {
        setMatrixContent()

        composeRule.onNodeWithTag("matrix_quadrant_Q1").assertExists()
        composeRule.onNodeWithTag("matrix_quadrant_Q2").assertExists()
        composeRule.onNodeWithTag("matrix_quadrant_Q3").assertExists()
        composeRule.onNodeWithTag("matrix_quadrant_Q4").assertExists()
        composeRule.onNodeWithText("Submit assignment").assertExists()
        composeRule.onNodeWithText("Morning walk").assertExists()
        composeRule.onNodeWithText("Reply to client emails").assertExists()
        composeRule.onNodeWithText("Water the plants").assertExists()
    }

    @Test
    fun `tapping a card calls onTaskClick with that task`() {
        val clicked = mutableListOf<String>()
        setMatrixContent(clicked)

        // Below the fold in the test viewport (see the class KDoc),
        // so a coordinate click never lands — invoke the same OnClick
        // the clickable exposes. The tag lives ON the clickable
        // Surface itself.
        composeRule.onNodeWithTag("matrix_card_a")
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("a"), clicked)
    }
}

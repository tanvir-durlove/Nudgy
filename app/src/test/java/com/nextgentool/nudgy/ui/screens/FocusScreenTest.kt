package com.nextgentool.nudgy.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.model.FocusSessionKind
import com.nextgentool.nudgy.ui.theme.NudgyTheme
import com.nextgentool.nudgy.ui.viewmodel.FocusUiState
import com.nextgentool.nudgy.ui.viewmodel.PomodoroPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 4-1 — smoke tests for the Focus timer screen (spec §4).
 *
 * Renders the pure [FocusScreenContent] with hand-built states (no
 * Hilt, no ViewModel): every phase shows its countdown, its
 * controls, and its stats line, and the buttons call back. Compose
 * 1.7 member rules apply: assertExists is a member, never a
 * top-level import.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FocusScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setContent(
        state: FocusUiState,
        onStartFocus: () -> Unit = {},
        onStartBreak: () -> Unit = {},
        onPause: () -> Unit = {},
        onResume: () -> Unit = {},
        onCancel: () -> Unit = {},
        onSelectTask: (String?) -> Unit = {},
        onDismissFinished: () -> Unit = {}
    ) {
        composeRule.setContent {
            NudgyTheme {
                FocusScreenContent(
                    state = state,
                    onBackClick = {},
                    onStartFocus = onStartFocus,
                    onStartBreak = onStartBreak,
                    onPause = onPause,
                    onResume = onResume,
                    onCancel = onCancel,
                    onSelectTask = onSelectTask,
                    onDismissFinished = onDismissFinished
                )
            }
        }
    }

    private fun idleState(tasks: List<TaskEntity> = emptyList()) = FocusUiState(
        phase = PomodoroPhase.IDLE,
        kind = FocusSessionKind.FOCUS,
        remainingMillis = 25 * 60_000L,
        totalMillis = 25 * 60_000L,
        todaysFocusMinutes = 50,
        completedFocusToday = 2,
        nextBreakMinutes = 5,
        availableTasks = tasks
    )

    @Test
    fun `idle screen shows the countdown, start button, picker and today's minutes`() {
        val task = TaskEntity(
            id = "task-1", userId = "user-1", title = "Call mom",
            dueDateEpochMillis = 0L
        )
        setContent(idleState(listOf(task)))

        composeRule.onNodeWithText("25:00").assertIsDisplayed()
        composeRule.onNodeWithTag("focus_start").assertIsDisplayed()
        composeRule.onNodeWithText("Today: 50 min focused • 2 sessions").assertExists()
        composeRule.onNodeWithText("Focus on").assertExists()
        composeRule.onNodeWithText("No task (standalone)").assertExists()
        composeRule.onNodeWithText("Call mom").assertExists()
    }

    @Test
    fun `start button calls back`() {
        var started = false
        setContent(idleState(), onStartFocus = { started = true })

        composeRule.onNodeWithTag("focus_start").performClick()
        assertTrue(started)
    }

    @Test
    fun `picking a task calls back with its id`() {
        val task = TaskEntity(
            id = "task-1", userId = "user-1", title = "Call mom",
            dueDateEpochMillis = 0L
        )
        var picked: String? = "unset"
        setContent(idleState(listOf(task)), onSelectTask = { picked = it })

        composeRule.onNodeWithTag("focus_task_task-1").performClick()
        assertEquals("task-1", picked)
    }

    @Test
    fun `running screen shows pause and cancel, not start`() {
        setContent(
            idleState().copy(
                phase = PomodoroPhase.RUNNING,
                kind = FocusSessionKind.FOCUS,
                remainingMillis = 24 * 60_000L + 30_000L,
                sessionId = "session-1",
                selectedTaskTitle = "Call mom"
            )
        )

        composeRule.onNodeWithText("24:30").assertIsDisplayed()
        composeRule.onNodeWithText("Focusing on: Call mom").assertExists()
        composeRule.onNodeWithTag("focus_pause").assertIsDisplayed()
        composeRule.onNodeWithTag("focus_cancel").assertIsDisplayed()
        composeRule.onNodeWithTag("focus_start").assertDoesNotExist()
    }

    @Test
    fun `pause button calls back`() {
        var paused = false
        setContent(
            idleState().copy(
                phase = PomodoroPhase.RUNNING,
                sessionId = "session-1"
            ),
            onPause = { paused = true }
        )

        composeRule.onNodeWithTag("focus_pause").performClick()
        assertTrue(paused)
    }

    @Test
    fun `paused screen shows resume and cancel`() {
        setContent(
            idleState().copy(
                phase = PomodoroPhase.PAUSED,
                kind = FocusSessionKind.FOCUS,
                remainingMillis = 20 * 60_000L,
                sessionId = "session-1"
            )
        )

        composeRule.onNodeWithText("20:00").assertIsDisplayed()
        composeRule.onNodeWithTag("focus_resume").assertIsDisplayed()
        composeRule.onNodeWithTag("focus_cancel").assertIsDisplayed()
        composeRule.onNodeWithTag("focus_pause").assertDoesNotExist()
    }

    @Test
    fun `finished focus offers the break and start focus instead`() {
        var breakStarted = false
        setContent(
            idleState().copy(
                justFinishedKind = FocusSessionKind.FOCUS,
                nextBreakMinutes = 5
            ),
            onStartBreak = { breakStarted = true }
        )

        composeRule.onNodeWithText("Focus complete — nice work!").assertIsDisplayed()
        composeRule.onNodeWithTag("focus_start_break").assertIsDisplayed()
        composeRule.onNodeWithTag("focus_start_break").performClick()
        assertTrue(breakStarted)
    }

    @Test
    fun `fourth finished focus offers the long break`() {
        setContent(
            idleState().copy(
                justFinishedKind = FocusSessionKind.FOCUS,
                completedFocusToday = 4,
                nextBreakMinutes = 15
            )
        )

        composeRule.onNodeWithText("Start break (15 min)").assertIsDisplayed()
    }

    @Test
    fun `finished break offers focus again`() {
        setContent(
            idleState().copy(justFinishedKind = FocusSessionKind.BREAK)
        )

        composeRule.onNodeWithText("Break over — ready to focus?").assertIsDisplayed()
        composeRule.onNodeWithTag("focus_start").assertIsDisplayed()
        composeRule.onNodeWithTag("focus_start_break").assertDoesNotExist()
    }
}

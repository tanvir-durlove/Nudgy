package com.nextgentool.nudgy.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.model.TaskStatus
import com.nextgentool.nudgy.ui.viewmodel.TaskListItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 1-4b — Board drag gesture tests (Feature 5).
 *
 * The S1-4 tests only covered grouping, so CI passed while the drop
 * could never land on a real device. These tests drive the actual
 * gesture — long-press a card, drag it, release — and assert the
 * onMoveTask callback fires with the moved task and the new status.
 * The drop decision itself is pinned pure in TaskBoardTest
 * (resolveBoardDrop / boardColumnAt); this pins the gesture wiring.
 *
 * Distances are computed from the column pitch (272dp column + 12dp
 * gap) through the rule's density, never hardcoded pixels: the card
 * starts at the centre of its column, so +1 pitch lands one column
 * over and +2 pitches land two over, whatever the density is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BoardDragTest {

    companion object {
        // S4-1 round 10 — TEMPORARY DIAGNOSTIC (delete once the leaker is
        // named). The CI log prints only "<type> at <file>:<line>" for a
        // test failure, so the UncaughtExceptionsBeforeTest wrapper hides
        // the underlying exception. This ClassRule runs BEFORE any test
        // method's runTest enter(), and reflectively reads the
        // coroutines-test ExceptionCollector's `unprocessedExceptions`
        // buffer (still holding the poison — nothing consumed it, or a
        // test between the leaker and here would have failed instead).
        // If non-empty it RE-THROWS the first buffered exception itself,
        // so the CI log shows THAT exception's type + throw site (the
        // leaker), not the wrapper. If the buffer is empty and this class
        // still fails, the leaker is BoardDragTest's own method 1.
        @ClassRule
        @JvmField
        val revealLeakRule = object : org.junit.rules.TestRule {
            override fun apply(
                base: org.junit.runners.model.Statement,
                description: org.junit.runner.Description
            ): org.junit.runners.model.Statement {
                return object : org.junit.runners.model.Statement() {
                    override fun evaluate() {
                        val buffered = readUnprocessedExceptions()
                        if (buffered.isNotEmpty()) {
                            // Re-throw the poison itself — its top frame is
                            // the leaker's throw site.
                            throw buffered.first()
                        }
                        base.evaluate()
                    }
                }
            }
        }

        private fun readUnprocessedExceptions(): List<Throwable> {
            return try {
                val clazz =
                    Class.forName("kotlinx.coroutines.test.internal.ExceptionCollector")
                val instanceField = clazz.getDeclaredField("INSTANCE")
                instanceField.isAccessible = true
                val instance = instanceField.get(null)
                val listField = clazz.getDeclaredField("unprocessedExceptions")
                listField.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                (listField.get(instance) as? List<Throwable>)?.toList().orEmpty()
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    @get:Rule
    val composeRule = createComposeRule()

    private fun item(id: String, status: TaskStatus, completed: Boolean = false) =
        TaskListItem(
            task = TaskEntity(
                id = id,
                userId = "u1",
                title = id,
                status = status,
                isCompleted = completed
            )
        )

    private val items = listOf(
        item("a", TaskStatus.TODO),
        item("b", TaskStatus.IN_PROGRESS),
        item("c", TaskStatus.DONE, completed = true)
    )

    private fun pitchPx(): Float = with(composeRule.density) { 284.dp.toPx() }

    private fun dragCard(
        tag: String,
        delta: Offset,
        moved: MutableList<Pair<TaskEntity, TaskStatus>>
    ) {
        composeRule.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                TaskBoard(
                    items = items,
                    onMoveTask = { task, status, _, _ -> moved.add(task to status) },
                    onToggle = {},
                    onTaskClick = {}
                )
            }
        }
        composeRule.onNodeWithTag(tag).performTouchInput {
            down(center)
            // Past the long-press timeout, so the drag starts.
            advanceEventTime(1000)
            moveBy(delta)
            up()
        }
    }

    @Test
    fun `long-press drag one column right moves the task`() {
        val moved = mutableListOf<Pair<TaskEntity, TaskStatus>>()
        dragCard("board_card_a", Offset(pitchPx(), 0f), moved)
        composeRule.runOnIdle {
            assertEquals(
                listOf(items[0].task to TaskStatus.IN_PROGRESS),
                moved
            )
        }
    }

    @Test
    fun `long-press drag two columns right moves the task to Done`() {
        val moved = mutableListOf<Pair<TaskEntity, TaskStatus>>()
        dragCard("board_card_a", Offset(2 * pitchPx(), 0f), moved)
        composeRule.runOnIdle {
            assertEquals(
                listOf(items[0].task to TaskStatus.DONE),
                moved
            )
        }
    }

    @Test
    fun `drag released inside the source column does nothing`() {
        val moved = mutableListOf<Pair<TaskEntity, TaskStatus>>()
        val small = with(composeRule.density) { 20.dp.toPx() }
        dragCard("board_card_a", Offset(small, 0f), moved)
        composeRule.runOnIdle {
            assertTrue(moved.isEmpty())
        }
    }
}

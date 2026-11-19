package com.nextgentool.nudgy.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.model.TaskStatus
import com.nextgentool.nudgy.ui.viewmodel.TaskListItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * Sprint 1-4c — reproduction tests for the two remaining Board drag
 * defects on a real device (ci-434): the drag preview appears far
 * from the finger after the board is scrolled, and auto-scroll never
 * fires. Written RED first: each test must fail on the S1-4b code
 * and pass once the position plumbing is fixed.
 *
 * The board is rendered 360dp wide so the three 272dp columns
 * (840dp of content) always need real horizontal scrolling; the
 * 12-card TODO column needs real vertical scrolling.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BoardScrollReproTest {

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

    private fun px(dp: Int): Float = with(composeRule.density) { dp.dp.toPx() }

    private fun setBoard(
        items: List<TaskListItem>,
        onMoveTask: (TaskEntity, TaskStatus) -> Unit = { _, _ -> }
    ) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                TaskBoard(
                    items = items,
                    onMoveTask = onMoveTask,
                    onToggle = {},
                    onTaskClick = {},
                    modifier = Modifier.width(360.dp)
                )
            }
        }
    }

    @Test(timeout = 60_000)
    fun `drag preview stays under the finger after the board is scrolled`() {
        val items = listOf(
            item("a", TaskStatus.TODO),
            item("b", TaskStatus.IN_PROGRESS),
            item("c", TaskStatus.DONE, completed = true)
        )
        setBoard(items)
        // Scroll the board so Done is visible, then drag its card.
        composeRule.onNodeWithTag("board_column_DONE").performScrollTo()
        var fingerX = 0f
        var previewCenterX = 0f
        composeRule.onNodeWithTag("board_card_c").performTouchInput {
            down(center)
            advanceEventTime(1000)
            moveBy(Offset(2f, 0f))
            // Read while the drag is in flight: the preview only exists then.
            val card = composeRule.onNodeWithTag("board_card_c")
                .fetchSemanticsNode().boundsInRoot
            val preview = composeRule.onNodeWithTag("board_drag_preview")
                .fetchSemanticsNode().boundsInRoot
            fingerX = card.center.x + 2f
            previewCenterX = preview.center.x
            up()
        }
        val tolerance = px(8)
        assertTrue(
            "preview center x=$previewCenterX should be within $tolerance px " +
                "of finger x=$fingerX (board was scrolled first)",
            abs(previewCenterX - fingerX) < tolerance
        )
    }

    @Test(timeout = 60_000)
    fun `drag from a scrolled column lands in the dragged-to column`() {
        val moved = mutableListOf<Pair<TaskEntity, TaskStatus>>()
        val items = listOf(
            item("a", TaskStatus.TODO),
            item("b", TaskStatus.IN_PROGRESS),
            item("c", TaskStatus.DONE, completed = true)
        )
        setBoard(items) { task, status -> moved.add(task to status) }
        composeRule.onNodeWithTag("board_column_DONE").performScrollTo()
        val pitch = px(284)
        composeRule.onNodeWithTag("board_card_c").performTouchInput {
            down(center)
            advanceEventTime(1000)
            moveBy(Offset(-pitch, 0f))
            up()
        }
        composeRule.runOnIdle {
            assertEquals(
                listOf(items[2].task to TaskStatus.IN_PROGRESS),
                moved
            )
        }
    }

    @Test(timeout = 60_000)
    fun `holding near the right edge scrolls the board right`() {
        val items = listOf(
            item("a", TaskStatus.TODO),
            item("b", TaskStatus.IN_PROGRESS),
            item("c", TaskStatus.DONE, completed = true)
        )
        setBoard(items)
        val board = composeRule.onNodeWithTag("task_board")
            .fetchSemanticsNode().boundsInRoot
        val cardA = composeRule.onNodeWithTag("board_card_a")
            .fetchSemanticsNode().boundsInRoot
        val doneBefore = composeRule.onNodeWithTag("board_column_DONE")
            .fetchSemanticsNode().boundsInRoot.left
        // Move the finger to the middle of the right edge zone (24dp from the edge).
        val targetX = board.left + board.width - px(24)
        val dx = targetX - cardA.center.x
        composeRule.onNodeWithTag("board_card_a").performTouchInput {
            down(center)
            advanceEventTime(1000)
            moveBy(Offset(dx, 0f))
            // Hold at the edge: let the auto-scroll loop tick.
            composeRule.mainClock.advanceTimeBy(1500)
            up()
        }
        val doneAfter = composeRule.onNodeWithTag("board_column_DONE")
            .fetchSemanticsNode().boundsInRoot.left
        assertTrue(
            "board should have scrolled right while held at the edge " +
                "(Done left $doneBefore -> $doneAfter)",
            doneBefore - doneAfter > px(50)
        )
    }

    @Test(timeout = 60_000)
    fun `holding near the bottom edge scrolls the column down`() {
        val items = (1..12).map { item("t$it", TaskStatus.TODO) } +
            listOf(
                item("b", TaskStatus.IN_PROGRESS),
                item("c", TaskStatus.DONE, completed = true)
            )
        setBoard(items)
        val board = composeRule.onNodeWithTag("task_board")
            .fetchSemanticsNode().boundsInRoot
        val first = composeRule.onNodeWithTag("board_card_t1")
            .fetchSemanticsNode().boundsInRoot
        val lastBefore = composeRule.onNodeWithTag("board_card_t12")
            .fetchSemanticsNode().boundsInRoot.top
        // Move the finger to the middle of the bottom edge zone (28dp from the edge).
        val targetY = board.top + board.height - px(28)
        val dy = targetY - first.center.y
        composeRule.onNodeWithTag("board_card_t1").performTouchInput {
            down(center)
            advanceEventTime(1000)
            moveBy(Offset(0f, dy))
            // Hold at the edge: let the auto-scroll loop tick.
            composeRule.mainClock.advanceTimeBy(1500)
            up()
        }
        val lastAfter = composeRule.onNodeWithTag("board_card_t12")
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue(
            "column should have scrolled down while held at the bottom edge " +
                "(last card top $lastBefore -> $lastAfter)",
            lastBefore - lastAfter > px(50)
        )
    }
}

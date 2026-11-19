package com.nextgentool.nudgy.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.model.TaskStatus
import com.nextgentool.nudgy.ui.viewmodel.TaskListItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
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
 * The board is rendered 360dp wide x 300dp tall so the three 272dp
 * columns (840dp of content) always need real horizontal scrolling
 * and a 12-card column always needs real vertical scrolling.
 *
 * Harness rules learned the hard way (the first two versions of
 * this file hung the CI test step for 12–20 minutes): never call
 * performScrollTo while the main clock is paused — the board is
 * scrolled instead through TaskBoard's test seams (a ScrollState
 * passed in, either pre-scrolled via ScrollState(initial = …) or
 * scrolled after layout via runBlocking { scrollTo(…) }); and never
 * read semantics during an active drag — the preview position is
 * asserted through TaskBoard's onDragPositionChanged hook (the
 * preview is centered on exactly that value, so it is the same
 * assertion). The auto-scroll tests run last (a/b/c/d/e order) so
 * a hang there cannot hide the safe tests' results.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
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
        boardScroll: ScrollState? = null,
        boardHeight: Dp = 300.dp,
        onMoveTask: (TaskEntity, TaskStatus) -> Unit = { _, _ -> },
        onDragPositionChanged: ((Offset) -> Unit)? = null
    ) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                TaskBoard(
                    items = items,
                    onMoveTask = onMoveTask,
                    onToggle = {},
                    onTaskClick = {},
                    modifier = Modifier.width(360.dp),
                    onDragPositionChanged = onDragPositionChanged,
                    boardScrollState = boardScroll,
                    boardHeight = boardHeight
                )
            }
        }
    }

    /** Finger position (board-viewport px) of a card's centre, read before any gesture. */
    private fun fingerOf(cardTag: String): Offset {
        val board = composeRule.onNodeWithTag("task_board")
            .fetchSemanticsNode().boundsInRoot
        val card = composeRule.onNodeWithTag(cardTag)
            .fetchSemanticsNode().boundsInRoot
        return Offset(card.center.x - board.left, card.center.y - board.top)
    }

    @Test(timeout = 60_000)
    fun `a_drag from a scrolled column lands in the dragged-to column`() {
        val moved = mutableListOf<Pair<TaskEntity, TaskStatus>>()
        val items = listOf(
            item("a", TaskStatus.TODO),
            item("b", TaskStatus.IN_PROGRESS),
            item("c", TaskStatus.DONE, completed = true)
        )
        // Board rendered already scrolled to Done (max scroll 480dp).
        setBoard(
            items,
            boardScroll = ScrollState(px(480).toInt()),
            onMoveTask = { task, status -> moved.add(task to status) }
        )
        composeRule.runOnIdle {}
        val pitch = px(284)
        composeRule.onNodeWithTag("board_card_c").performTouchInput {
            down(center)
            advanceEventTime(1000)
            moveBy(Offset(-pitch, 0f))
            up()
        }
        composeRule.runOnIdle {
            println("S14C-DIAG a moved=$moved")
            assertEquals(
                listOf(items[2].task to TaskStatus.IN_PROGRESS),
                moved
            )
        }
    }

    @Test(timeout = 60_000)
    fun `b_drag preview stays under the finger when board is rendered scrolled`() {
        val items = listOf(
            item("a", TaskStatus.TODO),
            item("b", TaskStatus.IN_PROGRESS),
            item("c", TaskStatus.DONE, completed = true)
        )
        val positions = mutableListOf<Offset>()
        setBoard(
            items,
            boardScroll = ScrollState(px(480).toInt()),
            onDragPositionChanged = { positions.add(it) }
        )
        composeRule.runOnIdle {}
        val finger = fingerOf("board_card_c")
        composeRule.onNodeWithTag("board_card_c").performTouchInput {
            down(center)
            advanceEventTime(1000)
            moveBy(Offset(2f, 0f))
            up()
        }
        composeRule.runOnIdle {
            assertTrue("expected at least one drag position report, got none", positions.isNotEmpty())
            val first = positions.first()
            val tolerance = px(8)
            println("S14C-DIAG b rendered-scrolled first=$first finger=$finger dx=${first.x - finger.x} dy=${first.y - finger.y}")
            assertTrue(
                "drag start position $first should be within $tolerance px " +
                    "of finger $finger (board rendered scrolled)",
                abs(first.x - finger.x) < tolerance &&
                    abs(first.y - finger.y) < tolerance
            )
        }
    }

    @Test(timeout = 60_000)
    fun `c_drag preview stays under the finger when board is scrolled after layout`() {
        val items = listOf(
            item("a", TaskStatus.TODO),
            item("b", TaskStatus.IN_PROGRESS),
            item("c", TaskStatus.DONE, completed = true)
        )
        val positions = mutableListOf<Offset>()
        val boardScroll = ScrollState(0)
        setBoard(
            items,
            boardScroll = boardScroll,
            onDragPositionChanged = { positions.add(it) }
        )
        composeRule.runOnIdle {}
        // The device sequence: layout first, THEN scroll, then drag.
        // If the cached card/board positions do not follow the scroll,
        // the drag start is off by exactly the scroll amount.
        runBlocking { boardScroll.scrollTo(px(480).toInt()) }
        composeRule.runOnIdle {}
        val finger = fingerOf("board_card_c")
        composeRule.onNodeWithTag("board_card_c").performTouchInput {
            down(center)
            advanceEventTime(1000)
            moveBy(Offset(2f, 0f))
            up()
        }
        composeRule.runOnIdle {
            assertTrue("expected at least one drag position report, got none", positions.isNotEmpty())
            val first = positions.first()
            val tolerance = px(8)
            println("S14C-DIAG c scrolled-after-layout first=$first finger=$finger dx=${first.x - finger.x} dy=${first.y - finger.y}")
            assertTrue(
                "drag start position $first should be within $tolerance px " +
                    "of finger $finger (board scrolled after layout)",
                abs(first.x - finger.x) < tolerance &&
                    abs(first.y - finger.y) < tolerance
            )
        }
    }

    @Test(timeout = 60_000)
    fun `d_holding near the right edge scrolls the board right`() {
        val items = listOf(
            item("a", TaskStatus.TODO),
            item("b", TaskStatus.IN_PROGRESS),
            item("c", TaskStatus.DONE, completed = true)
        )
        setBoard(items)
        composeRule.runOnIdle {}
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
        println("S14C-DIAG d doneBefore=$doneBefore doneAfter=$doneAfter delta=${doneBefore - doneAfter} threshold=${px(50)}")
        assertTrue(
            "board should have scrolled right while held at the edge " +
                "(Done left $doneBefore -> $doneAfter)",
            doneBefore - doneAfter > px(50)
        )
    }

    @Test(timeout = 60_000)
    fun `e_holding near the bottom edge scrolls the column down`() {
        val items = (1..12).map { item("t$it", TaskStatus.TODO) } +
            listOf(
                item("b", TaskStatus.IN_PROGRESS),
                item("c", TaskStatus.DONE, completed = true)
            )
        setBoard(items)
        composeRule.runOnIdle {}
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
        println("S14C-DIAG e lastBefore=$lastBefore lastAfter=$lastAfter delta=${lastBefore - lastAfter} threshold=${px(50)}")
        assertTrue(
            "column should have scrolled down while held at the bottom edge " +
                "(last card top $lastBefore -> $lastAfter)",
            lastBefore - lastAfter > px(50)
        )
    }
}

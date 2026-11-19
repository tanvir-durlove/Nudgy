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
 * Sprint 1-4d adds f/g/h/i: the left edge was never covered — d/e prove
 * right + down only. f drives the tick left; g asserts the loop's
 * INPUT during a leftward drag (the reported pointer must reach the
 * left edge zone), because the loop itself cannot tick during a held
 * gesture in Robolectric. h proves the Row does not scroll manually
 * during a long-press drag (the detector consumes the drag — see
 * DragGestureDetector.kt — so the loop is the only scroll mechanism).
 * i holds the pointer at the left edge while advancing the main clock
 * to see whether the loop ticks there at all.
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
 * assertion). The auto-scroll edge logic is proven by driving one
 * tick of the loop directly (boardAutoScrollTick) — Robolectric
 * cannot tick the loop's delay during a held gesture (proven by
 * three harness approaches: paused-clock advance, split-gesture
 * advance, real-time sleep), so the hold end-to-end is a
 * device-test item for the retest build. The tests run last
 * (a/b/c/d/e order) so a hang there cannot hide the safe tests'
 * results.
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
        columnScrolls: Map<TaskStatus, ScrollState>? = null,
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
                    columnScrollStates = columnScrolls,
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
        // If the drag-start position does not follow the scroll, it
        // is off by exactly the scroll amount.
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
            assertTrue(
                "drag start position $first should be within $tolerance px " +
                    "of finger $finger (board scrolled after layout)",
                abs(first.x - finger.x) < tolerance &&
                    abs(first.y - finger.y) < tolerance
            )
        }
    }

    @Test(timeout = 60_000)
    fun `d_auto-scroll tick scrolls the board right at the right edge`() {
        val items = listOf(
            item("a", TaskStatus.TODO),
            item("b", TaskStatus.IN_PROGRESS),
            item("c", TaskStatus.DONE, completed = true)
        )
        val boardScroll = ScrollState(0)
        val columnScrolls = TaskStatus.entries.associateWith { ScrollState(0) }
        setBoard(items, boardScroll = boardScroll, columnScrolls = columnScrolls)
        composeRule.runOnIdle {}
        val board = composeRule.onNodeWithTag("task_board")
            .fetchSemanticsNode().boundsInRoot
        // Pointer in the middle of the right edge zone (24dp from the
        // edge), vertically centred so no column scrolls. Five ticks
        // at 24dp each move the board 120dp. The ScrollState value is
        // the ground truth: on a device a value change always
        // re-places the content. (Semantics bounds do NOT update
        // after a programmatic scroll in Robolectric — proven by the
        // state assertion passing while the bounds assertion failed —
        // so they are not asserted here; the visible movement is a
        // device-test item for the retest build.)
        val pointer = Offset(board.width - px(24), board.height / 2)
        runBlocking {
            repeat(5) {
                boardAutoScrollTick(
                    pointer,
                    androidx.compose.ui.unit.IntSize(
                        board.width.toInt(), board.height.toInt()
                    ),
                    boardScroll,
                    columnScrolls,
                    px(284),
                    px(48),
                    px(56),
                    px(24)
                )
            }
        }
        assertTrue(
            "board scroll state should have moved (value=${boardScroll.value})",
            boardScroll.value > px(50).toInt()
        )
    }

    @Test(timeout = 60_000)
    fun `e_auto-scroll tick scrolls the column down at the bottom edge`() {
        val items = (1..12).map { item("t$it", TaskStatus.TODO) } +
            listOf(
                item("b", TaskStatus.IN_PROGRESS),
                item("c", TaskStatus.DONE, completed = true)
            )
        val boardScroll = ScrollState(0)
        val columnScrolls = TaskStatus.entries.associateWith { ScrollState(0) }
        setBoard(items, boardScroll = boardScroll, columnScrolls = columnScrolls)
        composeRule.runOnIdle {}
        val board = composeRule.onNodeWithTag("task_board")
            .fetchSemanticsNode().boundsInRoot
        // Pointer over the TODO column (x = its centre) in the middle
        // of the bottom edge zone (28dp from the edge), so the TODO
        // column scrolls and the board does not. Five ticks at 24dp
        // each move the column 120dp. Same ground-truth note as d:
        // the ScrollState value proves the scroll; the visible
        // movement is a device-test item.
        val pointer = Offset(px(136), board.height - px(28))
        runBlocking {
            repeat(5) {
                boardAutoScrollTick(
                    pointer,
                    androidx.compose.ui.unit.IntSize(
                        board.width.toInt(), board.height.toInt()
                    ),
                    boardScroll,
                    columnScrolls,
                    px(284),
                    px(48),
                    px(56),
                    px(24)
                )
            }
        }
        val todoScroll = columnScrolls.getValue(TaskStatus.TODO).value
        assertTrue(
            "TODO column scroll state should have moved (value=$todoScroll)",
            todoScroll > px(50).toInt()
        )
    }

    @Test(timeout = 60_000)
    fun `f_auto-scroll tick scrolls the board left at the left edge`() {
        val items = listOf(
            item("a", TaskStatus.TODO),
            item("b", TaskStatus.IN_PROGRESS),
            item("c", TaskStatus.DONE, completed = true)
        )
        // Board rendered already scrolled right to Done (max scroll
        // 480dp), so a left tick has room to move.
        val boardScroll = ScrollState(px(480).toInt())
        val columnScrolls = TaskStatus.entries.associateWith { ScrollState(0) }
        setBoard(items, boardScroll = boardScroll, columnScrolls = columnScrolls)
        composeRule.runOnIdle {}
        val board = composeRule.onNodeWithTag("task_board")
            .fetchSemanticsNode().boundsInRoot
        // Pointer in the middle of the left edge zone (24dp from the
        // edge), vertically centred so no column scrolls. Five ticks
        // at 24dp each move the board 120dp left. Same ground-truth
        // note as d: the ScrollState value proves the scroll.
        val pointer = Offset(px(24), board.height / 2)
        runBlocking {
            repeat(5) {
                boardAutoScrollTick(
                    pointer,
                    androidx.compose.ui.unit.IntSize(
                        board.width.toInt(), board.height.toInt()
                    ),
                    boardScroll,
                    columnScrolls,
                    px(284),
                    px(48),
                    px(56),
                    px(24)
                )
            }
        }
        assertTrue(
            "board scroll state should have moved left (value=${boardScroll.value})",
            boardScroll.value < px(480).toInt() - px(50).toInt()
        )
    }

    @Test(timeout = 60_000)
    fun `g_leftward drag from a scrolled column reports positions reaching the left edge zone`() {
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
        // Long-press the Done card and drag it two pitches left, the
        // device sequence for dragging back toward To do. The loop
        // cannot tick during a held gesture in Robolectric, so this
        // test asserts only the INPUT the loop would see: the reported
        // pointer positions must reach the left edge zone (x < 48dp).
        // Separate assertions on separate lines, so the failing line
        // localises the mechanism (CI strips assertion messages).
        val pitch = px(284)
        composeRule.onNodeWithTag("board_card_c").performTouchInput {
            down(center)
            advanceEventTime(1000)
            moveBy(Offset(-2 * pitch, 0f))
            up()
        }
        composeRule.runOnIdle {
            assertTrue(positions.isNotEmpty())
            val minX = positions.minOf { it.x }
            assertTrue(minX < px(100))
            assertTrue(minX < px(48))
        }
    }

    @Test(timeout = 60_000)
    fun `h_board does not scroll manually during a long-press drag`() {
        val items = listOf(
            item("a", TaskStatus.TODO),
            item("b", TaskStatus.IN_PROGRESS),
            item("c", TaskStatus.DONE, completed = true)
        )
        val boardScroll = ScrollState(0)
        setBoard(items, boardScroll = boardScroll)
        composeRule.runOnIdle {}
        // Long-press the To do card and drag it right 200dp. The loop
        // cannot tick during a held gesture in Robolectric, so any
        // board movement here would be the Row's manual scroll — the
        // drag detector not consuming the drag. The loop is the only
        // sanctioned scroll mechanism during a drag, so the board
        // must stay put until the loop ticks.
        composeRule.onNodeWithTag("board_card_a").performTouchInput {
            down(center)
            advanceEventTime(1000)
            moveBy(Offset(px(200), 0f))
            up()
        }
        composeRule.runOnIdle {
            assertEquals(0, boardScroll.value)
        }
    }

    @Test(timeout = 60_000)
    fun `i_holding at the left edge scrolls the board left (loop end-to-end)`() {
        val items = listOf(
            item("a", TaskStatus.TODO),
            item("b", TaskStatus.IN_PROGRESS),
            item("c", TaskStatus.DONE, completed = true)
        )
        val boardScroll = ScrollState(px(480).toInt())
        val positions = mutableListOf<Offset>()
        setBoard(
            items,
            boardScroll = boardScroll,
            onDragPositionChanged = { positions.add(it) }
        )
        composeRule.runOnIdle {}
        // Long-press the Done card, drag it left into the left edge
        // zone (24dp from the edge), HOLD there while the main clock
        // advances so the LaunchedEffect loop can tick, then release.
        // The loop is the only scroll mechanism during a drag (h
        // proves no manual scroll), so a ScrollState decrease here
        // proves the loop fires for the left edge on a held drag. No
        // semantics are read while the gesture is held (that hangs
        // the test worker).
        composeRule.onNodeWithTag("board_card_c").performTouchInput {
            down(center)
            advanceEventTime(1000)
            moveBy(Offset(-px(200), 0f))
            composeRule.mainClock.advanceTimeBy(1000)
            up()
        }
        composeRule.runOnIdle {
            assertTrue(positions.isNotEmpty())
            assertTrue(boardScroll.value < px(480).toInt() - px(50).toInt())
        }
    }

    @Test(timeout = 60_000)
    fun `j_drag position stays exact when the board scrolls mid-drag`() {
        val items = listOf(
            item("a", TaskStatus.TODO),
            item("b", TaskStatus.IN_PROGRESS),
            item("c", TaskStatus.DONE, completed = true)
        )
        val boardScroll = ScrollState(px(480).toInt())
        val positions = mutableListOf<Offset>()
        setBoard(
            items,
            boardScroll = boardScroll,
            onDragPositionChanged = { positions.add(it) }
        )
        composeRule.runOnIdle {}
        // Drag the Done card left 50dp (finger 224 -> 174), then scroll
        // the board left 80dp programmatically (the card moves right
        // 80dp under the stationary finger — exactly what the
        // auto-scroll loop does on a device between motion events),
        // then drag left 50dp more. If the drag deltas are
        // scroll-stable, the last reported x is the finger's viewport
        // position, 124dp. If the deltas include the scroll shift,
        // the reported x jumps by the shift to 44dp. Range assertions
        // on separate lines so the failing line names the outcome
        // (CI strips assertion messages).
        composeRule.onNodeWithTag("board_card_c").performTouchInput {
            down(center)
            advanceEventTime(1000)
            moveBy(Offset(-px(50), 0f))
            runBlocking { boardScroll.scrollTo(px(400).toInt()) }
            composeRule.mainClock.advanceTimeBy(100)
            moveBy(Offset(-px(50), 0f))
            up()
        }
        composeRule.runOnIdle {
            assertTrue(positions.isNotEmpty())
            val last = positions.last()
            assertTrue(last.x > px(80))
            assertTrue(last.x < px(150))
        }
    }

    @Test(timeout = 60_000)
    fun `k_drag position jumps by the scroll shift when the board scrolls mid-drag`() {
        val items = listOf(
            item("a", TaskStatus.TODO),
            item("b", TaskStatus.IN_PROGRESS),
            item("c", TaskStatus.DONE, completed = true)
        )
        val boardScroll = ScrollState(px(480).toInt())
        val positions = mutableListOf<Offset>()
        setBoard(
            items,
            boardScroll = boardScroll,
            onDragPositionChanged = { positions.add(it) }
        )
        composeRule.runOnIdle {}
        // Same device sequence as j, but asserting the shift-included
        // value (44dp). j and k together discriminate the two models:
        // j passes + k fails => deltas are scroll-stable; j fails +
        // k passes => the scroll shift enters the deltas.
        composeRule.onNodeWithTag("board_card_c").performTouchInput {
            down(center)
            advanceEventTime(1000)
            moveBy(Offset(-px(50), 0f))
            runBlocking { boardScroll.scrollTo(px(400).toInt()) }
            composeRule.mainClock.advanceTimeBy(100)
            moveBy(Offset(-px(50), 0f))
            up()
        }
        composeRule.runOnIdle {
            assertTrue(positions.isNotEmpty())
            val last = positions.last()
            assertTrue(abs(last.x - px(44)) < px(8))
        }
    }
}

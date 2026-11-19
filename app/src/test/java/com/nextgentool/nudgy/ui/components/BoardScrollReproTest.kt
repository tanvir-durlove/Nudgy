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
 * Board scroll + preview reproduction tests (S1-4c / S1-4d).
 *
 * S1-4c root cause, proven here: drag start computed the pointer as
 * card.localToRoot - board.localToRoot, which is board-CONTENT
 * coordinates, not viewport — the scroll offset cancels in the
 * subtraction. The fix subtracts the scroll offsets at drag start.
 *
 * S1-4d (left auto-scroll): the tick and the positions were proven
 * correct (f, g), the loop cannot be driven in a test (i's first
 * form held at the edge + advanced the clock 1000ms and still saw
 * no scroll — the documented harness limit), and the drag deltas
 * are scroll-stable (j: the pointer stays exact when the board
 * scrolls mid-drag; k, which asserted the opposite shift model,
 * failed and was removed). So the moving phase no longer depends
 * on the loop alone: the tick is now synchronous (dispatchRawDelta)
 * and is also called from onDrag, throttled to the loop's 32ms
 * cadence by event time. Tests i and l prove that move path
 * (left + right); the loop remains for dwelling and is verified
 * on device.
 *
 * S1-4f (half-visible column): dragging DOWN in a column that is
 * only half (or less) visible at the screen edge never scrolls it,
 * while a fully-visible column scrolls fine. The finger sits in the
 * board's horizontal edge zone for the whole drag, so the tick's
 * horizontal branch scrolls the board right on every move while the
 * pointer travels down — by the time it reaches the bottom edge
 * zone the board has scrolled so far that boardColumnAt flips to
 * the next column (one card, no overflow), so the dragged column
 * never scrolls at all. The small 10dp moves in test q matter:
 * with big moves the pointer reaches the bottom zone before the
 * board has scrolled far enough to flip, and the bug hides. Test q
 * (card-drag repro) and test r (plain swipe, the other reading)
 * separate the two behaviours: q must fail on the old code, r
 * must pass.
 *
 * S1-4e (vertical auto-scroll): the horizontal branches are proven
 * on device, but vertical never fired. The prime suspect is the
 * drag-start y: it subtracts this column's scroll, but the board
 * does NOT sit inside the column's vertical scroll, so the
 * difference is already the viewport y and the subtraction
 * double-counts it — the reported y is short by exactly the column
 * scroll, which puts the bottom edge zone out of reach. Tests m
 * (drag-start position with a pre-scrolled column) and o (drag
 * down through the bottom zone) reproduce it; n (tick top zone)
 * and p (drag up through the top zone) pin the parts that already
 * work. A column must actually overflow for vertical scroll to be
 * possible — the tests use 12 cards against a ~240dp viewport.
 *
 * S1-4d round 4 (both CI runs failed h + l, i passed) proved TWO
 * bugs in the first scroll-on-move implementation: (1) the move
 * throttle was seeded with Long.MIN_VALUE, and the first
 * subtraction (uptime - MIN_VALUE) overflows to a negative number
 * for any non-negative uptime — so scroll-on-move never fired at
 * all; the throttle is now a nullable Long (null = no move
 * scrolled yet). (2) boardSize was measured on the Row inside
 * horizontalScroll, which reports the CONTENT width (840dp), so
 * the right edge zone sat at x > 792dp — unreachable; boardSize
 * is now measured on the outer Box (the viewport). The left edge
 * does not depend on the width, which is why i passed while h/l
 * failed — the asymmetry was the tell.
 *
 * Ground rules, learned the hard way:
 * - Robolectric cannot tick a delay() loop during a held gesture.
 *   The loop is covered by direct tick tests (d/e/f) + device.
 * - Compose semantics boundsInRoot does NOT update after a
 *   programmatic scroll (S1-4c) — so tests assert the ScrollState
 *   value (the ground truth; a value change always re-places
 *   content on device), never bounds.
 * - Never read semantics during an active drag — it hangs the
 *   test worker. Never call performScrollTo with the main clock
 *   paused — it hangs. Use a synchronous hook
 *   (onDragPositionChanged) to observe the drag.
 * - Layout coordinates must be captured FRESH inside the drag
 *   gesture (localToRoot at drag start). Cached Offsets go stale
 *   the moment anything scrolls.
 * - Keep the clock REAL in these tests (createComposeRule, no
 *   paused clock): pauseClock + gesture APIs fight each other.
 *   Event time is advanced with advanceEventTime inside
 *   performTouchInput.
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
    fun `h_drag scrolls the board by stepped auto-scroll ticks, not by following the finger`() {
        val items = listOf(
            item("a", TaskStatus.TODO),
            item("b", TaskStatus.IN_PROGRESS),
            item("c", TaskStatus.DONE, completed = true)
        )
        val boardScroll = ScrollState(0)
        setBoard(items, boardScroll = boardScroll)
        composeRule.runOnIdle {}
        // Long-press the To do card and drag it right 200dp in one
        // move event. The pointer lands at 336dp — inside the right
        // edge zone — so scroll-on-move (S1-4d) fires exactly once:
        // one stepped 24dp tick. A manual scroll following the
        // finger would move ~200dp; no scroll at all would be 0.
        // (The old form of this test asserted value == 0, which was
        // right before scroll-on-move existed: then the loop was the
        // only sanctioned scroll mechanism and it cannot tick during
        // a held gesture in Robolectric. Now the move path scrolls
        // too, and this test pins that it scrolls in steps.)
        composeRule.onNodeWithTag("board_card_a").performTouchInput {
            down(center)
            advanceEventTime(1000)
            moveBy(Offset(px(200), 0f))
            up()
        }
        composeRule.runOnIdle {
            assertTrue(boardScroll.value > 0)
            assertTrue(boardScroll.value < px(100).toInt())
        }
    }

    @Test(timeout = 60_000)
    fun `i_dragging left through the edge zone scrolls the board left (scroll-on-move)`() {
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
        // Long-press the Done card (centre 224dp) and drag it left in
        // steps, 50ms of event time apart (the move-scroll throttle
        // is 32ms). Three -70dp moves put the pointer at 14dp
        // (inside the left edge zone), then two -10dp moves stay in
        // the zone — three ticks in the zone, so 3 x 24dp = 72dp of
        // left scroll, with NO clock advance. The scroll is
        // synchronous on the move event, so the board has already
        // moved when the gesture ends. (The old form of this test
        // held at the edge + advanced the main clock 1000ms to drive
        // the loop — and still saw no scroll. That is the documented
        // harness limit, not a product failure: the loop cannot tick
        // during a held gesture in Robolectric. The loop remains for
        // dwelling and is verified on device; this test proves the
        // move path instead. No semantics are read while the gesture
        // is held — that hangs the test worker.)
        composeRule.onNodeWithTag("board_card_c").performTouchInput {
            down(center)
            advanceEventTime(1000)
            repeat(3) {
                moveBy(Offset(-px(70), 0f))
                advanceEventTime(50)
            }
            repeat(2) {
                moveBy(Offset(-px(10), 0f))
                advanceEventTime(50)
            }
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

    /**
     * l — dragging RIGHT through the edge zone scrolls the board
     * right (scroll-on-move, S1-4d): the mirror of test i, proving
     * the new move path works in both directions. (Test k, which
     * asserted the shift-included drag position when the board
     * scrolls mid-drag, was removed in S1-4d: test j proved the
     * deltas are scroll-stable, so k's model was wrong.)
     */
    @Test(timeout = 60_000)
    fun `l_dragging right through the edge zone scrolls the board right (scroll-on-move)`() {
        val items = listOf(
            item("a", TaskStatus.TODO),
            item("b", TaskStatus.IN_PROGRESS),
            item("c", TaskStatus.DONE, completed = true)
        )
        val boardScroll = ScrollState(0)
        val positions = mutableListOf<Offset>()
        setBoard(
            items,
            boardScroll = boardScroll,
            onDragPositionChanged = { positions.add(it) }
        )
        composeRule.runOnIdle {}
        // To do card centre is 136dp; three +70dp moves put the
        // pointer at 346dp (inside the right edge zone, which starts
        // at 360 - 48 = 312dp), then two +10dp moves stay in the
        // zone — three ticks, 50ms apart, so 3 x 24dp = 72dp of
        // right scroll, with no clock advance.
        composeRule.onNodeWithTag("board_card_a").performTouchInput {
            down(center)
            advanceEventTime(1000)
            repeat(3) {
                moveBy(Offset(px(70), 0f))
                advanceEventTime(50)
            }
            repeat(2) {
                moveBy(Offset(px(10), 0f))
                advanceEventTime(50)
            }
            up()
        }
        composeRule.runOnIdle {
            assertTrue(positions.isNotEmpty())
            assertTrue(boardScroll.value > px(50).toInt())
        }
    }

    /**
     * m — S1-4e repro: drag-start y is exact when the column is
     * rendered pre-scrolled. The device reports vertical auto-scroll
     * never fires; the prime suspect is the drag-start y: it
     * subtracts this column's scroll, but the board does NOT sit
     * inside the column's vertical scroll, so the difference is
     * already the viewport y and the subtraction double-counts it
     * — the reported y is short by exactly the column scroll. If
     * the suspect is right, this fails by the pre-scroll amount
     * (200dp). The x and y assertions are on separate lines, so
     * the failing line localises the mechanism (CI strips
     * assertion messages).
     */
    @Test(timeout = 60_000)
    fun `m_drag start y is exact when the column is rendered pre-scrolled`() {
        val items = (1..12).map { item("t$it", TaskStatus.TODO) } +
            listOf(
                item("b", TaskStatus.IN_PROGRESS),
                item("c", TaskStatus.DONE, completed = true)
            )
        val positions = mutableListOf<Offset>()
        val columnScrolls = TaskStatus.entries.associateWith {
            if (it == TaskStatus.TODO) ScrollState(px(200).toInt()) else ScrollState(0)
        }
        setBoard(
            items,
            columnScrolls = columnScrolls,
            onDragPositionChanged = { positions.add(it) }
        )
        composeRule.runOnIdle {}
        val finger = fingerOf("board_card_t4")
        composeRule.onNodeWithTag("board_card_t4").performTouchInput {
            down(center)
            advanceEventTime(1000)
            moveBy(Offset(2f, 0f))
            up()
        }
        composeRule.runOnIdle {
            assertTrue("expected at least one drag position report, got none", positions.isNotEmpty())
            val first = positions.first()
            val tolerance = px(8)
            assertTrue(first.x > finger.x - tolerance)
            assertTrue(first.x < finger.x + tolerance)
            assertTrue(first.y > finger.y - tolerance)
            assertTrue(first.y < finger.y + tolerance)
        }
    }

    /**
     * n — S1-4e: the tick's top edge zone scrolls the column up.
     * Direct tick test (the missing half of d/e): pointer in the
     * top zone (28dp from the edge) over a pre-scrolled TODO
     * column — five ticks at 24dp each move it 120dp up. This
     * isolates the tick from the drag path: it passes on the old
     * code too (the tick is not the suspect), and pins the tick so
     * a later change cannot break the top branch silently.
     */
    @Test(timeout = 60_000)
    fun `n_auto-scroll tick scrolls the column up at the top edge`() {
        val items = (1..12).map { item("t$it", TaskStatus.TODO) } +
            listOf(
                item("b", TaskStatus.IN_PROGRESS),
                item("c", TaskStatus.DONE, completed = true)
            )
        val boardScroll = ScrollState(0)
        val columnScrolls = TaskStatus.entries.associateWith {
            if (it == TaskStatus.TODO) ScrollState(px(200).toInt()) else ScrollState(0)
        }
        setBoard(items, boardScroll = boardScroll, columnScrolls = columnScrolls)
        composeRule.runOnIdle {}
        val board = composeRule.onNodeWithTag("task_board")
            .fetchSemanticsNode().boundsInRoot
        val pointer = Offset(px(136), px(28))
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
        val todoScroll = columnScrolls.getValue(TaskStatus.TODO).value
        assertTrue(todoScroll < px(200).toInt() - px(50).toInt())
    }

    /**
     * o — S1-4e repro: dragging DOWN through the bottom edge zone
     * scrolls the column down (scroll-on-move). Vertical mirror of
     * test i/l: the TODO column is pre-scrolled 200dp and the card
     * is dragged down in steps into the bottom edge zone — three
     * ticks in the zone, 50ms apart, so 3 x 24dp = 72dp of down
     * scroll. On the old code the drag-start y is short by the
     * column scroll, so the bottom zone (y > height - 56dp) is
     * unreachable and the column never moves.
     */
    @Test(timeout = 60_000)
    fun `o_dragging down through the bottom edge zone scrolls the column down (scroll-on-move)`() {
        val items = (1..12).map { item("t$it", TaskStatus.TODO) } +
            listOf(
                item("b", TaskStatus.IN_PROGRESS),
                item("c", TaskStatus.DONE, completed = true)
            )
        val columnScrolls = TaskStatus.entries.associateWith {
            if (it == TaskStatus.TODO) ScrollState(px(200).toInt()) else ScrollState(0)
        }
        val positions = mutableListOf<Offset>()
        setBoard(
            items,
            columnScrolls = columnScrolls,
            onDragPositionChanged = { positions.add(it) }
        )
        composeRule.runOnIdle {}
        composeRule.onNodeWithTag("board_card_t4").performTouchInput {
            down(center)
            advanceEventTime(1000)
            repeat(3) {
                moveBy(Offset(0f, px(70)))
                advanceEventTime(50)
            }
            repeat(2) {
                moveBy(Offset(0f, px(10)))
                advanceEventTime(50)
            }
            up()
        }
        composeRule.runOnIdle {
            assertTrue(positions.isNotEmpty())
            val todoScroll = columnScrolls.getValue(TaskStatus.TODO).value
            assertTrue(todoScroll > px(200).toInt() + px(50).toInt())
        }
    }

    /**
     * p — S1-4e guard: dragging UP through the top edge zone
     * scrolls the column up (scroll-on-move). Vertical mirror of
     * test i for the top edge. This passes on the old code too:
     * with the drag-start y short by the column scroll, the top
     * zone fires even more readily — so it guards the fix against
     * breaking up-scroll rather than reproducing the bug.
     */
    @Test(timeout = 60_000)
    fun `p_dragging up through the top edge zone scrolls the column up (scroll-on-move)`() {
        val items = (1..12).map { item("t$it", TaskStatus.TODO) } +
            listOf(
                item("b", TaskStatus.IN_PROGRESS),
                item("c", TaskStatus.DONE, completed = true)
            )
        val columnScrolls = TaskStatus.entries.associateWith {
            if (it == TaskStatus.TODO) ScrollState(px(200).toInt()) else ScrollState(0)
        }
        val positions = mutableListOf<Offset>()
        setBoard(
            items,
            columnScrolls = columnScrolls,
            onDragPositionChanged = { positions.add(it) }
        )
        composeRule.runOnIdle {}
        composeRule.onNodeWithTag("board_card_t4").performTouchInput {
            down(center)
            advanceEventTime(1000)
            repeat(3) {
                moveBy(Offset(0f, -px(70)))
                advanceEventTime(50)
            }
            repeat(2) {
                moveBy(Offset(0f, -px(10)))
                advanceEventTime(50)
            }
            up()
        }
        composeRule.runOnIdle {
            assertTrue(positions.isNotEmpty())
            val todoScroll = columnScrolls.getValue(TaskStatus.TODO).value
            assertTrue(todoScroll < px(200).toInt() - px(50).toInt())
        }
    }

    /**
     * q — S1-4f repro: dragging DOWN in a half-visible column scrolls
     * that column (scroll-on-move), not the board sideways. The board
     * is at scroll 0, so the In progress column is only ~76dp visible
     * at the right edge and the finger sits in the board's right edge
     * zone for the whole drag. The touch starts 100dp left of the
     * card's centre — the card's centre is off-screen (the column is
     * less than half visible), so down(center) would miss the card;
     * the visible sliver is what the finger can actually touch.
     * 25 small 10dp moves (50ms apart, so every move ticks past the
     * 32ms throttle) carry the pointer into the bottom edge zone.
     * On the old code the board scrolls right on every move while the
     * pointer travels down, so by the time it reaches the bottom zone
     * boardColumnAt has flipped to Done (one card, no overflow) and
     * the dragged column never moves: inProgress stays 0, board hits
     * its 480dp max. Assertions on separate lines so the failing line
     * names the outcome (CI strips assertion messages).
     */
    @Test(timeout = 60_000)
    fun `q_dragging down in a half-visible column scrolls that column, not the board (scroll-on-move)`() {
        val items = (1..12).map { item("p$it", TaskStatus.IN_PROGRESS) } +
            listOf(
                item("a", TaskStatus.TODO),
                item("c", TaskStatus.DONE, completed = true)
            )
        val boardScroll = ScrollState(0)
        val columnScrolls = TaskStatus.entries.associateWith { ScrollState(0) }
        val positions = mutableListOf<Offset>()
        setBoard(
            items,
            boardScroll = boardScroll,
            columnScrolls = columnScrolls,
            onDragPositionChanged = { positions.add(it) }
        )
        composeRule.runOnIdle {}
        composeRule.onNodeWithTag("board_card_p1").performTouchInput {
            down(center + Offset(-px(100), 0f))
            advanceEventTime(1000)
            repeat(25) {
                moveBy(Offset(0f, px(10)))
                advanceEventTime(50)
            }
            up()
        }
        composeRule.runOnIdle {
            assertTrue(positions.isNotEmpty())
            val inProgressScroll = columnScrolls.getValue(TaskStatus.IN_PROGRESS).value
            assertTrue(inProgressScroll > px(50).toInt())
            assertTrue(boardScroll.value < px(50).toInt())
        }
    }

    /**
     * r — S1-4f, the other reading: a plain (non-long-press) upward
     * swipe on a half-visible column scrolls that column through its
     * own verticalScroll. No long-press delay, so the card's drag
     * detector never starts. This must pass on the old code too —
     * it pins that plain manual scrolling is NOT the broken reading,
     * so the fix belongs to the card-drag auto-scroll path (test q),
     * not to the column's scrollable.
     */
    @Test(timeout = 60_000)
    fun `r_plain swipe scrolls a half-visible column (no long-press)`() {
        val items = (1..12).map { item("p$it", TaskStatus.IN_PROGRESS) } +
            listOf(
                item("a", TaskStatus.TODO),
                item("c", TaskStatus.DONE, completed = true)
            )
        val boardScroll = ScrollState(0)
        val columnScrolls = TaskStatus.entries.associateWith { ScrollState(0) }
        setBoard(items, boardScroll = boardScroll, columnScrolls = columnScrolls)
        composeRule.runOnIdle {}
        // Touch the visible sliver (100dp left of the column centre,
        // which is off-screen) and swipe straight up.
        composeRule.onNodeWithTag("board_column_IN_PROGRESS").performTouchInput {
            down(center + Offset(-px(100), 0f))
            moveBy(Offset(0f, -px(100)))
            up()
        }
        composeRule.runOnIdle {
            val inProgressScroll = columnScrolls.getValue(TaskStatus.IN_PROGRESS).value
            assertTrue(inProgressScroll > px(20).toInt())
        }
    }

    /**
     * r2 — S1-4f control: the SAME single-move swipe as test r, but on
     * the fully-visible TODO column. If this passes while r fails, the
     * failure is specific to half-visibility (a real product issue).
     * If this fails too, plain swipes do not drive the column's
     * scrollable in this harness at all, and test r cannot prove a
     * product bug as written.
     */
    @Test(timeout = 60_000)
    fun `r2_plain swipe scrolls a fully-visible column (control)`() {
        val items = (1..12).map { item("t$it", TaskStatus.TODO) } +
            listOf(
                item("b", TaskStatus.IN_PROGRESS),
                item("c", TaskStatus.DONE, completed = true)
            )
        val boardScroll = ScrollState(0)
        val columnScrolls = TaskStatus.entries.associateWith { ScrollState(0) }
        setBoard(items, boardScroll = boardScroll, columnScrolls = columnScrolls)
        composeRule.runOnIdle {}
        composeRule.onNodeWithTag("board_column_TODO").performTouchInput {
            down(center)
            moveBy(Offset(0f, -px(100)))
            up()
        }
        composeRule.runOnIdle {
            val todoScroll = columnScrolls.getValue(TaskStatus.TODO).value
            assertTrue(todoScroll > px(20).toInt())
        }
    }

    /**
     * r3 — S1-4f: the same half-visible swipe as test r, but as
     * several small move events over event time instead of a single
     * move event. (TouchInjectionScope has no swipe() in Compose 1.7,
     * so the multi-event swipe is written out by hand.) If r3 passes
     * while r fails, the single-move method is what the harness
     * cannot drive, not the product.
     */
    @Test(timeout = 60_000)
    fun `r3_multi-event swipe scrolls a half-visible column (no long-press)`() {
        val items = (1..12).map { item("p$it", TaskStatus.IN_PROGRESS) } +
            listOf(
                item("a", TaskStatus.TODO),
                item("c", TaskStatus.DONE, completed = true)
            )
        val boardScroll = ScrollState(0)
        val columnScrolls = TaskStatus.entries.associateWith { ScrollState(0) }
        setBoard(items, boardScroll = boardScroll, columnScrolls = columnScrolls)
        composeRule.runOnIdle {}
        composeRule.onNodeWithTag("board_column_IN_PROGRESS").performTouchInput {
            down(center + Offset(-px(100), 0f))
            repeat(5) {
                moveBy(Offset(0f, -px(20)))
                advanceEventTime(60)
            }
            up()
        }
        composeRule.runOnIdle {
            val inProgressScroll = columnScrolls.getValue(TaskStatus.IN_PROGRESS).value
            assertTrue(inProgressScroll > px(20).toInt())
        }
    }

    /**
     * r4 — S1-4f control: board scrolled 284dp so the In progress
     * column is FULLY visible, then the same swipe at its centre.
     * If this passes, the column scrolls fine when fully visible and
     * the failure is specific to the half-visible state.
     */
    @Test(timeout = 60_000)
    fun `r4_swipe scrolls the In progress column when it is fully visible (control)`() {
        val items = (1..12).map { item("p$it", TaskStatus.IN_PROGRESS) } +
            listOf(
                item("a", TaskStatus.TODO),
                item("c", TaskStatus.DONE, completed = true)
            )
        val boardScroll = ScrollState(px(284).toInt())
        val columnScrolls = TaskStatus.entries.associateWith { ScrollState(0) }
        setBoard(items, boardScroll = boardScroll, columnScrolls = columnScrolls)
        composeRule.runOnIdle {}
        composeRule.onNodeWithTag("board_column_IN_PROGRESS").performTouchInput {
            down(center)
            moveBy(Offset(0f, -px(100)))
            up()
        }
        composeRule.runOnIdle {
            val inProgressScroll = columnScrolls.getValue(TaskStatus.IN_PROGRESS).value
            assertTrue(inProgressScroll > px(20).toInt())
        }
    }

    /**
     * r5 — S1-4f discriminator: the SAME swipe as test r (board at 0,
     * touch at centre − 100dp on the half-visible In progress column),
     * but asserting the TO DO column scrolled instead. If this passes,
     * the touch is landing on To do — i.e. `center` comes from the
     * column's clipped visible bounds, so the touch in r/q is
     * mis-targeted (a test bug, not a product bug). If it fails, the
     * touch is not landing on To do either.
     */
    @Test(timeout = 60_000)
    fun `r5_same swipe as r scrolls To do instead (mis-targeting check)`() {
        val items = (1..12).map { item("p$it", TaskStatus.IN_PROGRESS) } +
            listOf(
                item("a", TaskStatus.TODO),
                item("c", TaskStatus.DONE, completed = true)
            )
        val boardScroll = ScrollState(0)
        val columnScrolls = TaskStatus.entries.associateWith { ScrollState(0) }
        setBoard(items, boardScroll = boardScroll, columnScrolls = columnScrolls)
        composeRule.runOnIdle {}
        composeRule.onNodeWithTag("board_column_IN_PROGRESS").performTouchInput {
            down(center + Offset(-px(100), 0f))
            moveBy(Offset(0f, -px(100)))
            up()
        }
        composeRule.runOnIdle {
            val todoScroll = columnScrolls.getValue(TaskStatus.TODO).value
            assertTrue(todoScroll > px(20).toInt())
        }
    }
}

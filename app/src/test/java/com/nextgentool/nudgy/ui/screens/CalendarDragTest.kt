package com.nextgentool.nudgy.ui.screens

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.nextgentool.nudgy.data.local.entity.CalendarEventEntity
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.domain.PendingDropConflict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

/**
 * Sprint 2-2 — gesture tests for drag-to-slot (CalendarScreen + CalendarDrag).
 *
 * The harness mirrors BoardDragTest (same long-press + moveBy pattern,
 * default real clock, `down(position)` + `advanceEventTime(1000)`).
 * The exact y→slot mapping is pinned by the pure CalendarSlotMathTest;
 * these tests pin the wiring: the strip card drags, the slot under the
 * finger is reported, the drop hands the task + the slot the geometry
 * predicts, and a release outside the grid drops nothing.
 *
 * The main test asserts the drop IS the dragged-to target (01:30):
 * the target Y is computed from MEASURED geometry (the grid's
 * boundsInRoot top from the fresh first layout, the card's centre),
 * so the test stays deterministic whatever the test viewport height
 * is. Never read semantics mid-gesture (it hangs the test worker);
 * the live slot is read from the onDragSlotChanged callback.
 */
@RunWith(RobolectricTestRunner::class)
// The viewport is 1000 dp tall (w360dp-h1000dp) so the strip and the
// grid top are in view from the first layout — no scrolling is needed
// to press the card, and no scroll mechanism in Robolectric reliably
// brings it into a pressable state (see prepareDrag).
@Config(sdk = [36], qualifiers = "w360dp-h1000dp")
class CalendarDragTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun dayAt(hour: Int, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun task(id: String, due: Long, estimate: Int? = 60) = TaskEntity(
        id = id,
        userId = "test-user-1",
        title = id,
        dueDateEpochMillis = due,
        estimatedMinutes = estimate,
    )

    private fun event(id: String, start: Long, end: Long) = CalendarEventEntity(
        id = id,
        userId = "test-user-1",
        title = id,
        startEpochMillis = start,
        endEpochMillis = end,
    )

    private fun setContent(
        strip: List<TaskEntity>,
        placed: List<TaskEntity> = emptyList(),
        events: List<CalendarEventEntity> = emptyList(),
        pendingConflict: PendingDropConflict? = null,
        onDropTask: (TaskEntity, Long) -> Unit = { _, _ -> },
        onAcceptSuggestedSlot: () -> Unit = {},
        onDismissConflict: () -> Unit = {},
        onDragPositionChanged: ((Offset) -> Unit)? = null,
        onDragSlotChanged: ((Long?) -> Unit)? = null,
        screenScrollState: ScrollState? = null,
    ) {
        composeRule.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                CalendarScreen(
                    events = events,
                    stripTasks = strip,
                    placedTasks = placed,
                    pendingConflict = pendingConflict,
                    onDropTask = onDropTask,
                    onAcceptSuggestedSlot = onAcceptSuggestedSlot,
                    onDismissConflict = onDismissConflict,
                    onDragPositionChanged = onDragPositionChanged,
                    onDragSlotChanged = onDragSlotChanged,
                    screenScrollState = screenScrollState,
                )
            }
        }
    }

    /**
     * The strip card's position for a drag — measured fresh from the
     * first layout, no scrolling needed.
     *
     * Rounds 9–15 proved NO scroll mechanism brings the card into a
     * pressable state in Robolectric: a programmatic scrollTo moves
     * the ScrollState value but does not re-place the content; a
     * pre-scrolled ScrollState(initial = …) breaks long-press
     * hit-testing; performScrollTo leaves the bounds stale (this
     * round — the card is still reported below the fold). The
     * @Config qualifiers below make the test viewport 1000 dp tall,
     * so the strip (~510 dp) and the grid top (~575 dp) are in view
     * from the first layout and the bounds are fresh. Returns the
     * press position (the card's centre, viewport px) and the grid's
     * top (viewport px).
     */
    private fun prepareDrag(scrollState: ScrollState): Pair<Offset, Float> {
        val cardCenter = composeRule.onNodeWithTag("strip_card_t1")
            .fetchSemanticsNode().boundsInRoot.center
        val gridTop = composeRule.onNodeWithTag("calendar_grid")
            .fetchSemanticsNode().boundsInRoot.top
        val viewportHeight = composeRule.onRoot()
            .fetchSemanticsNode().boundsInRoot.height
        // Directional checks: the card and the grid top must be in
        // view (the viewport is 1000 dp tall, so they should be).
        assertTrue(
            "card centre y=${cardCenter.y} should be below the top edge",
            cardCenter.y > 0f,
        )
        assertTrue(
            "card centre y=${cardCenter.y} should be inside the viewport (height $viewportHeight)",
            cardCenter.y < viewportHeight,
        )
        return cardCenter to gridTop
    }

    @Test
    fun `long-press drag from the strip drops at the measured slot`() {
        val t1 = task("t1", dayAt(15))
        val drops = mutableListOf<Pair<TaskEntity, Long>>()
        val slots = mutableListOf<Long?>()
        val positions = mutableListOf<Offset>()
        val scrollState = ScrollState(0)
        setContent(
            strip = listOf(t1),
            onDropTask = { task, slot -> drops.add(task to slot) },
            onDragPositionChanged = { positions.add(it) },
            onDragSlotChanged = { slots.add(it) },
            screenScrollState = scrollState,
        )
        composeRule.runOnIdle { }
        val (cardCenter, gridTop) = prepareDrag(scrollState)
        val hourPx = with(composeRule.density) { 64.dp.toPx() }
        // Strict form (round 26): drag to the 01:30 target and assert
        // the drop IS 01:30. The 01:30 (+30 min) and 03:00 (+15 min)
        // harness offsets were measured in rounds 22–23 at earlier
        // production code and were never re-measured at the current
        // head; Tanvir device-tested the ci579 diagnostic build
        // (2026-11-20) and confirmed drags land EXACTLY where dropped
        // on a real phone (01:30, 03:30, 05:30 all exact). The
        // diagnostic oracle form of this test (assertNotEquals on
        // every candidate slot) is gone — it failed precisely when
        // the drop was correct.
        val targetY = gridTop + 1.5f * hourPx
        // The Board's proven gesture, exactly: down(center), hold past
        // the long-press timeout, one move, release. (Rounds 9–16 used
        // down(pressPos) + stepped moves; the isolation diagnostics
        // that pass use this form.)

        composeRule.onNodeWithTag("strip_card_t1").performTouchInput {
            down(center)
            advanceEventTime(1000)
            // The delta uses the PRE-MEASURED card centre (cardCenter),
            // not the live `center` re-evaluated here: if the card
            // shifts when the drag starts (preview appears, card dims),
            // the live centre moves and the pointer ends off-target.
            moveBy(Offset(0f, targetY - cardCenter.y))
            up()
        }
        composeRule.runOnIdle { }

        // Bisect first: did the drag even start? onDragPositionChanged
        // fires in onDragStart itself, so an empty list means the press
        // never became a drag (not a slot/drop problem).
        assertTrue("the long-press never started a drag (no position reports)", positions.isNotEmpty())
        // Bisect the drop: no drop at all (onDragEnd saw a null slot,
        // so the pointer never reached the grid) vs a drop at the
        // wrong slot (the pointer or the slot math is off). The two
        // cases fail on different lines.
        assertTrue("the drag should have dropped the task (drops is empty)", drops.isNotEmpty())
        assertEquals(listOf(t1 to dayAt(1, 30)), drops)
    }

    @Test
    fun `round 25 diagnostic — reported pointer ends at the target Y`() {
        // Round 25, option B: assert the screen's OWN reported pointer
        // directly. positions holds onDragPositionChanged reports in box
        // coords; the gesture moves the pointer by (targetY -
        // cardCenter.y), so the last report should be targetY. If this
        // PASSES, the pointer is right and the grid-top computation is
        // wrong; if it FAILS, the pointer itself is wrong in the
        // harness. Same 05:30 target as the main test (clear of the
        // bottom edge zone), so the drop is exact there and only the
        // pointer is under test. One assert, 1 px tolerance (~0.5 dp):
        // absorbs float rounding only — the offsets under investigation
        // are +32 dp / +16 dp, so a real pointer error fails this by a
        // wide margin. (The CI log strips values, so the verdict is
        // pass/fail only; that is the point of this test.)
        val t1 = task("t1", dayAt(15))
        val positions = mutableListOf<Offset>()
        val scrollState = ScrollState(0)
        setContent(
            strip = listOf(t1),
            onDropTask = { _, _ -> },
            onDragPositionChanged = { positions.add(it) },
            screenScrollState = scrollState,
        )
        composeRule.runOnIdle { }
        val (cardCenter, gridTop) = prepareDrag(scrollState)
        val hourPx = with(composeRule.density) { 64.dp.toPx() }
        val targetY = gridTop + 5.5f * hourPx

        composeRule.onNodeWithTag("strip_card_t1").performTouchInput {
            down(center)
            advanceEventTime(1000)
            moveBy(Offset(0f, targetY - cardCenter.y))
            up()
        }
        composeRule.runOnIdle { }

        assertTrue("the long-press never started a drag (no position reports)", positions.isNotEmpty())
        assertEquals(targetY, positions.last().y, 1f)
    }

    @Test
    fun `round 24 diagnostic — drop equals the live highlight slot`() {
        // Round 24, second data point: the drop (onDragEnd's live
        // recompute) against the last `slots` entry (the live
        // highlight, computed on the move through the same
        // computeSlot). If they AGREE, the bug is in the shared
        // pointer/grid geometry — both paths use the same wrong slot.
        // If they DISAGREE, onDragEnd's recompute differs from the
        // move-time compute, and the bug is in what changes at
        // release. Same 05:30 target as the main test (clear of the
        // bottom edge zone, so no scroll-on-move fires).
        val t1 = task("t1", dayAt(15))
        val drops = mutableListOf<Pair<TaskEntity, Long>>()
        val slots = mutableListOf<Long?>()
        val scrollState = ScrollState(0)
        setContent(
            strip = listOf(t1),
            onDropTask = { task, slot -> drops.add(task to slot) },
            onDragSlotChanged = { slots.add(it) },
            screenScrollState = scrollState,
        )
        composeRule.runOnIdle { }
        val (cardCenter, gridTop) = prepareDrag(scrollState)
        val hourPx = with(composeRule.density) { 64.dp.toPx() }
        val targetY = gridTop + 5.5f * hourPx

        composeRule.onNodeWithTag("strip_card_t1").performTouchInput {
            down(center)
            advanceEventTime(1000)
            moveBy(Offset(0f, targetY - cardCenter.y))
            up()
        }
        composeRule.runOnIdle { }

        assertTrue("the drag should have dropped the task (drops is empty)", drops.isNotEmpty())
        assertTrue("the live highlight should have fired (slots is empty)", slots.isNotEmpty())
        assertEquals(slots.last(), drops[0].second)
    }

    @Test
    fun `drag released above the grid drops nothing`() {
        val t1 = task("t1", dayAt(15))
        val drops = mutableListOf<Pair<TaskEntity, Long>>()
        val scrollState = ScrollState(0)
        setContent(
            strip = listOf(t1),
            onDropTask = { task, slot -> drops.add(task to slot) },
            screenScrollState = scrollState,
        )
        composeRule.runOnIdle { }
        prepareDrag(scrollState)
        // Lift the finger to the top of the viewport — well above the
        // grid (no slot there).
        val releaseY = with(composeRule.density) { 20.dp.toPx() }

        composeRule.onNodeWithTag("strip_card_t1").performTouchInput {
            down(center)
            advanceEventTime(1000)
            moveBy(Offset(0f, releaseY - center.y))
            up()
        }
        composeRule.runOnIdle { }

        assertTrue(drops.isEmpty())
    }

    @Test
    fun `drag to the bottom edge scrolls the screen`() {
        val t1 = task("t1", dayAt(15))
        val positions = mutableListOf<Offset>()
        val scrollState = ScrollState(0)
        setContent(
            strip = listOf(t1),
            onDropTask = { _, _ -> },
            onDragPositionChanged = { positions.add(it) },
            screenScrollState = scrollState,
        )
        composeRule.runOnIdle { }
        prepareDrag(scrollState)
        val scrolledBefore = scrollState.value
        // Move a full viewport height down: the pointer ends far past
        // the bottom edge zone whatever the density is (a fixed 600 px
        // is only ~228 dp at xxhdpi and may never reach the zone).
        val rootHeight = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.height

        composeRule.onNodeWithTag("strip_card_t1").performTouchInput {
            down(center)
            advanceEventTime(1000)
            moveBy(Offset(0f, rootHeight))
            up()
        }
        composeRule.runOnIdle { }

        // Bisect first: did the drag even start?
        assertTrue("the long-press never started a drag (no position reports)", positions.isNotEmpty())
        assertTrue(
            "the drag should have auto-scrolled the screen (was $scrolledBefore, now ${scrollState.value})",
            scrollState.value > scrolledBefore,
        )
    }

    // NOTE (rounds 12–14): a third diagnostic rendered the strip
    // inside a PRE-SCROLLED vertical Column (ScrollState initial =
    // 360 dp, spacer above the strip like the real screen) — and the
    // drag never started there either. So a pre-scrolled ScrollState
    // breaks long-press hit-testing in Robolectric, while the same
    // strip drags fine with no scroll and inside a plain vertical
    // scroll. The drag tests therefore bring the card into view with
    // performScrollTo (which re-places the content) and never render
    // pre-scrolled. Do NOT reintroduce a pre-scrolled ScrollState
    // for a drag test. (On a real device the pre-scroll is fine —
    // this is a Robolectric harness limitation, not a product bug;
    // the Board's pre-scrolled tests pass because its scroll is
    // horizontal and its drag is horizontal.)

    @Test
    fun `diagnostic — strip drag inside a vertical scroll column`() {
        // Bisection (round 12): the strip drags in isolation, but not
        // on the full screen. The screen's one structural difference
        // is the verticalScroll Column around everything — this renders
        // the strip inside a plain verticalScroll Column (strip at the
        // top, in view, no pre-scroll) to isolate that variable.
        val t1 = task("t1", dayAt(15))
        val starts = mutableListOf<Offset>()
        composeRule.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    CalendarTaskStrip(
                        tasks = listOf(t1),
                        draggingTaskId = null,
                        onDragStart = { _, offset, _ -> starts.add(offset) },
                        onDragMove = { _, _ -> },
                        onDragEnd = {},
                        onDragCancel = {},
                    )
                }
            }
        }
        composeRule.runOnIdle { }
        composeRule.onNodeWithTag("strip_card_t1").performTouchInput {
            down(center)
            advanceEventTime(1000)
            moveBy(Offset(0f, 100f))
            up()
        }
        composeRule.runOnIdle {
            assertTrue(
                "strip drag should start inside a vertical scroll column",
                starts.isNotEmpty()
            )
        }
    }

    @Test
    fun `diagnostic — strip drag starts in isolation, without the screen scroll`() {
        // Bisection (round 11): the drag never starts on the full
        // screen even though the card is in view and pressed at its
        // centre. This renders ONLY the strip (no vertical screen
        // scroll, no grid) — if the drag starts here, the screen's
        // nested scrollables are swallowing the long-press; if it
        // does not, the strip card's own detector is broken.
        val t1 = task("t1", dayAt(15))
        val starts = mutableListOf<Offset>()
        composeRule.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                CalendarTaskStrip(
                    tasks = listOf(t1),
                    draggingTaskId = null,
                    onDragStart = { _, offset, _ -> starts.add(offset) },
                    onDragMove = { _, _ -> },
                    onDragEnd = {},
                    onDragCancel = {},
                )
            }
        }
        composeRule.runOnIdle { }
        composeRule.onNodeWithTag("strip_card_t1").performTouchInput {
            down(center)
            advanceEventTime(1000)
            moveBy(Offset(0f, 100f))
            up()
        }
        composeRule.runOnIdle {
            assertTrue("strip drag should start in isolation", starts.isNotEmpty())
        }
    }

    @Test
    fun `busy dialog accept invokes the accept callback`() {
        val t1 = task("t1", dayAt(15))
        var accepted = 0
        setContent(
            strip = listOf(t1),
            pendingConflict = PendingDropConflict(
                task = t1,
                requestedStartMillis = dayAt(10, 30),
                suggestedStartMillis = dayAt(11),
                conflictingTitle = "Design review",
            ),
            onAcceptSuggestedSlot = { accepted++ },
        )
        composeRule.onNodeWithTag("busy_slot_dialog").assertExists()
        composeRule.onNodeWithTag("busy_slot_accept").performClick()
        assertEquals(1, accepted)
    }

    @Test
    fun `busy dialog cancel invokes the dismiss callback`() {
        val t1 = task("t1", dayAt(15))
        var dismissed = 0
        setContent(
            strip = listOf(t1),
            pendingConflict = PendingDropConflict(
                task = t1,
                requestedStartMillis = dayAt(10, 30),
                suggestedStartMillis = dayAt(11),
                conflictingTitle = "Design review",
            ),
            onDismissConflict = { dismissed++ },
        )
        composeRule.onNodeWithTag("busy_slot_dialog").assertExists()
        composeRule.onNodeWithTag("busy_slot_cancel").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun `grid renders placed-task and event blocks by test tag`() {
        val t1 = task("t1", dayAt(15))
        val placed = task("placed", dayAt(9)).copy(
            plannedStartEpochMillis = dayAt(10),
            plannedDate = "2026-11-19",
        )
        val e1 = event("e1", dayAt(12), dayAt(13))
        setContent(
            strip = listOf(t1),
            placed = listOf(placed),
            events = listOf(e1),
        )
        // Assert by testTag, not by text: the event title also appears in
        // the Schedule list above the grid, so a text match would be
        // ambiguous.
        composeRule.onNodeWithTag("grid_task_placed").assertExists()
        composeRule.onNodeWithTag("grid_event_e1").assertExists()
        composeRule.onNodeWithTag("calendar_strip").assertExists()
    }
}

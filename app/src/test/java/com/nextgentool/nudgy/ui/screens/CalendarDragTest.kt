package com.nextgentool.nudgy.ui.screens

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import com.nextgentool.nudgy.domain.slotStartForY
import com.nextgentool.nudgy.domain.startOfDayMillis
import kotlinx.coroutines.runBlocking
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
 * The expected slot is computed from MEASURED geometry (the grid's
 * boundsInRoot top from the fresh first layout, the card's centre, the
 * screen scroll value after the gesture) through the same pure
 * slotStartForY — so the test stays deterministic whatever the test
 * viewport height is: if the drag scrolled the screen (one 24 dp tick
 * from scroll-on-move, or a loop tick), the scroll is inside the
 * expectation instead of breaking it. Never read semantics mid-gesture
 * (it hangs the test worker); the live slot is read from the
 * onDragSlotChanged callback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
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
     * Brings the strip card into view for a drag — mirroring Board test c
     * (the proven sequence: layout, THEN scroll, then drag).
     *
     * Round 7's bisection proved the drag never started with the old
     * prepareDrag: it scrolled with dispatchRawDelta, which changes the
     * ScrollState value WITHOUT re-placing the content — so the card
     * never actually moved and the press missed it. runBlocking +
     * scrollTo (what Board test c uses) drives the real scroll, and
     * after it the semantics bounds ARE current (test c asserts the
     * drag-start position against them), so down(center) hits the card.
     *
     * Returns the grid's top (viewport px, after the scroll). The test
     * reads scrollState.value right after this returns to know how much
     * was scrolled during preparation (the drop expectation subtracts
     * it back out — only scrolling DURING the drag shifts the slot).
     */
    private fun prepareDrag(scrollState: ScrollState): Float {
        // Fresh measurement from the first layout: how far the card
        // must move to sit ~150 dp from the top.
        val cardCenter0 = composeRule.onNodeWithTag("strip_card_t1")
            .fetchSemanticsNode().boundsInRoot.center
        val cardTargetY = with(composeRule.density) { 150.dp.toPx() }
        val scrollPx = (cardCenter0.y - cardTargetY).coerceAtLeast(0f).toInt()
        runBlocking { scrollState.scrollTo(scrollPx) }
        composeRule.runOnIdle { }
        // Diagnostic (round 8): prove the card is actually where the
        // scroll was supposed to put it — if it is not, the press
        // below misses the card and no drag can start.
        val cardCenterAfter = composeRule.onNodeWithTag("strip_card_t1")
            .fetchSemanticsNode().boundsInRoot.center
        val tolerance = with(composeRule.density) { 10.dp.toPx() }
        assertTrue(
            "card centre y=${cardCenterAfter.y} should be within $tolerance px of $cardTargetY " +
                "(was ${cardCenter0.y} before scrolling $scrollPx px, scroll value now ${scrollState.value})",
            kotlin.math.abs(cardCenterAfter.y - cardTargetY) < tolerance,
        )
        // Re-measure fresh after the real scroll.
        return composeRule.onNodeWithTag("calendar_grid")
            .fetchSemanticsNode().boundsInRoot.top
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
        val gridTop = prepareDrag(scrollState)
        val scrolledBeforeDrag = scrollState.value
        val hourPx = with(composeRule.density) { 64.dp.toPx() }
        val dayStart = startOfDayMillis(System.currentTimeMillis())
        // Target 01:30 — the centre of a 15-minute snap bucket, so a few
        // px of measurement error is absorbed by the snap.
        val targetY = gridTop + 1.5f * hourPx
        val cardCenter = composeRule.onNodeWithTag("strip_card_t1")
            .fetchSemanticsNode().boundsInRoot.center
        val delta = Offset(0f, targetY - cardCenter.y)

        composeRule.onNodeWithTag("strip_card_t1").performTouchInput {
            down(center)
            advanceEventTime(1000)
            moveBy(delta)
            up()
        }
        composeRule.runOnIdle { }

        // Bisect first: did the drag even start? onDragPositionChanged
        // fires in onDragStart itself, so an empty list means the press
        // never became a drag (not a slot/drop problem).
        assertTrue("the long-press never started a drag (no position reports)", positions.isNotEmpty())
        assertTrue("no slot was ever reported during the drag", slots.isNotEmpty())
        // The drop slot is the pure function of the measured geometry
        // plus whatever the screen scrolled DURING the drag (a 24 dp
        // scroll-on-move tick, or a loop tick, moves the grid up under
        // the finger by exactly that many px). The preparation scroll
        // is already inside gridTop, so only the extra scroll counts.
        val expected = slotStartForY(
            targetY - gridTop + (scrollState.value - scrolledBeforeDrag),
            hourPx,
            dayStart,
        )
        assertEquals(listOf(t1 to expected), drops)
        // The highlight showed the dropped slot during the drag. (contains,
        // not last: a loop tick landing after the release could append one
        // more callback; the drop slot itself is pinned above.)
        assertTrue(slots.contains(expected))
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

        composeRule.onNodeWithTag("strip_card_t1").performTouchInput {
            down(center)
            advanceEventTime(1000)
            moveBy(Offset(0f, -600f))
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

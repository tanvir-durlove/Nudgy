package com.nextgentool.nudgy.ui.screens

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.nextgentool.nudgy.data.local.entity.CalendarEventEntity
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.domain.PendingDropConflict
import com.nextgentool.nudgy.domain.slotStartForY
import com.nextgentool.nudgy.domain.startOfDayMillis
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
     * Brings the strip card into view for a drag — WITHOUT performScrollTo.
     *
     * Round 3 proved performScrollTo + boundsInRoot cannot be trusted
     * here: the strip card sits inside a horizontalScroll Row, and
     * after the programmatic scroll the semantics bounds are stale
     * (the S1-4c lesson) — down(center) then presses the card's OLD
     * position and the drag never starts (both drag tests failed while
     * every non-drag test passed). Instead: measure the card + grid
     * ONCE from the fresh first layout, scroll synchronously with
     * dispatchRawDelta (the same call the auto-scroll tick uses), and
     * press with an EXPLICIT position computed from the fresh
     * measurement + the known scroll — never a re-read of the stale
     * bounds.
     *
     * Returns the card's centre (viewport px, after the scroll) and the
     * grid's top (viewport px, BEFORE the scroll — the expected-slot
     * formula adds the final scroll value back).
     */
    private fun prepareDrag(scrollState: ScrollState): Pair<Offset, Float> {
        val cardCenter0 = composeRule.onNodeWithTag("strip_card_t1")
            .fetchSemanticsNode().boundsInRoot.center
        val gridTop0 = composeRule.onNodeWithTag("calendar_grid")
            .fetchSemanticsNode().boundsInRoot.top
        // Park the card ~150 dp from the top: the grid below it is then
        // well inside the viewport, and the 01:30 target stays out of
        // the bottom edge zone.
        val cardTargetY = with(composeRule.density) { 150.dp.toPx() }
        val scrollPx = (cardCenter0.y - cardTargetY).coerceAtLeast(0f)
        scrollState.dispatchRawDelta(scrollPx)
        composeRule.runOnIdle { }
        val cardCenter = Offset(cardCenter0.x, cardCenter0.y - scrollState.value)
        return cardCenter to gridTop0
    }

    @Test
    fun `long-press drag from the strip drops at the measured slot`() {
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
        val (cardCenter, gridTop0) = prepareDrag(scrollState)
        val hourPx = with(composeRule.density) { 64.dp.toPx() }
        val dayStart = startOfDayMillis(System.currentTimeMillis())
        // Target 01:30 — the centre of a 15-minute snap bucket, so a few
        // px of measurement error is absorbed by the snap. The grid top
        // after the prepare scroll is gridTop0 minus what was scrolled.
        val gridTop = gridTop0 - scrollState.value
        val targetY = gridTop + 1.5f * hourPx
        val delta = Offset(0f, targetY - cardCenter.y)

        composeRule.onNodeWithTag("strip_card_t1").performTouchInput {
            down(cardCenter)
            advanceEventTime(1000)
            moveBy(delta)
            up()
        }
        composeRule.runOnIdle { }

        // The drop slot is the pure function of the measured geometry
        // plus whatever the screen scrolled during the drag (a 24 dp
        // scroll-on-move tick, or a loop tick, moves the grid up under
        // the finger by exactly that many px).
        val expected = slotStartForY(
            targetY - gridTop0 + scrollState.value,
            hourPx,
            dayStart,
        )
        assertEquals(listOf(t1 to expected), drops)
        assertTrue(slots.isNotEmpty())
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
        val (cardCenter, _) = prepareDrag(scrollState)

        composeRule.onNodeWithTag("strip_card_t1").performTouchInput {
            down(cardCenter)
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
        val scrollState = ScrollState(0)
        setContent(
            strip = listOf(t1),
            onDropTask = { _, _ -> },
            screenScrollState = scrollState,
        )
        composeRule.runOnIdle { }
        val (cardCenter, _) = prepareDrag(scrollState)
        val scrolledBefore = scrollState.value

        composeRule.onNodeWithTag("strip_card_t1").performTouchInput {
            down(cardCenter)
            advanceEventTime(1000)
            moveBy(Offset(0f, 600f))
            up()
        }
        composeRule.runOnIdle { }

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

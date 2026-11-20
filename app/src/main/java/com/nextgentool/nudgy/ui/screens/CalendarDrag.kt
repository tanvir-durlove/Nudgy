package com.nextgentool.nudgy.ui.screens

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextgentool.nudgy.data.local.entity.CalendarEventEntity
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.PendingDropConflict
import com.nextgentool.nudgy.domain.slotBlockMinutes
import com.nextgentool.nudgy.domain.timeLabel
import com.nextgentool.nudgy.ui.theme.NudgyCard
import com.nextgentool.nudgy.ui.theme.NudgyCardBorder
import com.nextgentool.nudgy.ui.theme.NudgyCharcoal
import com.nextgentool.nudgy.ui.theme.NudgyLavender
import com.nextgentool.nudgy.ui.theme.NudgyMuted
import com.nextgentool.nudgy.ui.theme.NudgyPeach
import com.nextgentool.nudgy.ui.theme.NudgyPrimary

/** One hour row in the day grid. */
val CALENDAR_HOUR_HEIGHT: Dp = 64.dp

/** Width of the strip cards (the drag source). */
private val STRIP_CARD_WIDTH: Dp = 176.dp

/** Width of the hour-label gutter in the grid. */
private val GRID_LABEL_WIDTH: Dp = 52.dp

/** The blocks start this far into the grid (label gutter + gap). */
private val GRID_BLOCKS_START: Dp = 60.dp

/** Shortest visible block height — a 15-minute event is still tappable. */
private val MIN_BLOCK_HEIGHT: Dp = 28.dp

/**
 * In-flight Calendar drag: the dragged task, the pointer in screen
 * viewport coordinates (the floating preview is centred on it), the
 * grid slot under the pointer (null = outside the grid — no highlight,
 * and a release there drops nothing), and the block length the task
 * would occupy (estimate, else the 30-min display default).
 */
internal data class CalendarDrag(
    val taskId: String,
    val pointerViewport: Offset,
    val slotStartMillis: Long?,
    val durationMinutes: Int
)

/**
 * One auto-scroll tick for the Calendar screen (S2-2, the Board's
 * boardAutoScrollTick pattern, vertical only): given the pointer in
 * screen-viewport coordinates, scroll the screen one step when the
 * pointer dwells near the top/bottom edge. Synchronous on purpose —
 * it scrolls via ScrollState.dispatchRawDelta, so it can be called
 * from onDrag as well as from the delay loop, and driven directly in
 * a test (Robolectric cannot tick the loop's delay during a held
 * gesture — the loop itself is verified end-to-end on device, exactly
 * like the Board's).
 */
internal fun calendarAutoScrollTick(
    pointerViewport: Offset,
    viewportSize: IntSize,
    scrollState: ScrollState,
    edgePx: Float,
    stepPx: Float
) {
    if (pointerViewport.y > viewportSize.height - edgePx) {
        scrollState.dispatchRawDelta(stepPx)
    } else if (pointerViewport.y < edgePx) {
        scrollState.dispatchRawDelta(-stepPx)
    }
}

private fun hourLabel(hour: Int): String = when (hour) {
    0 -> "12 AM"
    in 1..11 -> "$hour AM"
    12 -> "12 PM"
    else -> "${hour - 12} PM"
}

/** "3:00 PM" for a due time, "No date" for an undated task. */
private fun stripDueLabel(task: TaskEntity): String =
    if (task.dueDateEpochMillis == TaskRepository.NO_DUE_DATE) "No date"
    else timeLabel(task.dueDateEpochMillis)

/**
 * Sprint 2-2 — the drag strip (spec Q4(b)): tasks due on the selected
 * day with no slot yet, plus Anytime tasks. Long-press a card to drag
 * it into the grid below; the card dims in place and a floating
 * preview follows the finger (the Board's S1-4b pattern — manual drag,
 * never platform DnD, which exposes no drag position).
 */
@Composable
fun CalendarTaskStrip(
    tasks: List<TaskEntity>,
    draggingTaskId: String?,
    onDragStart: (task: TaskEntity, offsetInCard: Offset, cardCoords: LayoutCoordinates?) -> Unit,
    onDragMove: (dragAmount: Offset, uptimeMillis: Long) -> Unit,
    onDragEnd: (task: TaskEntity) -> Unit,
    onDragCancel: (task: TaskEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "TO PLAN",
            style = MaterialTheme.typography.labelSmall,
            color = com.nextgentool.nudgy.ui.theme.NudgyAccent
        )
        Text(
            text = "Drag a task into a slot",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = NudgyCharcoal
        )
        if (tasks.isEmpty()) {
            Text(
                text = "Nothing to place — tasks due today, or without a date, show up here.",
                style = MaterialTheme.typography.bodyMedium,
                color = NudgyMuted
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .testTag("calendar_strip"),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (task in tasks) {
                    StripTaskCard(
                        task = task,
                        isDragging = draggingTaskId == task.id,
                        onDragStart = onDragStart,
                        onDragMove = onDragMove,
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragCancel
                    )
                }
            }
        }
    }
}

@Composable
private fun StripTaskCard(
    task: TaskEntity,
    isDragging: Boolean,
    onDragStart: (task: TaskEntity, offsetInCard: Offset, cardCoords: LayoutCoordinates?) -> Unit,
    onDragMove: (dragAmount: Offset, uptimeMillis: Long) -> Unit,
    onDragEnd: (task: TaskEntity) -> Unit,
    onDragCancel: (task: TaskEntity) -> Unit
) {
    var cardCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Surface(
        modifier = Modifier
            .width(STRIP_CARD_WIDTH)
            .graphicsLayer { alpha = if (isDragging) 0.35f else 1f }
            .onGloballyPositioned { cardCoords = it }
            .pointerInput(task.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offsetInCard ->
                        onDragStart(task, offsetInCard, cardCoords)
                    },
                    // Required by the detector (no default in 1.7).
                    onDrag = { change, dragAmount ->
                        onDragMove(dragAmount, change.uptimeMillis)
                    },
                    onDragEnd = { onDragEnd(task) },
                    onDragCancel = { onDragCancel(task) }
                )
            }
            .testTag("strip_card_${task.id}"),
        shape = MaterialTheme.shapes.large,
        color = NudgyCard,
        border = androidx.compose.foundation.BorderStroke(1.dp, NudgyCardBorder),
        shadowElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = NudgyCharcoal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stripDueLabel(task) +
                    if (task.estimatedMinutes != null) " · ${task.estimatedMinutes} min" else "",
                style = MaterialTheme.typography.bodySmall,
                color = NudgyMuted
            )
        }
    }
}

/**
 * Sprint 2-2 — the day time-grid: 24 hour rows for the selected day,
 * with the day's events and already-placed tasks as blocks, and a live
 * slot highlight under the dragged task (the Board's insertion
 * indicator, for a time grid). A block's length is the estimate, or
 * 30 minutes when there is none (the estimate itself never changes).
 *
 * [dayStartMillis] is local midnight of the selected day; [highlight]
 * is (slot start, duration minutes) while a drag hovers the grid.
 */
@Composable
fun CalendarHourGrid(
    dayStartMillis: Long,
    events: List<CalendarEventEntity>,
    placedTasks: List<TaskEntity>,
    highlight: Pair<Long, Int>?,
    modifier: Modifier = Modifier
) {
    val gridHeight = CALENDAR_HOUR_HEIGHT * 24
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(gridHeight)
            .testTag("calendar_grid")
    ) {
        // Hour rows: label + the hour boundary line.
        Column(modifier = Modifier.fillMaxWidth()) {
            for (hour in 0 until 24) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(CALENDAR_HOUR_HEIGHT)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(NudgyCardBorder)
                            .align(Alignment.TopStart)
                    )
                    Text(
                        text = hourLabel(hour),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = NudgyMuted,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(top = 4.dp)
                            .width(GRID_LABEL_WIDTH)
                    )
                }
            }
        }
        // Blocks, positioned by time (label gutter + gap on the left).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(gridHeight)
                .padding(start = GRID_BLOCKS_START)
        ) {
            for (event in events) {
                val topDp = ((event.startEpochMillis - dayStartMillis).toFloat() / 3_600_000f * 64f)
                    .coerceAtLeast(0f)
                val heightDp = ((event.endEpochMillis - event.startEpochMillis).toFloat() / 3_600_000f * 64f)
                    .coerceAtLeast(28f)
                val isMeeting = event.eventType == "MEETING"
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset(y = topDp.dp)
                        .height(heightDp.dp)
                        .testTag("grid_event_${event.id}"),
                    shape = MaterialTheme.shapes.small,
                    color = if (isMeeting) NudgyPeach else NudgyLavender
                ) {
                    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Text(
                            text = event.title,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp
                            ),
                            color = NudgyCharcoal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${timeLabel(event.startEpochMillis)} – ${timeLabel(event.endEpochMillis)}",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = NudgyMuted,
                            maxLines = 1
                        )
                    }
                }
            }
            for (task in placedTasks) {
                val blockStart = task.plannedStartEpochMillis ?: continue
                val minutes = slotBlockMinutes(task)
                val topDp = ((blockStart - dayStartMillis).toFloat() / 3_600_000f * 64f)
                    .coerceAtLeast(0f)
                val heightDp = (minutes.toFloat() / 60f * 64f).coerceAtLeast(28f)
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset(y = topDp.dp)
                        .height(heightDp.dp)
                        .testTag("grid_task_${task.id}"),
                    shape = MaterialTheme.shapes.small,
                    color = NudgyPrimary.copy(alpha = 0.12f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, NudgyPrimary)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Text(
                            text = task.title,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp
                            ),
                            color = NudgyCharcoal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${timeLabel(blockStart)} – ${timeLabel(blockStart + minutes * 60_000L)}",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = NudgyMuted,
                            maxLines = 1
                        )
                    }
                }
            }
            // Live slot highlight while a drag hovers the grid.
            if (highlight != null) {
                val (slotStart, durationMinutes) = highlight
                val topDp = ((slotStart - dayStartMillis).toFloat() / 3_600_000f * 64f)
                    .coerceAtLeast(0f)
                val heightDp = (durationMinutes.toFloat() / 60f * 64f).coerceAtLeast(28f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset(y = topDp.dp)
                        .height(heightDp.dp)
                        .border(2.dp, NudgyPrimary, MaterialTheme.shapes.small)
                        .background(NudgyPrimary.copy(alpha = 0.10f), MaterialTheme.shapes.small)
                        .testTag("grid_slot_highlight")
                ) {
                    Text(
                        text = "${timeLabel(slotStart)} – ${timeLabel(slotStart + durationMinutes * 60_000L)}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        ),
                        color = NudgyPrimary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

/**
 * Sprint 2-2 — the busy-slot dialog (spec Q3(a)): the drop landed on
 * a slot something already occupies. Accept moves the task to the
 * suggested free slot; decline leaves it in the strip (no write).
 * When no free slot is left today there is no accept action at all.
 */
@Composable
fun CalendarBusySlotDialog(
    pending: PendingDropConflict,
    onAccept: () -> Unit,
    onDismiss: () -> Unit
) {
    val suggestedText = pending.suggestedStartMillis?.let {
        "${timeLabel(it)} – ${timeLabel(it + slotBlockMinutes(pending.task) * 60_000L)}"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "That time is busy",
                fontWeight = FontWeight.Bold,
                color = NudgyCharcoal,
                modifier = Modifier.testTag("busy_slot_dialog")
            )
        },
        text = {
            Text(
                text = if (suggestedText != null) {
                    "\"${pending.conflictingTitle ?: "Something"}\" is already there. " +
                        "Move to $suggestedText instead?"
                } else {
                    "\"${pending.conflictingTitle ?: "Something"}\" is already there, " +
                        "and no free slot is left today."
                },
                color = NudgyCharcoal
            )
        },
        confirmButton = {
            if (pending.suggestedStartMillis != null) {
                TextButton(
                    onClick = onAccept,
                    modifier = Modifier.testTag("busy_slot_accept")
                ) {
                    Text("Move here", color = NudgyPrimary, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("busy_slot_cancel")
            ) {
                Text("Cancel", color = NudgyMuted)
            }
        }
    )
}

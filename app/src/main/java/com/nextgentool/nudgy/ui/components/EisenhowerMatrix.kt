package com.nextgentool.nudgy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.usecase.EisenhowerQuadrant
import com.nextgentool.nudgy.domain.usecase.groupMatrixItems
import com.nextgentool.nudgy.ui.theme.NudgyAccent
import com.nextgentool.nudgy.ui.theme.NudgyCharcoal
import com.nextgentool.nudgy.ui.theme.NudgyMuted
import com.nextgentool.nudgy.ui.theme.NudgyTokens
import com.nextgentool.nudgy.ui.viewmodel.TaskListItem
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

private val MATRIX_HEIGHT = 560.dp
private val MATRIX_GAP = 12.dp

/**
 * The quadrant under a pointer in ROOT coordinates, from the
 * quadrants' measured bounds. Pure — unit-tested without Compose.
 * Returns null when the pointer is outside every quadrant (a drop
 * there is a no-op). Quadrants never overlap, so the first match is
 * the only match.
 */
fun quadrantAtPoint(
    bounds: Map<EisenhowerQuadrant, Rect>,
    pointRoot: Offset
): EisenhowerQuadrant? =
    EisenhowerQuadrant.entries.firstOrNull { bounds[it]?.contains(pointRoot) == true }

/**
 * In-flight Matrix drag: the dragged id, the pointer position in ROOT
 * coordinates, and the quadrant under it (live-computed from the
 * measured quadrant bounds on every move).
 */
private data class MatrixDrag(
    val taskId: String,
    val pointerRoot: Offset,
    val targetQuadrant: EisenhowerQuadrant?
)

/**
 * Sprint 4-3 — Eisenhower Matrix (spec §6, Q10=(a)): the third view
 * inside Today, next to List / Board, over the SAME items.
 *
 * The quadrants are derived ([quadrantOf]) — the matrix is a view, not
 * a second store. A card is moved with a long-press drag, the same
 * MANUAL drag as the Board (detectDragGesturesAfterLongPress with
 * offset tracking, NOT platform drag-and-drop: the platform session
 * exposes no drag position). The matrix is a 2x2 grid that fits on
 * screen, so unlike the Board there is no scrolling container under
 * the drag — no auto-scroll, no insertion index: the drop target is
 * simply which quadrant's measured bounds contain the pointer.
 * Dropping calls [onMoveTask]; dropping back on the card's own
 * quadrant, or outside every quadrant, is a no-op. A quadrant move is
 * a priority + due change (spec §6) — the List shows it from the same
 * TaskEntity. The checkbox on a card toggles completion the same way
 * the List does. The dragged card dims in place and a floating preview
 * follows the finger.
 */
@Composable
fun EisenhowerMatrix(
    items: List<TaskListItem>,
    onMoveTask: (TaskEntity, EisenhowerQuadrant) -> Unit,
    onToggle: (TaskEntity) -> Unit,
    onTaskClick: (TaskEntity) -> Unit,
    modifier: Modifier = Modifier,
    // Test observability (S1-4c, same rationale as the Board): reports
    // the live drag pointer position (root px) at drag start and on
    // every move. Null in production — no behaviour change. The
    // floating preview is centered on exactly this value, so asserting
    // it asserts the preview position without reading semantics
    // mid-gesture (which hangs the test worker). Invoked synchronously
    // from the gesture callbacks, never via recomposition.
    onDragPositionChanged: ((Offset) -> Unit)? = null,
    matrixHeight: Dp = MATRIX_HEIGHT
) {
    val density = LocalDensity.current
    val gapPx = with(density) { MATRIX_GAP.toPx() }
    val previewYOffsetPx = with(density) { 28.dp.toPx() }

    val dragState = remember { mutableStateOf<MatrixDrag?>(null) }
    val drag = dragState.value
    var matrixCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var matrixSize by remember { mutableStateOf(IntSize.Zero) }
    // Every quadrant's live bounds in root coordinates, by quadrant —
    // written by each quadrant's onGloballyPositioned. The drag
    // resolves its target against these, never against an assumed
    // layout (the quadrants are half the screen width, which varies
    // by device).
    val quadrantBounds = remember { mutableStateMapOf<EisenhowerQuadrant, Rect>() }
    val grouped = remember(items) { groupMatrixItems(items) }

    // Fresh reads for the gesture callbacks (the pointerInput block is
    // keyed on the task id only, so it must not close over stale values).
    val onMoveTaskState = rememberUpdatedState(onMoveTask)

    val draggedTask = drag?.let { d -> items.firstOrNull { it.task.id == d.taskId }?.task }

    Box(
        modifier = modifier
            .testTag("eisenhower_matrix")
            .onSizeChanged { matrixSize = it }
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(MATRIX_GAP),
            modifier = Modifier
                .fillMaxWidth()
                .height(matrixHeight)
                .onGloballyPositioned { matrixCoords = it }
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(MATRIX_GAP),
                modifier = Modifier.weight(1f)
            ) {
                MatrixQuadrant(
                    quadrant = EisenhowerQuadrant.Q1,
                    items = grouped.getValue(EisenhowerQuadrant.Q1),
                    dragState = dragState,
                    quadrantBounds = quadrantBounds,
                    onMoveTaskState = onMoveTaskState,
                    onDragPositionChanged = onDragPositionChanged,
                    onToggle = onToggle,
                    onTaskClick = onTaskClick,
                    modifier = Modifier.weight(1f)
                )
                MatrixQuadrant(
                    quadrant = EisenhowerQuadrant.Q2,
                    items = grouped.getValue(EisenhowerQuadrant.Q2),
                    dragState = dragState,
                    quadrantBounds = quadrantBounds,
                    onMoveTaskState = onMoveTaskState,
                    onDragPositionChanged = onDragPositionChanged,
                    onToggle = onToggle,
                    onTaskClick = onTaskClick,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(MATRIX_GAP),
                modifier = Modifier.weight(1f)
            ) {
                MatrixQuadrant(
                    quadrant = EisenhowerQuadrant.Q3,
                    items = grouped.getValue(EisenhowerQuadrant.Q3),
                    dragState = dragState,
                    quadrantBounds = quadrantBounds,
                    onMoveTaskState = onMoveTaskState,
                    onDragPositionChanged = onDragPositionChanged,
                    onToggle = onToggle,
                    onTaskClick = onTaskClick,
                    modifier = Modifier.weight(1f)
                )
                MatrixQuadrant(
                    quadrant = EisenhowerQuadrant.Q4,
                    items = grouped.getValue(EisenhowerQuadrant.Q4),
                    dragState = dragState,
                    quadrantBounds = quadrantBounds,
                    onMoveTaskState = onMoveTaskState,
                    onDragPositionChanged = onDragPositionChanged,
                    onToggle = onToggle,
                    onTaskClick = onTaskClick,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        // Floating preview under the finger while dragging.
        if (drag != null && draggedTask != null) {
            val origin = matrixCoords?.localToRoot(Offset.Zero) ?: Offset.Zero
            val previewWidthPx = (matrixSize.width - gapPx) / 2f
            Surface(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (drag.pointerRoot.x - origin.x - previewWidthPx / 2f).roundToInt(),
                            (drag.pointerRoot.y - origin.y - previewYOffsetPx).roundToInt()
                        )
                    }
                    .width(with(density) { previewWidthPx.toDp() })
                    .testTag("matrix_drag_preview"),
                shape = NudgyTokens.cardRadius,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = NudgyTokens.cardElevation
            ) {
                Text(
                    text = draggedTask.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = NudgyCharcoal,
                    maxLines = 2,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

@Composable
private fun MatrixQuadrant(
    quadrant: EisenhowerQuadrant,
    items: List<TaskListItem>,
    dragState: MutableState<MatrixDrag?>,
    quadrantBounds: SnapshotStateMap<EisenhowerQuadrant, Rect>,
    onMoveTaskState: State<(TaskEntity, EisenhowerQuadrant) -> Unit>,
    onDragPositionChanged: ((Offset) -> Unit)?,
    onToggle: (TaskEntity) -> Unit,
    onTaskClick: (TaskEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    // The quadrant under the pointer highlights while a card is dragged
    // over it — the same affordance the Board columns have.
    val hovered = dragState.value?.targetQuadrant == quadrant
    Surface(
        modifier = modifier
            .testTag("matrix_quadrant_${quadrant.name}")
            .onGloballyPositioned { quadrantBounds[quadrant] = it.boundsInRoot() },
        shape = NudgyTokens.cardRadius,
        color = if (hovered) {
            colorResource(R.color.nudgy_teal).copy(alpha = 0.10f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        shadowElevation = NudgyTokens.cardElevation
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = quadrant.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = NudgyCharcoal,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${items.size}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NudgyMuted,
                    modifier = Modifier.testTag("matrix_count_${quadrant.name}")
                )
            }
            Text(
                text = quadrant.caption,
                style = MaterialTheme.typography.bodySmall,
                color = NudgyMuted
            )
            Spacer(modifier = Modifier.height(8.dp))
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                if (items.isEmpty()) {
                    Text(
                        text = "No tasks here",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NudgyMuted,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    items.forEach { item ->
                        MatrixTaskCard(
                            item = item,
                            quadrant = quadrant,
                            dragState = dragState,
                            quadrantBounds = quadrantBounds,
                            onMoveTaskState = onMoveTaskState,
                            onDragPositionChanged = onDragPositionChanged,
                            isDragging = dragState.value?.taskId == item.task.id,
                            onToggle = { onToggle(item.task) },
                            onClick = { onTaskClick(item.task) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MatrixTaskCard(
    item: TaskListItem,
    quadrant: EisenhowerQuadrant,
    dragState: MutableState<MatrixDrag?>,
    quadrantBounds: SnapshotStateMap<EisenhowerQuadrant, Rect>,
    onMoveTaskState: State<(TaskEntity, EisenhowerQuadrant) -> Unit>,
    onDragPositionChanged: ((Offset) -> Unit)?,
    isDragging: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit
) {
    val task = item.task
    // Keep the card's LayoutCoordinates, not a cached Offset. The drag
    // start evaluates localToRoot fresh, so the position follows the
    // quadrant's current scroll (same fix as the Board, S1-4c).
    var cardCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (isDragging) 0.35f else 1f }
            .onGloballyPositioned { cardCoords = it }
            .pointerInput(task.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offsetInCard ->
                        // Pointer in ROOT coordinates, evaluated fresh
                        // from the live coordinates. The card and the
                        // quadrants share the same root, and the matrix
                        // does not sit inside a scrolling container
                        // (only each quadrant's card list scrolls, and
                        // localToRoot already carries that scroll) — so
                        // no scroll correction is needed here.
                        val startRoot = cardCoords?.localToRoot(offsetInCard)
                            ?: offsetInCard
                        dragState.value = MatrixDrag(
                            task.id,
                            startRoot,
                            quadrantAtPoint(quadrantBounds, startRoot)
                        )
                        onDragPositionChanged?.invoke(startRoot)
                    },
                    // Required by the detector (no default in 1.7).
                    onDrag = { _, dragAmount ->
                        val current = dragState.value
                        if (current != null && current.taskId == task.id) {
                            val pointer = current.pointerRoot + dragAmount
                            val next = current.copy(
                                pointerRoot = pointer,
                                targetQuadrant = quadrantAtPoint(quadrantBounds, pointer)
                            )
                            dragState.value = next
                            onDragPositionChanged?.invoke(next.pointerRoot)
                        }
                    },
                    onDragEnd = {
                        val info = dragState.value
                        dragState.value = null
                        if (info != null && info.taskId == task.id) {
                            // Recompute the target from the FINAL
                            // pointer and the LIVE bounds — the
                            // quadrant may have scrolled under a
                            // dwelling pointer since the last move.
                            val target = quadrantAtPoint(quadrantBounds, info.pointerRoot)
                            if (target != null && target != quadrant) {
                                onMoveTaskState.value(task, target)
                            }
                        }
                    },
                    onDragCancel = {
                        if (dragState.value?.taskId == task.id) dragState.value = null
                    }
                )
            }
            .clickable { onClick() }
            .testTag("matrix_card_${task.id}"),
        shape = NudgyTokens.cardRadius,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = NudgyTokens.cardElevation
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Checkbox(
                checked = task.isCompleted,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(
                    checkedColor = colorResource(R.color.nudgy_teal)
                )
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(matrixPriorityDotColor(task.priority))
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        textDecoration = if (task.isCompleted) {
                            TextDecoration.LineThrough
                        } else {
                            TextDecoration.None
                        }
                    ),
                    color = if (task.isCompleted) {
                        NudgyMuted
                    } else {
                        NudgyCharcoal
                    },
                    maxLines = 2
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = matrixDueLabel(task),
                        style = MaterialTheme.typography.bodySmall,
                        color = NudgyMuted
                    )
                    if (item.subtaskTotal > 0) {
                        Text(
                            text = "· ${item.subtaskDone}/${item.subtaskTotal}",
                            style = MaterialTheme.typography.bodySmall,
                            color = NudgyMuted
                        )
                    }
                }
                if (task.groupId != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(
                                colorResource(R.color.nudgy_teal).copy(alpha = 0.12f)
                            )
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "Group",
                            style = MaterialTheme.typography.bodySmall,
                            color = NudgyAccent
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun matrixPriorityDotColor(priority: Priority) = when (priority) {
    Priority.HIGH -> colorResource(R.color.nudgy_coral)
    Priority.MEDIUM -> colorResource(R.color.nudgy_teal)
    Priority.LOW -> colorResource(R.color.nudgy_muted)
}

private fun matrixDueLabel(task: TaskEntity): String {
    if (task.dueDateEpochMillis == TaskRepository.NO_DUE_DATE) return "No date"
    return SimpleDateFormat("EEE, MMM d", Locale.getDefault())
        .format(task.dueDateEpochMillis)
}

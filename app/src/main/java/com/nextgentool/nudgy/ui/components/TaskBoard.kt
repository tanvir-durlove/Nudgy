package com.nextgentool.nudgy.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.model.TaskStatus
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.ui.theme.NudgyTokens
import com.nextgentool.nudgy.ui.viewmodel.TaskListItem
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

/** List vs Board view for the Tasks screen and Today's My Day section. */
enum class TaskViewMode { LIST, BOARD }

/**
 * Groups filtered list items by Board column. Pure — unit-tested
 * without Compose. The Board shows the SAME filtered items as the List
 * (Feature 2 filters apply in Board too, spec Feature 5).
 */
fun groupBoardItems(items: List<TaskListItem>): Map<TaskStatus, List<TaskListItem>> =
    TaskStatus.entries.associateWith { status ->
        items.filter { it.task.status == status }
    }

/**
 * Resolves a Board drop against the FULL task list. Pure — unit-tested
 * without Compose.
 *
 * Returns the dragged task when it exists and its status differs from
 * the target column, otherwise null (unknown id, or a drop back on the
 * source column — both are no-ops).
 *
 * This is the S1-4b fix for the drop that could never land: the S1-4
 * target looked the dragged id up in the TARGET column's OWN items
 * (`items.firstOrNull { it.task.id == id }`), and a card dragged from
 * another column is never in that list — so every cross-column drop
 * was rejected, and on its own column the status check no-op'd. The
 * drop must resolve against all items, not the target column's.
 */
fun resolveBoardDrop(
    allItems: List<TaskListItem>,
    draggedId: String?,
    targetStatus: TaskStatus
): TaskEntity? {
    if (draggedId == null) return null
    val task = allItems.firstOrNull { it.task.id == draggedId }?.task ?: return null
    if (task.status == targetStatus) return null
    return task
}

/**
 * The column under a board-content x (px, i.e. viewport x + board
 * scroll offset). Pure — unit-tested without Compose. The pitch is
 * column width + gap; x outside the board clamps to the edge column.
 */
fun boardColumnAt(contentXPx: Float, pitchPx: Float): TaskStatus {
    val index = (contentXPx / pitchPx).toInt()
        .coerceIn(0, TaskStatus.entries.size - 1)
    return TaskStatus.entries[index]
}

private val BOARD_COLUMN_WIDTH = 272.dp
private val BOARD_COLUMN_GAP = 12.dp
private val BOARD_HEIGHT = 520.dp

/** In-flight Board drag: the dragged id + the pointer position in the board viewport (px). */
private data class BoardDrag(val taskId: String, val pointerViewport: Offset)

/**
 * Sprint 1-4b — Kanban board (Feature 5, spec §6), drag fixed.
 *
 * Three columns (To do / In progress / Done) over the SAME `status`
 * column as the List. A card is dragged with a long-press — a MANUAL
 * drag (detectDragGesturesAfterLongPress with offset tracking), NOT
 * platform drag-and-drop: the platform session exposes no drag
 * position, so it can neither auto-scroll nor be driven in a test,
 * and the S1-4 platform drop never landed (see [resolveBoardDrop]).
 *
 * While dragging, a loop auto-scrolls the board: near the horizontal
 * edge the board scrolls sideways to reveal the other columns; near
 * the vertical edge the hovered column scrolls to reveal more cards —
 * without that, an off-screen column or card could never be reached.
 * Dropping calls [onMoveTask]; dropping in Done sets `isCompleted =
 * true` + `completedAtEpochMillis` via the repository's single write
 * path (setTaskStatus). The checkbox on a card toggles completion the
 * same way the List does. The dragged card dims in place and a
 * floating preview follows the finger.
 */
@Composable
fun TaskBoard(
    items: List<TaskListItem>,
    onMoveTask: (TaskEntity, TaskStatus) -> Unit,
    onToggle: (TaskEntity) -> Unit,
    onTaskClick: (TaskEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val pitchPx = with(density) { (BOARD_COLUMN_WIDTH + BOARD_COLUMN_GAP).toPx() }
    val edgeXPx = with(density) { 48.dp.toPx() }
    val edgeYPx = with(density) { 56.dp.toPx() }
    val scrollStepPx = with(density) { 24.dp.toPx() }
    val previewHalfWidthPx = with(density) { (BOARD_COLUMN_WIDTH / 2).toPx() }
    val previewYOffsetPx = with(density) { 28.dp.toPx() }

    val dragState = remember { mutableStateOf<BoardDrag?>(null) }
    val drag = dragState.value
    val boardScrollState = rememberScrollState()
    val columnScrollStates = remember {
        TaskStatus.entries.associateWith { ScrollState(0) }
    }
    var boardRootPos by remember { mutableStateOf(Offset.Zero) }
    var boardSize by remember { mutableStateOf(IntSize.Zero) }
    val grouped = remember(items) { groupBoardItems(items) }

    // Fresh reads for the gesture callbacks (the pointerInput block is
    // keyed on the task id only, so it must not close over stale values).
    val allItemsState = rememberUpdatedState(items)
    val onMoveTaskState = rememberUpdatedState(onMoveTask)
    val boardRootPosState = rememberUpdatedState(boardRootPos)

    // Auto-scroll while dragging. A drag session scrolls nothing by
    // itself, and the pointer can sit still at an edge waiting for the
    // board to come to it — so this is a loop, not a per-move scroll:
    // it ticks while a drag is in flight and scrolls whenever the
    // pointer dwells near an edge.
    LaunchedEffect(drag?.taskId) {
        if (drag == null) return@LaunchedEffect
        while (true) {
            val current = dragState.value ?: break
            if (current.pointerViewport.x > boardSize.width - edgeXPx) {
                boardScrollState.scrollBy(scrollStepPx)
            } else if (current.pointerViewport.x < edgeXPx) {
                boardScrollState.scrollBy(-scrollStepPx)
            }
            val hovered = boardColumnAt(
                current.pointerViewport.x + boardScrollState.value, pitchPx
            )
            val columnState = columnScrollStates.getValue(hovered)
            if (current.pointerViewport.y > boardSize.height - edgeYPx) {
                columnState.scrollBy(scrollStepPx)
            } else if (current.pointerViewport.y < edgeYPx) {
                columnState.scrollBy(-scrollStepPx)
            }
            delay(32)
        }
    }

    val draggedTask = drag?.let { d -> items.firstOrNull { it.task.id == d.taskId }?.task }

    Box(modifier = modifier.testTag("task_board")) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(BOARD_COLUMN_GAP),
            modifier = Modifier
                .fillMaxWidth()
                .height(BOARD_HEIGHT)
                .horizontalScroll(boardScrollState)
                .onGloballyPositioned { boardRootPos = it.positionInRoot() }
                .onSizeChanged { boardSize = it }
        ) {
            TaskStatus.entries.forEach { status ->
                BoardColumn(
                    status = status,
                    items = grouped[status].orEmpty(),
                    columnScrollState = columnScrollStates.getValue(status),
                    dragState = dragState,
                    boardRootPosState = boardRootPosState,
                    boardScrollState = boardScrollState,
                    pitchPx = pitchPx,
                    allItemsState = allItemsState,
                    onMoveTaskState = onMoveTaskState,
                    onToggle = onToggle,
                    onTaskClick = onTaskClick
                )
            }
        }
        // Floating preview under the finger while dragging.
        if (drag != null && draggedTask != null) {
            Surface(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (drag.pointerViewport.x - previewHalfWidthPx).roundToInt(),
                            (drag.pointerViewport.y - previewYOffsetPx).roundToInt()
                        )
                    }
                    .width(BOARD_COLUMN_WIDTH)
                    .testTag("board_drag_preview"),
                shape = NudgyTokens.cardRadius,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = NudgyTokens.cardElevation
            ) {
                Text(
                    text = draggedTask.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colorResource(R.color.nudgy_ink),
                    maxLines = 2,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

@Composable
private fun BoardColumn(
    status: TaskStatus,
    items: List<TaskListItem>,
    columnScrollState: ScrollState,
    dragState: MutableState<BoardDrag?>,
    boardRootPosState: State<Offset>,
    boardScrollState: ScrollState,
    pitchPx: Float,
    allItemsState: State<List<TaskListItem>>,
    onMoveTaskState: State<(TaskEntity, TaskStatus) -> Unit>,
    onToggle: (TaskEntity) -> Unit,
    onTaskClick: (TaskEntity) -> Unit
) {
    // The column under the pointer highlights while a card is dragged
    // over it — the same affordance the S1-4 drop target had.
    val hovered = dragState.value?.let {
        boardColumnAt(it.pointerViewport.x + boardScrollState.value, pitchPx) == status
    } ?: false
    Surface(
        modifier = Modifier
            .width(BOARD_COLUMN_WIDTH)
            .testTag("board_column_${status.name}"),
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
                    text = when (status) {
                        TaskStatus.TODO -> "To do"
                        TaskStatus.IN_PROGRESS -> "In progress"
                        TaskStatus.DONE -> "Done"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = colorResource(R.color.nudgy_ink),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${items.size}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorResource(R.color.nudgy_muted)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(columnScrollState)
            ) {
                if (items.isEmpty()) {
                    Text(
                        text = "No tasks here",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorResource(R.color.nudgy_muted),
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                items.forEach { item ->
                    BoardTaskCard(
                        item = item,
                        dragState = dragState,
                        boardRootPosState = boardRootPosState,
                        boardScrollState = boardScrollState,
                        pitchPx = pitchPx,
                        allItemsState = allItemsState,
                        onMoveTaskState = onMoveTaskState,
                        isDragging = dragState.value?.taskId == item.task.id,
                        onToggle = { onToggle(item.task) },
                        onClick = { onTaskClick(item.task) }
                    )
                }
            }
        }
    }
}

@Composable
private fun BoardTaskCard(
    item: TaskListItem,
    dragState: MutableState<BoardDrag?>,
    boardRootPosState: State<Offset>,
    boardScrollState: ScrollState,
    pitchPx: Float,
    allItemsState: State<List<TaskListItem>>,
    onMoveTaskState: State<(TaskEntity, TaskStatus) -> Unit>,
    isDragging: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit
) {
    val task = item.task
    var cardRootPos by remember { mutableStateOf(Offset.Zero) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (isDragging) 0.35f else 1f }
            .onGloballyPositioned { cardRootPos = it.positionInRoot() }
            .pointerInput(task.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offsetInCard ->
                        // Pointer in board-viewport coordinates: the card
                        // and the board share the same root, so the
                        // difference is viewport-relative. Later moves
                        // only add deltas, so this stays right even as
                        // the board auto-scrolls under the finger.
                        val startViewport =
                            cardRootPos + offsetInCard - boardRootPosState.value
                        dragState.value = BoardDrag(task.id, startViewport)
                    },
                    // Required by the detector (no default in 1.7).
                    onDrag = { _, dragAmount ->
                        val current = dragState.value
                        if (current != null && current.taskId == task.id) {
                            dragState.value = current.copy(
                                pointerViewport = current.pointerViewport + dragAmount
                            )
                        }
                    },
                    onDragEnd = {
                        val info = dragState.value
                        dragState.value = null
                        if (info != null && info.taskId == task.id) {
                            val contentX =
                                info.pointerViewport.x + boardScrollState.value
                            val target = boardColumnAt(contentX, pitchPx)
                            resolveBoardDrop(allItemsState.value, info.taskId, target)
                                ?.let { onMoveTaskState.value(it, target) }
                        }
                    },
                    onDragCancel = {
                        if (dragState.value?.taskId == task.id) dragState.value = null
                    }
                )
            }
            .clickable { onClick() }
            .testTag("board_card_${task.id}"),
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
                    .background(boardPriorityDotColor(task.priority))
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
                        colorResource(R.color.nudgy_muted)
                    } else {
                        colorResource(R.color.nudgy_ink)
                    },
                    maxLines = 2
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = boardDueLabel(task),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorResource(R.color.nudgy_muted)
                    )
                    if (item.subtaskTotal > 0) {
                        Text(
                            text = "· ${item.subtaskDone}/${item.subtaskTotal}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colorResource(R.color.nudgy_muted)
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
                            color = colorResource(R.color.nudgy_teal)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun boardPriorityDotColor(priority: Priority) = when (priority) {
    Priority.HIGH -> colorResource(R.color.nudgy_coral)
    Priority.MEDIUM -> colorResource(R.color.nudgy_teal)
    Priority.LOW -> colorResource(R.color.nudgy_muted)
}

private fun boardDueLabel(task: TaskEntity): String {
    if (task.dueDateEpochMillis == TaskRepository.NO_DUE_DATE) return "No date"
    return SimpleDateFormat("EEE, MMM d", Locale.getDefault())
        .format(task.dueDateEpochMillis)
}

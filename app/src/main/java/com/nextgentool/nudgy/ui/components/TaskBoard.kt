package com.nextgentool.nudgy.ui.components

import android.content.ClipData
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.model.TaskStatus
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.ui.theme.NudgyTokens
import com.nextgentool.nudgy.ui.viewmodel.TaskListItem
import java.text.SimpleDateFormat
import java.util.Locale

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
 * Sprint 1-4 — Kanban board (Feature 5, spec §6).
 *
 * Three columns (To do / In progress / Done) over the SAME `status`
 * column as the List. A card is dragged with a long-press (platform
 * drag-and-drop, Compose 1.7 `dragAndDropSource` / `dragAndDropTarget`
 * — signatures verified against the 1.7.0 sources, not guessed).
 * Dropping on a column calls [onMoveTask]; dropping in Done sets
 * `isCompleted = true` + `completedAtEpochMillis` via the repository's
 * single write path (setTaskStatus). The checkbox on a card toggles
 * completion the same way the List does.
 *
 * The dragged task id is tracked in [dragState] (shared by source and
 * targets) — the drop target reads it from there instead of parsing
 * ClipData, so the drop never depends on the platform payload shape.
 * A card can only be dropped on a column visible at drop time (the
 * board scrolls horizontally); moving across two columns takes two
 * drags — that is the mobile-Kanban trade-off, not a bug.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TaskBoard(
    items: List<TaskListItem>,
    onMoveTask: (TaskEntity, TaskStatus) -> Unit,
    onToggle: (TaskEntity) -> Unit,
    onTaskClick: (TaskEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val dragState = remember { mutableStateOf<String?>(null) }
    val grouped = remember(items) { groupBoardItems(items) }
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(520.dp)
            .horizontalScroll(rememberScrollState())
            .testTag("task_board")
    ) {
        TaskStatus.entries.forEach { status ->
            BoardColumn(
                status = status,
                items = grouped[status].orEmpty(),
                dragState = dragState,
                onMoveTask = onMoveTask,
                onToggle = onToggle,
                onTaskClick = onTaskClick
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BoardColumn(
    status: TaskStatus,
    items: List<TaskListItem>,
    dragState: MutableState<String?>,
    onMoveTask: (TaskEntity, TaskStatus) -> Unit,
    onToggle: (TaskEntity) -> Unit,
    onTaskClick: (TaskEntity) -> Unit
) {
    var highlight by remember { mutableStateOf(false) }
    val target = remember(status, items) {
        object : DragAndDropTarget {
            override fun onDrop(event: androidx.compose.ui.draganddrop.DragAndDropEvent): Boolean {
                val id = dragState.value ?: return false
                val task = items.firstOrNull { it.task.id == id }?.task ?: return false
                if (task.status != status) onMoveTask(task, status)
                return true
            }

            override fun onEntered(event: androidx.compose.ui.draganddrop.DragAndDropEvent) {
                highlight = true
            }

            override fun onExited(event: androidx.compose.ui.draganddrop.DragAndDropEvent) {
                highlight = false
            }

            override fun onEnded(event: androidx.compose.ui.draganddrop.DragAndDropEvent) {
                highlight = false
                dragState.value = null
            }
        }
    }
    Surface(
        modifier = Modifier
            .width(272.dp)
            .dragAndDropTarget(
                shouldStartDragAndDrop = { dragState.value != null },
                target = target
            )
            .testTag("board_column_${status.name}"),
        shape = NudgyTokens.cardRadius,
        color = if (highlight) {
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
                    .verticalScroll(rememberScrollState())
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
                        onToggle = { onToggle(item.task) },
                        onClick = { onTaskClick(item.task) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BoardTaskCard(
    item: TaskListItem,
    dragState: MutableState<String?>,
    onToggle: () -> Unit,
    onClick: () -> Unit
) {
    val task = item.task
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .dragAndDropSource(
                drawDragDecoration = {
                    drawRoundRect(
                        color = Color(0xFF0F766E).copy(alpha = 0.30f),
                        cornerRadius = CornerRadius(24f, 24f)
                    )
                }
            ) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        dragState.value = task.id
                        startTransfer(
                            DragAndDropTransferData(
                                ClipData.newPlainText("nudgy-task-id", task.id)
                            )
                        )
                    },
                    // Required by the detector (no default). The platform
                    // drag session owns the drag visuals once startTransfer
                    // runs, so there is nothing to do per move here.
                    onDrag = { _, _ -> },
                    onDragEnd = { dragState.value = null },
                    onDragCancel = { dragState.value = null }
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

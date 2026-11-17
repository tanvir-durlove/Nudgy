package com.nextgentool.nudgy.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.ui.components.QuickAddTask
import com.nextgentool.nudgy.ui.theme.NudgyTokens
import com.nextgentool.nudgy.ui.viewmodel.TaskListItem
import com.nextgentool.nudgy.ui.viewmodel.TasksViewModel
import com.nextgentool.nudgy.ui.viewmodel.isDueToday
import com.nextgentool.nudgy.ui.viewmodel.startOfTodayMillis
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Sprint 1-0 — Today destination. Sprint 1-1 UI-FIX (2026-11-16) rebuilt
 * it around real data after the device test showed it unusable:
 *
 * - The screen SCROLLS (it never did — content below the fold was
 *   unreachable, which is why "All tasks" was effectively invisible).
 * - "All tasks" sits directly under the quick-add, visible on first
 *   paint, with the total count.
 * - The quick-add creates tasks due TODAY (see
 *   [TasksViewModel.createTaskForToday]), so a created task appears in
 *   My Day immediately instead of vanishing into "No date".
 * - "My Day" lists today's tasks from the same ViewModel state — no
 *   dead skeleton rows. While the first Flow emission loads, one
 *   skeleton row shows; after that, an empty day says so in words.
 * - "Timeline" shows today's tasks that have a time set, earliest
 *   first. With no timed task the whole section is HIDDEN (a blank
 *   peach box is not a timeline). S1-2 owns the full Today rewrite
 *   (meetings, carry-over, Plan) — this only makes S1-1's Today usable.
 */
@Composable
fun TodayScreen(
    onPlanClick: () -> Unit,
    // Sprint 1-1: entry point to the Tasks list. Defaults to no-op so
    // older call sites keep compiling.
    onAllTasksClick: () -> Unit = {},
    // Sprint 1-1 fix: the Today quick-add (Feature 1) shares TasksViewModel
    // with TasksScreen — a separate instance scoped to this destination,
    // same singleton repository, so the guards (empty title, double-tap)
    // are identical.
    viewModel: TasksViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()
    // Today's tasks: due on today's local date. No-date tasks (due = 0)
    // never land here — they live in the Tasks list's "No date" group.
    val todayItems = uiState.items.filter { isDueToday(it.task.dueDateEpochMillis) }
    val doneCount = todayItems.count { it.task.isCompleted }
    val percent = if (todayItems.isEmpty()) 0 else doneCount * 100 / todayItems.size
    // Timed today: a due time was set. The Today quick-add defaults to
    // 00:00 (start of today), which is "no time picked" — those tasks
    // live in My Day only, never in the Timeline.
    val timedItems = todayItems
        .filter { it.task.dueDateEpochMillis != startOfTodayMillis() }
        .sortedBy { it.task.dueDateEpochMillis }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(R.color.nudgy_background))
            .verticalScroll(scrollState)
            .padding(NudgyTokens.screenPadding)
    ) {
        // Hero card (Figma: "Make today count." + progress ring + button).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surface,
                    shape = NudgyTokens.cardRadius
                )
                .padding(NudgyTokens.screenPadding)
        ) {
            Column {
                Text(
                    text = "Make today count.",
                    style = MaterialTheme.typography.titleLarge,
                    color = colorResource(R.color.nudgy_ink)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(64.dp)
                        .background(
                            color = colorResource(R.color.nudgy_cream),
                            shape = CircleShape
                        )
                ) {
                    Text(
                        text = "$percent%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorResource(R.color.nudgy_muted)
                    )
                }
                if (todayItems.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "$doneCount of ${todayItems.size} done",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorResource(R.color.nudgy_muted)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onPlanClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colorResource(R.color.nudgy_teal),
                        contentColor = colorResource(R.color.nudgy_cream)
                    )
                ) {
                    Text(text = "Plan my day")
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        // Quick add on Today — creates due TODAY (Feature 1 requires the
        // field on the Today hero AND the Tasks screen).
        val lastCreated = uiState.items
            .firstOrNull { it.task.id == uiState.lastCreatedTaskId }
        QuickAddTask(
            onCreate = { viewModel.createTaskForToday(it) },
            isCreating = uiState.isCreating,
            lastCreatedTask = lastCreated?.task,
            onLastCreatedDue = { viewModel.setLastCreatedDue(it) },
            onLastCreatedPriority = { viewModel.setLastCreatedPriority(it) },
            onDismissLastCreated = { viewModel.clearLastCreated() }
        )
        Spacer(modifier = Modifier.height(12.dp))
        // All tasks — directly under the quick-add, visible on first
        // paint. It used to sit below the Timeline skeleton, under the
        // fold on a phone, on a screen that did not scroll at all.
        AllTasksRow(
            count = uiState.totalCount,
            onClick = onAllTasksClick
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "My Day",
            style = MaterialTheme.typography.titleMedium,
            color = colorResource(R.color.nudgy_ink)
        )
        Spacer(modifier = Modifier.height(12.dp))
        if (uiState.isLoading) {
            // Real loading only: one skeleton until the first emission.
            NudgySkeletonRow()
        } else if (todayItems.isEmpty()) {
            Text(
                text = "No tasks yet — add one above",
                style = MaterialTheme.typography.bodyMedium,
                color = colorResource(R.color.nudgy_muted),
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                todayItems.forEach { item ->
                    TodayTaskRow(
                        item = item,
                        onToggle = { viewModel.toggleTask(item.task) }
                    )
                }
            }
        }
        if (timedItems.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Timeline",
                style = MaterialTheme.typography.titleMedium,
                color = colorResource(R.color.nudgy_ink)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                timedItems.forEach { item ->
                    TodayTaskRow(
                        item = item,
                        onToggle = { viewModel.toggleTask(item.task) }
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

/** "All tasks" entry with the total count — the Tasks list's front door. */
@Composable
private fun AllTasksRow(
    count: Int,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .testTag("all_tasks_row"),
        shape = NudgyTokens.cardRadius,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = NudgyTokens.cardElevation
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "All tasks",
                style = MaterialTheme.typography.titleMedium,
                color = colorResource(R.color.nudgy_ink),
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "$count tasks",
                style = MaterialTheme.typography.bodyMedium,
                color = colorResource(R.color.nudgy_muted)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colorResource(R.color.nudgy_muted)
            )
        }
    }
}

/**
 * One row in My Day / Timeline: checkbox + priority dot + title + time.
 * Same visual language as the Tasks list's TaskRow, without the detail
 * sheet (S1-2 owns task details on Today).
 */
@Composable
private fun TodayTaskRow(
    item: TaskListItem,
    onToggle: () -> Unit
) {
    val task = item.task
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = NudgyTokens.cardRadius,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = NudgyTokens.cardElevation
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
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
                    .background(todayPriorityDotColor(task.priority))
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
                    }
                )
                Text(
                    text = todayTimeLabel(task.dueDateEpochMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorResource(R.color.nudgy_muted)
                )
            }
        }
    }
}

@Composable
private fun todayPriorityDotColor(priority: Priority) = when (priority) {
    Priority.HIGH -> colorResource(R.color.nudgy_coral)
    Priority.MEDIUM -> colorResource(R.color.nudgy_teal)
    Priority.LOW -> colorResource(R.color.nudgy_muted)
}

/** "Today" for the 00:00 quick-add default, otherwise the set time. */
private fun todayTimeLabel(dueEpochMillis: Long): String =
    if (dueEpochMillis == startOfTodayMillis()) {
        "Today"
    } else {
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(dueEpochMillis)
    }

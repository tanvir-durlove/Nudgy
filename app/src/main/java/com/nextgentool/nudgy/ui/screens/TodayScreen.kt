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
import com.nextgentool.nudgy.data.local.entity.CalendarEventEntity
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.domain.formatMinutes
import com.nextgentool.nudgy.domain.startOfDayMillis
import com.nextgentool.nudgy.domain.timeLabel
import com.nextgentool.nudgy.ui.components.QuickAddTask
import com.nextgentool.nudgy.ui.theme.NudgyTokens
import com.nextgentool.nudgy.ui.viewmodel.TaskListItem
import com.nextgentool.nudgy.ui.viewmodel.TasksViewModel
import com.nextgentool.nudgy.ui.viewmodel.TodayTab
import com.nextgentool.nudgy.ui.viewmodel.TodayTimelineEntry
import com.nextgentool.nudgy.ui.viewmodel.TodayViewModel
import com.nextgentool.nudgy.ui.viewmodel.startOfTodayMillis

/**
 * Sprint 1-2 — Today + Upcoming (Features 4, 15, 16).
 *
 * Layout (approved design v4): hero → plan nudge → carry-over banner →
 * quick-add → All tasks → Today | Upcoming tabs.
 *
 * - My Day (Q3) is Plan-picked tasks ONLY, the top section of the Today
 *   tab. A task the user did not pick never lands here.
 * - Timeline is the day's full schedule: every today-due task (picked
 *   or not) + today's calendar events, time-ordered, with a teal
 *   now-line. A task created after Plan — or left out of it — stays
 *   visible here instead of vanishing (the S1-1 device-test lesson).
 *   Unpicked today-due tasks are ALSO listed in Upcoming under "Today".
 * - The screen SCROLLS (S1-1 UI-FIX). Pills are single-line and scroll
 *   horizontally, never wrapping.
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
    tasksViewModel: TasksViewModel = hiltViewModel(),
    // Sprint 1-2: Today/Upcoming/My Day/Timeline/carry-over state.
    todayViewModel: TodayViewModel = hiltViewModel()
) {
    val uiState by todayViewModel.uiState.collectAsState()
    val tasksUiState by tasksViewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()
    val percent = if (uiState.todayTotal == 0) 0
    else uiState.doneCount * 100 / uiState.todayTotal

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
                if (uiState.todayTotal > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${uiState.doneCount} of ${uiState.todayTotal} done",
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
        // Plan nudge (Q7): a light card, never auto-open. Shown only
        // while nothing is planned for today yet.
        if (!uiState.isLoading && !uiState.hasPlannedToday) {
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("plan_nudge_card"),
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
                        text = "আজকের দিন সাজাবে?",
                        style = MaterialTheme.typography.titleMedium,
                        color = colorResource(R.color.nudgy_ink),
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = onPlanClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colorResource(R.color.nudgy_teal),
                            contentColor = colorResource(R.color.nudgy_cream)
                        )
                    ) {
                        Text(text = "Plan now")
                    }
                }
            }
        }
        // Carry-over banner (Feature 16, Q2): yesterday's unfinished
        // tasks wait here — never moved automatically.
        if (uiState.carryOverItems.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("carry_over_banner"),
                shape = NudgyTokens.cardRadius,
                color = colorResource(R.color.nudgy_cream),
                shadowElevation = NudgyTokens.cardElevation
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (uiState.carryOverItems.size == 1) {
                                "1 task carried over"
                            } else {
                                "${uiState.carryOverItems.size} tasks carried over"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = colorResource(R.color.nudgy_ink),
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = { todayViewModel.moveCarryOverAll() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colorResource(R.color.nudgy_teal),
                                contentColor = colorResource(R.color.nudgy_cream)
                            ),
                            modifier = Modifier.testTag("carry_over_move_all")
                        ) {
                            Text(text = "Move all to today")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        uiState.carryOverItems.forEach { item ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = item.task.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colorResource(R.color.nudgy_ink),
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "Move",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colorResource(R.color.nudgy_teal),
                                    modifier = Modifier
                                        .clickable { todayViewModel.moveCarryOverOne(item.task) }
                                        .padding(8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        // Quick add on Today — creates due TODAY (Feature 1 requires the
        // field on the Today hero AND the Tasks screen).
        val lastCreated = tasksUiState.items
            .firstOrNull { it.task.id == tasksUiState.lastCreatedTaskId }
        QuickAddTask(
            onCreate = { tasksViewModel.createTaskForToday(it) },
            isCreating = tasksUiState.isCreating,
            lastCreatedTask = lastCreated?.task,
            onLastCreatedDue = { tasksViewModel.setLastCreatedDue(it) },
            onLastCreatedPriority = { tasksViewModel.setLastCreatedPriority(it) },
            onDismissLastCreated = { tasksViewModel.clearLastCreated() }
        )
        Spacer(modifier = Modifier.height(12.dp))
        // All tasks — directly under the quick-add, visible on first
        // paint. It used to sit below the Timeline skeleton, under the
        // fold on a phone, on a screen that did not scroll at all.
        AllTasksRow(
            count = tasksUiState.totalCount,
            onClick = onAllTasksClick
        )
        Spacer(modifier = Modifier.height(16.dp))
        // Segmented control: exactly two tabs (Q3). Single-line pills.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            TodayTabButton(
                label = "Today",
                selected = uiState.tab == TodayTab.TODAY,
                onClick = { todayViewModel.setTab(TodayTab.TODAY) },
                modifier = Modifier.weight(1f).testTag("tab_today")
            )
            TodayTabButton(
                label = "Upcoming",
                selected = uiState.tab == TodayTab.UPCOMING,
                onClick = { todayViewModel.setTab(TodayTab.UPCOMING) },
                modifier = Modifier.weight(1f).testTag("tab_upcoming")
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        if (uiState.tab == TodayTab.TODAY) {
            // My Day — Plan-picked tasks only (Q3), top section.
            Text(
                text = "My Day",
                style = MaterialTheme.typography.titleMedium,
                color = colorResource(R.color.nudgy_ink)
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (uiState.isLoading) {
                // Real loading only: one skeleton until the first emission.
                NudgySkeletonRow()
            } else if (uiState.myDayItems.isEmpty()) {
                Text(
                    text = "Nothing planned for today yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorResource(R.color.nudgy_muted),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    uiState.myDayItems.forEach { item ->
                        TodayTaskRow(
                            item = item,
                            timeLabel = myDayTimeLabel(item),
                            onToggle = { todayViewModel.toggleTask(item.task) }
                        )
                    }
                }
            }
            // Timeline — the day's full schedule. Hidden entirely when
            // there is nothing timed and nothing untimed (a blank box is
            // not a timeline — S1-1 UI-FIX rule, kept).
            if (uiState.timelineEntries.isNotEmpty() || uiState.untimedTodayItems.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Timeline",
                    style = MaterialTheme.typography.titleMedium,
                    color = colorResource(R.color.nudgy_ink)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    uiState.timelineEntries.forEach { entry ->
                        when (entry) {
                            is TodayTimelineEntry.TaskBlock -> TodayTaskRow(
                                item = entry.item,
                                timeLabel = "${timeLabel(entry.startMillis)} – " +
                                    "${timeLabel(entry.startMillis + entry.durationMinutes * 60 * 1000L)}" +
                                    " · ${formatMinutes(entry.durationMinutes)}",
                                onToggle = { todayViewModel.toggleTask(entry.item.task) }
                            )
                            is TodayTimelineEntry.TaskPoint -> TodayTaskRow(
                                item = entry.item,
                                timeLabel = timeLabel(entry.startMillis),
                                onToggle = { todayViewModel.toggleTask(entry.item.task) }
                            )
                            is TodayTimelineEntry.EventBlock -> TimelineEventRow(entry.event)
                            is TodayTimelineEntry.NowLine -> NowLineRow(entry.atMillis)
                        }
                    }
                    if (uiState.untimedTodayItems.isNotEmpty()) {
                        Text(
                            text = "Anytime",
                            style = MaterialTheme.typography.titleSmall,
                            color = colorResource(R.color.nudgy_muted)
                        )
                        uiState.untimedTodayItems.forEach { item ->
                            TodayTaskRow(
                                item = item,
                                timeLabel = "Anytime",
                                onToggle = { todayViewModel.toggleTask(item.task) }
                            )
                        }
                    }
                }
            }
        } else {
            // Upcoming — next 7 days (today first) + "No date".
            if (uiState.isLoading) {
                NudgySkeletonRow()
            } else if (uiState.upcomingDays.isEmpty() && uiState.noDateItems.isEmpty()) {
                Text(
                    text = "Nothing coming up",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorResource(R.color.nudgy_muted),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    uiState.upcomingDays.forEach { day ->
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = day.label,
                                style = MaterialTheme.typography.titleMedium,
                                color = colorResource(R.color.nudgy_ink)
                            )
                            day.items.forEach { item ->
                                TodayTaskRow(
                                    item = item,
                                    timeLabel = upcomingTimeLabel(item),
                                    onToggle = { todayViewModel.toggleTask(item.task) }
                                )
                            }
                        }
                    }
                    if (uiState.noDateItems.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = "No date",
                                style = MaterialTheme.typography.titleMedium,
                                color = colorResource(R.color.nudgy_ink)
                            )
                            uiState.noDateItems.forEach { item ->
                                TodayTaskRow(
                                    item = item,
                                    timeLabel = "No date",
                                    onToggle = { todayViewModel.toggleTask(item.task) }
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

/** One half of the Today | Upcoming segmented control. */
@Composable
private fun TodayTabButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.clickable { onClick() },
        shape = NudgyTokens.cardRadius,
        color = if (selected) {
            colorResource(R.color.nudgy_teal)
        } else {
            MaterialTheme.colorScheme.surface
        },
        shadowElevation = NudgyTokens.cardElevation
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = if (selected) {
                    colorResource(R.color.nudgy_cream)
                } else {
                    colorResource(R.color.nudgy_ink)
                }
            )
        }
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
 * One row in My Day / Timeline / Upcoming: checkbox + priority dot +
 * title + time label + Group badge + subtask progress. Same visual
 * language as the Tasks list's TaskRow, without the detail sheet
 * (S1-2 owns task details on Today — the sheet lands with S1-4).
 */
@Composable
private fun TodayTaskRow(
    item: TaskListItem,
    timeLabel: String,
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
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = timeLabel,
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
                    Spacer(modifier = Modifier.height(6.dp))
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

/** A calendar event on the timeline: read-only, no checkbox. */
@Composable
private fun TimelineEventRow(event: CalendarEventEntity) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = NudgyTokens.cardRadius,
        color = colorResource(R.color.nudgy_cream),
        shadowElevation = NudgyTokens.cardElevation
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = event.title,
                style = MaterialTheme.typography.titleMedium,
                color = colorResource(R.color.nudgy_ink)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Event · ${timeLabel(event.startEpochMillis)} – " +
                    timeLabel(event.endEpochMillis),
                style = MaterialTheme.typography.bodySmall,
                color = colorResource(R.color.nudgy_muted)
            )
            if (!event.locationOrLink.isNullOrBlank()) {
                Text(
                    text = event.locationOrLink,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorResource(R.color.nudgy_muted)
                )
            }
        }
    }
}

/** The now-line: teal, never coral. */
@Composable
private fun NowLineRow(atMillis: Long) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(2.dp)
                .background(
                    color = colorResource(R.color.nudgy_teal),
                    shape = CircleShape
                )
        )
        Text(
            text = "Now · ${timeLabel(atMillis)}",
            style = MaterialTheme.typography.bodySmall,
            color = colorResource(R.color.nudgy_teal)
        )
    }
}

@Composable
private fun todayPriorityDotColor(priority: Priority) = when (priority) {
    Priority.HIGH -> colorResource(R.color.nudgy_coral)
    Priority.MEDIUM -> colorResource(R.color.nudgy_teal)
    Priority.LOW -> colorResource(R.color.nudgy_muted)
}

/** My Day: the planned start time, else the due time, else "Today". */
private fun myDayTimeLabel(item: TaskListItem): String {
    val task = item.task
    return when {
        task.plannedStartEpochMillis != null -> timeLabel(task.plannedStartEpochMillis)
        task.dueDateEpochMillis != startOfTodayMillis() -> timeLabel(task.dueDateEpochMillis)
        else -> "Today"
    }
}

/** Upcoming: the due time, or "Anytime" for a date-only task. */
private fun upcomingTimeLabel(item: TaskListItem): String {
    val due = item.task.dueDateEpochMillis
    return if (due == startOfDayMillis(due)) "Anytime" else timeLabel(due)
}

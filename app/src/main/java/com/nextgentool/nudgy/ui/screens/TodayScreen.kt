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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.data.local.entity.CalendarEventEntity
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.domain.formatMinutes
import com.nextgentool.nudgy.domain.startOfDayMillis
import com.nextgentool.nudgy.domain.timeLabel
import com.nextgentool.nudgy.ui.components.QuickAddTask
import com.nextgentool.nudgy.ui.components.EisenhowerMatrix
import com.nextgentool.nudgy.ui.components.SampleChip
import com.nextgentool.nudgy.ui.components.SampleDeleteButton
import com.nextgentool.nudgy.ui.components.TaskBoard
import com.nextgentool.nudgy.ui.components.TaskViewMode
import com.nextgentool.nudgy.ui.theme.NudgyAccent
import com.nextgentool.nudgy.ui.theme.NudgyCanvas
import com.nextgentool.nudgy.ui.theme.NudgyCard
import com.nextgentool.nudgy.ui.theme.NudgyCharcoal
import com.nextgentool.nudgy.ui.theme.NudgyMuted
import com.nextgentool.nudgy.ui.theme.NudgyTokens
import com.nextgentool.nudgy.ui.viewmodel.HabitViewModel
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
    // Sprint 4-1: entry point to the Focus timer (Q10=(a)). Defaults
    // to no-op so older call sites keep compiling.
    onFocusClick: () -> Unit = {},
    // Sprint 4-4: entry point to the weekly review screen (Q9=(a)) —
    // the progress ring opens the week's summary. Defaults to no-op
    // so older call sites keep compiling.
    onWeeklyReviewClick: () -> Unit = {},
    // Sprint 6-4: entry point to the global search screen (Q5) — the
    // magnifier in the hero card's action row. Defaults to no-op so
    // older call sites keep compiling.
    onSearchClick: () -> Unit = {},
    // Sprint 1-1 fix: the Today quick-add (Feature 1) shares TasksViewModel
    // with TasksScreen — a separate instance scoped to this destination,
    // same singleton repository, so the guards (empty title, double-tap)
    // are identical.
    tasksViewModel: TasksViewModel = hiltViewModel(),
    // Sprint 1-2: Today/Upcoming/My Day/Timeline/carry-over state.
    todayViewModel: TodayViewModel = hiltViewModel(),
    // Sprint 4-2: the Habits section (spec §5, Q10=(a)). Passed in
    // from the NavHost; null in older call sites and in tests that do
    // not wire habits — the section is simply skipped then. It is
    // nullable (not a hiltViewModel() default) on purpose: the
    // existing Today tests omit this parameter, and a default would
    // evaluate hiltViewModel() in a non-Hilt Robolectric test.
    habitViewModel: HabitViewModel? = null
) {
    val uiState by todayViewModel.uiState.collectAsState()
    val tasksUiState by tasksViewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()
    // Sprint 1-4 — List / Board toggle for the My Day section (Feature 5).
    var myDayViewMode by remember { mutableStateOf(TaskViewMode.LIST) }
    val percent = if (uiState.todayTotal == 0) 0
    else uiState.doneCount * 100 / uiState.todayTotal

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NudgyCanvas)
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
                    color = NudgyCharcoal
                )
                Spacer(modifier = Modifier.height(12.dp))
                // Sprint 4-4 — the progress ring opens the week's
                // summary (spec §7, Q9=(a)): completed count, focus
                // minutes and streaks on one screen.
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(
                            color = colorResource(R.color.nudgy_cream),
                            shape = CircleShape
                        )
                        .clickable { onWeeklyReviewClick() }
                        .testTag("progress_ring")
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
                        color = NudgyMuted
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                // Sprint 4-1 — Focus timer entry (Q10=(a)): next to the
                // Plan button, same teal treatment.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onPlanClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colorResource(R.color.nudgy_teal),
                            contentColor = colorResource(R.color.nudgy_cream)
                        )
                    ) {
                        Text(text = "Plan my day")
                    }
                    Button(
                        onClick = onFocusClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colorResource(R.color.nudgy_teal),
                            contentColor = colorResource(R.color.nudgy_cream)
                        ),
                        modifier = Modifier.testTag("focus_button")
                    ) {
                        Text(text = "Focus")
                    }
                    // Sprint 6-4 — global search entry point (Q5): the
                    // magnifier opens the search screen. The testTag
                    // lives ON the IconButton itself — a testTag inside
                    // a clickable is invisible in the merged tree.
                    IconButton(
                        onClick = onSearchClick,
                        modifier = Modifier.testTag("search_button")
                    ) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = stringResource(R.string.search_title)
                        )
                    }
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
                        color = NudgyCharcoal,
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
                label = stringResource(R.string.today_tab_today),
                selected = uiState.tab == TodayTab.TODAY,
                onClick = { todayViewModel.setTab(TodayTab.TODAY) },
                modifier = Modifier.weight(1f).testTag("tab_today")
            )
            TodayTabButton(
                label = stringResource(R.string.today_tab_upcoming),
                selected = uiState.tab == TodayTab.UPCOMING,
                onClick = { todayViewModel.setTab(TodayTab.UPCOMING) },
                modifier = Modifier.weight(1f).testTag("tab_upcoming")
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        if (uiState.tab == TodayTab.TODAY) {
            // My Day — Plan-picked tasks only (Q3), top section.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.today_my_day),
                    style = MaterialTheme.typography.titleMedium,
                    color = NudgyCharcoal,
                    modifier = Modifier.weight(1f)
                )
                // Sprint 1-4 — List / Board / Matrix toggle (Feature 5,
                // Sprint 4-3 — the Matrix is the third view of My Day).
                TaskViewMode.entries.forEach { mode ->
                    FilterChip(
                        selected = myDayViewMode == mode,
                        onClick = { myDayViewMode = mode },
                        label = {
                            Text(
                                when (mode) {
                                    TaskViewMode.LIST -> stringResource(R.string.today_view_list)
                                    TaskViewMode.BOARD -> stringResource(R.string.today_view_board)
                                    TaskViewMode.MATRIX -> stringResource(R.string.today_view_matrix)
                                }
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = colorResource(R.color.nudgy_teal),
                            selectedLabelColor = colorResource(R.color.nudgy_cream),
                            // Unselected: explicit theme-aware colors. The
                            // M3 defaults put a light onSurface label on
                            // a transparent container — over the light
                            // screen that read as white-on-white in dark
                            // mode ("Board" invisible, device 2026-11-21).
                            containerColor = NudgyCard,
                            labelColor = NudgyCharcoal
                        ),
                        modifier = Modifier.testTag(
                            "my_day_view_mode_${mode.name.lowercase()}"
                        )
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            if (uiState.isLoading) {
                // Real loading only: one skeleton until the first emission.
                NudgySkeletonRow()
            } else if (uiState.myDayItems.isEmpty()) {
                Text(
                    text = stringResource(R.string.today_nothing_planned),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NudgyMuted,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else if (myDayViewMode == TaskViewMode.BOARD) {
                // Sprint 1-4 — Board over the SAME My Day items. Dragging
                // a card reorders the day (S1-4g, decision (e2)): the
                // drop re-slots its plannedStart, so it lands where it
                // was dropped; the detail sheet lives on the Tasks
                // screen, so a tap here does nothing. The Board keeps
                // the My Day (plannedStart) order — boardOrder is only
                // written, never displayed here.
                TaskBoard(
                    items = uiState.myDayItems,
                    onMoveTask = { task, status, index, visibleItems ->
                        tasksViewModel.moveTaskToPosition(
                            task, status, index, visibleItems,
                            updatePlannedStart = true
                        )
                    },
                    onToggle = { todayViewModel.toggleTask(it) },
                    onTaskClick = {},
                    // Sprint 6-3 — the board card carries the sample
                    // delete too (same deleteTask).
                    onDeleteSample = { tasksViewModel.deleteTask(it) },
                    sortByBoardOrder = false
                )
            } else if (myDayViewMode == TaskViewMode.MATRIX) {
                // Sprint 4-3 — Eisenhower Matrix over the SAME My Day
                // items. A quadrant move is a priority + due change
                // (spec §6), not a position change — the List shows it
                // from the same TaskEntity, and the Board's boardOrder
                // rules do not apply here.
                EisenhowerMatrix(
                    items = uiState.myDayItems,
                    onMoveTask = { task, quadrant ->
                        tasksViewModel.moveTaskToQuadrant(task, quadrant)
                    },
                    onToggle = { todayViewModel.toggleTask(it) },
                    onTaskClick = {}
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    uiState.myDayItems.forEach { item ->
                        TodayTaskRow(
                            item = item,
                            timeLabel = myDayTimeLabel(item, stringResource(R.string.today_time_label)),
                            onToggle = { todayViewModel.toggleTask(item.task) },
                            onDeleteSample = { tasksViewModel.deleteTask(it) }
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
                    color = NudgyCharcoal
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
                                onToggle = { todayViewModel.toggleTask(entry.item.task) },
                                onDeleteSample = { tasksViewModel.deleteTask(it) }
                            )
                            is TodayTimelineEntry.TaskPoint -> TodayTaskRow(
                                item = entry.item,
                                timeLabel = timeLabel(entry.startMillis),
                                onToggle = { todayViewModel.toggleTask(entry.item.task) },
                                onDeleteSample = { tasksViewModel.deleteTask(it) }
                            )
                            is TodayTimelineEntry.EventBlock -> TimelineEventRow(entry.event)
                            is TodayTimelineEntry.NowLine -> NowLineRow(entry.atMillis)
                        }
                    }
                    if (uiState.untimedTodayItems.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.today_anytime),
                            style = MaterialTheme.typography.titleSmall,
                            color = NudgyMuted
                        )
                        uiState.untimedTodayItems.forEach { item ->
                            TodayTaskRow(
                                item = item,
                                // "Today", not "Anytime": the group header
                                // already says Anytime (S1-1 showed
                                // "Today" here; repeating the header text
                                // on every row is redundant).
                                timeLabel = stringResource(R.string.today_time_label),
                                onToggle = { todayViewModel.toggleTask(item.task) },
                                onDeleteSample = { tasksViewModel.deleteTask(it) }
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
                    color = NudgyMuted,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    uiState.upcomingDays.forEach { day ->
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = day.label,
                                style = MaterialTheme.typography.titleMedium,
                                color = NudgyCharcoal
                            )
                            day.items.forEach { item ->
                                TodayTaskRow(
                                    item = item,
                                    timeLabel = upcomingTimeLabel(item, stringResource(R.string.today_anytime)),
                                    onToggle = { todayViewModel.toggleTask(item.task) },
                                    onDeleteSample = { tasksViewModel.deleteTask(it) }
                                )
                            }
                        }
                    }
                    if (uiState.noDateItems.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = stringResource(R.string.today_no_date),
                                style = MaterialTheme.typography.titleMedium,
                                color = NudgyCharcoal
                            )
                            uiState.noDateItems.forEach { item ->
                                TodayTaskRow(
                                    item = item,
                                    // "No due date", not "No date": the
                                    // section header already says No date.
                                    timeLabel = stringResource(R.string.today_no_due_date),
                                    onToggle = { todayViewModel.toggleTask(item.task) },
                                    onDeleteSample = { tasksViewModel.deleteTask(it) }
                                )
                            }
                        }
                    }
                }
            }
        }
        // Sprint 4-2 — Habits (spec §5, Q10=(a)): a section inside
        // Today, above the weekly review, on either tab.
        habitViewModel?.let { habitVm ->
            Spacer(modifier = Modifier.height(24.dp))
            HabitsSection(viewModel = habitVm)
        }
        // Weekly review (Feature 17, S1-5) — always visible at the
        // bottom of Today, on either tab: this week's finished / missed /
        // slipped, as three plain lists. No counts, no percentages, no
        // report — Sprint 4 owns numbers (feature 27).
        Spacer(modifier = Modifier.height(24.dp))
        uiState.weeklyReview?.let { review ->
            WeeklyReviewCard(review = review, stats = uiState.weeklyReviewStats)
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

/**
 * Sprint 1-5 — Weekly review card (Feature 17, spec §6).
 *
 * Three plain lists for the current Mon–Sun week, computed from tasks
 * only (no new table). A finished row shows the hand-written actual
 * time when one was logged (Feature 18) — list content, not a report.
 * Group rows carry the same "Group" badge as everywhere else.
 *
 * Sprint 4-4 — the card also carries the review's numbers (Feature
 * 27): focus minutes this week + best streaks, from real session /
 * check-in data ([stats]). Null when the FocusHabitRepository is not
 * wired — the card then shows its lists only, exactly as before.
 * The weekly review screen reuses this card, so the inline card and
 * the screen can never disagree.
 */
@Composable
fun WeeklyReviewCard(
    review: com.nextgentool.nudgy.domain.usecase.WeeklyReview,
    stats: com.nextgentool.nudgy.domain.usecase.WeeklyReviewStats? = null
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("weekly_review_card"),
        shape = NudgyTokens.cardRadius,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = NudgyTokens.cardElevation
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Weekly review",
                    style = MaterialTheme.typography.titleMedium,
                    color = NudgyCharcoal,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = review.weekLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NudgyMuted
                )
            }
            Text(
                text = "What finished, what was missed, what slipped this week.",
                style = MaterialTheme.typography.bodySmall,
                color = NudgyMuted
            )
            if (stats != null) {
                WeeklyReviewStatsBlock(stats = stats)
            }
            WeeklyReviewSection(
                title = "Finished",
                tasks = review.finished,
                emptyText = "Nothing finished yet",
                showActual = true
            )
            WeeklyReviewSection(
                title = "Missed",
                tasks = review.missed,
                emptyText = "Nothing missed",
                showActual = false
            )
            WeeklyReviewSection(
                title = "Slipped",
                tasks = review.slipped,
                emptyText = "Nothing slipped",
                showActual = false
            )
        }
    }
}

/**
 * Sprint 4-4 — the review's numbers inside the card (spec §7).
 *
 * Focus minutes this week (completed FOCUS sessions only, breaks and
 * cancelled sessions never count) + the best current streak and the
 * best longest streak across all habits. All text uses the theme
 * tokens (PR #86); the numbers are real data, never cached counters.
 */
@Composable
private fun WeeklyReviewStatsBlock(
    stats: com.nextgentool.nudgy.domain.usecase.WeeklyReviewStats
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "This week",
            style = MaterialTheme.typography.titleSmall,
            color = NudgyCharcoal
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("weekly_review_focus_minutes")
        ) {
            Text(
                text = "Focus",
                style = MaterialTheme.typography.bodyMedium,
                color = NudgyCharcoal,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = formatMinutes(stats.focusMinutesThisWeek),
                style = MaterialTheme.typography.bodyMedium,
                color = NudgyMuted
            )
        }
        if (stats.habits.isEmpty()) {
            Text(
                text = "No habits yet — streaks start with your first habit",
                style = MaterialTheme.typography.bodyMedium,
                color = NudgyMuted,
                modifier = Modifier.testTag("weekly_review_no_habits")
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("weekly_review_current_streak")
            ) {
                Text(
                    text = "Current streak",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NudgyCharcoal,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = streakDays(stats.bestCurrentStreak),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NudgyMuted
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("weekly_review_best_streak")
            ) {
                Text(
                    text = "Best streak",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NudgyCharcoal,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = streakDays(stats.bestLongestStreak),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NudgyMuted
                )
            }
        }
    }
}

/** "3 days" / "1 day" / "0 days" — the streak value, plainly. */
private fun streakDays(days: Int): String =
    if (days == 1) "1 day" else "$days days"

@Composable
private fun WeeklyReviewSection(
    title: String,
    tasks: List<com.nextgentool.nudgy.data.local.entity.TaskEntity>,
    emptyText: String,
    showActual: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = NudgyCharcoal
        )
        if (tasks.isEmpty()) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodyMedium,
                color = NudgyMuted
            )
        } else {
            tasks.forEach { task ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("weekly_review_row_${task.id}")
                ) {
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.bodyMedium.copy(
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
                        modifier = Modifier.weight(1f)
                    )
                    // Feature 18: the hand-written actual time surfaces
                    // here on finished rows, when one was logged.
                    if (showActual && task.actualMinutes != null) {
                        Text(
                            text = formatMinutes(task.actualMinutes),
                            style = MaterialTheme.typography.bodySmall,
                            color = NudgyMuted
                        )
                    }
                    if (task.groupId != null) {
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
                        // Sprint 5-5 (feature 36): the assignee's name,
                        // denormalized on the row — blank reads "Member".
                        if (task.assigneeUid != null) {
                            Text(
                                text = "· ${task.assigneeName?.ifBlank { "Member" } ?: "Member"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = NudgyMuted
                            )
                        }
                    }
                }
            }
        }
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
                    NudgyCharcoal
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
                text = stringResource(R.string.today_all_tasks),
                style = MaterialTheme.typography.titleMedium,
                color = NudgyCharcoal,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(R.string.today_tasks_count, count),
                style = MaterialTheme.typography.bodyMedium,
                color = NudgyMuted
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = NudgyMuted
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
    onToggle: () -> Unit,
    // Sprint 6-3 — the sample row's one-tap delete (spec §6): null
    // in older call sites (no delete button renders).
    onDeleteSample: ((TaskEntity) -> Unit)? = null
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
                        NudgyMuted
                    } else {
                        NudgyCharcoal
                    }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = timeLabel,
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
                            color = NudgyAccent
                        )
                    }
                    // Sprint 5-5 (feature 36): the assignee's name,
                    // denormalized on the row — blank reads "Member".
                    if (task.assigneeUid != null) {
                        Text(
                            text = "· ${task.assigneeName?.ifBlank { "Member" } ?: "Member"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = NudgyMuted
                        )
                    }
                }
                // Sprint 6-3 — the sample chip (a group row can never
                // be a sample: samples are local-only personal tasks).
                if (task.isSample) {
                    Spacer(modifier = Modifier.height(6.dp))
                    SampleChip()
                }
            }
            // Sprint 6-3 — the sample row's one-tap delete.
            if (task.isSample && onDeleteSample != null) {
                SampleDeleteButton(task = task, onDeleteSample = { onDeleteSample(task) })
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

/** My Day: the planned start time, else the due time, else the "Today" label. */
private fun myDayTimeLabel(item: TaskListItem, todayLabel: String): String {
    val task = item.task
    return when {
        task.plannedStartEpochMillis != null -> timeLabel(task.plannedStartEpochMillis)
        task.dueDateEpochMillis != startOfTodayMillis() -> timeLabel(task.dueDateEpochMillis)
        else -> todayLabel
    }
}

/** Upcoming: the due time, or the "Anytime" label for a date-only task. */
private fun upcomingTimeLabel(item: TaskListItem, anytimeLabel: String): String {
    val due = item.task.dueDateEpochMillis
    return if (due == startOfDayMillis(due)) anytimeLabel else timeLabel(due)
}

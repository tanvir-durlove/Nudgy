package com.nextgentool.nudgy.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.data.local.entity.CalendarEventEntity
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.domain.formatMinutes
import com.nextgentool.nudgy.domain.startOfDayMillis
import com.nextgentool.nudgy.domain.timeLabel
import com.nextgentool.nudgy.ui.components.QuickAddTask
import com.nextgentool.nudgy.ui.components.SampleChip
import com.nextgentool.nudgy.ui.components.SampleDeleteButton
import com.nextgentool.nudgy.ui.theme.NudgyAccent
import com.nextgentool.nudgy.ui.theme.NudgyCanvas
import com.nextgentool.nudgy.ui.theme.NudgyCharcoal
import com.nextgentool.nudgy.ui.theme.NudgyChipBackground
import com.nextgentool.nudgy.ui.theme.NudgyIsDarkTheme
import com.nextgentool.nudgy.ui.theme.NudgyMuted
import com.nextgentool.nudgy.ui.theme.NudgyTokens
import com.nextgentool.nudgy.ui.viewmodel.HabitViewModel
import com.nextgentool.nudgy.ui.viewmodel.TaskListItem
import com.nextgentool.nudgy.ui.viewmodel.TasksViewModel
import com.nextgentool.nudgy.ui.viewmodel.TodayTab
import com.nextgentool.nudgy.ui.viewmodel.TodayTimelineEntry
import com.nextgentool.nudgy.ui.viewmodel.TodayViewModel
import com.nextgentool.nudgy.ui.viewmodel.startOfTodayMillis
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Sprint 1-2 — Today + Upcoming (Features 4, 15, 16).
 *
 * Layout (Sprint 7-2a, prototype scr-today): hero card (title + date
 * + 104px donut + Plan / Focus / search) → quick-add card → All
 * tasks / Templates ghost buttons → carry-over banner → Today |
 * Upcoming segmented control → Today pane (My Day seccard, plan
 * nudge banner, Timeline seccard with the now-line rail, This week
 * seccard with four statboxes) / Upcoming pane.
 *
 * - My Day (Q3) is Plan-picked tasks ONLY, the top section of the Today
 *   tab. A task the user did not pick never lands here. Sprint 7
 *   Q3(a) removed the List / Board / Matrix switcher: Board lives on
 *   the Tasks screen, Matrix gets its own screen later in the sprint
 *   — so My Day is a plain list here, no view-mode state at all.
 * - Timeline is the day's full schedule: every today-due task (picked
 *   or not) + today's calendar events, time-ordered, with a teal
 *   now-line. A task created after Plan — or left out of it — stays
 *   visible here instead of vanishing (the S1-1 device-test lesson).
 *   Unpicked today-due tasks are ALSO listed in Upcoming under "Today".
 * - The screen SCROLLS (S1-1 UI-FIX). Pills are single-line and scroll
 *   horizontally, never wrapping.
 * - "More in Nudgy" (S7-2b, prototype scr-today): Focus / Habits /
 *   Priority matrix tiles below the This-week card. Focus + Matrix
 *   navigate to their screens (Matrix access RETURNS here — the
 *   screen existed since PR #140 with no button reaching it);
 *   Habits scrolls Today to the Habits section below. The Widget
 *   tile and the Share-preview button are NOT here (disclosed
 *   deviations, decided by Tanvir 2026-11-27 on the PM's
 *   recommendation): the widget has no in-app destination (no
 *   Settings row, no pin flow — a new feature) and the app has no
 *   outbound share (the prototype button is a demo).
 *   The weekly review's task lists live on the WeeklyReview screen
 *   only — Today shows the four This-week statboxes + a button there.
 */
@Composable
fun TodayScreen(
    onPlanClick: () -> Unit,
    // Sprint 1-1: entry point to the Tasks list. Defaults to no-op so
    // older call sites keep compiling.
    onAllTasksClick: () -> Unit = {},
    // Sprint 7-2a — entry point to the Templates screen (prototype
    // ghost button next to All tasks). Defaults to no-op so older
    // call sites keep compiling.
    onTemplatesClick: () -> Unit = {},
    // Sprint 4-1: entry point to the Focus timer (Q10=(a)). Defaults
    // to no-op so older call sites keep compiling.
    onFocusClick: () -> Unit = {},
    // Sprint 7-2b: entry point to the Priority matrix screen
    // (prototype "More in Nudgy" tile). Defaults to no-op so older
    // call sites keep compiling.
    onMatrixClick: () -> Unit = {},
    // Sprint 4-4: entry point to the weekly review screen (Q9=(a)) —
    // the progress ring opens the week's summary. Defaults to no-op
    // so older call sites keep compiling.
    onWeeklyReviewClick: () -> Unit = {},
    // Sprint 6-4: entry point to the global search screen (Q5) — the
    // magnifier in the hero card's action row. Defaults to no-op so
    // older call sites keep compiling.
    onSearchClick: () -> Unit = {},
    // Sprint 6-5 (step 3b) — the widget "+" quick-add: the NavHost
    // passes the one-shot focus signal here; the QuickAddTask field
    // focuses on entry and consumes it. Defaults to no-op so older
    // call sites keep compiling.
    focusQuickAdd: Boolean = false,
    onFocusQuickAddConsumed: () -> Unit = {},
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
    // Sprint 7-2b — the Habits tile scrolls Today to the Habits
    // section (no Habits screen exists): the section's measured
    // Y offset inside this Column, captured once laid out. The
    // Column composes every child, so the offset is measurable
    // even while the section sits below the fold.
    val scope = rememberCoroutineScope()
    var habitsOffsetY by remember { mutableStateOf<Int?>(null) }
    val percent = if (uiState.todayTotal == 0) 0
    else uiState.doneCount * 100 / uiState.todayTotal
    // Sprint 7-2a — the prototype's soft teal: light uses the brand
    // token, dark uses the M3 tertiary tint (same family, readable
    // on the dark card). Used by the timeline blocks.
    val softBackground = if (NudgyIsDarkTheme) {
        MaterialTheme.colorScheme.tertiary
    } else {
        colorResource(R.color.nudgy_teal_soft)
    }
    // Prototype hero date line: "Monday, Nov 16 · 2 of 5 done".
    val todayLabel = SimpleDateFormat("EEEE, MMM d", Locale.getDefault())
        .format(Date())
    val heroDateLine = if (uiState.todayTotal > 0) {
        "$todayLabel · ${uiState.doneCount} of ${uiState.todayTotal} done"
    } else {
        todayLabel
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NudgyCanvas)
            .verticalScroll(scrollState)
            .padding(NudgyTokens.screenPadding)
    ) {
        // Hero card (prototype scr-today): title + date on the left,
        // the 104px donut on the right, the action row below. The
        // Plan button sits in the action row with Focus + search —
        // the prototype's left column cannot fit all three on a
        // 360dp screen (the donut alone takes 104dp).
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = NudgyTokens.cardRadius,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = NudgyTokens.cardElevation,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Make today count.",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Black,
                            color = NudgyCharcoal
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = heroDateLine,
                            fontSize = 13.sp,
                            color = NudgyMuted
                        )
                    }
                    // Sprint 4-4 — the progress ring opens the week's
                    // summary (spec §7, Q9=(a)): completed count, focus
                    // minutes and streaks on one screen.
                    DonutRing(percent = percent, onClick = onWeeklyReviewClick)
                }
                Spacer(modifier = Modifier.height(14.dp))
                // Sprint 4-1 — Focus timer entry (Q10=(a)): next to the
                // Plan button, same teal treatment. Sprint 6-4 — the
                // magnifier opens the search screen (Q5); the testTag
                // lives ON the IconButton itself — a testTag inside
                // a clickable is invisible in the merged tree.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onPlanClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colorResource(R.color.nudgy_teal),
                            contentColor = colorResource(R.color.nudgy_cream)
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CalendarToday,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
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
        // Plan nudge (Q7) — Sprint 7-2a: it lives inside the Today
        // tab, after My Day (prototype order), not above the
        // quick-add. See the Today pane below.
        // Quick-add card (prototype scr-today): label + field + Save.
        // The hint is reworded from the prototype's "land in My Day":
        // S1-2 — a quick-added task lands in the Timeline's Anytime
        // group, never in My Day (Plan-picked only).
        Spacer(modifier = Modifier.height(14.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = NudgyTokens.cardRadius,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = NudgyTokens.cardElevation,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "QUICK ADD — DUE TODAY",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = NudgyMuted,
                    letterSpacing = 0.5.sp
                )
                Spacer(modifier = Modifier.height(9.dp))
                val lastCreated = tasksUiState.items
                    .firstOrNull { it.task.id == tasksUiState.lastCreatedTaskId }
                QuickAddTask(
                    onCreate = { tasksViewModel.createTaskForToday(it) },
                    isCreating = tasksUiState.isCreating,
                    lastCreatedTask = lastCreated?.task,
                    onLastCreatedDue = { tasksViewModel.setLastCreatedDue(it) },
                    onLastCreatedPriority = { tasksViewModel.setLastCreatedPriority(it) },
                    onDismissLastCreated = { tasksViewModel.clearLastCreated() },
                    autofocus = focusQuickAdd,
                    onAutofocusConsumed = onFocusQuickAddConsumed,
                    showSaveButton = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "New tasks land in your Timeline below, right away.",
                    fontSize = 12.sp,
                    color = NudgyMuted,
                    modifier = Modifier.padding(start = 2.dp)
                )
            }
        }
        // All tasks / Templates ghost buttons (prototype): equal
        // width, same navigation as before.
        Spacer(modifier = Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton(
                icon = Icons.Filled.List,
                text = stringResource(R.string.today_all_tasks),
                onClick = onAllTasksClick,
                testTag = "all_tasks_button",
                modifier = Modifier.weight(1f)
            )
            GhostButton(
                icon = Icons.Filled.GridView,
                text = "Templates",
                onClick = onTemplatesClick,
                testTag = "templates_button",
                modifier = Modifier.weight(1f)
            )
        }
        // Carry-over banner (Feature 16, Q2): yesterday's unfinished
        // tasks wait here — never moved automatically. Prototype
        // shape (white card, teal icon, subcopy); the actions stay
        // the established ones — "Move all to today" + per-item
        // "Move". The prototype's "Choose in Plan" / "Dismiss"
        // buttons have no behaviour behind them and are not added.
        if (uiState.carryOverItems.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("carry_over_banner"),
                shape = NudgyTokens.cardRadius,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = NudgyTokens.cardElevation,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        tint = colorResource(R.color.nudgy_teal),
                        modifier = Modifier
                            .size(22.dp)
                            .padding(top = 2.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (uiState.carryOverItems.size == 1) {
                                "1 task carried over"
                            } else {
                                "${uiState.carryOverItems.size} tasks carried over"
                            },
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = colorResource(R.color.nudgy_ink)
                        )
                        Text(
                            text = "Carry-over is never automatic — you choose.",
                            fontSize = 13.sp,
                            color = NudgyMuted,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        Row(modifier = Modifier.padding(top = 10.dp)) {
                            Button(
                                onClick = { todayViewModel.moveCarryOverAll() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colorResource(R.color.nudgy_teal),
                                    contentColor = colorResource(R.color.nudgy_cream)
                                ),
                                contentPadding = PaddingValues(
                                    horizontal = 14.dp,
                                    vertical = 7.dp
                                ),
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 38.dp)
                                    .testTag("carry_over_move_all")
                            ) {
                                Text(text = "Move all to today", fontSize = 14.sp)
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
        }
        Spacer(modifier = Modifier.height(18.dp))
        // Segmented control (prototype .seg): one pill container,
        // the selected tab a white pill with a shadow. Exactly two
        // tabs (Q3). Single-line pills.
        Surface(
            shape = CircleShape,
            color = if (NudgyIsDarkTheme) {
                NudgyChipBackground
            } else {
                colorResource(R.color.nudgy_track)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(4.dp)
            ) {
                TodayTabButton(
                    label = stringResource(R.string.today_tab_today),
                    selected = uiState.tab == TodayTab.TODAY,
                    onClick = { todayViewModel.setTab(TodayTab.TODAY) },
                    modifier = Modifier.weight(1f).testTag("tab_today")
                )
                TodayTabButton(
                    label = stringResource(R.string.today_tab_upcoming_7days),
                    selected = uiState.tab == TodayTab.UPCOMING,
                    onClick = { todayViewModel.setTab(TodayTab.UPCOMING) },
                    modifier = Modifier.weight(1f).testTag("tab_upcoming")
                )
            }
        }
        Spacer(modifier = Modifier.height(22.dp))
        if (uiState.tab == TodayTab.TODAY) {
            // My Day — Plan-picked tasks only (Q3), top section, as a
            // prototype seccard. Sprint 7 Q3(a): no List / Board /
            // Matrix switcher — Board lives on the Tasks screen,
            // Matrix gets its own screen later in the sprint. The
            // rows are flat (dividers, no per-row card).
            SectionCard(modifier = Modifier.testTag("my_day_section")) {
                SectionHead(
                    title = stringResource(R.string.today_my_day),
                    meta = "Picked in Plan this morning"
                )
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
                } else {
                    Column {
                        uiState.myDayItems.forEachIndexed { index, item ->
                            if (index > 0) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outline,
                                    thickness = 1.dp
                                )
                            }
                            TodayTaskRow(
                                item = item,
                                timeLabel = myDayTimeLabel(item, stringResource(R.string.today_time_label)),
                                onToggle = { todayViewModel.toggleTask(item.task) },
                                onDeleteSample = { tasksViewModel.deleteTask(it) },
                                flat = true
                            )
                        }
                    }
                }
            }
            // Plan nudge (Q7): a light card, never auto-open. Shown
            // only while nothing is planned for today yet. Prototype
            // copy, English-first — the draft clause is dropped: a
            // draft only exists once Plan has been opened.
            if (!uiState.isLoading && !uiState.hasPlannedToday) {
                Spacer(modifier = Modifier.height(20.dp))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("plan_nudge_card"),
                    shape = NudgyTokens.cardRadius,
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = NudgyTokens.cardElevation,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Explore,
                            contentDescription = null,
                            tint = colorResource(R.color.nudgy_teal),
                            modifier = Modifier
                                .size(22.dp)
                                .padding(top = 2.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Plan your day?",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = NudgyCharcoal
                            )
                            Text(
                                text = "Two minutes now saves the evening scramble.",
                                fontSize = 13.sp,
                                color = NudgyMuted,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                            Row(modifier = Modifier.padding(top = 10.dp)) {
                                SoftButton(
                                    text = "Open Plan",
                                    onClick = onPlanClick
                                )
                            }
                        }
                    }
                }
            }
            // Timeline — the day's full schedule, as a prototype
            // seccard with the now-line rail. Hidden entirely when
            // there is nothing timed and nothing untimed (a blank box is
            // not a timeline — S1-1 UI-FIX rule, kept).
            if (uiState.timelineEntries.isNotEmpty() || uiState.untimedTodayItems.isNotEmpty()) {
                Spacer(modifier = Modifier.height(22.dp))
                SectionCard(modifier = Modifier.testTag("timeline_section")) {
                    SectionHead("Timeline", "Tasks + calendar, read-only events")
                    Spacer(modifier = Modifier.height(12.dp))
                    if (uiState.timelineEntries.isNotEmpty()) {
                        val railLineColor = MaterialTheme.colorScheme.outline
                        Box(
                            modifier = Modifier.drawBehind {
                                // The rail (prototype .tl::before): the
                                // vertical line runs behind the pips.
                                // drawBehind, not fillMaxHeight — the
                                // screen scrolls, so the height
                                // constraint here is unbounded.
                                val x = 67.dp.toPx()
                                drawLine(
                                    color = railLineColor,
                                    start = Offset(x, 12.dp.toPx()),
                                    end = Offset(x, size.height - 12.dp.toPx()),
                                    strokeWidth = 2.dp.toPx()
                                )
                            }
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                uiState.timelineEntries.forEach { entry ->
                                    when (entry) {
                                        is TodayTimelineEntry.TaskBlock -> TimelineTaskBlock(
                                            item = entry.item,
                                            startMillis = entry.startMillis,
                                            metaLabel = "${timeLabel(entry.startMillis)} – " +
                                                "${timeLabel(entry.startMillis + entry.durationMinutes * 60 * 1000L)}" +
                                                " · ${formatMinutes(entry.durationMinutes)}" +
                                                if (entry.item.task.isCompleted) " · Done" else "",
                                            onToggle = { todayViewModel.toggleTask(entry.item.task) },
                                            onDeleteSample = { tasksViewModel.deleteTask(it) },
                                            softBackground = softBackground
                                        )
                                        is TodayTimelineEntry.TaskPoint -> TimelineTaskPoint(
                                            item = entry.item,
                                            startMillis = entry.startMillis,
                                            onToggle = { todayViewModel.toggleTask(entry.item.task) },
                                            onDeleteSample = { tasksViewModel.deleteTask(it) }
                                        )
                                        is TodayTimelineEntry.EventBlock -> TimelineEventRow(entry.event)
                                        is TodayTimelineEntry.NowLine -> NowLineRow(entry.atMillis)
                                    }
                                }
                            }
                        }
                    }
                    if (uiState.untimedTodayItems.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = stringResource(R.string.today_anytime),
                            style = MaterialTheme.typography.titleSmall,
                            color = NudgyMuted
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Column {
                            uiState.untimedTodayItems.forEachIndexed { index, item ->
                                if (index > 0) {
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outline,
                                        thickness = 1.dp
                                    )
                                }
                                TodayTaskRow(
                                    item = item,
                                    // "Today", not "Anytime": the group header
                                    // already says Anytime (S1-1 showed
                                    // "Today" here; repeating the header text
                                    // on every row is redundant).
                                    timeLabel = stringResource(R.string.today_time_label),
                                    onToggle = { todayViewModel.toggleTask(item.task) },
                                    onDeleteSample = { tasksViewModel.deleteTask(it) },
                                    flat = true
                                )
                            }
                        }
                    }
                }
            }
            // This week (prototype scr-today): four statboxes + a
            // button to the full review. The review's task lists live
            // on the WeeklyReview screen only — Today shows counts.
            // The rate matches the prototype's arithmetic (18 done /
            // 3 missed = 86%): finished over finished + missed.
            uiState.weeklyReview?.let { review ->
                Spacer(modifier = Modifier.height(22.dp))
                SectionCard(modifier = Modifier.testTag("this_week_section")) {
                    SectionHead("This week", "Weekly review · ${review.weekLabel}")
                    Spacer(modifier = Modifier.height(12.dp))
                    val total = review.finished.size + review.missed.size
                    val rate = if (total == 0) 0 else review.finished.size * 100 / total
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            StatBox(
                                value = "${review.finished.size}",
                                label = "Done this week",
                                testTag = "this_week_done",
                                modifier = Modifier.weight(1f)
                            )
                            StatBox(
                                value = "${review.missed.size}",
                                label = "Missed",
                                testTag = "this_week_missed",
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            StatBox(
                                value = "${review.slipped.size}",
                                label = "Carried over",
                                testTag = "this_week_carried",
                                modifier = Modifier.weight(1f)
                            )
                            StatBox(
                                value = "$rate%",
                                label = "Completion rate",
                                testTag = "this_week_rate",
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    SoftButton(
                        text = "See full statistics",
                        onClick = onWeeklyReviewClick,
                        modifier = Modifier.testTag("see_full_statistics")
                    )
                }
            }
            // Sprint 7-2b — "More in Nudgy" (prototype scr-today):
            // three tiles in the prototype's 2-column statgrid below
            // the This-week card. Focus + Matrix navigate to their
            // screens; Habits scrolls this page to the Habits
            // section (its offset is measured at the section below).
            // The Widget tile and the Share-preview button are NOT
            // here — decided by Tanvir 2026-11-27 (no destination
            // exists for either; see the file KDoc).
            Spacer(modifier = Modifier.height(22.dp))
            SectionCard(modifier = Modifier.testTag("more_in_nudgy_section")) {
                Text(
                    text = "More in Nudgy",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = NudgyCharcoal
                )
                Spacer(modifier = Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MoreTile(
                            icon = Icons.Filled.Schedule,
                            title = "Focus timer",
                            subtitle = "25-minute Pomodoro",
                            testTag = "more_tile_focus",
                            onClick = onFocusClick,
                            modifier = Modifier.weight(1f)
                        )
                        MoreTile(
                            icon = Icons.Filled.Whatshot,
                            title = "Habits",
                            subtitle = "Streaks that stick",
                            testTag = "more_tile_habits",
                            onClick = {
                                habitsOffsetY?.let { y ->
                                    scope.launch { scrollState.animateScrollTo(y) }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MoreTile(
                            icon = Icons.Filled.GridView,
                            title = "Priority matrix",
                            subtitle = "Urgent vs important",
                            testTag = "more_tile_matrix",
                            onClick = onMatrixClick,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.weight(1f))
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
        // Today, below the tab content, on either tab. Sprint 7-2b —
        // the Box measures the section's Y offset inside this Column
        // so the Habits tile can scroll to it. Compose 1.7 (BOM
        // 2024.09.00) has NEITHER positionInParent() NOR
        // positionInRoot() (runs 1034 + 1035 failed compile on each),
        // so the offset is derived from the WINDOW positions
        // instead: the Box's window Y minus its parent Column's
        // window Y, plus the current scroll — the window position
        // moves up as the page scrolls, so the raw difference alone
        // would shrink while scrolling. The guard keeps a remeasure
        // from re-triggering composition in a loop.
        habitViewModel?.let { habitVm ->
            Spacer(modifier = Modifier.height(24.dp))
            Box(
                modifier = Modifier.onGloballyPositioned { coordinates ->
                    val parentY = coordinates.parentLayoutCoordinates
                        ?.positionInWindow()?.y ?: 0f
                    val y = (coordinates.positionInWindow().y - parentY +
                        scrollState.value).roundToInt()
                    if (habitsOffsetY != y) habitsOffsetY = y
                }
            ) {
                HabitsSection(viewModel = habitVm)
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

/**
 * Sprint 7-2a — the prototype's donut (scr-today): a 104px ring, teal
 * arc over the #E7ECEA track, the percent + "of My Day" in the centre.
 * The whole ring is the button to the weekly review.
 */
@Composable
private fun DonutRing(percent: Int, onClick: () -> Unit) {
    val trackColor = if (NudgyIsDarkTheme) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        colorResource(R.color.nudgy_track)
    }
    val arcColor = colorResource(R.color.nudgy_teal)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(104.dp)
            .clip(CircleShape)
            .clickable { onClick() }
            .testTag("progress_ring")
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 11.dp.toPx()
            // Prototype geometry: r=44 in a 104 viewBox — the arc
            // sits 2dp inside the edge, stroke centred on the path.
            val inset = stroke / 2f + 2.dp.toPx()
            drawCircle(
                color = trackColor,
                radius = size.minDimension / 2f - inset,
                style = Stroke(width = stroke)
            )
            drawArc(
                color = arcColor,
                startAngle = -90f,
                sweepAngle = 360f * percent / 100f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - inset * 2f, size.height - inset * 2f),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "$percent%",
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                color = NudgyCharcoal
            )
            Text(
                text = "of My Day",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = NudgyMuted
            )
        }
    }
}

/** A prototype .seccard: white card, 1px line border, radius 22, padding 16. */
@Composable
private fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = NudgyTokens.cardRadius,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = NudgyTokens.cardElevation,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

/** A prototype .sec-head: 16px bold title + 12px muted meta. */
@Composable
private fun SectionHead(title: String, meta: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = NudgyCharcoal,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = meta,
            fontSize = 12.sp,
            color = NudgyMuted
        )
    }
}

/** One prototype .statbox inside a seccard: no shadow, 1px line border. */
@Composable
private fun StatBox(
    value: String,
    label: String,
    testTag: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.testTag(testTag),
        shape = NudgyTokens.cardRadius,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(13.dp)) {
            // The value carries its own tag: the Surface does not
            // merge its children's semantics, so the value text is
            // only readable on the Text node itself.
            Text(
                text = value,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                color = NudgyCharcoal,
                modifier = Modifier.testTag("${testTag}_value")
            )
            Text(
                text = label,
                fontSize = 13.sp,
                color = NudgyMuted
            )
        }
    }
}

/**
 * Sprint 7-2b — one prototype .statbox tile in the "More in Nudgy"
 * card: teal icon + bold title + muted subtitle, same Surface shape
 * as StatBox (22dp radius, 1px line border, 13dp padding). The
 * testTag lives ON the clickable Surface — a tag inside a clickable
 * is invisible in the merged semantics tree.
 */
@Composable
private fun MoreTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clickable { onClick() }
            .testTag(testTag),
        shape = NudgyTokens.cardRadius,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(13.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colorResource(R.color.nudgy_teal),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = title,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = NudgyCharcoal
            )
            Text(
                text = subtitle,
                fontSize = 13.sp,
                color = NudgyMuted
            )
        }
    }
}

/** A prototype .btn-soft: teal-soft fill, teal-dark text (dark: M3 tertiary). */
@Composable
private fun SoftButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val background = if (NudgyIsDarkTheme) {
        MaterialTheme.colorScheme.tertiary
    } else {
        colorResource(R.color.nudgy_teal_soft)
    }
    val content = if (NudgyIsDarkTheme) {
        MaterialTheme.colorScheme.onTertiary
    } else {
        colorResource(R.color.nudgy_teal_dark)
    }
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = background,
            contentColor = content
        ),
        shape = CircleShape,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp),
        modifier = modifier.defaultMinSize(minHeight = 38.dp)
    ) {
        Text(text = text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/** A prototype .btn-ghost: white pill, 1px line border, ink text + icon. */
@Composable
private fun GhostButton(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = NudgyCharcoal
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = CircleShape,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
        modifier = modifier
            .defaultMinSize(minHeight = 44.dp)
            .testTag(testTag)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * Sprint 7-2a — one timed row on the timeline rail (prototype .tl-row):
 * the start time right-aligned in a 44dp column, the pip on the rail,
 * then the content. The pip sits 5dp down for blocks, 7dp for points
 * (prototype .pip / .point .pip).
 */
@Composable
private fun TimelineRow(
    whenLabel: String,
    point: Boolean,
    content: @Composable () -> Unit
) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = whenLabel,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = NudgyMuted,
            textAlign = TextAlign.End,
            modifier = Modifier.width(44.dp)
        )
        Spacer(modifier = Modifier.width(17.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .width(14.dp)
                .padding(top = if (point) 7.dp else 5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(if (point) 10.dp else 14.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(
                        BorderStroke(
                            3.dp,
                            if (point) NudgyMuted else colorResource(R.color.nudgy_teal)
                        ),
                        CircleShape
                    )
            )
        }
        Spacer(modifier = Modifier.width(11.dp))
        Box(modifier = Modifier.weight(1f)) {
            content()
        }
    }
}

/** A timed task with an estimate: the teal-soft block (prototype .tl-block). */
@Composable
private fun TimelineTaskBlock(
    item: TaskListItem,
    startMillis: Long,
    metaLabel: String,
    onToggle: () -> Unit,
    onDeleteSample: ((TaskEntity) -> Unit)?,
    softBackground: Color
) {
    TimelineRow(whenLabel = timeLabel(startMillis), point = false) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = softBackground
        ) {
            TaskRowMain(
                item = item,
                timeLabel = metaLabel,
                onToggle = onToggle,
                onDeleteSample = onDeleteSample,
                titleFontSize = 14.5.sp,
                titleWeight = FontWeight.Medium,
                metaFontSize = 12.5.sp,
                showDot = false,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
    }
}

/** A timed task with no estimate: a point, not a block (prototype .point). */
@Composable
private fun TimelineTaskPoint(
    item: TaskListItem,
    startMillis: Long,
    onToggle: () -> Unit,
    onDeleteSample: ((TaskEntity) -> Unit)?
) {
    TimelineRow(whenLabel = timeLabel(startMillis), point = true) {
        TaskRowMain(
            item = item,
            timeLabel = "No estimate — shown as a point, not a block",
            onToggle = onToggle,
            onDeleteSample = onDeleteSample,
            titleFontSize = 14.5.sp,
            titleWeight = FontWeight.Medium,
            metaFontSize = 12.5.sp,
            showDot = false
        )
    }
}

/** A 1dp dashed rounded border (prototype .tl-block.evt). */
private fun Modifier.dashedBorder(color: Color, cornerRadius: Dp, strokeWidth: Dp = 1.dp): Modifier =
    drawBehind {
        val stroke = strokeWidth.toPx()
        val radius = CornerRadius(cornerRadius.toPx())
        val inset = stroke / 2f
        drawRoundRect(
            color = color,
            topLeft = Offset(inset, inset),
            size = Size(size.width - stroke, size.height - stroke),
            cornerRadius = radius,
            style = Stroke(
                width = stroke,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f)
            )
        )
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

/** One half of the Today | Upcoming segmented control (prototype .seg):
 * the selected tab is a white pill with a shadow on the track. */
@Composable
private fun TodayTabButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.clickable { onClick() },
        shape = CircleShape,
        color = if (selected) {
            MaterialTheme.colorScheme.surface
        } else {
            Color.Transparent
        },
        shadowElevation = if (selected) NudgyTokens.cardElevation else 0.dp
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp)
        ) {
            Text(
                text = label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (selected) {
                    NudgyCharcoal
                } else {
                    NudgyMuted
                }
            )
        }
    }
}

/**
 * One row in My Day / Timeline / Upcoming: checkbox + priority dot +
 * title + time label + Group badge + subtask progress. Same visual
 * language as the Tasks list's TaskRow, without the detail sheet
 * (S1-2 owns task details on Today — the sheet lands with S1-4).
 *
 * Sprint 7-2a — `flat` renders the row without its card (My Day /
 * Anytime inside a seccard: dividers separate the rows instead).
 * The timeline blocks / points share the row content through
 * [TaskRowMain] with the prototype's 14.5sp title.
 */
@Composable
private fun TodayTaskRow(
    item: TaskListItem,
    timeLabel: String,
    onToggle: () -> Unit,
    // Sprint 6-3 — the sample row's one-tap delete (spec §6): null
    // in older call sites (no delete button renders).
    onDeleteSample: ((TaskEntity) -> Unit)? = null,
    flat: Boolean = false
) {
    if (flat) {
        TaskRowMain(
            item = item,
            timeLabel = timeLabel,
            onToggle = onToggle,
            onDeleteSample = onDeleteSample,
            titleFontSize = 15.sp,
            titleWeight = FontWeight.SemiBold,
            metaFontSize = 12.sp,
            showDot = true,
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 2.dp)
        )
    } else {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = NudgyTokens.cardRadius,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = NudgyTokens.cardElevation
        ) {
            TaskRowMain(
                item = item,
                timeLabel = timeLabel,
                onToggle = onToggle,
                onDeleteSample = onDeleteSample,
                titleFontSize = 15.sp,
                titleWeight = FontWeight.SemiBold,
                metaFontSize = 12.sp,
                showDot = true,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

/**
 * The row content shared by the card row, the flat row and the
 * timeline block / point: checkbox + optional priority dot + title
 * + time label + Group badge + subtask progress + sample chip /
 * delete. Only the type sizes and the container change.
 */
@Composable
private fun TaskRowMain(
    item: TaskListItem,
    timeLabel: String,
    onToggle: () -> Unit,
    onDeleteSample: ((TaskEntity) -> Unit)?,
    titleFontSize: TextUnit,
    titleWeight: FontWeight,
    metaFontSize: TextUnit,
    showDot: Boolean,
    modifier: Modifier = Modifier
) {
    val task = item.task
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Checkbox(
            checked = task.isCompleted,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = colorResource(R.color.nudgy_teal)
            )
        )
        if (showDot) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(todayPriorityDotColor(task.priority))
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = titleFontSize,
                    fontWeight = titleWeight,
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
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = metaFontSize
                    ),
                    color = NudgyMuted
                )
                if (item.subtaskTotal > 0) {
                    Text(
                        text = "· ${item.subtaskDone}/${item.subtaskTotal}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = metaFontSize
                        ),
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

/** A calendar event on the timeline: read-only, no checkbox. */
@Composable
private fun TimelineEventRow(event: CalendarEventEntity) {
    TimelineRow(whenLabel = timeLabel(event.startEpochMillis), point = false) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(14.dp))
                .dashedBorder(
                    color = Color(0xFFC9D4D0),
                    cornerRadius = 14.dp
                )
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                text = event.title,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Medium,
                color = colorResource(R.color.nudgy_ink)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Event · ${timeLabel(event.startEpochMillis)} – " +
                    timeLabel(event.endEpochMillis),
                fontSize = 12.5.sp,
                color = colorResource(R.color.nudgy_muted)
            )
            if (!event.locationOrLink.isNullOrBlank()) {
                Text(
                    text = event.locationOrLink,
                    fontSize = 12.5.sp,
                    color = colorResource(R.color.nudgy_muted)
                )
            }
        }
    }
}

/** The now-line: teal, never coral. */
@Composable
private fun NowLineRow(atMillis: Long) {
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier.fillMaxWidth()
    ) {
        // The teal line starts at the rail's left edge (prototype
        // .tl-now::before, left -37px from the row) and runs to the
        // right edge; the pill sits on it, over the vertical rail.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 15.dp)
                .height(2.dp)
                .background(
                    color = colorResource(R.color.nudgy_teal),
                    shape = CircleShape
                )
        )
        Row {
            Spacer(modifier = Modifier.width(52.dp))
            Surface(
                color = colorResource(R.color.nudgy_teal),
                shape = CircleShape
            ) {
                Text(
                    text = "NOW · ${timeLabel(atMillis)}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
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

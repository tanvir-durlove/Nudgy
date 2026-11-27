package com.nextgentool.nudgy.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.data.local.entity.CalendarEventEntity
import com.nextgentool.nudgy.data.local.entity.GroupEntity
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.ui.theme.NudgyCanvas
import com.nextgentool.nudgy.ui.theme.NudgyCharcoal
import com.nextgentool.nudgy.ui.theme.NudgyMuted
import com.nextgentool.nudgy.ui.theme.NudgyTokens
import com.nextgentool.nudgy.ui.viewmodel.SearchViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Sprint 6-4, step 2a — global search screen: UI shell + navigation
 * only (approved Sprint 6 spec, Q5 + §7 — a separate search screen,
 * grouped results, tap navigates; empty query = genuine empty state).
 *
 * Step 2b — the Tasks section is wired to real data: the query lives
 * in the SearchViewModel and the Tasks group shows the Room LIKE
 * results (title / description / tag name). Tap on a task row calls
 * [onTaskClick] — the NavHost opens that task's detail sheet (step
 * 2e): the id is set on the NavHost's searchOpenTaskId state and the
 * Tasks destination selects it on entry (TasksViewModel.selectTask).
 * There is no task-detail route: the detail sheet lives in TasksScreen
 * behind TasksViewModel.selectTask, so the deep-link uses the same
 * state pattern as the group deep-link, not a route argument.
 *
 * Step 2c — the other three sections are wired too: Schedules shows
 * the calendar_events LIKE results (title / location), Notes shows
 * the description hits of the same task search as their own group,
 * and Groups shows the groups whose name matches. Tap on a schedule
 * row calls [onScheduleClick] — the NavHost opens that event's edit
 * dialog (step 2f): the id + the event's day are set on the NavHost's
 * searchOpenEventId / searchOpenEventDayMillis state and the Calendar
 * destination jumps to that day and selects the event once the day's
 * list emits (CalendarScreen's day-scoped list needs the day, not
 * just the id); tap on a note row calls [onTaskClick] (the note IS its
 * task, so it opens that task's sheet too); tap on a group row calls
 * [onGroupClick] (the NavHost opens that group's detail through the
 * S5-3 openGroupId mechanism).
 *
 * The four groups follow the prototype's Search screen (Tasks /
 * Schedules / Notes / Groups). Notes here means task notes and
 * descriptions (spec Q5: "নোট মানে টাস্কের নোট/বিবরণ" — NOT Memories
 * text), and schedules means calendar events.
 *
 * No fake results, no placeholder rows (the Q11 honest-empty-state
 * rule; the v1 hardcoded fake example must never come back).
 *
 * A plain Column with verticalScroll (not a LazyColumn): the screen
 * has only four short sections, so everything composes and the
 * section headers are assertable without scrolling (the S15SmokeTest
 * flake was a LazyColumn item that never composed below the fold).
 * Steps 2b/2c may switch the results area to a LazyColumn when real
 * result lists land — the section testTags stay.
 *
 * Sprint 7 — Search restyle (prototype scr-search): the TopAppBar is
 * gone, replaced by the sprint backbar (back + "Search" 21sp/800,
 * testTag search_backbar; back behavior unchanged); the field is the
 * prototype's .field (white, 16dp radius, same placeholder verbatim,
 * same debounce/clear/focus behavior); section headers are the
 * prototype's .searchgroup (13sp/700, uppercase, tracked, muted);
 * result rows are the prototype's .result-row (white card, 16dp
 * radius, 13/14dp padding, 8dp gaps) with a pdot + title + meta:
 * tasks get the priority pdot (HIGH coral / MEDIUM teal / LOW muted,
 * the Eisenhower/Board mapping) + a due meta, schedules get a
 * calendar icon + a day/time/source meta, notes get a note icon +
 * the description as the meta, groups get a 36dp first-letter avatar
 * (teal-soft, cream by name hash — the GroupsScreen pattern) + a
 * "Group" meta.
 *
 * Meta honesty (no invented data): the task meta is the due label
 * ("Today · 10:30 AM" shape) when the task has a due date, else the
 * description when there is one, else no meta line — the prototype's
 * "Project: Home" segment is NOT shown because the project NAME is
 * not on TaskEntity (only projectId) and resolving it needs a data
 * task. The schedule meta is day · time · location (or "Google
 * Calendar" when the event came from Google and has no location);
 * all-day events show "All day" instead of a time. The group meta
 * is just "Group": member counts and open-task counts are NOT on
 * GroupEntity, so the prototype's "4 members · 3 open tasks" cannot
 * be shown honestly. Day words ("Today" / "Tomorrow" / "All day")
 * are English, following TasksScreen.formatDueLabel's established
 * precedent.
 *
 * Empty states: a blank query still shows the per-section "Type to
 * search" (established model). When a non-blank query matches
 * NOTHING anywhere, the four per-section "No results" are replaced
 * by the prototype's single centered sentence ("Nothing matches.
 * Search covers tasks, schedules, notes and groups — try a shorter
 * word.", testTag search_empty_all) and the sections hide — the
 * prototype's #searchEmpty. When only SOME groups match, the
 * non-matching groups still show their per-section "No results".
 */
@Composable
fun SearchScreen(
    viewModel: SearchViewModel = hiltViewModel(),
    onBackClick: () -> Unit,
    onTaskClick: (TaskEntity) -> Unit = {},
    onScheduleClick: (CalendarEventEntity) -> Unit = {},
    onGroupClick: (GroupEntity) -> Unit = {}
) {
    val query by viewModel.query.collectAsState()
    val taskResults by viewModel.taskResults.collectAsState()
    val scheduleResults by viewModel.scheduleResults.collectAsState()
    val noteResults by viewModel.noteResults.collectAsState()
    val groupResults by viewModel.groupResults.collectAsState()
    // Sprint 7 — the prototype's single empty sentence, shown only
    // when a non-blank query matches nothing anywhere.
    val nothingMatches = query.isNotBlank() &&
        taskResults.isEmpty() && scheduleResults.isEmpty() &&
        noteResults.isEmpty() && groupResults.isEmpty()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NudgyCanvas)
            .verticalScroll(rememberScrollState())
            .padding(NudgyTokens.screenPadding)
            .testTag("search_screen")
    ) {
        // Backbar (prototype): the same back Row Matrix/Stats/Chat use
        // (back + titleLarge, no TopAppBar).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .testTag("search_backbar")
        ) {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = NudgyCharcoal
                )
            }
            Text(
                text = stringResource(R.string.search_title),
                fontSize = 21.sp,
                fontWeight = FontWeight.ExtraBold,
                color = NudgyCharcoal
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { viewModel.setQuery(it) },
            placeholder = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = {
                Icon(Icons.Filled.Search, contentDescription = null)
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("search_field")
        )
        Spacer(modifier = Modifier.height(16.dp))
        if (nothingMatches) {
            Text(
                text = stringResource(R.string.search_nothing_matches),
                style = MaterialTheme.typography.bodyMedium,
                color = NudgyMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 24.dp)
                    .testTag("search_empty_all")
            )
        } else {
            // Empty query: "type to search". A non-blank query with no
            // matches in this group: "no results" (every group is live
            // since step 2c; a group with no matches still shows it).
            val emptyText = if (query.isBlank()) {
                stringResource(R.string.search_type_to_search)
            } else {
                stringResource(R.string.search_no_results)
            }
            // Step 2b — the Tasks section is live (Room LIKE results).
            SearchTaskSection(
                tasks = taskResults,
                query = query,
                emptyText = emptyText,
                onTaskClick = onTaskClick
            )
            Spacer(modifier = Modifier.height(20.dp))
            // Step 2c — the Schedules section is live (calendar_events
            // LIKE results).
            SearchScheduleSection(
                events = scheduleResults,
                query = query,
                emptyText = emptyText,
                onScheduleClick = onScheduleClick
            )
            Spacer(modifier = Modifier.height(20.dp))
            // Step 2c — the Notes section is live (description hits of
            // the task search, as their own group).
            SearchNoteSection(
                tasks = noteResults,
                query = query,
                emptyText = emptyText,
                onTaskClick = onTaskClick
            )
            Spacer(modifier = Modifier.height(20.dp))
            // Step 2c — the Groups section is live (name filter over
            // the observed groups).
            SearchGroupSection(
                groups = groupResults,
                query = query,
                emptyText = emptyText,
                onGroupClick = onGroupClick
            )
        }
    }
}

/** The prototype's .searchgroup header: 13sp/700, uppercase, tracked, muted. */
@Composable
private fun SearchSectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
        color = NudgyMuted,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

/**
 * The prototype's .result-row card (white, 16dp radius, 13/14dp
 * padding, 8dp gaps). The testTag stays ON the clickable Row inside
 * — a testTag inside a clickable is invisible in the merged
 * semantics tree, so it must live on the clickable itself, never on
 * a child (the S6-4 rule this file already records).
 */
@Composable
private fun SearchResultCard(
    testTag: String,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = NudgyTokens.cardElevation,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .testTag(testTag)
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            content()
        }
    }
}

/** Title + optional meta for a result row (prototype: 500 title, 12sp tiny meta). */
@Composable
private fun RowScope.SearchRowTexts(title: String, meta: String?) {
    Column(modifier = Modifier.weight(1f)) {
        Text(
            text = title,
            fontSize = 15.5.sp,
            fontWeight = FontWeight.Medium,
            color = NudgyCharcoal
        )
        meta?.let {
            Text(
                text = it,
                fontSize = 12.sp,
                color = NudgyMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** The priority pdot (prototype .pdot): 9dp, HIGH coral / MEDIUM teal / LOW muted. */
@Composable
private fun searchPriorityDotColor(priority: Priority): Color = when (priority) {
    Priority.HIGH -> colorResource(R.color.nudgy_coral)
    Priority.MEDIUM -> colorResource(R.color.nudgy_teal)
    Priority.LOW -> colorResource(R.color.nudgy_muted)
}

/** "Today" / "Tomorrow" / "EEE, MMM d" for a timestamp (TasksScreen.formatDueLabel shape). */
private fun searchDayLabel(millis: Long): String {
    val now = Calendar.getInstance()
    val day = Calendar.getInstance().apply { timeInMillis = millis }
    val sameDay = now.get(Calendar.YEAR) == day.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == day.get(Calendar.DAY_OF_YEAR)
    val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
    val isTomorrow = tomorrow.get(Calendar.YEAR) == day.get(Calendar.YEAR) &&
        tomorrow.get(Calendar.DAY_OF_YEAR) == day.get(Calendar.DAY_OF_YEAR)
    return when {
        sameDay -> "Today"
        isTomorrow -> "Tomorrow"
        else -> SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(millis)
    }
}

/**
 * Task meta (prototype: "Today · 10:30 AM · Project: Home"): the due
 * label when the task has a due date, else the description when
 * there is one, else no meta line. No "Project:" segment — the
 * project name is not on TaskEntity (see the file KDoc).
 */
private fun searchTaskMeta(task: TaskEntity): String? {
    if (task.dueDateEpochMillis != TaskRepository.NO_DUE_DATE) {
        val time = SimpleDateFormat("h:mm a", Locale.getDefault())
            .format(task.dueDateEpochMillis)
        return "${searchDayLabel(task.dueDateEpochMillis)} · $time"
    }
    return task.description?.takeIf { it.isNotBlank() }
}

/**
 * Schedule meta (prototype: "Today · 9:00 AM · Google Calendar"):
 * day · time · location (or "Google Calendar" when the event came
 * from Google and has no location). All-day events show "All day"
 * instead of a time.
 */
private fun searchScheduleMeta(event: CalendarEventEntity): String {
    val day = searchDayLabel(event.startEpochMillis)
    val time = if (event.isAllDay) {
        "All day"
    } else {
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(event.startEpochMillis)
    }
    val source = event.locationOrLink?.takeIf { it.isNotBlank() }
        ?: if (event.isFromGoogleCalendar) "Google Calendar" else null
    return listOfNotNull("$day · $time", source).joinToString(" · ")
}

/**
 * Sprint 6-4, step 2b — the Tasks group, wired to real data: the
 * Room LIKE results (title / description / tag name). Blank query or
 * no matches show the honest empty state, with the same testTags the
 * shell used (search_section_tasks, search_empty_tasks). A result row
 * is one clickable Row carrying its own testTag
 * (search_result_task_<id>) ON the clickable — a testTag inside a
 * clickable row is invisible in the merged semantics tree, so the tag
 * must live on the clickable itself, never on a child.
 */
@Composable
private fun SearchTaskSection(
    tasks: List<TaskEntity>,
    query: String,
    emptyText: String,
    onTaskClick: (TaskEntity) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("search_section_tasks")
    ) {
        SearchSectionHeader(text = stringResource(R.string.search_section_tasks))
        if (query.isBlank() || tasks.isEmpty()) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodyMedium,
                color = NudgyMuted,
                modifier = Modifier.testTag("search_empty_tasks")
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tasks.forEach { task ->
                    SearchResultCard(
                        testTag = "search_result_task_${task.id}",
                        onClick = { onTaskClick(task) }
                    ) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .background(
                                    color = searchPriorityDotColor(task.priority),
                                    shape = CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        SearchRowTexts(
                            title = task.title,
                            meta = searchTaskMeta(task)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Sprint 6-4, step 2c — the Schedules group, wired to real data: the
 * calendar_events LIKE results (title / location). Same shape as the
 * Tasks group: blank query or no matches show the honest empty
 * state, with the same testTags the shell used
 * (search_section_schedules, search_empty_schedules). A result row
 * is one clickable Row carrying its own testTag
 * (search_result_schedule_<id>) ON the clickable — a testTag inside
 * a clickable row is invisible in the merged semantics tree, so the
 * tag must live on the clickable itself, never on a child.
 */
@Composable
private fun SearchScheduleSection(
    events: List<CalendarEventEntity>,
    query: String,
    emptyText: String,
    onScheduleClick: (CalendarEventEntity) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("search_section_schedules")
    ) {
        SearchSectionHeader(text = stringResource(R.string.search_section_schedules))
        if (query.isBlank() || events.isEmpty()) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodyMedium,
                color = NudgyMuted,
                modifier = Modifier.testTag("search_empty_schedules")
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                events.forEach { event ->
                    SearchResultCard(
                        testTag = "search_result_schedule_${event.id}",
                        onClick = { onScheduleClick(event) }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CalendarMonth,
                            contentDescription = null,
                            tint = NudgyMuted,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        SearchRowTexts(
                            title = event.title,
                            meta = searchScheduleMeta(event)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Sprint 6-4, step 2c — the Notes group: tasks whose DESCRIPTION
 * matches the query, as their own group (spec Q5: notes = task notes
 * / descriptions, NOT Memories text). Same shape as the Tasks group,
 * with its own testTags (search_section_notes, search_empty_notes,
 * search_result_note_<id> on the clickable Row). Tap goes to the
 * task itself — the note IS its task.
 */
@Composable
private fun SearchNoteSection(
    tasks: List<TaskEntity>,
    query: String,
    emptyText: String,
    onTaskClick: (TaskEntity) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("search_section_notes")
    ) {
        SearchSectionHeader(text = stringResource(R.string.search_section_notes))
        if (query.isBlank() || tasks.isEmpty()) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodyMedium,
                color = NudgyMuted,
                modifier = Modifier.testTag("search_empty_notes")
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tasks.forEach { task ->
                    SearchResultCard(
                        testTag = "search_result_note_${task.id}",
                        onClick = { onTaskClick(task) }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Description,
                            contentDescription = null,
                            tint = NudgyMuted,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        SearchRowTexts(
                            title = task.title,
                            meta = task.description?.takeIf { it.isNotBlank() }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Sprint 6-4, step 2c — the Groups group: groups whose name matches
 * the query (name filter over the observed groups). Same shape as
 * the Tasks group, with its own testTags (search_section_groups,
 * search_empty_groups, search_result_group_<groupId> on the
 * clickable Row). Tap opens that group's detail (the NavHost sets
 * the S5-3 openGroupId from the search tap).
 */
@Composable
private fun SearchGroupSection(
    groups: List<GroupEntity>,
    query: String,
    emptyText: String,
    onGroupClick: (GroupEntity) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("search_section_groups")
    ) {
        SearchSectionHeader(text = stringResource(R.string.search_section_groups))
        if (query.isBlank() || groups.isEmpty()) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodyMedium,
                color = NudgyMuted,
                modifier = Modifier.testTag("search_empty_groups")
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                groups.forEach { group ->
                    SearchResultCard(
                        testTag = "search_result_group_${group.groupId}",
                        onClick = { onGroupClick(group) }
                    ) {
                        // Sprint 7 — prototype avatar: a 36dp circle
                        // with the group's first letter; teal-soft by
                        // default, cream for the hashed variant (the
                        // GroupsScreen pattern).
                        val isCreamAvatar =
                            (group.name.hashCode() and Int.MAX_VALUE) % 2 == 1
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(
                                    color = colorResource(
                                        if (isCreamAvatar) R.color.nudgy_cream
                                        else R.color.nudgy_teal_soft
                                    ),
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = group.name.firstOrNull()
                                    ?.uppercase() ?: "G",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = colorResource(R.color.nudgy_teal_dark)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        SearchRowTexts(
                            title = group.name,
                            meta = stringResource(R.string.search_group_meta)
                        )
                    }
                }
            }
        }
    }
}

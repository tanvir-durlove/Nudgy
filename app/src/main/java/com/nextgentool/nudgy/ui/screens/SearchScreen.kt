package com.nextgentool.nudgy.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.data.local.entity.CalendarEventEntity
import com.nextgentool.nudgy.data.local.entity.GroupEntity
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.ui.theme.NudgyMuted
import com.nextgentool.nudgy.ui.viewmodel.SearchViewModel

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
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    Scaffold(
        modifier = Modifier.testTag("search_screen"),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.search_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { viewModel.setQuery(it) },
                placeholder = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null)
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("search_field")
            )
            Spacer(modifier = Modifier.height(16.dp))
            // Empty query: "type to search". A non-blank query with no
            // matches: "no results" (every group is live since step
            // 2c; a group with no matches still shows it).
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
        Text(
            text = stringResource(R.string.search_section_tasks).uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            ),
            color = NudgyMuted
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (query.isBlank() || tasks.isEmpty()) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodyMedium,
                color = NudgyMuted,
                modifier = Modifier.testTag("search_empty_tasks")
            )
        } else {
            tasks.forEach { task ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onTaskClick(task) }
                        .testTag("search_result_task_${task.id}")
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = task.title,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        task.description?.takeIf { it.isNotBlank() }?.let { description ->
                            Text(
                                text = description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = NudgyMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
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
        Text(
            text = stringResource(R.string.search_section_schedules).uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            ),
            color = NudgyMuted
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (query.isBlank() || events.isEmpty()) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodyMedium,
                color = NudgyMuted,
                modifier = Modifier.testTag("search_empty_schedules")
            )
        } else {
            events.forEach { event ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onScheduleClick(event) }
                        .testTag("search_result_schedule_${event.id}")
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = event.title,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        event.locationOrLink?.takeIf { it.isNotBlank() }?.let { location ->
                            Text(
                                text = location,
                                style = MaterialTheme.typography.bodyMedium,
                                color = NudgyMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
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
        Text(
            text = stringResource(R.string.search_section_notes).uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            ),
            color = NudgyMuted
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (query.isBlank() || tasks.isEmpty()) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodyMedium,
                color = NudgyMuted,
                modifier = Modifier.testTag("search_empty_notes")
            )
        } else {
            tasks.forEach { task ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onTaskClick(task) }
                        .testTag("search_result_note_${task.id}")
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = task.title,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        task.description?.takeIf { it.isNotBlank() }?.let { description ->
                            Text(
                                text = description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = NudgyMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
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
        Text(
            text = stringResource(R.string.search_section_groups).uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            ),
            color = NudgyMuted
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (query.isBlank() || groups.isEmpty()) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodyMedium,
                color = NudgyMuted,
                modifier = Modifier.testTag("search_empty_groups")
            )
        } else {
            groups.forEach { group ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onGroupClick(group) }
                        .testTag("search_result_group_${group.groupId}")
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = group.name,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }
}

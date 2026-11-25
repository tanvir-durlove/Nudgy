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
 * [onTaskClick] — the NavHost navigates to the Tasks screen, the
 * task's home. There is no task-detail route: the detail sheet lives
 * in TasksScreen behind TasksViewModel.selectTask, so a deep-link to
 * one task's sheet is a follow-up, not this step. The other three
 * sections (Schedules / Notes / Groups) still show honest empty
 * states until step 2c wires them.
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
    onTaskClick: (TaskEntity) -> Unit = {}
) {
    val query by viewModel.query.collectAsState()
    val taskResults by viewModel.taskResults.collectAsState()
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
            // matches: "no results" (the Tasks group is live since
            // step 2b; the other groups still show it until step 2c
            // wires them).
            val emptyText = if (query.isBlank()) {
                stringResource(R.string.search_type_to_search)
            } else {
                stringResource(R.string.search_no_results)
            }
            // Step 2b — the Tasks section is live (Room LIKE results);
            // the other three sections stay empty until step 2c.
            SearchTaskSection(
                tasks = taskResults,
                query = query,
                emptyText = emptyText,
                onTaskClick = onTaskClick
            )
            Spacer(modifier = Modifier.height(20.dp))
            SearchSection(
                title = stringResource(R.string.search_section_schedules),
                sectionTag = "search_section_schedules",
                emptyTag = "search_empty_schedules",
                emptyText = emptyText
            )
            Spacer(modifier = Modifier.height(20.dp))
            SearchSection(
                title = stringResource(R.string.search_section_notes),
                sectionTag = "search_section_notes",
                emptyTag = "search_empty_notes",
                emptyText = emptyText
            )
            Spacer(modifier = Modifier.height(20.dp))
            SearchSection(
                title = stringResource(R.string.search_section_groups),
                sectionTag = "search_section_groups",
                emptyTag = "search_empty_groups",
                emptyText = emptyText
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
 * One grouped result section: an uppercase, letter-spaced header in
 * the prototype's .searchgroup style, then the section's content —
 * for now only its honest empty state (step 2c puts real result rows
 * here). The header and the empty text carry their own testTags
 * (OUTSIDE any clickable row, so clickable-merging cannot hide them).
 */
@Composable
private fun SearchSection(
    title: String,
    sectionTag: String,
    emptyTag: String,
    emptyText: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(sectionTag)
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            ),
            color = NudgyMuted
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = emptyText,
            style = MaterialTheme.typography.bodyMedium,
            color = NudgyMuted,
            modifier = Modifier.testTag(emptyTag)
        )
    }
}

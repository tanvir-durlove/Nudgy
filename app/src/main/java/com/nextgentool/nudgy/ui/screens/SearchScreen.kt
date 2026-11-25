package com.nextgentool.nudgy.ui.screens

import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.ui.theme.NudgyMuted

/**
 * Sprint 6-4, step 2a — global search screen: UI shell + navigation
 * only (approved Sprint 6 spec, Q5 + §7 — a separate search screen,
 * grouped results, tap navigates; empty query = genuine empty state).
 *
 * The four groups follow the prototype's Search screen (Tasks /
 * Schedules / Notes / Groups). Notes here means task notes and
 * descriptions (spec Q5: "নোট মানে টাস্কের নোট/বিবরণ" — NOT Memories
 * text), and schedules means calendar events — the exact data sources
 * are pinned down in steps 2b/2c.
 *
 * The query lives in rememberSaveable in this screen for now; steps
 * 2b/2c lift it into a SearchViewModel when the Room LIKE queries
 * land. Until then every section shows an honest empty state — no
 * fake results, no placeholder rows (the Q11 honest-empty-state
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
    onBackClick: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
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
                onValueChange = { query = it },
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
            // wired logic yet: "no results" — steps 2b/2c replace it
            // with real grouped results (or keep it for a genuine
            // no-match).
            val emptyText = if (query.isBlank()) {
                stringResource(R.string.search_type_to_search)
            } else {
                stringResource(R.string.search_no_results)
            }
            SearchSection(
                title = stringResource(R.string.search_section_tasks),
                sectionTag = "search_section_tasks",
                emptyTag = "search_empty_tasks",
                emptyText = emptyText
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
 * One grouped result section: an uppercase, letter-spaced header in
 * the prototype's .searchgroup style, then the section's content —
 * for now only its honest empty state (steps 2b/2c put real result
 * rows here). The header and the empty text carry their own testTags
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

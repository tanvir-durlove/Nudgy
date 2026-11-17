package com.nextgentool.nudgy.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.ui.components.QuickAddTask
import com.nextgentool.nudgy.ui.theme.NudgyTokens
import com.nextgentool.nudgy.ui.viewmodel.TasksViewModel

/**
 * Sprint 1-0 — Today destination (skeleton only).
 *
 * The real Today content (due tasks, meetings, carry-over, timeline,
 * My Day section) is S1-2. This file only holds the screen's place in
 * the Today graph and proves the "Plan my day" → today/plan route:
 * the button navigates to the Plan skeleton, back returns to Today.
 * No ViewModel, no data, nothing is saved here.
 */
@Composable
fun TodayScreen(
    onPlanClick: () -> Unit,
    // Sprint 1-1: entry point to the Tasks list (its only entry until S1-2
    // rebuilds Today). Defaults to no-op so older call sites keep compiling.
    onAllTasksClick: () -> Unit = {},
    // Sprint 1-1 fix: the Today quick-add (Feature 1) shares TasksViewModel
    // with TasksScreen — a separate instance scoped to this destination,
    // same singleton repository, so the guards (empty title, double-tap)
    // are identical. S1-2 owns the full Today rewrite; this only adds the
    // quick-add field.
    viewModel: TasksViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(R.color.nudgy_background))
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
                        text = "0%",
                        style = MaterialTheme.typography.bodyMedium,
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
        // Sprint 1-1 fix — quick add on Today (Feature 1 requires the field
        // on the Today hero AND the Tasks screen; S1-1 only put it on Tasks,
        // so there was no way to create a task until the Tasks list opened).
        val lastCreated = uiState.items
            .firstOrNull { it.task.id == uiState.lastCreatedTaskId }
        QuickAddTask(
            onCreate = { viewModel.createTask(it) },
            isCreating = uiState.isCreating,
            lastCreatedTask = lastCreated?.task,
            onLastCreatedDue = { viewModel.setLastCreatedDue(it) },
            onLastCreatedPriority = { viewModel.setLastCreatedPriority(it) },
            onDismissLastCreated = { viewModel.clearLastCreated() }
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "My Day",
            style = MaterialTheme.typography.titleMedium,
            color = colorResource(R.color.nudgy_ink)
        )
        Spacer(modifier = Modifier.height(12.dp))
        NudgySkeletonRow()
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Timeline",
            style = MaterialTheme.typography.titleMedium,
            color = colorResource(R.color.nudgy_ink)
        )
        Spacer(modifier = Modifier.height(12.dp))
        NudgySkeletonRow()
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onAllTasksClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = colorResource(R.color.nudgy_teal),
                contentColor = colorResource(R.color.nudgy_cream)
            )
        ) {
            Text(text = "All tasks")
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Sprint 1-2 fills this in: due tasks, meetings, carry-over — one timeline.",
            style = MaterialTheme.typography.bodySmall,
            color = colorResource(R.color.nudgy_muted)
        )
    }
}

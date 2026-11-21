package com.nextgentool.nudgy.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.domain.dayLabel
import com.nextgentool.nudgy.domain.formatMinutes
import com.nextgentool.nudgy.domain.timeLabel
import com.nextgentool.nudgy.ui.theme.NudgyAccent
import com.nextgentool.nudgy.ui.theme.NudgyCanvas
import com.nextgentool.nudgy.ui.theme.NudgyCharcoal
import com.nextgentool.nudgy.ui.theme.NudgyMuted
import com.nextgentool.nudgy.ui.theme.NudgyTokens
import com.nextgentool.nudgy.ui.viewmodel.PlanRow
import com.nextgentool.nudgy.ui.viewmodel.PlanViewModel
import com.nextgentool.nudgy.ui.viewmodel.startOfTodayMillis

/**
 * Sprint 1-2 — Plan My Day (Feature 14, spec §6).
 *
 * Full-screen, never auto-opened (Q7): the Today hero's "Plan my day"
 * button and the nudge card open it; back returns to Today. The auto
 * draft (Q1) is carry-over first, then today-due, within an 8h
 * estimate budget; the user picks, unpicks and reorders, then "Start
 * my day" stamps plannedDate = today + plannedStartEpochMillis on the
 * picked tasks — they show in Today My Day. Doing Plan a second time
 * shows the previous picks.
 *
 * Reorder is via the up/down controls on each picked row (see
 * PlanViewModel's note on why there is no long-press drag here).
 */
@Composable
fun PlanScreen(
    onBackClick: () -> Unit = {},
    viewModel: PlanViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    LaunchedEffect(uiState.planApplied) {
        if (uiState.planApplied) onBackClick()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NudgyCanvas)
            .verticalScroll(scrollState)
            .padding(NudgyTokens.screenPadding)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = NudgyCharcoal
                )
            }
            Column {
                Text(
                    text = "Plan my day",
                    style = MaterialTheme.typography.titleLarge,
                    color = NudgyCharcoal
                )
                Text(
                    text = dayLabel(startOfTodayMillis()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NudgyMuted
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        if (uiState.isLoading) {
            NudgySkeletonRow()
        } else {
            Text(
                text = "${formatMinutes(uiState.totalEstimatedMinutes)} of 8h planned",
                style = MaterialTheme.typography.bodyMedium,
                color = NudgyMuted
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Today's plan",
                style = MaterialTheme.typography.titleMedium,
                color = NudgyCharcoal
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (uiState.pickedRows.isEmpty()) {
                Text(
                    text = "Nothing picked yet — pick tasks below",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NudgyMuted,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    uiState.pickedRows.forEachIndexed { index, row ->
                        PlanPickedRow(
                            row = row,
                            startMillis = uiState.startTimes[row.task.id],
                            canMoveUp = index > 0,
                            canMoveDown = index < uiState.pickedRows.size - 1,
                            onMoveUp = { viewModel.movePicked(row.task.id, -1) },
                            onMoveDown = { viewModel.movePicked(row.task.id, 1) },
                            onUnpick = { viewModel.togglePick(row.task.id) }
                        )
                    }
                }
            }
            if (uiState.unpickedRows.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Not planned",
                    style = MaterialTheme.typography.titleMedium,
                    color = NudgyCharcoal
                )
                Spacer(modifier = Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    uiState.unpickedRows.forEach { row ->
                        PlanUnpickedRow(
                            row = row,
                            onPick = { viewModel.togglePick(row.task.id) }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = { viewModel.startMyDay() },
                enabled = uiState.pickedRows.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colorResource(R.color.nudgy_teal),
                    contentColor = colorResource(R.color.nudgy_cream)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("plan_start_button")
            ) {
                Text(text = "Start my day")
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/** A picked row: checkbox + title + times + up/down reorder controls. */
@Composable
private fun PlanPickedRow(
    row: PlanRow,
    startMillis: Long?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onUnpick: () -> Unit
) {
    val task = row.task
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("plan_row_" + task.title),
        shape = NudgyTokens.cardRadius,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = NudgyTokens.cardElevation
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Checkbox(
                checked = true,
                onCheckedChange = { onUnpick() },
                colors = CheckboxDefaults.colors(
                    checkedColor = colorResource(R.color.nudgy_teal)
                )
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = NudgyCharcoal
                )
                Text(
                    text = planRowDetail(task, startMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = NudgyMuted
                )
                if (row.isCarryOver) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Carried over",
                        style = MaterialTheme.typography.bodySmall,
                        color = NudgyAccent
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowUp,
                        contentDescription = "Move ${task.title} up",
                        tint = if (canMoveUp) {
                            NudgyCharcoal
                        } else {
                            NudgyMuted
                        }
                    )
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = "Move ${task.title} down",
                        tint = if (canMoveDown) {
                            NudgyCharcoal
                        } else {
                            NudgyMuted
                        }
                    )
                }
            }
        }
    }
}

/** An unpicked candidate row: checkbox + title + times. */
@Composable
private fun PlanUnpickedRow(
    row: PlanRow,
    onPick: () -> Unit
) {
    val task = row.task
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPick() }
            .testTag("plan_row_" + task.title),
        shape = NudgyTokens.cardRadius,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = NudgyTokens.cardElevation
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Checkbox(
                checked = false,
                onCheckedChange = { onPick() },
                colors = CheckboxDefaults.colors(
                    checkedColor = colorResource(R.color.nudgy_teal)
                )
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = NudgyCharcoal
                )
                Text(
                    text = planRowDetail(task, null),
                    style = MaterialTheme.typography.bodySmall,
                    color = NudgyMuted
                )
                if (row.isCarryOver) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Carried over",
                        style = MaterialTheme.typography.bodySmall,
                        color = NudgyAccent
                    )
                }
            }
        }
    }
}

/**
 * "Starts 9:00 AM · Due 3:00 PM · 60 min" — the parts that exist.
 * Unpicked rows show no start time (they have none until picked).
 */
private fun planRowDetail(task: TaskEntity, startMillis: Long?): String {
    val parts = mutableListOf<String>()
    if (startMillis != null) parts.add("Starts ${timeLabel(startMillis)}")
    if (task.dueDateEpochMillis != 0L && task.dueDateEpochMillis != startOfTodayMillis()) {
        parts.add("Due ${timeLabel(task.dueDateEpochMillis)}")
    }
    if (task.estimatedMinutes != null) parts.add(formatMinutes(task.estimatedMinutes))
    return if (parts.isEmpty()) "No time set" else parts.joinToString(" · ")
}

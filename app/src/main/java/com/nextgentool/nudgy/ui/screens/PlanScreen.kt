package com.nextgentool.nudgy.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.domain.dayLabel
import com.nextgentool.nudgy.domain.formatMinutes
import com.nextgentool.nudgy.domain.timeLabel
import com.nextgentool.nudgy.ui.components.SampleChip
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
 *
 * Sprint 7 — rebuilt to the prototype (scr-plan): backbar + intro
 * line (the date moved here from the backbar), cream carry banner
 * (live count, hidden when nothing is carried over), white
 * Time-budget card (total + 9px bar + note), "Draft — in order",
 * "Start my day" 52dp with a play icon + a tiny note below it.
 * Design-only: the ViewModel, pick/unpick/reorder and startMyDay
 * are untouched. One disclosed deviation: the "Not planned" section
 * stays below the draft — the prototype shows no such section, but
 * it is the pick/unpick mechanism; removing it would break behaviour.
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
            Text(
                text = "Plan my day",
                style = MaterialTheme.typography.titleLarge,
                color = NudgyCharcoal
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        // Intro line (prototype scr-plan): the date moved here from the
        // backbar; the body is the prototype copy, verbatim.
        Text(
            text = "${dayLabel(startOfTodayMillis())} · ${stringResource(R.string.plan_intro_body)}",
            style = MaterialTheme.typography.bodyMedium,
            color = NudgyMuted
        )
        Spacer(modifier = Modifier.height(14.dp))
        if (uiState.isLoading) {
            NudgySkeletonRow()
        } else {
            // Carry banner (prototype .banner, cream): only when at least
            // one row is carried over — hide-when-empty, the app's rule.
            // The count is derived from the existing rows; no new state.
            val carryCount = (uiState.pickedRows + uiState.unpickedRows)
                .count { it.isCarryOver }
            if (carryCount > 0) {
                PlanCarryBanner(carryCount = carryCount)
                Spacer(modifier = Modifier.height(14.dp))
            }
            PlanTimeBudgetCard(totalMinutes = uiState.totalEstimatedMinutes)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.plan_draft_title),
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
                    .heightIn(min = 52.dp)
                    .testTag("plan_start_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Start my day")
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.plan_start_note),
                style = MaterialTheme.typography.bodySmall,
                color = NudgyMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * Carry banner (prototype .banner, cream): "{N} unfinished from
 * yesterday" + the sub-line. Static copy; only the count is live.
 */
@Composable
private fun PlanCarryBanner(carryCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = colorResource(R.color.nudgy_cream),
                shape = NudgyTokens.cardRadius
            )
            .padding(horizontal = 18.dp, vertical = 16.dp)
            .testTag("plan_carry_banner"),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = Icons.Filled.Refresh,
            contentDescription = null,
            tint = colorResource(R.color.nudgy_teal),
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = stringResource(R.string.plan_carry_banner_title, carryCount),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = NudgyCharcoal
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.plan_carry_banner_sub),
                style = MaterialTheme.typography.bodyMedium,
                color = NudgyMuted
            )
        }
    }
}

/**
 * Time-budget card (prototype scr-plan): "Time budget" + the picked
 * total as "{Xh Ym} of 8h max" in one Text (so the semantics tree
 * holds the full line), a 9px fully-rounded teal bar on the
 * nudgy_track track (fraction = total / 480, capped at 1.0), and the
 * tiny note. This replaces the old plain "X of 8h planned" line.
 */
@Composable
private fun PlanTimeBudgetCard(totalMinutes: Int) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("plan_time_budget_card"),
        shape = NudgyTokens.cardRadius,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = NudgyTokens.cardElevation,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.plan_time_budget_title),
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = NudgyCharcoal
                )
                Text(
                    text = buildAnnotatedString {
                        withStyle(
                            SpanStyle(
                                fontWeight = FontWeight.Bold,
                                color = NudgyCharcoal
                            )
                        ) {
                            append(formatMinutes(totalMinutes))
                        }
                        append(" of 8h max")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = NudgyMuted
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = (totalMinutes / 480f).coerceIn(0f, 1f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(9.dp)
                    .clip(RoundedCornerShape(50))
                    .testTag("plan_time_budget_bar"),
                color = colorResource(R.color.nudgy_teal),
                trackColor = colorResource(R.color.nudgy_track)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.plan_time_budget_note),
                style = MaterialTheme.typography.bodySmall,
                color = NudgyMuted
            )
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
                // Sprint 6-3 — the sample chip (Plan is a picker, so
                // the chip only: the one-tap delete lives on the
                // task rows in Today/Tasks).
                if (task.isSample) {
                    Spacer(modifier = Modifier.height(4.dp))
                    SampleChip()
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
                // Sprint 6-3 — the sample chip (see PlanPickedRow).
                if (task.isSample) {
                    Spacer(modifier = Modifier.height(4.dp))
                    SampleChip()
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

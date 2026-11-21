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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.data.model.FocusSessionKind
import com.nextgentool.nudgy.focus.PomodoroCycle
import com.nextgentool.nudgy.ui.theme.NudgyAccent
import com.nextgentool.nudgy.ui.theme.NudgyCanvas
import com.nextgentool.nudgy.ui.theme.NudgyCard
import com.nextgentool.nudgy.ui.theme.NudgyCharcoal
import com.nextgentool.nudgy.ui.theme.NudgyMuted
import com.nextgentool.nudgy.ui.theme.NudgyTokens
import com.nextgentool.nudgy.ui.viewmodel.FocusUiState
import com.nextgentool.nudgy.ui.viewmodel.FocusViewModel
import com.nextgentool.nudgy.ui.viewmodel.PomodoroPhase

/**
 * Sprint 4-1 — the Pomodoro timer screen (spec §4, Q10=(a)).
 *
 * Reached from the Focus button on Today; NOT a bottom destination
 * (same treatment as Tasks). Full-screen countdown: 25-minute focus
 * / 5-minute break (15 after every 4th completed focus session),
 * pause / resume / cancel, and an optional task to focus on.
 *
 * The wrapper owns the ViewModel; [FocusScreenContent] is pure state
 * + callbacks so the smoke test renders it without Hilt. All text
 * uses the theme tokens (PR #86: never static nudgy_ink/muted on
 * theme surfaces); the teal buttons follow the TodayScreen pattern
 * (teal container, cream content).
 */
@Composable
fun FocusScreen(
    onBackClick: () -> Unit = {},
    viewModel: FocusViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    FocusScreenContent(
        state = uiState,
        onBackClick = onBackClick,
        onStartFocus = { viewModel.startFocus() },
        onStartBreak = { viewModel.startBreak() },
        onPause = { viewModel.pause() },
        onResume = { viewModel.resume() },
        onCancel = { viewModel.cancelCurrent() },
        onSelectTask = { viewModel.selectTask(it) },
        onDismissFinished = { viewModel.clearJustFinished() }
    )
}

@Composable
fun FocusScreenContent(
    state: FocusUiState,
    onBackClick: () -> Unit,
    onStartFocus: () -> Unit,
    onStartBreak: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onSelectTask: (String?) -> Unit,
    onDismissFinished: () -> Unit
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NudgyCanvas)
            .verticalScroll(scrollState)
            .padding(NudgyTokens.screenPadding)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onBackClick,
                modifier = Modifier.testTag("focus_back")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = NudgyCharcoal
                )
            }
            Text(
                text = "Focus",
                style = MaterialTheme.typography.titleLarge,
                color = NudgyCharcoal
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        // Phase label + countdown.
        Text(
            text = when (state.phase) {
                PomodoroPhase.RUNNING ->
                    if (state.kind == FocusSessionKind.FOCUS) "Focusing" else "On a break"
                PomodoroPhase.PAUSED ->
                    if (state.kind == FocusSessionKind.FOCUS) "Focus paused" else "Break paused"
                PomodoroPhase.IDLE ->
                    if (state.kind == FocusSessionKind.FOCUS) "Ready to focus" else "Ready for a break"
            },
            style = MaterialTheme.typography.titleMedium,
            color = NudgyMuted,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = PomodoroCycle.formatRemaining(state.remainingMillis),
            style = MaterialTheme.typography.displayLarge,
            color = NudgyCharcoal,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .testTag("focus_countdown")
        )
        Spacer(modifier = Modifier.height(16.dp))
        LinearProgressIndicator(
            progress = progressOf(state),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("focus_progress"),
            color = colorResource(R.color.nudgy_teal),
            trackColor = NudgyCard
        )
        if (state.phase != PomodoroPhase.IDLE) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = state.selectedTaskTitle?.let { "Focusing on: $it" } ?: "Standalone focus",
                style = MaterialTheme.typography.bodyMedium,
                color = NudgyMuted,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
        // Session-complete prompt (Q4=(a): the break never auto-starts).
        if (state.phase == PomodoroPhase.IDLE && state.justFinishedKind != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = if (state.justFinishedKind == FocusSessionKind.FOCUS) {
                    "Focus complete — nice work!"
                } else {
                    "Break over — ready to focus?"
                },
                style = MaterialTheme.typography.titleMedium,
                color = NudgyCharcoal,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .testTag("focus_finished_prompt")
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        // Controls by phase.
        when (state.phase) {
            PomodoroPhase.IDLE -> {
                if (state.justFinishedKind == FocusSessionKind.FOCUS) {
                    TealButton(
                        text = "Start break (${state.nextBreakMinutes} min)",
                        onClick = onStartBreak,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("focus_start_break")
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = onStartFocus,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("focus_start")
                    ) {
                        Text(text = "Start focus instead", color = NudgyAccent)
                    }
                    TextButton(
                        onClick = onDismissFinished,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("focus_dismiss_finished")
                    ) {
                        Text(text = "Dismiss", color = NudgyMuted)
                    }
                } else {
                    TealButton(
                        text = "Start focus (25 min)",
                        onClick = onStartFocus,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("focus_start")
                    )
                }
            }
            PomodoroPhase.RUNNING -> {
                TealButton(
                    text = "Pause",
                    onClick = onPause,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("focus_pause")
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("focus_cancel")
                ) {
                    Text(text = "Cancel session", color = NudgyCharcoal)
                }
            }
            PomodoroPhase.PAUSED -> {
                TealButton(
                    text = "Resume",
                    onClick = onResume,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("focus_resume")
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("focus_cancel")
                ) {
                    Text(text = "Cancel session", color = NudgyCharcoal)
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        // Today's focus stats (S4-1 scope: at minimum today's minutes).
        Text(
            text = "Today: ${state.todaysFocusMinutes} min focused • ${state.completedFocusToday} sessions",
            style = MaterialTheme.typography.bodyMedium,
            color = NudgyMuted,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .testTag("focus_today_minutes")
        )
        // Task picker (Q3=(a)) — only while idle; a running session
        // keeps the task it started with.
        if (state.phase == PomodoroPhase.IDLE) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Focus on",
                style = MaterialTheme.typography.titleMedium,
                color = NudgyCharcoal
            )
            Spacer(modifier = Modifier.height(8.dp))
            TaskPickerRow(
                title = "No task (standalone)",
                selected = state.selectedTaskId == null,
                onClick = { onSelectTask(null) },
                modifier = Modifier.testTag("focus_task_none")
            )
            state.availableTasks.forEach { task ->
                TaskPickerRow(
                    title = task.title,
                    selected = state.selectedTaskId == task.id,
                    onClick = { onSelectTask(task.id) },
                    modifier = Modifier.testTag("focus_task_${task.id}")
                )
            }
        }
    }
}

private fun progressOf(state: FocusUiState): Float {
    if (state.totalMillis <= 0L) return 0f
    return (state.remainingMillis.toFloat() / state.totalMillis.toFloat()).coerceIn(0f, 1f)
}

@Composable
private fun TealButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = colorResource(R.color.nudgy_teal),
            contentColor = colorResource(R.color.nudgy_cream)
        ),
        modifier = modifier
    ) {
        Text(text = text)
    }
}

@Composable
private fun TaskPickerRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(
                    color = if (selected) colorResource(R.color.nudgy_teal) else NudgyCard,
                    shape = CircleShape
                )
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) NudgyCharcoal else NudgyMuted
        )
    }
}

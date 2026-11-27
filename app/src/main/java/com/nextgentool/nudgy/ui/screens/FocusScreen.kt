package com.nextgentool.nudgy.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.data.model.FocusSessionKind
import com.nextgentool.nudgy.focus.PomodoroCycle
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
 * + callbacks so the smoke test renders it without Hilt.
 *
 * Sprint 7 — design-only restyle to the prototype (scr-focus):
 * backbar + centered timer card (pill, 64sp timerface, "Focusing
 * on:" line, centered Start/Pause/Finish buttons) + "Focus
 * statistics" section + the "Focus on" task picker. All text uses
 * the theme tokens (PR #86: never static nudgy_ink/muted on theme
 * surfaces); the buttons follow the prototype (teal primary,
 * teal-soft Pause, ghost Cancel).
 *
 * DISCLOSED DEVIATION (data, not design): the prototype shows 4
 * statboxes (today / this week / completion % / streak) and a
 * Recent sessions card, but FocusUiState carries ONLY today's
 * minutes + today's session count — no week minutes, no completion
 * %, no streak, no recent-sessions list anywhere in the app (the
 * focus_sessions table has no recent-list query). So the statistics
 * section shows today's statbox only, and there is no Recent
 * sessions card. Wiring the missing stats needs a data task
 * (DAO + repository + ViewModel), not this design-only micro-task.
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

@OptIn(ExperimentalLayoutApi::class)
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
        // Backbar (Sprint 7 — "Focus", 21sp/800 like Calendar).
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
                fontSize = 21.sp,
                fontWeight = FontWeight.ExtraBold,
                color = NudgyCharcoal
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        // Timer card (prototype: centered, padding 26px/18px).
        Surface(
            shape = NudgyTokens.cardRadius,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 26.dp, horizontal = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Pill — describes the timer face (kind + duration).
                val pillMinutes = (state.totalMillis / 60_000L).toInt()
                val pillText = if (state.kind == FocusSessionKind.FOCUS) {
                    "Pomodoro · $pillMinutes minutes"
                } else {
                    "Break · $pillMinutes minutes"
                }
                Box(
                    modifier = Modifier
                        .background(
                            color = colorResource(R.color.nudgy_teal_soft),
                            shape = RoundedCornerShape(999.dp)
                        )
                        .padding(horizontal = 11.dp, vertical = 5.dp)
                        .testTag("focus_pill"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = pillText,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = colorResource(R.color.nudgy_teal_dark)
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = PomodoroCycle.formatRemaining(state.remainingMillis),
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontSize = 64.sp,
                        fontWeight = FontWeight.Black,
                        fontFeatureSettings = "tnum"
                    ),
                    color = NudgyCharcoal,
                    modifier = Modifier.testTag("focus_countdown")
                )
                Spacer(modifier = Modifier.height(4.dp))
                // "Focusing on:" — one Text node so the full string
                // stays searchable; the task name is bold ink.
                if (state.selectedTaskTitle != null) {
                    Text(
                        text = buildAnnotatedString {
                            append("Focusing on: ")
                            withStyle(
                                SpanStyle(
                                    fontWeight = FontWeight.Bold,
                                    color = NudgyCharcoal
                                )
                            ) {
                                append(state.selectedTaskTitle)
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = NudgyMuted
                    )
                } else {
                    Text(
                        text = "Standalone focus",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NudgyMuted
                    )
                }
                Spacer(modifier = Modifier.height(18.dp))
                LinearProgressIndicator(
                    progress = progressOf(state),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("focus_progress"),
                    color = colorResource(R.color.nudgy_teal),
                    trackColor = NudgyCard
                )
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
                        modifier = Modifier.testTag("focus_finished_prompt")
                    )
                }
                Spacer(modifier = Modifier.height(18.dp))
                // Controls by phase — centered row (wraps on narrow
                // screens); every state and testTag kept.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(
                        10.dp,
                        Alignment.CenterHorizontally
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    when (state.phase) {
                        PomodoroPhase.IDLE -> {
                            if (state.justFinishedKind == FocusSessionKind.FOCUS) {
                                TealButton(
                                    text = "Start break (${state.nextBreakMinutes} min)",
                                    onClick = onStartBreak,
                                    modifier = Modifier.testTag("focus_start_break")
                                )
                                GhostButton(
                                    text = "Start focus instead",
                                    onClick = onStartFocus,
                                    modifier = Modifier.testTag("focus_start")
                                )
                                GhostButton(
                                    text = "Dismiss",
                                    onClick = onDismissFinished,
                                    modifier = Modifier.testTag("focus_dismiss_finished")
                                )
                            } else {
                                TealButton(
                                    text = "Start focus (25 min)",
                                    onClick = onStartFocus,
                                    modifier = Modifier.testTag("focus_start")
                                )
                            }
                        }
                        PomodoroPhase.RUNNING -> {
                            SoftButton(
                                text = "Pause",
                                onClick = onPause,
                                modifier = Modifier.testTag("focus_pause")
                            )
                            GhostButton(
                                text = "Cancel session",
                                onClick = onCancel,
                                modifier = Modifier.testTag("focus_cancel")
                            )
                        }
                        PomodoroPhase.PAUSED -> {
                            TealButton(
                                text = "Resume",
                                onClick = onResume,
                                modifier = Modifier.testTag("focus_resume")
                            )
                            GhostButton(
                                text = "Cancel session",
                                onClick = onCancel,
                                modifier = Modifier.testTag("focus_cancel")
                            )
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(22.dp))
        // Focus statistics — today's statbox only (see the file KDoc:
        // week / % / streak have no data source in FocusUiState).
        Text(
            text = "Focus statistics",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = NudgyCharcoal,
            modifier = Modifier.testTag("focus_statistics_title")
        )
        Spacer(modifier = Modifier.height(10.dp))
        Surface(
            shape = NudgyTokens.cardRadius,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("focus_today_statbox")
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "${state.todaysFocusMinutes} min",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Black,
                    color = NudgyCharcoal,
                    modifier = Modifier.testTag("focus_today_minutes")
                )
                Text(
                    text = "Focused today · ${state.completedFocusToday} sessions",
                    fontSize = 13.sp,
                    color = NudgyMuted
                )
            }
        }
        // Task picker (Q3=(a)) — only while idle; a running session
        // keeps the task it started with.
        if (state.phase == PomodoroPhase.IDLE) {
            Spacer(modifier = Modifier.height(22.dp))
            Text(
                text = "Focus on",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = NudgyCharcoal
            )
            Spacer(modifier = Modifier.height(10.dp))
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
        shape = RoundedCornerShape(999.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
        modifier = modifier.defaultMinSize(minWidth = 118.dp, minHeight = 44.dp)
    ) {
        Text(text = text)
    }
}

@Composable
private fun SoftButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = colorResource(R.color.nudgy_teal_soft),
            contentColor = colorResource(R.color.nudgy_teal_dark)
        ),
        shape = RoundedCornerShape(999.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
        modifier = modifier.defaultMinSize(minWidth = 118.dp, minHeight = 44.dp)
    ) {
        Text(text = text)
    }
}

@Composable
private fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = NudgyCharcoal
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(999.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
        modifier = modifier.defaultMinSize(minHeight = 44.dp)
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

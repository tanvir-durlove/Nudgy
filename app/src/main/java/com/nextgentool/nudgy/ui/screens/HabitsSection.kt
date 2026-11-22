package com.nextgentool.nudgy.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.data.repository.HabitStreak
import com.nextgentool.nudgy.ui.theme.NudgyAccent
import com.nextgentool.nudgy.ui.theme.NudgyCharcoal
import com.nextgentool.nudgy.ui.theme.NudgyMuted
import com.nextgentool.nudgy.ui.theme.NudgyTokens
import com.nextgentool.nudgy.ui.viewmodel.HabitUiState
import com.nextgentool.nudgy.ui.viewmodel.HabitViewModel

/**
 * Sprint 4-2 — the Habits section inside Today (spec §5, Q10=(a)).
 *
 * A separate list from tasks (Q5=(a)): title-only habits, one
 * check-in per local day, streaks counted on consecutive device-local
 * days. The wrapper owns the ViewModel; [HabitsSectionContent] is
 * pure state + callbacks so the smoke test renders it without Hilt
 * (the FocusScreen pattern).
 *
 * Streak display: the count stays as computed — when today is not
 * checked in yet the streak is "at risk", NOT broken, so the row
 * says "not done today yet" next to the live count; it never shows
 * 0 while the day is still open (HabitStreakUseCase).
 *
 * All text uses the theme tokens (PR #86: never static nudgy_ink /
 * muted on theme surfaces); teal is only the checkbox / add icon,
 * never small text.
 */
@Composable
fun HabitsSection(
    viewModel: HabitViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    HabitsSectionContent(
        state = uiState,
        onNewTitleChange = { viewModel.setNewTitle(it) },
        onCreateHabit = { viewModel.createHabit() },
        onToggleCheckIn = { habit ->
            if (habit.checkedInToday) {
                viewModel.undoCheckIn(habit.habit.id)
            } else {
                viewModel.checkIn(habit.habit.id)
            }
        },
        onStartRename = { viewModel.startRename(it) },
        onEditTitleChange = { viewModel.setEditTitle(it) },
        onConfirmRename = { viewModel.confirmRename() },
        onCancelRename = { viewModel.cancelRename() },
        onRequestDelete = { viewModel.requestDelete(it.habit.id) },
        onConfirmDelete = { viewModel.confirmDelete() },
        onCancelDelete = { viewModel.cancelDelete() }
    )
}

@Composable
fun HabitsSectionContent(
    state: HabitUiState,
    onNewTitleChange: (String) -> Unit,
    onCreateHabit: () -> Unit,
    onToggleCheckIn: (HabitStreak) -> Unit,
    onStartRename: (HabitStreak) -> Unit,
    onEditTitleChange: (String) -> Unit,
    onConfirmRename: () -> Unit,
    onCancelRename: () -> Unit,
    onRequestDelete: (HabitStreak) -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("habits_section"),
        shape = NudgyTokens.cardRadius,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = NudgyTokens.cardElevation
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Habits",
                style = MaterialTheme.typography.titleMedium,
                color = NudgyCharcoal
            )
            Text(
                text = "One check-in a day keeps the streak alive.",
                style = MaterialTheme.typography.bodySmall,
                color = NudgyMuted
            )
            // Add a habit (title only — spec §5: no daily target).
            OutlinedTextField(
                value = state.newTitle,
                onValueChange = onNewTitleChange,
                placeholder = { Text("New habit", color = NudgyMuted) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onCreateHabit() }),
                trailingIcon = {
                    IconButton(
                        onClick = onCreateHabit,
                        enabled = state.newTitle.isNotBlank(),
                        modifier = Modifier.testTag("habit_add")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "Add habit",
                            tint = if (state.newTitle.isNotBlank()) {
                                colorResource(R.color.nudgy_teal)
                            } else {
                                NudgyMuted
                            }
                        )
                    }
                },
                shape = NudgyTokens.cardRadius,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("habit_new_title")
            )
            if (!state.isLoading && state.habits.isEmpty()) {
                Text(
                    text = "No habits yet — add your first one above",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NudgyMuted
                )
            } else {
                state.habits.forEach { habit ->
                    HabitRow(
                        habit = habit,
                        isEditing = state.editingHabitId == habit.habit.id,
                        editTitle = state.editTitle,
                        isConfirmingDelete =
                            state.confirmingDeleteHabitId == habit.habit.id,
                        onToggleCheckIn = { onToggleCheckIn(habit) },
                        onStartRename = { onStartRename(habit) },
                        onEditTitleChange = onEditTitleChange,
                        onConfirmRename = onConfirmRename,
                        onCancelRename = onCancelRename,
                        onRequestDelete = { onRequestDelete(habit) },
                        onConfirmDelete = onConfirmDelete,
                        onCancelDelete = onCancelDelete
                    )
                }
            }
        }
    }
}

@Composable
private fun HabitRow(
    habit: HabitStreak,
    isEditing: Boolean,
    editTitle: String,
    isConfirmingDelete: Boolean,
    onToggleCheckIn: () -> Unit,
    onStartRename: () -> Unit,
    onEditTitleChange: (String) -> Unit,
    onConfirmRename: () -> Unit,
    onCancelRename: () -> Unit,
    onRequestDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit
) {
    val id = habit.habit.id
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("habit_row_$id")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Checkbox(
                checked = habit.checkedInToday,
                onCheckedChange = { onToggleCheckIn() },
                colors = CheckboxDefaults.colors(
                    checkedColor = colorResource(R.color.nudgy_teal)
                ),
                modifier = Modifier.testTag("habit_checkin_$id")
            )
            if (isEditing) {
                OutlinedTextField(
                    value = editTitle,
                    onValueChange = onEditTitleChange,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onConfirmRename() }),
                    shape = NudgyTokens.cardRadius,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("habit_edit_field_$id")
                )
            } else {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = habit.habit.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = NudgyCharcoal
                    )
                    Text(
                        text = streakLabel(habit),
                        style = MaterialTheme.typography.bodySmall,
                        color = NudgyMuted,
                        modifier = Modifier.testTag("habit_streak_$id")
                    )
                }
            }
            if (isEditing) {
                TextButton(
                    onClick = onConfirmRename,
                    modifier = Modifier.testTag("habit_edit_save_$id")
                ) {
                    Text(text = "Save", color = NudgyAccent)
                }
                TextButton(
                    onClick = onCancelRename,
                    modifier = Modifier.testTag("habit_edit_cancel_$id")
                ) {
                    Text(text = "Cancel", color = NudgyMuted)
                }
            } else {
                IconButton(
                    onClick = onStartRename,
                    modifier = Modifier.testTag("habit_rename_$id")
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Rename habit",
                        tint = NudgyMuted
                    )
                }
                IconButton(
                    onClick = onRequestDelete,
                    modifier = Modifier.testTag("habit_delete_$id")
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete habit",
                        tint = NudgyMuted
                    )
                }
            }
        }
        if (isConfirmingDelete) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Delete this habit? Its check-ins go too.",
                style = MaterialTheme.typography.bodyMedium,
                color = NudgyCharcoal
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onConfirmDelete,
                    modifier = Modifier.testTag("habit_delete_confirm_$id")
                ) {
                    Text(text = "Delete", color = NudgyAccent)
                }
                TextButton(
                    onClick = onCancelDelete,
                    modifier = Modifier.testTag("habit_delete_cancel_$id")
                ) {
                    Text(text = "Cancel", color = NudgyMuted)
                }
            }
        }
    }
}

/**
 * The streak line for one habit (spec §5). The count is the live
 * streak from HabitStreakUseCase: today not checked in yet means
 * "at risk", never 0 while the day is still open — so the label says
 * "not done today yet" next to the count instead of showing a broken
 * streak. A missed day is the only way back to "No current streak".
 */
private fun streakLabel(streak: HabitStreak): String {
    val base = when {
        streak.checkedInToday -> "${streak.currentStreak}-day streak · done today"
        streak.currentStreak > 0 -> "${streak.currentStreak}-day streak · not done today yet"
        else -> "No current streak"
    }
    return if (streak.longestStreak > 0) "$base · Best ${streak.longestStreak}" else base
}

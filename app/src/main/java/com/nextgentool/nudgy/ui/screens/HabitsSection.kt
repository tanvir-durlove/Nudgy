package com.nextgentool.nudgy.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.data.repository.HabitStreak
import com.nextgentool.nudgy.ui.theme.NudgyAccent
import com.nextgentool.nudgy.ui.theme.NudgyCanvas
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
 *
 * Sprint 7 — design-only restyle to the prototype (scr-habits):
 * sec-title + prototype-verbatim intro, habit rows (checkbox + name
 * 15.5sp/500 + streak chip + status line), "+ New habit" soft block
 * button. The section stays where it lives, inside Today — there is
 * no standalone Habits screen and no backbar is added.
 *
 * Sprint 7 (Habits data) — the "This week" dots card is wired now:
 * one row per habit (same order as the habit rows) with 7 dots
 * Mon..Sun — done = that local date is in the habit's real
 * check-in dates (carried by HabitStreak.thisWeekCheckInDates from
 * getHabitsWithStreaks, the same read that computes the streaks),
 * today marked with a teal ring, future days plain. Dots are never
 * derived from a streak count: a 3-day streak lights exactly the 3
 * days checked in, no more.
 *
 * DISCLOSED DEVIATIONS (data, not design):
 * - FOOTNOTE DROPPED: the prototype's "One missed day does not
 *   break a streak if you are back the next day" contradicts the
 *   established model — spec §5 Q6=(a), HabitStreakUseCase KDoc: NO
 *   grace day, a miss yesterday means the streak is 0. Showing the
 *   footnote would promise behaviour the app does not have, so it
 *   is left out entirely (not reworded into a softer promise).
 * - PLACEMENT: the card sits below the habit rows, at the bottom
 *   of the section. The brief's "above the create field" clause
 *   assumed the create field sits below the rows (prototype order:
 *   list → card → button), but in the app the create field +
 *   button are at the TOP (PR #139 deviation) — there is nothing
 *   between the rows and the create field, so below the rows is
 *   the only place the card can go.
 * - No schedule line under the habit name: habits are title-only
 *   (HabitEntity has no schedule), so the prototype's "Every day ·
 *   Evening" / "Weekdays" lines have no data. The tiny line under
 *   the name is the streak status instead ("done today" /
 *   "not done today yet" / "No current streak" + best).
 * - The create UI keeps the always-visible field (the prototype's
 *   button only reveals one); the soft "+ New habit" button sits
 *   directly under its field instead of at the bottom.
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
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = NudgyCharcoal
            )
            Text(
                text = "Small things, daily. Tick today off — streaks update right away.",
                style = MaterialTheme.typography.bodySmall,
                color = NudgyMuted
            )
            // Add a habit (title only — spec §5: no daily target).
            // The field stays always visible (the prototype's button
            // only reveals one); the soft block button below it is
            // the create action, same callback as before.
            OutlinedTextField(
                value = state.newTitle,
                onValueChange = onNewTitleChange,
                placeholder = { Text("New habit", color = NudgyMuted) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onCreateHabit() }),
                shape = NudgyTokens.cardRadius,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("habit_new_title")
            )
            Button(
                onClick = onCreateHabit,
                enabled = state.newTitle.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colorResource(R.color.nudgy_teal_soft),
                    contentColor = colorResource(R.color.nudgy_teal_dark),
                    disabledContainerColor = colorResource(R.color.nudgy_teal_soft),
                    disabledContentColor = NudgyMuted
                ),
                shape = RoundedCornerShape(999.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("habit_add")
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = "New habit")
            }
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
            // "This week" dots (Sprint 7 Habits data) — below the
            // habit rows, hidden when there is nothing to show (the
            // hide-when-empty precedent) or when the state carries
            // no full week (a fabricated state must not render a
            // partial card).
            if (!state.isLoading && state.habits.isNotEmpty() && state.weekDates.size == 7) {
                HabitWeekCard(
                    habits = state.habits,
                    weekDates = state.weekDates,
                    todayDate = state.todayDate
                )
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
                        fontSize = 15.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = NudgyCharcoal
                    )
                    Text(
                        text = streakStatusLabel(habit),
                        style = MaterialTheme.typography.bodySmall,
                        color = NudgyMuted
                    )
                }
            }
            // Streak chip (prototype): the short count on the right;
            // the tiny line under the name keeps the status ("done
            // today" / "not done today yet" / best). Hidden while
            // editing — the edit field + Save/Cancel take the row.
            if (!isEditing) {
                Box(
                    modifier = Modifier
                        .background(
                            color = colorResource(R.color.nudgy_teal_soft),
                            shape = RoundedCornerShape(999.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                        .testTag("habit_streak_$id"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = streakChipLabel(habit),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = colorResource(R.color.nudgy_teal_dark)
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
 * The streak chip for one habit (prototype): the short count only —
 * "3-day streak", or "No streak" when there is no current streak.
 * The status detail lives in [streakStatusLabel] under the name.
 */
private fun streakChipLabel(streak: HabitStreak): String =
    if (streak.currentStreak > 0) "${streak.currentStreak}-day streak" else "No streak"

/**
 * The streak status line under the habit name (spec §5). The count
 * is the live streak from HabitStreakUseCase: today not checked in
 * yet means "at risk", never 0 while the day is still open — so the
 * label says "not done today yet" next to the count instead of
 * showing a broken streak. A missed day is the only way back to
 * "No current streak".
 */
private fun streakStatusLabel(streak: HabitStreak): String {
    val base = when {
        streak.checkedInToday -> "done today"
        streak.currentStreak > 0 -> "not done today yet"
        else -> "No current streak"
    }
    return if (streak.longestStreak > 0) "$base · Best ${streak.longestStreak}" else base
}

/**
 * Sprint 7 (Habits data) — the prototype's "This week" card: one
 * row per habit (same order as the habit rows) with 7 dots Mon..Sun.
 * The dots render REAL check-in dates only
 * (HabitStreak.thisWeekCheckInDates) — never the streak count. The
 * prototype's grace-day footnote is deliberately NOT here (see the
 * file KDoc: it contradicts the no-grace-day rule).
 */
@Composable
private fun HabitWeekCard(
    habits: List<HabitStreak>,
    weekDates: List<String>,
    todayDate: String
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = NudgyCanvas,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("habit_week_card")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "This week",
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Bold,
                color = NudgyCharcoal
            )
            habits.forEach { habit ->
                HabitWeekRow(habit = habit, weekDates = weekDates, todayDate = todayDate)
            }
        }
    }
}

@Composable
private fun HabitWeekRow(
    habit: HabitStreak,
    weekDates: List<String>,
    todayDate: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("habit_week_row_${habit.habit.id}")
    ) {
        Text(
            text = habit.habit.title,
            fontSize = 14.sp,
            color = NudgyMuted,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            weekDates.forEachIndexed { index, date ->
                HabitWeekDot(
                    habitId = habit.habit.id,
                    index = index,
                    // A future day is plain, never done: a date in
                    // the set that lies after today cannot be a real
                    // check-in for this week.
                    done = date in habit.thisWeekCheckInDates && date <= todayDate,
                    isToday = date == todayDate
                )
            }
        }
    }
}

/** One 22dp week dot (prototype .weekdot): teal when done, a teal ring on today. */
@Composable
private fun HabitWeekDot(
    habitId: String,
    index: Int,
    done: Boolean,
    isToday: Boolean
) {
    val dayName = WEEK_DAY_NAMES[index]
    val description = when {
        done && isToday -> "$dayName, done, today"
        done -> "$dayName, done"
        isToday -> "$dayName, today"
        else -> "$dayName, not done"
    }
    Box(
        modifier = Modifier
            .size(22.dp)
            .background(
                color = if (done) {
                    colorResource(R.color.nudgy_teal)
                } else {
                    colorResource(R.color.nudgy_track)
                },
                shape = CircleShape
            )
            .then(
                if (isToday) {
                    Modifier.border(2.dp, colorResource(R.color.nudgy_teal), CircleShape)
                } else {
                    Modifier
                }
            )
            .semantics { contentDescription = description }
            .testTag("habit_week_dot_${habitId}_$index"),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = WEEK_DAY_LETTERS[index],
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            color = if (done) Color.White else NudgyMuted
        )
    }
}

/** Mon..Sun, matching weekDateStrings order (Monday first). */
private val WEEK_DAY_LETTERS = listOf("M", "T", "W", "T", "F", "S", "S")
private val WEEK_DAY_NAMES = listOf(
    "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"
)

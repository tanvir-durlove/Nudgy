package com.nextgentool.nudgy.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.ui.theme.NudgyCard
import com.nextgentool.nudgy.ui.theme.NudgyCharcoal
import com.nextgentool.nudgy.ui.theme.NudgyMuted
import com.nextgentool.nudgy.ui.theme.NudgyTokens
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Sprint 1-1 — quick task capture (Feature 1).
 *
 * A big "Add task" field: title + Enter/✓ creates AND SAVES the task
 * immediately — the confirmation card says "Saved:" and has a Done
 * button, so it never looks like an unsaved draft. An empty title
 * never saves — the button is disabled, and the UseCase rejects blanks
 * as a backstop. While a create is in flight the button stays disabled
 * (double-tap guard).
 *
 * The due date is the caller's decision: the Tasks screen passes no due
 * date (→ "No date", lands in the list's No-date group, never in
 * Today); Today passes due-today (UI-FIX 2026-11-16). After creation,
 * the card under the field offers due date/time and priority for the
 * just-created task — inline, no sheet (Feature 1). Picking a date
 * defaults to 09:00 local; the time button refines it.
 *
 * Sprint 6-5 (step 3b) — the widget "+" quick-add: `autofocus` focuses
 * this field once on entry (keyboard up, ready to type) and consumes
 * the one-shot signal via [onAutofocusConsumed], the same state
 * pattern as the search deep-links (2e/2f). Defaults keep every
 * existing call site unchanged — only the Today destination passes it.
 */
@Composable
fun QuickAddTask(
    onCreate: (String) -> Unit,
    isCreating: Boolean,
    lastCreatedTask: TaskEntity?,
    onLastCreatedDue: (Long) -> Unit,
    onLastCreatedPriority: (Priority) -> Unit,
    onDismissLastCreated: () -> Unit,
    autofocus: Boolean = false,
    onAutofocusConsumed: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    val canCreate = text.isNotBlank() && !isCreating
    // Sprint 6-5 (step 3b) — one-shot focus for the widget "+" entry:
    // the field is laid out by the time this effect runs, so the
    // request lands; the keyboard is shown explicitly (a programmatic
    // focus does not always raise it on its own) and the signal is
    // consumed at once so a later recomposition never re-focuses.
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(autofocus) {
        if (autofocus) {
            focusRequester.requestFocus()
            keyboardController?.show()
            onAutofocusConsumed()
        }
    }

    fun submit() {
        if (!canCreate) return
        onCreate(text)
        text = ""
    }

    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Add task", color = NudgyMuted) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            trailingIcon = {
                IconButton(onClick = { submit() }, enabled = canCreate) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "Add task",
                        tint = if (canCreate) {
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
                .focusRequester(focusRequester)
                .testTag("quick_add_field")
        )

        if (lastCreatedTask != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = NudgyTokens.cardRadius,
                color = colorResource(R.color.nudgy_cream)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Saved: ${lastCreatedTask.title}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colorResource(R.color.nudgy_ink),
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = onDismissLastCreated,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Dismiss",
                                tint = colorResource(R.color.nudgy_muted),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val dueLabel = if (lastCreatedTask.dueDateEpochMillis == TaskRepository.NO_DUE_DATE) {
                            "Set due date"
                        } else {
                            SimpleDateFormat("EEE, MMM d · h:mm a", Locale.getDefault())
                                .format(lastCreatedTask.dueDateEpochMillis)
                        }
                        TextButton(onClick = { showDatePicker = true }) {
                            Text(dueLabel, color = colorResource(R.color.nudgy_teal))
                        }
                        if (lastCreatedTask.dueDateEpochMillis != TaskRepository.NO_DUE_DATE) {
                            TextButton(onClick = { showTimePicker = true }) {
                                Text("Set time", color = colorResource(R.color.nudgy_teal))
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Priority.entries.forEach { priority ->
                            val selected = lastCreatedTask.priority == priority
                            FilterChip(
                                selected = selected,
                                onClick = { onLastCreatedPriority(priority) },
                                label = { Text(priority.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = colorResource(R.color.nudgy_teal),
                                    selectedLabelColor = colorResource(R.color.nudgy_cream),
                                    // Unselected: explicit theme-aware
                                    // colors (same M3-defaults trap as the
                                    // task-list chips — light label on a
                                    // transparent container in dark mode).
                                    containerColor = NudgyCard,
                                    labelColor = NudgyCharcoal
                                )
                            )
                        }
                    }
                    // Explicit Done: the card is a saved task, not a draft —
                    // Done dismisses it (same as the X, but unmistakable).
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismissLastCreated) {
                            Text("Done", color = colorResource(R.color.nudgy_teal))
                        }
                    }
                }
            }
        }
    }

    if (showDatePicker) {
        NudgyDatePickerDialog(
            initialDateMillis = lastCreatedTask?.dueDateEpochMillis
                ?.takeIf { it != TaskRepository.NO_DUE_DATE },
            onDateSelected = { dateMillis ->
                showDatePicker = false
                onLastCreatedDue(combineDateAndTime(dateMillis, 9, 0))
            },
            onDismiss = { showDatePicker = false }
        )
    }
    if (showTimePicker) {
        val due = lastCreatedTask?.dueDateEpochMillis ?: 0L
        val cal = Calendar.getInstance().apply { timeInMillis = due }
        NudgyTimePickerDialog(
            initialHour = cal.get(Calendar.HOUR_OF_DAY),
            initialMinute = cal.get(Calendar.MINUTE),
            onTimeSelected = { hour, minute ->
                showTimePicker = false
                val (year, month, day) = localDateParts(due)
                onLastCreatedDue(
                    Calendar.getInstance().apply {
                        set(Calendar.YEAR, year)
                        set(Calendar.MONTH, month)
                        set(Calendar.DAY_OF_MONTH, day)
                        set(Calendar.HOUR_OF_DAY, hour)
                        set(Calendar.MINUTE, minute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                )
            },
            onDismiss = { showTimePicker = false }
        )
    }
}

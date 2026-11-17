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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.ui.theme.NudgyTokens
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Sprint 1-1 — quick task capture (Feature 1).
 *
 * A big "Add task" field: title + Enter/✓ creates the task (no due date
 * → "No date", it lands in the list's No-date group, never in Today).
 * An empty title never saves — the button is disabled, and the UseCase
 * rejects blanks as a backstop. While a create is in flight the button
 * stays disabled (double-tap guard).
 *
 * After creation, the row under the field offers due date/time and
 * priority for the just-created task — inline, no sheet (Feature 1).
 * The date defaults to 09:00 local; the time button refines it.
 */
@Composable
fun QuickAddTask(
    onCreate: (String) -> Unit,
    isCreating: Boolean,
    lastCreatedTask: TaskEntity?,
    onLastCreatedDue: (Long) -> Unit,
    onLastCreatedPriority: (Priority) -> Unit,
    onDismissLastCreated: () -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    val canCreate = text.isNotBlank() && !isCreating

    fun submit() {
        if (!canCreate) return
        onCreate(text)
        text = ""
    }

    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Add task", color = colorResource(R.color.nudgy_muted)) },
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
                            colorResource(R.color.nudgy_muted)
                        }
                    )
                }
            },
            shape = NudgyTokens.cardRadius,
            modifier = Modifier.fillMaxWidth()
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
                            text = "Added: ${lastCreatedTask.title}",
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
                                    selectedLabelColor = colorResource(R.color.nudgy_cream)
                                )
                            )
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

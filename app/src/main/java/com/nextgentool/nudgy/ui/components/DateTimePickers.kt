package com.nextgentool.nudgy.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import java.util.Calendar

/**
 * Sprint 1-1 — date / time pickers shared by QuickAddTask and the task
 * detail sheet. Calendar-based on purpose: java.time needs library
 * desugaring below API 26, and this build does not enable it.
 *
 * A picked date defaults to 09:00 local unless a time is picked too.
 * [combineDateAndTime] merges a DatePicker millis value (UTC midnight)
 * with an hour/minute into device-local epoch millis.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NudgyDatePickerDialog(
    initialDateMillis: Long?,
    onDateSelected: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initialDateMillis)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedDateMillis != null,
                onClick = {
                    state.selectedDateMillis?.let { onDateSelected(it) }
                }
            ) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    ) {
        DatePicker(state = state)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NudgyTimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    onTimeSelected: (hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberTimePickerState(initialHour = initialHour, initialMinute = initialMinute)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onTimeSelected(state.hour, state.minute) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        text = { TimePicker(state = state) }
    )
}

/** DatePicker millis (UTC midnight) + a local hour/minute → epoch millis. */
fun combineDateAndTime(dateMillisUtcMidnight: Long, hour: Int, minute: Int): Long {
    val dateCal = Calendar.getInstance().apply { timeInMillis = dateMillisUtcMidnight }
    return Calendar.getInstance().apply {
        set(Calendar.YEAR, dateCal.get(Calendar.YEAR))
        set(Calendar.MONTH, dateCal.get(Calendar.MONTH))
        set(Calendar.DAY_OF_MONTH, dateCal.get(Calendar.DAY_OF_MONTH))
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

/** The local date parts of epoch millis, for pre-filling the pickers. */
fun localDateParts(epochMillis: Long): Triple<Int, Int, Int> {
    val cal = Calendar.getInstance().apply { timeInMillis = epochMillis }
    return Triple(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
}

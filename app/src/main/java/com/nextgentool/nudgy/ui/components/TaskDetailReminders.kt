package com.nextgentool.nudgy.ui.components

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.usecase.Recurrence
import com.nextgentool.nudgy.reminders.ReminderManager
import com.nextgentool.nudgy.ui.theme.NudgyTokens
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private val MINUTE = 60_000L
private val HOUR = 60 * MINUTE
private val DAY = 24 * HOUR

/** One reminder-timing choice: its label and the trigger it computes from the due time. */
private enum class ReminderOption(val label: String) {
    OFF("Off"),
    TWO_HOURS("2 hours before"),
    ONE_HOUR("1 hour before"),
    THIRTY_MIN("30 min before"),
    TEN_MIN("10 min before"),
    FIVE_MIN("5 min before"),
    ONE_DAY("1 day before"),
    AT_DUE("At due time"),
    CUSTOM("Custom…");

    fun triggerMillis(dueMillis: Long): Long? = when (this) {
        OFF -> null
        TWO_HOURS -> dueMillis - 2 * HOUR
        ONE_HOUR -> dueMillis - HOUR
        THIRTY_MIN -> dueMillis - 30 * MINUTE
        TEN_MIN -> dueMillis - 10 * MINUTE
        FIVE_MIN -> dueMillis - 5 * MINUTE
        ONE_DAY -> dueMillis - DAY
        AT_DUE -> dueMillis
        CUSTOM -> null
    }
}

private fun selectedReminderOption(task: TaskEntity): ReminderOption {
    if (!task.reminderSet) return ReminderOption.OFF
    val due = task.dueDateEpochMillis
    val trigger = task.reminderEpochMillis ?: return ReminderOption.TWO_HOURS
    return ReminderOption.entries.firstOrNull { it != ReminderOption.OFF && it != ReminderOption.CUSTOM && it.triggerMillis(due) == trigger }
        ?: ReminderOption.CUSTOM
}

/**
 * Sprint 1-3a — Reminder section of the task detail sheet (Feature 6).
 *
 * Timing chips (Off / 2h default / 1h / 30m / 10m / 5m / 1 day / at due /
 * custom time), a "Repeat reminder" row (every 15 / 30 / 60 min until
 * completed), and the "missed" state: when exact alarms are off and the
 * reminder time has passed, the task shows "Reminder missed" with a button
 * to turn exact alarms on — the reminder is never silently lost.
 * Needs a due date; without one the section explains why it is disabled.
 */
@Composable
fun ReminderSection(
    task: TaskEntity,
    exactAlarmsAllowed: Boolean,
    enabled: Boolean,
    onUpdateTask: (TaskEntity) -> Unit
) {
    val context = LocalContext.current
    var showTimePicker by remember { mutableStateOf(false) }
    var customTimeError by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Reminder",
            style = MaterialTheme.typography.labelLarge,
            color = colorResource(R.color.nudgy_ink)
        )
        if (task.dueDateEpochMillis == TaskRepository.NO_DUE_DATE) {
            Text(
                text = "Set a due date to add a reminder.",
                style = MaterialTheme.typography.bodyMedium,
                color = colorResource(R.color.nudgy_muted)
            )
            return@Column
        }
        val selected = selectedReminderOption(task)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ReminderOption.entries, key = { it.name }) { option ->
                val label = if (option == ReminderOption.CUSTOM && selected == ReminderOption.CUSTOM) {
                    task.reminderEpochMillis?.let {
                        SimpleDateFormat("h:mm a", Locale.getDefault()).format(it)
                    } ?: option.label
                } else {
                    option.label
                }
                FilterChip(
                    selected = selected == option,
                    onClick = {
                        if (!enabled) return@FilterChip
                        customTimeError = false
                        if (option == ReminderOption.OFF) {
                            onUpdateTask(task.copy(reminderSet = false, reminderEpochMillis = null))
                        } else if (option == ReminderOption.CUSTOM) {
                            showTimePicker = true
                        } else {
                            option.triggerMillis(task.dueDateEpochMillis)?.let { trigger ->
                                onUpdateTask(task.copy(reminderSet = true, reminderEpochMillis = trigger))
                            }
                        }
                    },
                    label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = colorResource(R.color.nudgy_teal),
                        selectedLabelColor = colorResource(R.color.nudgy_cream)
                    )
                )
            }
        }
        if (customTimeError) {
            Text(
                text = "That time has passed — pick a future time.",
                style = MaterialTheme.typography.bodySmall,
                color = colorResource(R.color.nudgy_coral)
            )
        }

        // Repeat reminder: re-fire every N minutes until completed.
        if (task.reminderSet) {
            Text(
                text = "Repeat reminder",
                style = MaterialTheme.typography.labelMedium,
                color = colorResource(R.color.nudgy_ink)
            )
            val repeatOptions = listOf(
                null to "Off",
                15 to "Every 15 min",
                30 to "Every 30 min",
                60 to "Every 1 hour"
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(repeatOptions, key = { it.first?.toString() ?: "off" }) { (minutes, label) ->
                    FilterChip(
                        selected = task.reminderRepeatMinutes == minutes,
                        onClick = {
                            if (!enabled) return@FilterChip
                            onUpdateTask(task.copy(reminderRepeatMinutes = minutes))
                        },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = colorResource(R.color.nudgy_teal),
                            selectedLabelColor = colorResource(R.color.nudgy_cream)
                        )
                    )
                }
            }
        }

        // Missed state + exact-alarm hint (Feature 6: never silently lost).
        val effectiveTrigger = task.reminderEpochMillis
            ?: ReminderManager.dueReminderTriggerMillis(null, task.dueDateEpochMillis)
        val missed = ReminderManager.isReminderMissed(
            reminderSet = task.reminderSet,
            effectiveTriggerMillis = effectiveTrigger,
            isCompleted = task.isCompleted,
            exactAlarmsAllowed = exactAlarmsAllowed,
            nowMillis = System.currentTimeMillis()
        )
        if (missed) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(colorResource(R.color.nudgy_coral))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Reminder missed",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorResource(R.color.nudgy_ink)
                    )
                    Text(
                        text = "Exact alarms are off, so this reminder could not fire on time.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorResource(R.color.nudgy_muted)
                    )
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    TextButton(onClick = { openExactAlarmSettings(context) }) {
                        Text("Turn on", color = colorResource(R.color.nudgy_teal))
                    }
                }
            }
        } else if (!exactAlarmsAllowed && task.reminderSet) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Exact alarms are off — reminders may be delayed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colorResource(R.color.nudgy_muted),
                    modifier = Modifier.weight(1f)
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    TextButton(onClick = { openExactAlarmSettings(context) }) {
                        Text("Turn on", color = colorResource(R.color.nudgy_teal))
                    }
                }
            }
        }
    }

    if (showTimePicker) {
        val due = task.dueDateEpochMillis
        val base = task.reminderEpochMillis ?: due
        val cal = Calendar.getInstance().apply { timeInMillis = base }
        NudgyTimePickerDialog(
            initialHour = cal.get(Calendar.HOUR_OF_DAY),
            initialMinute = cal.get(Calendar.MINUTE),
            onTimeSelected = { hour, minute ->
                showTimePicker = false
                val (year, month, day) = localDateParts(due)
                val combined = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, day)
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                if (combined <= System.currentTimeMillis()) {
                    customTimeError = true
                } else {
                    customTimeError = false
                    onUpdateTask(task.copy(reminderSet = true, reminderEpochMillis = combined))
                }
            },
            onDismiss = { showTimePicker = false }
        )
    }
}

private fun openExactAlarmSettings(context: android.content.Context) {
    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    context.startActivity(intent)
}

private enum class RepeatType(val label: String) {
    NONE("None"),
    DAILY("Daily"),
    WEEKLY("Weekly"),
    MONTHLY("Monthly"),
    EVERY_N_DAYS("Every N days")
}

private fun repeatTypeOf(rule: String?): RepeatType {
    return when (Recurrence.parse(rule)) {
        null -> RepeatType.NONE
        is Recurrence.Rule.Daily -> RepeatType.DAILY
        is Recurrence.Rule.Weekly -> RepeatType.WEEKLY
        is Recurrence.Rule.Monthly -> RepeatType.MONTHLY
        is Recurrence.Rule.EveryNDays -> RepeatType.EVERY_N_DAYS
    }
}

/**
 * Sprint 1-3a — "Repeat task" section of the task detail sheet (Feature 3).
 *
 * None / Daily / Weekly (day picker) / Monthly (day-of-month) / Every N
 * days. Completing the task creates the next occurrence (same title, new
 * due); the hint shows when that is. Needs a due date — the recurrence is
 * anchored at it.
 */
@Composable
fun RecurrenceSection(
    task: TaskEntity,
    enabled: Boolean,
    onUpdateTask: (TaskEntity) -> Unit
) {
    val due = task.dueDateEpochMillis
    var type by remember(task.id) { mutableStateOf(repeatTypeOf(task.recurrenceRule)) }
    var weeklyDays by remember(task.id) {
        mutableStateOf(
            (Recurrence.parse(task.recurrenceRule) as? Recurrence.Rule.Weekly)?.days
                ?: setOf(Calendar.getInstance().apply { timeInMillis = due }.get(Calendar.DAY_OF_WEEK))
        )
    }
    var monthlyDay by remember(task.id) {
        mutableStateOf(
            ((Recurrence.parse(task.recurrenceRule) as? Recurrence.Rule.Monthly)?.dayOfMonth
                ?: Calendar.getInstance().apply { timeInMillis = due }.get(Calendar.DAY_OF_MONTH)).toString()
        )
    }
    var everyN by remember(task.id) {
        mutableStateOf(
            ((Recurrence.parse(task.recurrenceRule) as? Recurrence.Rule.EveryNDays)?.n ?: 3).toString()
        )
    }

    fun applyRule(rule: String?) {
        onUpdateTask(task.copy(recurrenceRule = rule))
    }

    fun ruleForCurrentPicker(): String? = when (type) {
        RepeatType.NONE -> null
        RepeatType.DAILY -> "DAILY"
        RepeatType.WEEKLY -> if (weeklyDays.isEmpty()) null
        else Recurrence.format(Recurrence.Rule.Weekly(weeklyDays))
        RepeatType.MONTHLY -> monthlyDay.toIntOrNull()
            ?.takeIf { it in 1..31 }?.let { "MONTHLY:$it" }
        RepeatType.EVERY_N_DAYS -> everyN.toIntOrNull()
            ?.takeIf { it in 1..365 }?.let { "EVERY_N_DAYS:$it" }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Repeat task",
            style = MaterialTheme.typography.labelLarge,
            color = colorResource(R.color.nudgy_ink)
        )
        if (due == TaskRepository.NO_DUE_DATE) {
            Text(
                text = "Set a due date to repeat this task.",
                style = MaterialTheme.typography.bodyMedium,
                color = colorResource(R.color.nudgy_muted)
            )
            return@Column
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(RepeatType.entries, key = { it.name }) { repeatType ->
                FilterChip(
                    selected = type == repeatType,
                    onClick = {
                        if (!enabled) return@FilterChip
                        type = repeatType
                        applyRule(
                            when (repeatType) {
                                RepeatType.NONE -> null
                                RepeatType.DAILY -> "DAILY"
                                RepeatType.WEEKLY -> Recurrence.format(Recurrence.Rule.Weekly(weeklyDays))
                                RepeatType.MONTHLY -> monthlyDay.toIntOrNull()
                                    ?.takeIf { it in 1..31 }?.let { "MONTHLY:$it" }
                                RepeatType.EVERY_N_DAYS -> everyN.toIntOrNull()
                                    ?.takeIf { it in 1..365 }?.let { "EVERY_N_DAYS:$it" }
                            }
                        )
                    },
                    label = { Text(repeatType.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = colorResource(R.color.nudgy_teal),
                        selectedLabelColor = colorResource(R.color.nudgy_cream)
                    )
                )
            }
        }
        if (type == RepeatType.WEEKLY) {
            val days = listOf(
                Calendar.MONDAY to "Mon",
                Calendar.TUESDAY to "Tue",
                Calendar.WEDNESDAY to "Wed",
                Calendar.THURSDAY to "Thu",
                Calendar.FRIDAY to "Fri",
                Calendar.SATURDAY to "Sat",
                Calendar.SUNDAY to "Sun"
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(days, key = { it.first }) { (day, label) ->
                    FilterChip(
                        selected = day in weeklyDays,
                        onClick = {
                            if (!enabled) return@FilterChip
                            weeklyDays = if (day in weeklyDays) weeklyDays - day else weeklyDays + day
                            applyRule(ruleForCurrentPicker())
                        },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = colorResource(R.color.nudgy_teal),
                            selectedLabelColor = colorResource(R.color.nudgy_cream)
                        )
                    )
                }
            }
        }
        if (type == RepeatType.MONTHLY) {
            OutlinedTextField(
                value = monthlyDay,
                onValueChange = { text ->
                    if (!enabled) return@OutlinedTextField
                    monthlyDay = text.filter { it.isDigit() }.take(2)
                    monthlyDay.toIntOrNull()?.takeIf { it in 1..31 }?.let {
                        applyRule("MONTHLY:$it")
                    }
                },
                label = { Text("Day of month (1–31)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = NudgyTokens.cardRadius,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (type == RepeatType.EVERY_N_DAYS) {
            OutlinedTextField(
                value = everyN,
                onValueChange = { text ->
                    if (!enabled) return@OutlinedTextField
                    everyN = text.filter { it.isDigit() }.take(3)
                    everyN.toIntOrNull()?.takeIf { it in 1..365 }?.let {
                        applyRule("EVERY_N_DAYS:$it")
                    }
                },
                label = { Text("Every N days") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = NudgyTokens.cardRadius,
                modifier = Modifier.fillMaxWidth()
            )
        }
        task.recurrenceRule?.let { rule ->
            Recurrence.nextOccurrence(rule, due)?.let { next ->
                Text(
                    text = "Next: " + SimpleDateFormat("EEE, MMM d · h:mm a", Locale.getDefault()).format(next),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorResource(R.color.nudgy_muted)
                )
            }
        }
    }
}

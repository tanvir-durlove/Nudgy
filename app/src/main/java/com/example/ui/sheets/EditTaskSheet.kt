package com.example.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.entity.TaskEntity
import com.example.data.model.Priority
import com.example.data.model.TaskCategory
import com.example.reminders.ReminderManager
import com.example.ui.theme.NudgyAccent
import com.example.ui.theme.NudgyCard
import com.example.ui.theme.NudgyCardBorder
import com.example.ui.theme.NudgyCharcoal
import com.example.ui.theme.NudgyChipBackground
import com.example.ui.theme.NudgyFieldBorder
import com.example.ui.theme.NudgyMuted
import com.example.ui.theme.NudgyPrimary
import com.example.ui.theme.NudgyPrimaryDark
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.TimeZone
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTaskSheet(
    task: TaskEntity,
    onDismiss: () -> Unit,
    onUpdateTask: (TaskEntity) -> Unit,
    onDeleteTask: (taskId: String) -> Unit,
    onToggleComplete: (TaskEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var title by remember { mutableStateOf(task.title) }
    var description by remember { mutableStateOf(task.description ?: "") }
    var priority by remember { mutableStateOf(task.priority) }
    var category by remember { mutableStateOf(task.category) }
    var currentDueDateMillis by remember { mutableLongStateOf(task.dueDateEpochMillis) }
    var showM3DatePicker by remember { mutableStateOf(false) }
    var showM3TimePicker by remember { mutableStateOf(false) }
    var editPickedDateMillis by remember { mutableStateOf<Long?>(null) }
    var reminderSet by remember { mutableStateOf(task.reminderSet) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    val dateFormatter = remember { SimpleDateFormat("EEEE, MMM d · h:mm a", Locale.getDefault()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = NudgyCard,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(24.dp)
        ) {
            // Header with status indicator and Close/Delete
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.clickable {
                        onToggleComplete(task)
                        onDismiss()
                    }
                ) {
                    Icon(
                        imageVector = if (task.isCompleted) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                        contentDescription = "Toggle status",
                        tint = if (task.isCompleted) Color(0xFF2E7D32) else NudgyMuted,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = if (task.isCompleted) "Completed" else "In Progress",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = if (task.isCompleted) Color(0xFF2E7D32) else NudgyCharcoal
                        )
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showDeleteConfirmDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete task",
                            tint = Color(0xFFD32F2F)
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = NudgyCharcoal
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Title input
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Task Title") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NudgyPrimary,
                    unfocusedBorderColor = NudgyFieldBorder
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Description / notes
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Notes & details") },
                placeholder = { Text("Add any notes or context...") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NudgyPrimary,
                    unfocusedBorderColor = NudgyFieldBorder
                ),
                maxLines = 4
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Due Date Display & Editor
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(NudgyChipBackground)
                    .border(1.dp, NudgyFieldBorder, RoundedCornerShape(14.dp))
                    .clickable { showM3DatePicker = true }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = "Edit due time",
                        tint = NudgyPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Due Date & Time",
                            style = MaterialTheme.typography.labelSmall,
                            color = NudgyMuted
                        )
                        Text(
                            text = dateFormatter.format(currentDueDateMillis),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = NudgyCharcoal
                        )
                    }
                }
                Text(
                    text = "Change",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = NudgyAccent
                )
            }

            if (showM3DatePicker) {
                val currentCal = Calendar.getInstance().apply { timeInMillis = currentDueDateMillis }
                val initialUtc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                    set(Calendar.YEAR, currentCal.get(Calendar.YEAR))
                    set(Calendar.MONTH, currentCal.get(Calendar.MONTH))
                    set(Calendar.DAY_OF_MONTH, currentCal.get(Calendar.DAY_OF_MONTH))
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialUtc.timeInMillis)
                DatePickerDialog(
                    onDismissRequest = { showM3DatePicker = false },
                    confirmButton = {
                        TextButton(onClick = {
                            editPickedDateMillis = datePickerState.selectedDateMillis
                            showM3DatePicker = false
                            if (datePickerState.selectedDateMillis != null) showM3TimePicker = true
                        }) { Text("OK") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showM3DatePicker = false }) { Text("Cancel") }
                    }
                ) {
                    DatePicker(state = datePickerState)
                }
            }
            if (showM3TimePicker) {
                val timeCal = Calendar.getInstance().apply { timeInMillis = currentDueDateMillis }
                val timePickerState = rememberTimePickerState(
                    initialHour = timeCal.get(Calendar.HOUR_OF_DAY),
                    initialMinute = timeCal.get(Calendar.MINUTE),
                    is24Hour = false
                )
                AlertDialog(
                    onDismissRequest = { showM3TimePicker = false; editPickedDateMillis = null },
                    title = { Text("Pick due time", fontWeight = FontWeight.Bold, color = NudgyCharcoal) },
                    text = { TimePicker(state = timePickerState) },
                    confirmButton = {
                        TextButton(onClick = {
                            editPickedDateMillis?.let { picked ->
                                val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = picked }
                                currentDueDateMillis = Calendar.getInstance().apply {
                                    set(Calendar.YEAR, utc.get(Calendar.YEAR))
                                    set(Calendar.MONTH, utc.get(Calendar.MONTH))
                                    set(Calendar.DAY_OF_MONTH, utc.get(Calendar.DAY_OF_MONTH))
                                    set(Calendar.HOUR_OF_DAY, timePickerState.hour)
                                    set(Calendar.MINUTE, timePickerState.minute)
                                    set(Calendar.SECOND, 0)
                                    set(Calendar.MILLISECOND, 0)
                                }.timeInMillis
                            }
                            editPickedDateMillis = null
                            showM3TimePicker = false
                        }) { Text("OK") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showM3TimePicker = false; editPickedDateMillis = null }) { Text("Cancel") }
                    }
                )
            }

            // Past-due guard: a time picked for today that has already passed
            // rolls to tomorrow on save (same rule as the create sheet); an
            // older date stays overdue and gets no reminder — say so openly.
            val editNow = System.currentTimeMillis()
            val dueIsToday = run {
                val a = Calendar.getInstance()
                val b = Calendar.getInstance().apply { timeInMillis = currentDueDateMillis }
                a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
            }
            val dueInPast = currentDueDateMillis <= editNow
            if (dueInPast) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = if (dueIsToday) {
                        "⚠ This time has already passed today — saving will move it to tomorrow at the same time."
                    } else {
                        "⚠ This date is in the past — the task will stay overdue and no reminder will fire."
                    },
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = NudgyPrimaryDark
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Priority
            Text(
                text = "PRIORITY",
                style = MaterialTheme.typography.labelSmall,
                color = NudgyMuted
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Priority.values().forEach { p ->
                    val isSelected = p == priority
                    val pColor = when (p) {
                        Priority.HIGH -> Color(0xFFCC3F27)
                        Priority.MEDIUM -> Color(0xFFB45309)
                        Priority.LOW -> Color(0xFF2E7D32)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(CircleShape)
                            .background(if (isSelected) pColor else NudgyChipBackground)
                            .clickable { priority = p }
                            .height(48.dp)
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = p.name.lowercase().replaceFirstChar { it.uppercase() },
                            color = if (isSelected) Color.White else NudgyCharcoal,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Category
            Text(
                text = "CATEGORY",
                style = MaterialTheme.typography.labelSmall,
                color = NudgyMuted
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TaskCategory.values().forEach { c ->
                    val isSelected = c == category
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(CircleShape)
                            .background(if (isSelected) MaterialTheme.colorScheme.inverseSurface else NudgyChipBackground)
                            .clickable { category = c }
                            .height(48.dp)
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = c.name.lowercase().replaceFirstChar { it.uppercase() },
                            color = if (isSelected) MaterialTheme.colorScheme.inverseOnSurface else NudgyCharcoal,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Reminder Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(NudgyCard)
                    .border(1.dp, NudgyFieldBorder, RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (reminderSet) Color(0xFFFFECE8) else Color(0xFFF0EFEA)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Notifications,
                            contentDescription = null,
                            tint = if (reminderSet) NudgyPrimary else NudgyMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "Notification Reminder",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
                            color = NudgyCharcoal
                        )
                        Text(
                            text = if (reminderSet) "Alarm scheduled" else "No reminder",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                            color = NudgyMuted
                        )
                    }
                }

                Switch(
                    checked = reminderSet,
                    onCheckedChange = { reminderSet = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = NudgyPrimary
                    )
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Save Changes button
            Button(
                onClick = {
                    if (title.isNotBlank()) {
                        val finalDueMillis = if (dueInPast && dueIsToday) {
                            Calendar.getInstance().apply {
                                timeInMillis = currentDueDateMillis
                                add(Calendar.DAY_OF_YEAR, 1)
                            }.timeInMillis
                        } else {
                            currentDueDateMillis
                        }
                        val updated = task.copy(
                            title = title.trim(),
                            description = if (description.isNotBlank()) description.trim() else null,
                            dueDateEpochMillis = finalDueMillis,
                            reminderEpochMillis = if (reminderSet) ReminderManager.dueReminderTriggerMillis(null, finalDueMillis) else null,
                            priority = priority,
                            category = category,
                            reminderSet = reminderSet
                        )
                        onUpdateTask(updated)
                        onDismiss()
                    }
                },
                enabled = title.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(100.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NudgyPrimary)
            ) {
                Text(
                    text = "Save Changes",
                    style = MaterialTheme.typography.titleMedium.copy(color = Color.White, fontWeight = FontWeight.Bold)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Delete Task?") },
            text = { Text("Are you sure you want to delete \"${task.title}\"? This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmDialog = false
                        onDeleteTask(task.id)
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) {
                    Text("Delete", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel", color = NudgyCharcoal)
                }
            }
        )
    }
}

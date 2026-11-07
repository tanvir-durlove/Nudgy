package com.example.ui.sheets

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.Notifications
import java.util.Locale
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.data.model.Priority
import com.example.data.model.TaskCategory
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateTaskSheet(
    onDismiss: () -> Unit,
    onSaveTask: (
        title: String,
        description: String?,
        dueEpochMillis: Long,
        priority: Priority,
        category: TaskCategory,
        reminderSet: Boolean
    ) -> Unit,
    headerTitle: String = "New Task",
    headerSubtitle: String? = null,
    saveButtonText: String = "Add Task",
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf(Priority.MEDIUM) }
    var category by remember { mutableStateOf(TaskCategory.WORK) }
    var selectedDayOption by remember { mutableIntStateOf(0) } // Shortcuts: 0: Today, 1: Tomorrow, 2: In 2 Days (a picked date overrides these)
    var selectedTimeOption by remember { mutableIntStateOf(1) } // 0: 9 AM, 1: 2 PM, 2: 7 PM
    var customHour by remember { mutableIntStateOf(14) }
    var customMinute by remember { mutableIntStateOf(0) }
    var hasCustomTime by remember { mutableStateOf(false) }
    var reminderSet by remember { mutableStateOf(true) }
    var hasCustomDate by remember { mutableStateOf(false) }

    // C10: surface the notification-permission state in the reminder
    // toggle area — a denied permission must not masquerade as a working
    // reminder. Re-read on every composition of the sheet.
    val context = LocalContext.current
    val notificationsBlocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) != PackageManager.PERMISSION_GRANTED
    fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // No settings activity — the warning text still stands.
        }
    }
    var customDueDateMillis by remember { mutableStateOf<Long?>(null) }
    var showMoreOptions by remember { mutableStateOf(false) }

    val dueDateFormatter = remember { SimpleDateFormat("EEEE, MMM d", Locale.getDefault()) }

    // Effective due day: a picked date overrides the day-chip shortcuts.
    // The returned calendar carries the picked/chip day; callers set the
    // time-of-day fields from the time selection afterwards.
    fun effectiveDueCalendar(): Calendar {
        val cal = Calendar.getInstance()
        if (hasCustomDate && customDueDateMillis != null) {
            cal.timeInMillis = customDueDateMillis!!
        } else {
            cal.add(Calendar.DAY_OF_YEAR, selectedDayOption)
        }
        return cal
    }

    fun formatCustomTime(h: Int, m: Int): String {
        val amPm = if (h < 12) "AM" else "PM"
        val displayH = when {
            h == 0 -> 12
            h > 12 -> h - 12
            else -> h
        }
        return String.format(Locale.getDefault(), "%d:%02d %s", displayH, m, amPm)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = NudgyCard,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(24.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = headerTitle,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = NudgyCharcoal
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = NudgyCharcoal
                    )
                }
            }

            headerSubtitle?.let { subtitle ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = NudgyMuted
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            // Title input
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                placeholder = { Text("What needs to get done?", color = NudgyMuted) },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NudgyPrimary,
                    unfocusedBorderColor = NudgyFieldBorder
                ),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(18.dp))

            var showM3DatePicker by remember { mutableStateOf(false) }
            // Custom-date picker (shared by the calendar button and the banner row).
            val showDatePicker = { showM3DatePicker = true }
            if (showM3DatePicker) {
                val baseLocal = if (hasCustomDate && customDueDateMillis != null) {
                    Calendar.getInstance().apply { timeInMillis = customDueDateMillis!! }
                } else {
                    Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, selectedDayOption) }
                }
                val baseUtc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                    set(Calendar.YEAR, baseLocal.get(Calendar.YEAR))
                    set(Calendar.MONTH, baseLocal.get(Calendar.MONTH))
                    set(Calendar.DAY_OF_MONTH, baseLocal.get(Calendar.DAY_OF_MONTH))
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val datePickerState = rememberDatePickerState(initialSelectedDateMillis = baseUtc.timeInMillis)
                DatePickerDialog(
                    onDismissRequest = { showM3DatePicker = false },
                    confirmButton = {
                        TextButton(onClick = {
                            datePickerState.selectedDateMillis?.let { picked ->
                                val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = picked }
                                customDueDateMillis = Calendar.getInstance().apply {
                                    set(Calendar.YEAR, utc.get(Calendar.YEAR))
                                    set(Calendar.MONTH, utc.get(Calendar.MONTH))
                                    set(Calendar.DAY_OF_MONTH, utc.get(Calendar.DAY_OF_MONTH))
                                    set(Calendar.HOUR_OF_DAY, 0)
                                    set(Calendar.MINUTE, 0)
                                    set(Calendar.SECOND, 0)
                                    set(Calendar.MILLISECOND, 0)
                                }.timeInMillis
                                hasCustomDate = true
                            }
                            showM3DatePicker = false
                        }) { Text("OK") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showM3DatePicker = false }) { Text("Cancel") }
                    }
                ) {
                    DatePicker(state = datePickerState)
                }
            }

            // DUE DATE SELECTOR
            Text(
                text = "WHEN IS IT DUE?",
                style = MaterialTheme.typography.labelSmall,
                color = NudgyMuted
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val dayOptions = listOf("Today", "Tomorrow", "In 2 days")
                dayOptions.forEachIndexed { index, label ->
                    val isSelected = !hasCustomDate && selectedDayOption == index
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(CircleShape)
                            .background(if (isSelected) MaterialTheme.colorScheme.inverseSurface else NudgyChipBackground)
                            .clickable {
                                hasCustomDate = false
                                selectedDayOption = index
                            }
                            .height(48.dp)
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = if (isSelected) MaterialTheme.colorScheme.inverseOnSurface else NudgyCharcoal,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 13.sp
                        )
                    }
                }
                // Custom-date picker entry (shown only when no custom date is picked;
                // once picked, the banner row below shows it with Change).
                if (!hasCustomDate) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .border(1.dp, NudgyFieldBorder, CircleShape)
                            .clickable { showDatePicker() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.DateRange,
                            contentDescription = "Pick a specific date",
                            tint = NudgyCharcoal,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Pick a specific date banner — only when the picked date is NOT a chip option.
            if (hasCustomDate) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(NudgyPrimary.copy(alpha = 0.12f))
                    .border(1.dp, NudgyPrimary, MaterialTheme.shapes.medium)
                    .clickable { showDatePicker() }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.DateRange,
                        contentDescription = "Pick a due date",
                        tint = NudgyPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (customDueDateMillis != null) {
                            "Due date: ${dueDateFormatter.format(customDueDateMillis!!)}"
                        } else {
                            "Pick a specific date"
                        },
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = NudgyAccent
                        )
                    )
                }
                Text(
                    text = "Change",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = NudgyAccent
                )
            }
            }

            Spacer(modifier = Modifier.height(12.dp))

            var showM3TimePicker by remember { mutableStateOf(false) }
            // Custom-time picker (shared by the clock button and the banner row).
            val showTimePicker = { showM3TimePicker = true }
            if (showM3TimePicker) {
                val timePickerState = rememberTimePickerState(
                    initialHour = if (hasCustomTime) customHour else 14,
                    initialMinute = if (hasCustomTime) customMinute else 0,
                    is24Hour = false
                )
                AlertDialog(
                    onDismissRequest = { showM3TimePicker = false },
                    title = { Text("Pick time", fontWeight = FontWeight.Bold, color = NudgyCharcoal) },
                    text = { TimePicker(state = timePickerState) },
                    confirmButton = {
                        TextButton(onClick = {
                            customHour = timePickerState.hour
                            customMinute = timePickerState.minute
                            hasCustomTime = true
                            showM3TimePicker = false
                        }) { Text("OK") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showM3TimePicker = false }) { Text("Cancel") }
                    }
                )
            }

            // TIME OF DAY SELECTOR
            Text(
                text = "WHEN SHOULD IT BE DONE?",
                style = MaterialTheme.typography.labelSmall,
                color = NudgyMuted
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val timeOptions = listOf("Morning (9 AM)", "Afternoon (2 PM)", "Evening (7 PM)")
                timeOptions.forEachIndexed { index, label ->
                    val isSelected = !hasCustomTime && selectedTimeOption == index
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(CircleShape)
                            .background(if (isSelected) NudgyPrimary else NudgyChipBackground)
                            .clickable {
                                hasCustomTime = false
                                selectedTimeOption = index
                            }
                            .height(48.dp)
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = if (isSelected) Color.White else NudgyCharcoal,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 12.sp
                        )
                    }
                }
                // Custom-time picker entry (shown only when no custom time is picked).
                if (!hasCustomTime) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .border(1.dp, NudgyFieldBorder, CircleShape)
                            .clickable { showTimePicker() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = "Pick a custom time",
                            tint = NudgyCharcoal,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Custom Exact Time Picker Row — only when the picked time is NOT a chip time.
            if (hasCustomTime) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(NudgyPrimary.copy(alpha = 0.12f))
                    .border(1.dp, NudgyPrimary, MaterialTheme.shapes.medium)
                    .clickable { showTimePicker() }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = "Pick custom time",
                        tint = NudgyPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Custom Time: ${formatCustomTime(customHour, customMinute)}",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = NudgyAccent
                        )
                    )
                }
                Text(
                    text = "Change",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = NudgyAccent
                )
            }
            }

            // Past-time guard: "Today" + a time that has already passed would
            // silently kill the reminder, so warn here and roll to tomorrow.
            val previewCalendar = effectiveDueCalendar()
            val (previewHour, previewMinute) = if (hasCustomTime) {
                customHour to customMinute
            } else {
                when (selectedTimeOption) {
                    0 -> 9 to 0
                    1 -> 14 to 0
                    else -> 19 to 0
                }
            }
            previewCalendar.set(Calendar.HOUR_OF_DAY, previewHour)
            previewCalendar.set(Calendar.MINUTE, previewMinute)
            previewCalendar.set(Calendar.SECOND, 0)
            previewCalendar.set(Calendar.MILLISECOND, 0)
            val willRollToTomorrow = previewCalendar.timeInMillis <= System.currentTimeMillis()
            if (willRollToTomorrow) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "⚠ This time has already passed today — it will be scheduled for tomorrow at the same time.",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = NudgyPrimaryDark
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // MORE OPTIONS TOGGLE (collapsed by default)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { showMoreOptions = !showMoreOptions }
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "More options",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = NudgyCharcoal
                )
                Icon(
                    imageVector = if (showMoreOptions) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (showMoreOptions) "Hide more options" else "Show more options",
                    tint = NudgyPrimary
                )
            }

            if (showMoreOptions) {
                Spacer(modifier = Modifier.height(12.dp))

                // Description / Notes input
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    placeholder = { Text("Add notes or details (optional)...", color = NudgyMuted) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NudgyPrimary,
                        unfocusedBorderColor = NudgyFieldBorder
                    ),
                    maxLines = 3
                )

                Spacer(modifier = Modifier.height(18.dp))

                // PRIORITY SELECTOR
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

            // CATEGORY SELECTOR
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

            // REMINDER TOGGLE
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .background(NudgyCard)
                    .border(1.dp, NudgyCardBorder, MaterialTheme.shapes.large)
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
                            text = "Notification Nudge",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
                            color = NudgyCharcoal
                        )
                        Text(
                            // C10: when notifications are denied, say so
                            // here instead of promising an alert.
                            text = if (!reminderSet) "No notification"
                                else if (notificationsBlocked) "Notifications are off — no alert will appear"
                                else "Alert 2 hours before due time",
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

            // C10: the toggle must not masquerade as a working reminder
            // when the OS will drop every notification — offer the fix
            // right under the toggle.
            if (reminderSet && notificationsBlocked) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Reminders won't appear until notifications are on.",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                        color = NudgyPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { openNotificationSettings() }) {
                        Text(
                            text = "Open settings",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                            color = NudgyPrimary
                        )
                    }
                }
            }

            }

            Spacer(modifier = Modifier.height(24.dp))

            // Save Button
            Button(
                onClick = {
                    if (title.isNotBlank()) {
                        val calendar = effectiveDueCalendar()
                        val (finalHour, finalMinute) = if (hasCustomTime) {
                            customHour to customMinute
                        } else {
                            when (selectedTimeOption) {
                                0 -> 9 to 0
                                1 -> 14 to 0
                                else -> 19 to 0
                            }
                        }
                        calendar.set(Calendar.HOUR_OF_DAY, finalHour)
                        calendar.set(Calendar.MINUTE, finalMinute)
                        calendar.set(Calendar.SECOND, 0)
                        calendar.set(Calendar.MILLISECOND, 0)

                        // Past time chosen for today -> roll to tomorrow so
                        // the reminder can actually fire (the sheet warns
                        // about this next to the time picker).
                        if (calendar.timeInMillis <= System.currentTimeMillis()) {
                            calendar.add(Calendar.DAY_OF_YEAR, 1)
                        }

                        val computedDueTime = calendar.timeInMillis
                        val desc = if (description.isNotBlank()) description.trim() else null

                        onSaveTask(
                            title.trim(),
                            desc,
                            computedDueTime,
                            priority,
                            category,
                            reminderSet
                        )
                        onDismiss()
                    }
                },
                enabled = title.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = NudgyPrimary)
            ) {
                Text(
                    text = saveButtonText,
                    style = MaterialTheme.typography.titleMedium.copy(color = Color.White, fontWeight = FontWeight.Bold)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

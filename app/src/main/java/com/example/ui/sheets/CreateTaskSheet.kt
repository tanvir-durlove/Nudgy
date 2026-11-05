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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Priority
import com.example.data.model.TaskCategory
import com.example.ui.theme.NudgyCard
import com.example.ui.theme.NudgyCardBorder
import com.example.ui.theme.NudgyCharcoal
import com.example.ui.theme.NudgyMuted
import com.example.ui.theme.NudgyPrimary
import com.example.ui.theme.NudgyPrimaryDark
import java.text.SimpleDateFormat
import java.util.Calendar

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
    val context = LocalContext.current
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
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = null
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
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
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NudgyPrimary,
                    unfocusedBorderColor = NudgyCardBorder
                ),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(18.dp))

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
                            .clip(RoundedCornerShape(100.dp))
                            .background(if (isSelected) NudgyCharcoal else Color(0xFFF3EFE6))
                            .clickable {
                                hasCustomDate = false
                                selectedDayOption = index
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = if (isSelected) Color.White else NudgyCharcoal,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Pick a specific date (any date — the chips above are shortcuts)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (hasCustomDate) NudgyPrimary.copy(alpha = 0.12f) else Color(0xFFF3EFE6))
                    .border(1.dp, if (hasCustomDate) NudgyPrimary else NudgyCardBorder, RoundedCornerShape(14.dp))
                    .clickable {
                        val base = if (hasCustomDate && customDueDateMillis != null) {
                            Calendar.getInstance().apply { timeInMillis = customDueDateMillis!! }
                        } else {
                            Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, selectedDayOption) }
                        }
                        DatePickerDialog(
                            context,
                            { _, y, m, d ->
                                val picked = Calendar.getInstance().apply {
                                    set(Calendar.YEAR, y)
                                    set(Calendar.MONTH, m)
                                    set(Calendar.DAY_OF_MONTH, d)
                                    set(Calendar.HOUR_OF_DAY, 0)
                                    set(Calendar.MINUTE, 0)
                                    set(Calendar.SECOND, 0)
                                    set(Calendar.MILLISECOND, 0)
                                }
                                customDueDateMillis = picked.timeInMillis
                                hasCustomDate = true
                            },
                            base.get(Calendar.YEAR),
                            base.get(Calendar.MONTH),
                            base.get(Calendar.DAY_OF_MONTH)
                        ).show()
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.DateRange,
                        contentDescription = "Pick a due date",
                        tint = if (hasCustomDate) NudgyPrimary else NudgyCharcoal,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (hasCustomDate && customDueDateMillis != null) {
                            "Due date: ${dueDateFormatter.format(customDueDateMillis!!)}"
                        } else {
                            "Pick a specific date"
                        },
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = if (hasCustomDate) FontWeight.Bold else FontWeight.Normal,
                            color = if (hasCustomDate) NudgyPrimary else NudgyCharcoal
                        )
                    )
                }
                Text(
                    text = if (hasCustomDate) "Change" else "Pick",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = NudgyPrimary
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

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
                            .clip(RoundedCornerShape(100.dp))
                            .background(if (isSelected) NudgyPrimary else Color(0xFFF3EFE6))
                            .clickable {
                                hasCustomTime = false
                                selectedTimeOption = index
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = if (isSelected) Color.White else NudgyCharcoal,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Custom Exact Time Picker Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (hasCustomTime) NudgyPrimary.copy(alpha = 0.12f) else Color(0xFFF3EFE6))
                    .border(1.dp, if (hasCustomTime) NudgyPrimary else NudgyCardBorder, RoundedCornerShape(14.dp))
                    .clickable {
                        TimePickerDialog(
                            context,
                            { _, h, m ->
                                customHour = h
                                customMinute = m
                                hasCustomTime = true
                            },
                            if (hasCustomTime) customHour else 14,
                            if (hasCustomTime) customMinute else 0,
                            false
                        ).show()
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = "Pick custom time",
                        tint = if (hasCustomTime) NudgyPrimary else NudgyCharcoal,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (hasCustomTime) "Custom Time: ${formatCustomTime(customHour, customMinute)}" else "Set Exact Time (e.g. 10:30 AM)",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = if (hasCustomTime) FontWeight.Bold else FontWeight.Normal,
                            color = if (hasCustomTime) NudgyPrimary else NudgyCharcoal
                        )
                    )
                }
                Text(
                    text = if (hasCustomTime) "Change" else "Pick",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = NudgyPrimary
                )
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
                    .clip(RoundedCornerShape(14.dp))
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
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NudgyPrimary,
                        unfocusedBorderColor = NudgyCardBorder
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
                        Priority.HIGH -> Color(0xFFE85542)
                        Priority.MEDIUM -> Color(0xFFE67E22)
                        Priority.LOW -> Color(0xFF2E7D32)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(100.dp))
                            .background(if (isSelected) pColor else Color(0xFFF3EFE6))
                            .clickable { priority = p }
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
                            .clip(RoundedCornerShape(100.dp))
                            .background(if (isSelected) NudgyCharcoal else Color(0xFFF3EFE6))
                            .clickable { category = c }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = c.name.lowercase().replaceFirstChar { it.uppercase() },
                            color = if (isSelected) Color.White else NudgyCharcoal,
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
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFFFBF8F2))
                    .border(1.dp, NudgyCardBorder, RoundedCornerShape(16.dp))
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
                            text = if (reminderSet) "Alert at selected due time" else "No notification",
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
                shape = RoundedCornerShape(100.dp),
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

package com.example.ui.sheets

import android.app.TimePickerDialog
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.NudgyCard
import com.example.ui.theme.NudgyCardBorder
import com.example.ui.theme.NudgyCharcoal
import com.example.ui.theme.NudgyMuted
import com.example.ui.theme.NudgyPrimary
import java.util.Calendar
import java.util.Locale

/**
 * Create a schedule inside a group (creator only). Mirrors CreateTaskSheet's
 * day/time option model so group schedules behave like personal ones.
 *
 * Note: schedule EDIT is intentionally not in v1 — the creator deletes and
 * re-creates a schedule to change it (a date picker is needed for a correct
 * edit flow; that lands in Phase 3). Task edit is fully supported via
 * EditTaskSheet reuse in GroupsScreen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupScheduleSheet(
    onDismiss: () -> Unit,
    onSaveSchedule: (
        title: String,
        startEpochMillis: Long,
        endEpochMillis: Long,
        locationOrLink: String?,
        eventType: String
    ) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var title by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf("EVENT") }
    var selectedDayOption by remember { mutableIntStateOf(0) } // 0: Today, 1: Tomorrow, 2: In 2 days
    var selectedTimeOption by remember { mutableIntStateOf(1) } // 0: 9 AM, 1: 2 PM, 2: 7 PM
    var hasCustomTime by remember { mutableStateOf(false) }
    var customHour by remember { mutableIntStateOf(14) }
    var customMinute by remember { mutableIntStateOf(0) }
    var selectedDurationOption by remember { mutableIntStateOf(1) } // 0: 30 min, 1: 1 hour, 2: 2 hours

    val dayOptions = listOf("Today", "Tomorrow", "In 2 days")
    val timeOptions = listOf("9:00 AM", "2:00 PM", "7:00 PM")
    val durationOptions = listOf("30 min", "1 hour", "2 hours")
    val typeOptions = listOf("EVENT", "MEETING", "FOCUS_TIME")

    fun formatCustomTime(hour: Int, minute: Int): String {
        val amPm = if (hour < 12) "AM" else "PM"
        val displayHour = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        return String.format(Locale.getDefault(), "%d:%02d %s", displayHour, minute, amPm)
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
                .padding(horizontal = 24.dp, vertical = 24.dp)
        ) {
            Text(
                text = "Add a group schedule",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = NudgyCharcoal
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Every member sees this in their own schedule list.",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                color = NudgyMuted
            )
            Spacer(modifier = Modifier.height(20.dp))

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                placeholder = { Text("e.g. Class test review") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NudgyPrimary,
                    unfocusedBorderColor = NudgyMuted.copy(alpha = 0.4f)
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = location,
                onValueChange = { location = it },
                label = { Text("Location or link (optional)") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NudgyPrimary,
                    unfocusedBorderColor = NudgyMuted.copy(alpha = 0.4f)
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = "TYPE",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = NudgyMuted
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                typeOptions.forEach { type ->
                    val isSelected = selectedType == type
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(100.dp))
                            .background(
                                if (isSelected) NudgyPrimary else Color.Transparent
                            )
                            .border(
                                1.dp,
                                if (isSelected) NudgyPrimary else NudgyCardBorder,
                                RoundedCornerShape(100.dp)
                            )
                            .clickable { selectedType = type }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = type.replace("_", " ").lowercase()
                                .replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold, fontSize = 13.sp
                            ),
                            color = if (isSelected) Color.White else NudgyCharcoal
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = "DAY",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = NudgyMuted
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                dayOptions.forEachIndexed { index, label ->
                    val isSelected = selectedDayOption == index
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(100.dp))
                            .background(if (isSelected) NudgyPrimary else Color.Transparent)
                            .border(
                                1.dp,
                                if (isSelected) NudgyPrimary else NudgyCardBorder,
                                RoundedCornerShape(100.dp)
                            )
                            .clickable { selectedDayOption = index }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold, fontSize = 13.sp
                            ),
                            color = if (isSelected) Color.White else NudgyCharcoal
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = "START TIME",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = NudgyMuted
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                timeOptions.forEachIndexed { index, label ->
                    val isSelected = !hasCustomTime && selectedTimeOption == index
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(100.dp))
                            .background(if (isSelected) NudgyPrimary else Color.Transparent)
                            .border(
                                1.dp,
                                if (isSelected) NudgyPrimary else NudgyCardBorder,
                                RoundedCornerShape(100.dp)
                            )
                            .clickable { hasCustomTime = false; selectedTimeOption = index }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold, fontSize = 13.sp
                            ),
                            color = if (isSelected) Color.White else NudgyCharcoal
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, NudgyCardBorder, RoundedCornerShape(12.dp))
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
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = null,
                    tint = NudgyPrimary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (hasCustomTime) "Custom Time: ${formatCustomTime(customHour, customMinute)}"
                        else "Set Exact Time (e.g. 10:30 AM)",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = NudgyCharcoal,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (hasCustomTime) "Change" else "Pick",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = NudgyPrimary
                )
            }

            // Past-time guard: same rule as CreateTaskSheet — roll to tomorrow.
            val previewCalendar = Calendar.getInstance()
            previewCalendar.add(Calendar.DAY_OF_YEAR, selectedDayOption)
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
            val willRollToTomorrow = selectedDayOption == 0 &&
                previewCalendar.timeInMillis <= System.currentTimeMillis()
            if (willRollToTomorrow) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "⚠ This time has already passed today — it will be scheduled for tomorrow at the same time.",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = NudgyPrimary
                )
            }

            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = "DURATION",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = NudgyMuted
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                durationOptions.forEachIndexed { index, label ->
                    val isSelected = selectedDurationOption == index
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(100.dp))
                            .background(if (isSelected) NudgyPrimary else Color.Transparent)
                            .border(
                                1.dp,
                                if (isSelected) NudgyPrimary else NudgyCardBorder,
                                RoundedCornerShape(100.dp)
                            )
                            .clickable { selectedDurationOption = index }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold, fontSize = 13.sp
                            ),
                            color = if (isSelected) Color.White else NudgyCharcoal
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = {
                    if (title.isBlank()) return@Button
                    val startCalendar = Calendar.getInstance()
                    var dayOffset = selectedDayOption
                    if (dayOffset == 0) {
                        startCalendar.set(Calendar.HOUR_OF_DAY, previewHour)
                        startCalendar.set(Calendar.MINUTE, previewMinute)
                        startCalendar.set(Calendar.SECOND, 0)
                        startCalendar.set(Calendar.MILLISECOND, 0)
                        if (startCalendar.timeInMillis <= System.currentTimeMillis()) {
                            dayOffset = 1
                        }
                    }
                    startCalendar.add(Calendar.DAY_OF_YEAR, dayOffset)
                    startCalendar.set(Calendar.HOUR_OF_DAY, previewHour)
                    startCalendar.set(Calendar.MINUTE, previewMinute)
                    startCalendar.set(Calendar.SECOND, 0)
                    startCalendar.set(Calendar.MILLISECOND, 0)
                    val durationMillis = when (selectedDurationOption) {
                        0 -> 30 * 60 * 1000L
                        2 -> 2 * 60 * 60 * 1000L
                        else -> 60 * 60 * 1000L
                    }
                    onSaveSchedule(
                        title.trim(),
                        startCalendar.timeInMillis,
                        startCalendar.timeInMillis + durationMillis,
                        location.trim().ifBlank { null },
                        selectedType
                    )
                },
                enabled = title.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = NudgyPrimary),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Post to group",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

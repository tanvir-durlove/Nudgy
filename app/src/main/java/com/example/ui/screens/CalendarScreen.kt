package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.local.entity.TaskEntity
import com.example.data.model.Priority
import com.example.data.local.entity.CalendarEventEntity
import com.example.ui.theme.NudgyAccent
import com.example.ui.theme.NudgyCard
import com.example.ui.theme.NudgyCardBorder
import com.example.ui.theme.NudgyCharcoal
import com.example.ui.theme.NudgyChipBackground
import com.example.ui.theme.NudgyFieldBorder
import com.example.ui.theme.NudgyGreen
import com.example.ui.theme.NudgyLavender
import com.example.ui.theme.NudgyMuted
import com.example.ui.theme.NudgyPeach
import com.example.ui.theme.NudgyPrimary
import com.example.ui.theme.NudgySage
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class DynamicDayItem(
    val dayOfWeek: String,
    val dayOfMonth: Int,
    val epochMillis: Long,
    val isToday: Boolean
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun CalendarScreen(
    events: List<CalendarEventEntity>,
    tasks: List<TaskEntity> = emptyList(),
    isCalendarConnected: Boolean = false,
    onConnectGoogleCalendar: () -> Unit = {},
    onSyncNow: () -> Unit = {},
    onBlockFocusTime: (startTime: Long, endTime: Long, title: String) -> Unit,
    onAddEvent: (title: String, startTime: Long, endTime: Long, location: String?, type: String) -> Unit = { _, _, _, _, _ -> },
    onDeleteEvent: (eventId: String) -> Unit = {},
    onSelectDay: (Long) -> Unit = {},
    groupNames: Map<String, String> = emptyMap(),
    modifier: Modifier = Modifier
) {
    var showAddEventDialog by remember { mutableStateOf(false) }
    var eventPendingDelete by remember { mutableStateOf<CalendarEventEntity?>(null) }
    var isBlockingTime by remember { mutableStateOf(false) }

    LaunchedEffect(events) {
        isBlockingTime = false
    }

    val timeFormatter = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val monthFormatter = remember { SimpleDateFormat("MMMM yyyy", Locale.getDefault()) }

    // Dynamically compute the 7 days of the shown week, keyed by day of
    // year so midnight updates cleanly; weekOffset shifts whole weeks so
    // events beyond the current week can be created (week nav buttons).
    val currentDayOfYear = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
    var weekOffset by remember { mutableIntStateOf(0) }
    val (_, daysList) = remember(currentDayOfYear, weekOffset) {
        val cal = Calendar.getInstance()
        val monthStr = monthFormatter.format(cal.time).uppercase()
        val dayOfWeekToday = cal.get(Calendar.DAY_OF_WEEK)
        val diffToMonday = if (dayOfWeekToday == Calendar.SUNDAY) -6 else Calendar.MONDAY - dayOfWeekToday
        cal.add(Calendar.DAY_OF_MONTH, diffToMonday)
        cal.add(Calendar.DAY_OF_MONTH, weekOffset * 7)

        val days = mutableListOf<DynamicDayItem>()
        val dayFormat = SimpleDateFormat("EEE", Locale.getDefault())
        val todayCal = Calendar.getInstance()

        for (i in 0 until 7) {
            val isToday = cal.get(Calendar.YEAR) == todayCal.get(Calendar.YEAR) &&
                    cal.get(Calendar.DAY_OF_YEAR) == todayCal.get(Calendar.DAY_OF_YEAR)
            days.add(
                DynamicDayItem(
                    dayOfWeek = dayFormat.format(cal.time),
                    dayOfMonth = cal.get(Calendar.DAY_OF_MONTH),
                    epochMillis = cal.timeInMillis,
                    isToday = isToday
                )
            )
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        Pair(monthStr, days)
    }

    val todayIndex = daysList.indexOfFirst { it.isToday }.coerceAtLeast(0)
    var selectedDayIndex by remember { mutableIntStateOf(todayIndex) }

    val selectedDayMillis = daysList.getOrNull(selectedDayIndex)?.epochMillis ?: System.currentTimeMillis()
    val headerMonth = remember(selectedDayMillis) {
        val cal = Calendar.getInstance().apply { timeInMillis = selectedDayMillis }
        monthFormatter.format(cal.time).uppercase()
    }

    val displayEvents = remember(events, selectedDayMillis) {
        val (startOfDay, endOfDay) = run {
            val cal = Calendar.getInstance().apply { timeInMillis = selectedDayMillis }
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val start = cal.timeInMillis
            cal.set(Calendar.HOUR_OF_DAY, 23)
            cal.set(Calendar.MINUTE, 59)
            cal.set(Calendar.SECOND, 59)
            cal.set(Calendar.MILLISECOND, 999)
            val end = cal.timeInMillis
            start to end
        }

        // Calendar events only — tasks are never projected into the
        // schedule; they live in the Tasks tab.
        val list = events.filter {
            (it.startEpochMillis in startOfDay..endOfDay) ||
            (it.startEpochMillis <= endOfDay && it.endEpochMillis >= startOfDay)
        }.toMutableList()
        list.sortedBy { it.startEpochMillis }
    }

    val suggestedSlot = remember(displayEvents, tasks, selectedDayIndex) {
        val baseCal = Calendar.getInstance().apply { timeInMillis = selectedDayMillis }
        // Busy timeline for slot-finding = events + timed tasks (due + 30m).
        // Tasks stay off the visible schedule, but a suggested focus slot
        // must never collide with one.
        val dayStart = (baseCal.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val dayEnd = dayStart + 24 * 60 * 60 * 1000L - 1
        val taskBlocks = tasks.filter { it.dueDateEpochMillis in dayStart..dayEnd && !it.isCompleted }
            .map { ev -> CalendarEventEntity(
                id = "busy_${ev.id}", userId = ev.userId, title = ev.title,
                startEpochMillis = ev.dueDateEpochMillis,
                endEpochMillis = ev.dueDateEpochMillis + 30 * 60 * 1000L,
                locationOrLink = null, eventType = "TASK"
            ) }
        val sortedEvents = (displayEvents + taskBlocks).sortedBy { it.startEpochMillis }
        val now = System.currentTimeMillis()
        val isToday = daysList.getOrNull(selectedDayIndex)?.isToday == true

        if (sortedEvents.isEmpty()) {
            val startMillis = if (isToday) {
                val cal = Calendar.getInstance().apply { timeInMillis = now + 10 * 60 * 1000L }
                val m = cal.get(Calendar.MINUTE)
                val rounded = if (m < 30) 30 else 0
                if (rounded == 0) cal.add(Calendar.HOUR_OF_DAY, 1)
                cal.set(Calendar.MINUTE, rounded)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis
            } else {
                val cal = (baseCal.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, 14)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                cal.timeInMillis
            }
            val endMillis = startMillis + 45 * 60 * 1000L
            Triple(startMillis, endMillis, 45)
        } else {
            var foundStart = 0L
            var foundEnd = 0L
            var foundDuration = 0

            // Check gap after first meeting or between meetings
            for (i in 0 until sortedEvents.size - 1) {
                val currentEnd = sortedEvents[i].endEpochMillis
                val nextStart = sortedEvents[i + 1].startEpochMillis
                val candidateStart = currentEnd + (5 * 60 * 1000L) // 5 min buffer
                if (isToday && candidateStart < now) continue

                val gapMinutes = ((nextStart - candidateStart) / (60 * 1000L)).toInt()
                if (gapMinutes >= 30) {
                    foundStart = candidateStart
                    val dur = minOf(gapMinutes, 45)
                    foundEnd = foundStart + (dur * 60 * 1000L)
                    foundDuration = dur
                    break
                }
            }

            if (foundDuration == 0) {
                val lastEventEnd = sortedEvents.last().endEpochMillis
                val candidateStart = maxOf(lastEventEnd + (15 * 60 * 1000L), if (isToday) now + 10 * 60 * 1000L else 0L)
                foundStart = candidateStart
                foundDuration = 45
                foundEnd = foundStart + (45 * 60 * 1000L)
            }

            Triple(foundStart, foundEnd, foundDuration)
        }
    }

    val suggestedStart = suggestedSlot.first
    val suggestedEnd = suggestedSlot.second
    val suggestedDuration = suggestedSlot.third

    val hasBlockedSuggestedTime = remember(events, suggestedStart, suggestedEnd) {
        events.any { event ->
            event.startEpochMillis < suggestedEnd && event.endEpochMillis > suggestedStart
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = headerMonth,
                    style = MaterialTheme.typography.labelSmall,
                    color = NudgyPrimary
                )
                val isSelectedDayToday = daysList.getOrNull(selectedDayIndex)?.isToday == true
                val scheduleTitle = if (isSelectedDayToday) {
                    "Today’s schedule"
                } else {
                    "${daysList.getOrNull(selectedDayIndex)?.dayOfWeek ?: "Day"}’s schedule"
                }
                Text(
                    text = scheduleTitle,
                    style = MaterialTheme.typography.displayMedium,
                    color = NudgyCharcoal
                )
            }

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.inverseSurface)
                    .clickable { showAddEventDialog = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add event",
                    tint = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // Horizontal Weekly Date Selector (with prev/next week controls)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable { weekOffset-- },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ChevronLeft,
                    contentDescription = "Previous week",
                    tint = NudgyCharcoal,
                    modifier = Modifier.size(22.dp)
                )
            }
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                daysList.forEachIndexed { index, day ->
                    val isSelected = index == selectedDayIndex

                    Column(
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.large)
                            .background(if (isSelected) MaterialTheme.colorScheme.inverseSurface else Color.Transparent)
                            .clickable {
                                selectedDayIndex = index
                                onSelectDay(day.epochMillis)
                            }
                            .padding(horizontal = 10.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = day.dayOfWeek,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 12.sp,
                                color = if (isSelected) MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.8f) else NudgyMuted
                            )
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "${day.dayOfMonth}",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) MaterialTheme.colorScheme.inverseOnSurface else NudgyCharcoal
                            )
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable { weekOffset++ },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "Next week",
                    tint = NudgyCharcoal,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // Google Calendar Connect & Sync Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = if (isCalendarConnected) NudgySage else NudgyCard,
            border = androidx.compose.foundation.BorderStroke(1.dp, if (isCalendarConnected) Color(0xFFA5D6A7) else NudgyCardBorder)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .border(1.dp, if (isCalendarConnected) Color(0xFFA5D6A7) else NudgyCardBorder, MaterialTheme.shapes.medium)
                        .background(if (isCalendarConnected) NudgyCard else Color.Transparent),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_google_logo),
                        contentDescription = "Google Calendar",
                        tint = Color.Unspecified,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isCalendarConnected) "Calendar synced" else "Bring your calendar in",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = NudgyCharcoal
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (isCalendarConnected) "Events from your device calendar stay in sync." else "Allow calendar access to spot conflicts and find open time.",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                        color = NudgyMuted
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                if (isCalendarConnected) {
                    OutlinedButton(
                        onClick = onSyncNow,
                        shape = CircleShape,
                        modifier = Modifier.height(48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NudgyGreen),
                        border = androidx.compose.foundation.BorderStroke(1.dp, NudgyGreen)
                    ) {
                        Text(
                            text = "Sync now",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        )
                    }
                } else {
                    Button(
                        onClick = onConnectGoogleCalendar,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = NudgyPrimary),
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text(
                            text = "Allow access",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        )
                    }
                }
            }
        }

        // Schedule Timeline Section
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = NudgyCard,
            border = androidx.compose.foundation.BorderStroke(1.dp, NudgyCardBorder)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "UP NEXT",
                            style = MaterialTheme.typography.labelSmall,
                            color = NudgyPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Schedule · ${displayEvents.size} items",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = NudgyCharcoal
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(MaterialTheme.shapes.small)
                            .border(1.dp, NudgyCardBorder, MaterialTheme.shapes.small),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CalendarMonth,
                            contentDescription = null,
                            tint = NudgyCharcoal,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                if (displayEvents.isEmpty()) {
                    Text(
                        text = "No items scheduled for this day.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NudgyMuted,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    // Vertical Timeline Cards
                    displayEvents.forEach { event ->
                        val isMeeting = event.eventType == "MEETING"
                        val cardBg = if (isMeeting) NudgyPeach else NudgyLavender
                        val dotColor = if (isMeeting) NudgyPrimary else Color(0xFF7A68E6)

                        val startTimeStr = timeFormatter.format(event.startEpochMillis)
                        val endTimeStr = timeFormatter.format(event.endEpochMillis)

                        Row(
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.width(52.dp)
                            ) {
                                Text(
                                    text = startTimeStr,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                                    color = NudgyMuted
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .border(2.dp, dotColor, CircleShape)
                                        .background(Color.White)
                                )
                            }

                            Surface(
                                modifier = Modifier.weight(1f),
                                shape = MaterialTheme.shapes.large,
                                color = cardBg
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "$startTimeStr – $endTimeStr",
                                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                                            color = NudgyMuted
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = event.title,
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 15.sp
                                            ),
                                            color = NudgyCharcoal
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            val locationText = event.locationOrLink?.takeIf { it.isNotBlank() }
                                            if (locationText != null) {
                                                Text(
                                                    text = locationText,
                                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                                                    color = NudgyMuted
                                                )
                                            }
                                            if (event.isFromGoogleCalendar) {
                                                if (locationText != null) {
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                }
                                                Text(
                                                    text = "· Google Calendar",
                                                    style = MaterialTheme.typography.bodyMedium.copy(
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = Color(0xFF2E7D32)
                                                    )
                                                )
                                            }
                                        }
                                        // Group v1: origin badge for group schedules.
                                        if (event.groupId != null) {
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Box(
                                                modifier = Modifier
                                                    .clip(CircleShape)
                                                    .background(NudgyPrimary.copy(alpha = 0.12f))
                                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = groupNames[event.groupId] ?: "Group",
                                                    style = MaterialTheme.typography.bodyMedium.copy(
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    ),
                                                    color = NudgyPrimary
                                                )
                                            }
                                        }
                                    }

                                    // Group v1: members never delete a group schedule
                                    // from here — only the creator, from the Groups
                                    // tab, can delete the shared item.
                                    if (event.groupId == null) {
                                        IconButton(
                                            onClick = { eventPendingDelete = event },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.DeleteOutline,
                                                contentDescription = "Delete event",
                                                tint = NudgyMuted,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Suggested Focus Time Card (Figma Screen 2)
        val (suggestedStart, suggestedEnd, suggestedDuration) = suggestedSlot
        val suggestedStartStr = timeFormatter.format(suggestedStart)
        val suggestedEndStr = timeFormatter.format(suggestedEnd)

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = NudgyCard,
            border = androidx.compose.foundation.BorderStroke(1.dp, NudgyCardBorder)
        ) {
            Row(
                modifier = Modifier.padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "SUGGESTED FOCUS TIME",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF2E7D32)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (hasBlockedSuggestedTime) "Blocked: $suggestedStartStr – $suggestedEndStr" else "$suggestedStartStr – $suggestedEndStr",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = NudgyCharcoal
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (hasBlockedSuggestedTime) "Focus time added to your schedule" else "$suggestedDuration minutes free in your schedule",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                        color = NudgyMuted
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Button(
                    onClick = {
                        if (!isBlockingTime && !hasBlockedSuggestedTime) {
                            isBlockingTime = true
                            onBlockFocusTime(suggestedStart, suggestedEnd, "Deep focus session")
                        }
                    },
                    enabled = !hasBlockedSuggestedTime && !isBlockingTime,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (hasBlockedSuggestedTime) Color(0xFFE0E0E0) else NudgyPrimary
                    ),
                    modifier = Modifier.height(48.dp)
                ) {
                    Text(
                        text = if (hasBlockedSuggestedTime) "Blocked" else "Block time",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = if (hasBlockedSuggestedTime) NudgyMuted else Color.White
                        )
                    )
                }
            }
        }

        // (No bottom clearance spacer: the floating mascot button is gone;
        // Scaffold innerPadding + navigationBarsPadding handle the nav bar.)
    }

        // Delete confirmation: one tap used to delete instantly — and for a
        // repeating event the repository deletes by the series-level Google
        // id, so a single occurrence tap wiped the whole series from Google
        // Calendar. Always confirm, and say plainly when a series is involved.
        eventPendingDelete?.let { pending ->
            val isSeries = pending.googleCalendarEventId != null &&
                events.count { it.googleCalendarEventId == pending.googleCalendarEventId } > 1
            AlertDialog(
                onDismissRequest = { eventPendingDelete = null },
                title = { Text("Delete this event?", fontWeight = FontWeight.Bold, color = NudgyCharcoal) },
                text = {
                    Text(
                        text = when {
                            isSeries -> "\"${pending.title}\" is one occurrence of a repeating series. Deleting it removes the ENTIRE series, including from Google Calendar."
                            pending.googleCalendarEventId != null -> "\"${pending.title}\" will be deleted, including from Google Calendar."
                            else -> "\"${pending.title}\" will be deleted."
                        },
                        color = NudgyCharcoal
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        onDeleteEvent(pending.id)
                        eventPendingDelete = null
                    }) {
                        Text("Delete", color = Color(0xFFE85542), fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { eventPendingDelete = null }) {
                        Text("Cancel", color = NudgyMuted)
                    }
                }
            )
        }


    if (showAddEventDialog) {
        var eventTitle by remember { mutableStateOf("") }
        var eventLocation by remember { mutableStateOf("") }
        var selectedDurationMinutes by remember { mutableIntStateOf(30) }
        var eventType by remember { mutableStateOf("MEETING") }

        // Start time defaults: Current hour + 1 rounded to :00, or 10:00 AM
        val now = Calendar.getInstance()
        val defaultHour = now.get(Calendar.HOUR_OF_DAY).let { if (it in 7..21) (it + 1) % 24 else 10 }
        var selectedHour by remember { mutableIntStateOf(defaultHour) }
        var selectedMinute by remember { mutableIntStateOf(0) }
        var showTimePicker by remember { mutableStateOf(false) }

        fun formatTimeStr(h: Int, m: Int): String {
            val amPm = if (h < 12) "AM" else "PM"
            val displayH = when {
                h == 0 -> 12
                h > 12 -> h - 12
                else -> h
            }
            return String.format(Locale.getDefault(), "%d:%02d %s", displayH, m, amPm)
        }

        val totalEndMins = selectedHour * 60 + selectedMinute + selectedDurationMinutes
        val endHour = (totalEndMins / 60) % 24
        val endMinute = totalEndMins % 60
        val scheduleSummary = "${formatTimeStr(selectedHour, selectedMinute)} – ${formatTimeStr(endHour, endMinute)} ($selectedDurationMinutes min)"

        AlertDialog(
            onDismissRequest = { showAddEventDialog = false },
            title = { Text("Schedule Event", fontWeight = FontWeight.Bold, color = NudgyCharcoal) },
            text = {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .imePadding(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = eventTitle,
                        onValueChange = { eventTitle = it },
                        label = { Text("Event Name") },
                        placeholder = { Text("e.g. Design review") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NudgyPrimary,
                            unfocusedBorderColor = NudgyFieldBorder
                        ),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = eventLocation,
                        onValueChange = { eventLocation = it },
                        label = { Text("Location / Link") },
                        placeholder = { Text("Google Meet or Room 4B") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NudgyPrimary,
                            unfocusedBorderColor = NudgyFieldBorder
                        ),
                        singleLine = true
                    )

                    // START TIME SELECTOR
                    Text("START TIME", style = MaterialTheme.typography.labelSmall, color = NudgyMuted)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .background(NudgyChipBackground)
                            .border(1.dp, NudgyCardBorder, MaterialTheme.shapes.medium)
                            .clickable { showTimePicker = true }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = "Pick Time",
                                tint = NudgyPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = formatTimeStr(selectedHour, selectedMinute),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = NudgyCharcoal
                            )
                        }
                        Text(
                            text = "Tap to pick",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = NudgyAccent
                        )
                    }

                    // Quick Time Preset Chips
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            "9:00 AM" to (9 to 0),
                            "11:00 AM" to (11 to 0),
                            "2:00 PM" to (14 to 0),
                            "4:00 PM" to (16 to 0)
                        ).forEach { (label, pair) ->
                            val isSelected = selectedHour == pair.first && selectedMinute == pair.second
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(CircleShape)
                                    .background(if (isSelected) NudgyPrimary else NudgyChipBackground)
                                    .clickable {
                                        selectedHour = pair.first
                                        selectedMinute = pair.second
                                    }
                                    .height(48.dp)
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 12.sp,
                                    color = if (isSelected) Color.White else NudgyCharcoal,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }

                    Text("DURATION", style = MaterialTheme.typography.labelSmall, color = NudgyMuted)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(15, 30, 45, 60).forEach { mins ->
                            val isSelected = selectedDurationMinutes == mins
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(CircleShape)
                                    .background(if (isSelected) NudgyPrimary else NudgyChipBackground)
                                    .clickable { selectedDurationMinutes = mins }
                                    .height(48.dp)
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "$mins min",
                                    color = if (isSelected) Color.White else NudgyCharcoal,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    // Schedule Time Slot Summary
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = NudgyPrimary.copy(alpha = 0.08f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Slot: $scheduleSummary",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                color = NudgyCharcoal
                            )
                        }
                    }

                    Text("TYPE", style = MaterialTheme.typography.labelSmall, color = NudgyMuted)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("MEETING" to "Meeting", "FOCUS_TIME" to "Focus Time").forEach { (typeKey, typeLabel) ->
                            val isSelected = eventType == typeKey
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(CircleShape)
                                    .background(if (isSelected) MaterialTheme.colorScheme.inverseSurface else NudgyChipBackground)
                                    .clickable { eventType = typeKey }
                                    .height(48.dp)
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = typeLabel,
                                    color = if (isSelected) MaterialTheme.colorScheme.inverseOnSurface else NudgyCharcoal,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (eventTitle.isNotBlank()) {
                            val selectedEpoch = daysList.getOrNull(selectedDayIndex)?.epochMillis ?: System.currentTimeMillis()
                            val eventStartCal = Calendar.getInstance().apply {
                                timeInMillis = selectedEpoch
                                set(Calendar.HOUR_OF_DAY, selectedHour)
                                set(Calendar.MINUTE, selectedMinute)
                                set(Calendar.SECOND, 0)
                                set(Calendar.MILLISECOND, 0)
                            }
                            val startMillis = eventStartCal.timeInMillis
                            val endMillis = startMillis + (selectedDurationMinutes * 60 * 1000L)

                            onAddEvent(
                                eventTitle.trim(),
                                startMillis,
                                endMillis,
                                eventLocation.trim().ifEmpty { null },
                                eventType
                            )
                            showAddEventDialog = false
                        }
                    },
                    enabled = eventTitle.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = NudgyPrimary)
                ) {
                    Text("Add", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddEventDialog = false }) {
                    Text("Cancel", color = NudgyCharcoal)
                }
            }
        )

        if (showTimePicker) {
            val timePickerState = rememberTimePickerState(
                initialHour = selectedHour,
                initialMinute = selectedMinute,
                is24Hour = false
            )
            AlertDialog(
                onDismissRequest = { showTimePicker = false },
                title = { Text("Pick start time", fontWeight = FontWeight.Bold, color = NudgyCharcoal) },
                text = { TimePicker(state = timePickerState) },
                confirmButton = {
                    TextButton(onClick = {
                        selectedHour = timePickerState.hour
                        selectedMinute = timePickerState.minute
                        showTimePicker = false
                    }) { Text("OK", color = NudgyPrimary) }
                },
                dismissButton = {
                    TextButton(onClick = { showTimePicker = false }) { Text("Cancel", color = NudgyCharcoal) }
                }
            )
        }
    }
}

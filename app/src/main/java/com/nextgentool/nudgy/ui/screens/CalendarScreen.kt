package com.nextgentool.nudgy.ui.screens

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
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.data.local.entity.CalendarEventEntity
import com.nextgentool.nudgy.data.repository.FocusTimeSlot
import com.nextgentool.nudgy.ui.theme.NudgyAccent
import com.nextgentool.nudgy.ui.theme.NudgyCard
import com.nextgentool.nudgy.ui.theme.NudgyCardBorder
import com.nextgentool.nudgy.ui.theme.NudgyCharcoal
import com.nextgentool.nudgy.ui.theme.NudgyChipBackground
import com.nextgentool.nudgy.ui.theme.NudgyFieldBorder
import com.nextgentool.nudgy.ui.theme.NudgyGreen
import com.nextgentool.nudgy.ui.theme.NudgyLavender
import com.nextgentool.nudgy.ui.theme.NudgyMuted
import com.nextgentool.nudgy.ui.theme.NudgyPeach
import com.nextgentool.nudgy.ui.theme.NudgyPrimary
import com.nextgentool.nudgy.ui.theme.NudgySage
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
    // Sprint 2-1: the suggested slot comes from the ViewModel (repository
    // rule, 35-min gap) — the screen no longer computes it, and the
    // `tasks` parameter it was computed from is gone.
    suggestedSlot: FocusTimeSlot? = null,
    isCalendarConnected: Boolean = false,
    isSyncing: Boolean = false,
    errorMessage: String? = null,
    onDismissError: () -> Unit = {},
    onConnectGoogleCalendar: () -> Unit = {},
    onSyncNow: () -> Unit = {},
    onBlockFocusTime: (startTime: Long, endTime: Long, title: String) -> Unit = { _, _, _ -> },
    onAddEvent: (title: String, startTime: Long, endTime: Long, location: String?, type: String) -> Unit = { _, _, _, _, _ -> },
    // Sprint 2-1 (Q8(a)): edit an event — same dialog as add, prefilled.
    onUpdateEvent: (eventId: String, title: String, startTime: Long, endTime: Long, location: String?, type: String) -> Unit = { _, _, _, _, _, _ -> },
    onDeleteEvent: (eventId: String) -> Unit = {},
    onSelectDay: (Long) -> Unit = {},
    groupNames: Map<String, String> = emptyMap(),
    modifier: Modifier = Modifier
) {
    var showAddEventDialog by remember { mutableStateOf(false) }
    var eventPendingDelete by remember { mutableStateOf<CalendarEventEntity?>(null) }
    var eventPendingEdit by remember { mutableStateOf<CalendarEventEntity?>(null) }
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

    // Sprint 2-1: the suggested slot arrives from the ViewModel, which
    // uses the repository rule (35-min minimum gap). The screen's old
    // inline calculation (30-min gap) disagreed with it and is deleted.
    // A null slot means the selected day has no free slot — the card
    // below is hidden then.
    val suggestedStart = suggestedSlot?.startEpochMillis ?: 0L
    val suggestedEnd = suggestedSlot?.endEpochMillis ?: 0L
    val suggestedDuration = suggestedSlot?.durationMinutes ?: 0

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
        // Sprint 2-1: sync / edit failures are shown, not swallowed.
        if (errorMessage != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = Color(0xFFFDECEA),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE57373))
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NudgyCharcoal,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onDismissError) {
                        Text("Dismiss", color = NudgyAccent, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

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
                    color = NudgyAccent
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
                        enabled = !isSyncing,
                        shape = CircleShape,
                        modifier = Modifier.height(48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NudgyGreen),
                        border = androidx.compose.foundation.BorderStroke(1.dp, NudgyGreen)
                    ) {
                        Text(
                            text = if (isSyncing) "Syncing…" else "Sync now",
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
                            color = NudgyAccent
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
                                modifier = Modifier
                                    .weight(1f)
                                    .then(
                                        // Sprint 2-1 (Q8(a)): tap a personal
                                        // event to edit it. Group rows are
                                        // never edited from here (same rule
                                        // as delete).
                                        if (event.groupId == null) Modifier.clickable { eventPendingEdit = event }
                                        else Modifier
                                    ),
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
                                                    color = NudgyAccent
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

        // Suggested Focus Time Card (Figma Screen 2) — hidden when the
        // selected day has no free slot (suggestedSlot is null).
        if (suggestedSlot != null) {
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


    if (showAddEventDialog || eventPendingEdit != null) {
        // Sprint 2-1 (Q8(a)): when opened from an event card, the dialog
        // is an EDIT — prefilled from the event (title / location /
        // duration / type / start time on the event's own day; the date
        // itself is not editable). Otherwise it is a CREATE.
        val editingEvent = eventPendingEdit
        var eventTitle by remember(editingEvent) { mutableStateOf(editingEvent?.title ?: "") }
        var eventLocation by remember(editingEvent) { mutableStateOf(editingEvent?.locationOrLink ?: "") }
        var selectedDurationMinutes by remember(editingEvent) {
            mutableIntStateOf(
                editingEvent?.let { ((it.endEpochMillis - it.startEpochMillis) / 60_000L).toInt().coerceAtLeast(5) } ?: 30
            )
        }
        var eventType by remember(editingEvent) { mutableStateOf(editingEvent?.eventType ?: "MEETING") }

        // Start time defaults: Current hour + 1 rounded to :00, or 10:00 AM
        val now = Calendar.getInstance()
        val defaultHour = now.get(Calendar.HOUR_OF_DAY).let { if (it in 7..21) (it + 1) % 24 else 10 }
        val editingCal = editingEvent?.let { Calendar.getInstance().apply { timeInMillis = it.startEpochMillis } }
        var selectedHour by remember(editingEvent) { mutableIntStateOf(editingCal?.get(Calendar.HOUR_OF_DAY) ?: defaultHour) }
        var selectedMinute by remember(editingEvent) { mutableIntStateOf(editingCal?.get(Calendar.MINUTE) ?: 0) }
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
            onDismissRequest = { showAddEventDialog = false; eventPendingEdit = null },
            title = { Text(if (editingEvent != null) "Edit event" else "Schedule Event", fontWeight = FontWeight.Bold, color = NudgyCharcoal) },
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
                            // Edit keeps the event's own day; create uses
                            // the selected day. The date is not editable.
                            val baseEpoch = editingEvent?.startEpochMillis
                                ?: daysList.getOrNull(selectedDayIndex)?.epochMillis
                                ?: System.currentTimeMillis()
                            val eventStartCal = Calendar.getInstance().apply {
                                timeInMillis = baseEpoch
                                set(Calendar.HOUR_OF_DAY, selectedHour)
                                set(Calendar.MINUTE, selectedMinute)
                                set(Calendar.SECOND, 0)
                                set(Calendar.MILLISECOND, 0)
                            }
                            val startMillis = eventStartCal.timeInMillis
                            val endMillis = startMillis + (selectedDurationMinutes * 60 * 1000L)

                            if (editingEvent != null) {
                                onUpdateEvent(
                                    editingEvent.id,
                                    eventTitle.trim(),
                                    startMillis,
                                    endMillis,
                                    eventLocation.trim().ifEmpty { null },
                                    eventType
                                )
                                eventPendingEdit = null
                            } else {
                                onAddEvent(
                                    eventTitle.trim(),
                                    startMillis,
                                    endMillis,
                                    eventLocation.trim().ifEmpty { null },
                                    eventType
                                )
                                showAddEventDialog = false
                            }
                        }
                    },
                    enabled = eventTitle.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = NudgyPrimary)
                ) {
                    Text(if (editingEvent != null) "Save" else "Add", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddEventDialog = false; eventPendingEdit = null }) {
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
                    }) { Text("OK", color = NudgyAccent) }
                },
                dismissButton = {
                    TextButton(onClick = { showTimePicker = false }) { Text("Cancel", color = NudgyCharcoal) }
                }
            )
        }
    }
}

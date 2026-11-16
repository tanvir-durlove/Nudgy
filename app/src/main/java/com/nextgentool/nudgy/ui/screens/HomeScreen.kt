package com.nextgentool.nudgy.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextgentool.nudgy.analytics.RemoteConfigValues
import com.nextgentool.nudgy.auth.NudgyUser
import com.nextgentool.nudgy.data.local.entity.CalendarEventEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.repository.DailyTaskStats
import com.nextgentool.nudgy.ui.components.MascotMood
import com.nextgentool.nudgy.ui.components.NudgyCelebrationCard
import com.nextgentool.nudgy.ui.components.NudgyCheckbox
import com.nextgentool.nudgy.ui.components.NudgyInsightCard
import com.nextgentool.nudgy.ui.components.NudgyMascot
import com.nextgentool.nudgy.ui.components.NudgyProgressRing
import com.nextgentool.nudgy.ui.theme.NudgyAccent
import com.nextgentool.nudgy.ui.theme.NudgyCard
import com.nextgentool.nudgy.ui.theme.NudgyCardBorder
import com.nextgentool.nudgy.ui.theme.NudgyCharcoal
import com.nextgentool.nudgy.ui.theme.NudgyLavender
import com.nextgentool.nudgy.ui.theme.NudgyMuted
import com.nextgentool.nudgy.ui.theme.NudgyPeach
import com.nextgentool.nudgy.ui.theme.NudgyPrimary
import com.nextgentool.nudgy.ui.theme.NudgySage

@Composable
fun HomeScreen(
    user: NudgyUser,
    todayTasks: List<TaskEntity>,
    events: List<CalendarEventEntity>,
    dailyStats: DailyTaskStats,
    remoteConfigValues: RemoteConfigValues,
    onToggleTask: (TaskEntity) -> Unit,
    onAddTaskClick: () -> Unit,
    onViewAllTasksClick: () -> Unit,
    onPlanMyDayClick: () -> Unit,
    onCalendarClick: () -> Unit,
    onFreeTimeSuggestionClick: () -> Unit,
    onTaskClick: (TaskEntity) -> Unit = {},
    onPromoCardClick: (String) -> Unit = {},
    groupNames: Map<String, String> = emptyMap(),
    modifier: Modifier = Modifier
) {
    val isAllComplete = dailyStats.percentage >= 1.0f && dailyStats.total > 0
    val timeFormatter = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val hourFormatter = remember { SimpleDateFormat("h:mm", Locale.getDefault()) }

    val morningBriefText = remember(events, dailyStats) {
        if (events.isNotEmpty()) {
            val firstEvent = events.minByOrNull { it.startEpochMillis }
            val timeStr = firstEvent?.let { timeFormatter.format(it.startEpochMillis) } ?: "11:30 AM"
            "You've got ${dailyStats.total} tasks and ${events.size} events today. Your first meeting starts at $timeStr."
        } else {
            "You've got ${dailyStats.total} tasks and an open calendar today. A great day to focus on your top priorities!"
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Header Section with Circular + Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val greetingPrefix = when (currentHour) {
                in 5..11 -> "GOOD MORNING"
                in 12..16 -> "GOOD AFTERNOON"
                in 17..21 -> "GOOD EVENING"
                else -> "HELLO"
            }
            val firstName = user.displayName.split(" ").firstOrNull { it.isNotBlank() }?.uppercase() ?: "FRIEND"

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$greetingPrefix, $firstName",
                    style = MaterialTheme.typography.labelSmall,
                    color = NudgyAccent
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Let's make today count.",
                    style = MaterialTheme.typography.displayMedium,
                    color = NudgyCharcoal
                )
                Spacer(modifier = Modifier.height(6.dp))
                val contextSubtitle = when {
                    dailyStats.total == 0 -> "Your schedule is clear. A great time to plan or relax."
                    dailyStats.completed == dailyStats.total -> "All tasks completed! Fantastic job today."
                    currentHour in 5..11 -> "You have a clear window this morning. A good time to focus."
                    currentHour in 12..16 -> "Keep up the great momentum this afternoon."
                    else -> "Wrap up your priorities or wind down for the night."
                }
                Text(
                    text = contextSubtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NudgyMuted
                )
            }

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.inverseSurface)
                    .clickable { onAddTaskClick() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add task",
                    tint = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // Mascot Celebration Card when all tasks completed!
        if (isAllComplete) {
            NudgyCelebrationCard()
        }

        // Coral Gradient Hero Card ("Nudgy's Brief")
        val briefLabel = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 12..16 -> "NUDGY'S AFTERNOON BRIEF"
            in 17..23, in 0..4 -> "NUDGY'S EVENING BRIEF"
            else -> "NUDGY'S MORNING BRIEF"
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            shadowElevation = 0.dp
        ) {
            Box(
                modifier = Modifier
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFFFA6B58), Color(0xFFE85542))
                        )
                    )
                    .padding(20.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth(0.72f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = briefLabel,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = Color.White.copy(alpha = 0.85f),
                                letterSpacing = 1.2.sp
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = if (isAllComplete) "All tasks completed!" else "Your day looks balanced.",
                        style = MaterialTheme.typography.titleLarge.copy(
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = morningBriefText,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = Color.White.copy(alpha = 0.9f),
                            lineHeight = 18.sp
                        )
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { onPlanMyDayClick() }
                        ) {
                            Text(
                                text = "Plan my day",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { onFreeTimeSuggestionClick() }
                        ) {
                            Text(
                                text = "Find free time",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    color = Color.White.copy(alpha = 0.9f),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.9f),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }

                NudgyMascot(
                    size = 90.dp,
                    mood = if (isAllComplete) MascotMood.CELEBRATING else MascotMood.IDLE,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 6.dp, y = 6.dp)
                )
            }
        }

        // Today's Tasks Section
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "TODAY",
                        style = MaterialTheme.typography.labelSmall,
                        color = NudgyMuted
                    )
                    Text(
                        text = "My tasks",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = NudgyCharcoal
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { onViewAllTasksClick() }
                ) {
                    Text(
                        text = "View all",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = NudgyCharcoal
                        )
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = NudgyCharcoal,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = NudgyCard,
                border = androidx.compose.foundation.BorderStroke(1.dp, NudgyCardBorder)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (todayTasks.isEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp)
                                .clickable { onAddTaskClick() }
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircleOutline,
                                contentDescription = null,
                                tint = NudgyPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                            Column {
                                Text(
                                    text = "No tasks left for today!",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = NudgyCharcoal
                                )
                                Text(
                                    text = "Tap here or the + button above to add a task.",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                                    color = NudgyMuted
                                )
                            }
                        }
                    } else {
                        todayTasks.take(3).forEach { task ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                NudgyCheckbox(
                                    checked = task.isCompleted,
                                    onCheckedChange = { onToggleTask(task) }
                                )

                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { onTaskClick(task) }
                                ) {
                                    Text(
                                        text = task.title,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            textDecoration = if (task.isCompleted) TextDecoration.LineThrough else TextDecoration.None
                                        ),
                                        color = if (task.isCompleted) NudgyMuted else NudgyCharcoal
                                    )
                                    Text(
                                        text = if (task.priority == Priority.HIGH) "High priority" else if (task.reminderSet) "Reminder set" else "Daily habit",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                                        color = NudgyMuted
                                    )
                                    // Group v1: origin badge for group tasks.
                                    if (task.groupId != null) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(100.dp))
                                                .background(NudgyPrimary.copy(alpha = 0.12f))
                                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = groupNames[task.groupId] ?: "Group",
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                ),
                                                color = NudgyAccent
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

        // Daily Progress Section
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = NudgySage,
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFD3EEDD))
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "DAILY PROGRESS",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF2E7D32)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (isAllComplete) "Awesome job!" else "On your way",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = NudgyCharcoal
                        )
                    }

                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = Color(0xFF2E7D32),
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    NudgyProgressRing(
                        percentage = if (dailyStats.total == 0) 1.0f else dailyStats.percentage,
                        size = 94.dp,
                        ringColor = Color(0xFF2E7D32),
                        trackColor = Color(0xFFC7E7D3)
                    )

                    Column {
                        if (dailyStats.total == 0) {
                            Text(
                                text = "All clear today",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                ),
                                color = NudgyCharcoal
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "No tasks on schedule",
                                style = MaterialTheme.typography.bodyMedium,
                                color = NudgyMuted
                            )
                        } else {
                            Text(
                                text = "${dailyStats.completed} of ${dailyStats.total} tasks",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                ),
                                color = NudgyCharcoal
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (dailyStats.remaining == 0) "All done today!" else "${dailyStats.remaining} remaining today",
                                style = MaterialTheme.typography.bodyMedium,
                                color = NudgyMuted
                            )
                        }
                    }
                }
            }
        }

        // Dynamic Remote Config Promotional Card (Controlled via Firebase)
        if (remoteConfigValues.promoCardEnabled) {
            NudgyInsightCard(
                title = remoteConfigValues.promoHeadline,
                subtitle = remoteConfigValues.promoSubtitle,
                trailingContent = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = NudgyCharcoal
                    )
                },
                onClick = { onPromoCardClick(remoteConfigValues.promoHeadline) }
            )
        }

        // Schedule shows calendar events only — tasks live in the Tasks
        // list and are never projected into the schedule timeline.
        val scheduledItems = remember(events) {
            events.sortedBy { it.startEpochMillis }
        }

        // Up Next Schedule Section
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                    Text(
                        text = "Schedule",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = NudgyCharcoal
                    )
                }

                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(NudgyCard)
                        .border(1.dp, NudgyCardBorder, RoundedCornerShape(12.dp))
                        .clickable { onCalendarClick() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CalendarMonth,
                        contentDescription = "Open Calendar",
                        tint = NudgyCharcoal,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = NudgyCard,
                border = androidx.compose.foundation.BorderStroke(1.dp, NudgyCardBorder)
            ) {
                if (scheduledItems.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "No items scheduled for today",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = NudgyCharcoal
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Your schedule is clear. Perfect time for deep focus!",
                            style = MaterialTheme.typography.bodyMedium,
                            color = NudgyMuted
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { onCalendarClick() },
                            shape = RoundedCornerShape(100.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.CalendarMonth,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = NudgyPrimary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Open Schedule", fontSize = 12.sp, color = NudgyCharcoal)
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        scheduledItems.forEach { event ->
                            val isMeeting = event.eventType == "MEETING"
                            val cardBg = if (isMeeting) NudgyPeach else NudgyLavender
                            val dotColor = if (isMeeting) NudgyPrimary else Color(0xFF7A68E6)
                            val startFormatted = hourFormatter.format(event.startEpochMillis)
                            val timeRangeFormatted = "${timeFormatter.format(event.startEpochMillis)} – ${timeFormatter.format(event.endEpochMillis)}"

                            Row(
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.width(44.dp)
                                ) {
                                    Text(
                                        text = startFormatted,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                                        color = NudgyMuted
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(dotColor)
                                    )
                                }

                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { onCalendarClick() },
                                    shape = RoundedCornerShape(14.dp),
                                    color = cardBg
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            text = timeRangeFormatted,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                                            color = NudgyMuted
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = event.title,
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                            color = NudgyCharcoal
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = event.locationOrLink ?: "Google Meet",
                                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                                            color = NudgyMuted
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Proactive Suggestion Card
        NudgyInsightCard(
            title = "You have 45 minutes free",
            subtitle = "Want to finish a pending task?",
            trailingContent = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = NudgyCharcoal
                )
            },
            onClick = onFreeTimeSuggestionClick
        )

        // Generous bottom spacer so content isn't clipped by bottom nav or floating mascot button
        Spacer(modifier = Modifier.height(84.dp))
    }
}

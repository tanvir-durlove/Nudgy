package com.example.ui.screens

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
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.example.analytics.RemoteConfigValues
import com.example.auth.NudgyUser
import com.example.data.local.entity.CalendarEventEntity
import java.text.SimpleDateFormat
import java.util.Locale
import com.example.data.local.entity.TaskEntity
import com.example.data.model.Priority
import com.example.data.repository.DailyTaskStats
import com.example.ui.components.MascotMood
import com.example.ui.components.NudgyCelebrationCard
import com.example.ui.components.NudgyCheckbox
import com.example.ui.components.NudgyInsightCard
import com.example.ui.components.NudgyMascot
import com.example.ui.components.NudgyProgressRing
import com.example.ui.theme.NudgyCard
import com.example.ui.theme.NudgyCardBorder
import com.example.ui.theme.NudgyCharcoal
import com.example.ui.theme.NudgyLavender
import com.example.ui.theme.NudgyMuted
import com.example.ui.theme.NudgyPeach
import com.example.ui.theme.NudgyPrimary
import com.example.ui.theme.NudgySage

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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "GOOD MORNING, ${user.displayName.split(" ").firstOrNull()?.uppercase() ?: "ARIF"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = NudgyPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Let's make today count.",
                    style = MaterialTheme.typography.displayMedium,
                    color = NudgyCharcoal
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "You have a clear window this morning. A good time to focus.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NudgyMuted
                )
            }

            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(NudgyCharcoal)
                    .clickable { onAddTaskClick() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add task",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // Mascot Celebration Card when all tasks completed!
        if (isAllComplete) {
            NudgyCelebrationCard()
        }

        // Coral Gradient Hero Card ("Nudgy's Morning Brief")
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
                            text = "NUDGY'S MORNING BRIEF",
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
                        percentage = dailyStats.percentage,
                        size = 94.dp,
                        ringColor = Color(0xFF2E7D32),
                        trackColor = Color(0xFFC7E7D3)
                    )

                    Column {
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
                            text = "${dailyStats.remaining} remaining today",
                            style = MaterialTheme.typography.bodyMedium,
                            color = NudgyMuted
                        )
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
                        color = NudgyPrimary
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
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
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
                if (events.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "No events scheduled for today",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = NudgyCharcoal
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Your calendar is clear. Perfect time for deep focus!",
                            style = MaterialTheme.typography.bodyMedium,
                            color = NudgyMuted
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        events.forEach { event ->
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
                                    modifier = Modifier.weight(1f),
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

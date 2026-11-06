package com.example.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.entity.TaskEntity
import com.example.data.model.Priority
import com.example.data.repository.DailyTaskStats
import com.example.ui.components.NudgyCheckbox
import com.example.ui.components.NudgyInsightCard
import com.example.ui.components.NudgyPillTabs
import com.example.ui.components.NudgyProgressBar
import com.example.ui.theme.NudgyAccent
import com.example.ui.theme.NudgyCard
import com.example.ui.theme.NudgyCardBorder
import com.example.ui.theme.NudgyCharcoal
import com.example.ui.theme.NudgyMuted
import com.example.ui.theme.NudgyPeach
import com.example.ui.theme.NudgyPrimary
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun TasksScreen(
    todayTasks: List<TaskEntity>,
    upcomingTasks: List<TaskEntity>,
    completedTasks: List<TaskEntity>,
    dailyStats: DailyTaskStats,
    onToggleTask: (TaskEntity) -> Unit,
    onAddTaskClick: () -> Unit,
    onTaskClick: (TaskEntity) -> Unit = {},
    groupNames: Map<String, String> = emptyMap(),
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val timeFormatter = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }

    val displayedTasks = when (selectedTab) {
        0 -> todayTasks
        1 -> upcomingTasks
        else -> completedTasks
    }

    val tabTitle = when (selectedTab) {
        0 -> "TODAY · ${todayTasks.size}"
        1 -> "UPCOMING · ${upcomingTasks.size}"
        else -> "COMPLETED · ${completedTasks.size}"
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header with '+' FAB
        item(key = "tasks_header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "STAY ON TRACK",
                        style = MaterialTheme.typography.labelSmall,
                        color = NudgyAccent
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "My tasks",
                        style = MaterialTheme.typography.displayMedium,
                        color = NudgyCharcoal
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
        }

        // Segmented Filter Tabs
        item(key = "tasks_tabs") {
            NudgyPillTabs(
                tabs = listOf(
                    "Today (${todayTasks.size})",
                    "Upcoming (${upcomingTasks.size})",
                    "Completed (${completedTasks.size})"
                ),
                selectedIndex = selectedTab,
                onTabSelected = { selectedTab = it }
            )
        }

        // Progress Card Banner (Coral background matching Figma Screen 3)
        item(key = "tasks_progress_card") {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = NudgyPrimary
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        if (dailyStats.total == 0) {
                            Text(
                                text = "All clear!",
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "No tasks scheduled today",
                                fontSize = 13.sp,
                                color = Color.White.copy(alpha = 0.9f),
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                        } else {
                            Text(
                                text = "${dailyStats.completed}/${dailyStats.total}",
                                fontSize = 36.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = if (dailyStats.completed == dailyStats.total) "all done today! 🎉" else "tasks complete",
                                fontSize = 13.sp,
                                color = Color.White.copy(alpha = 0.9f),
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    NudgyProgressBar(
                        progress = if (dailyStats.total == 0) 1.0f else dailyStats.percentage,
                        height = 7.dp,
                        trackColor = Color.White.copy(alpha = 0.35f),
                        progressColor = Color.White
                    )
                }
            }
        }

        // Section Title
        item(key = "tasks_section_title") {
            Text(
                text = tabTitle,
                style = MaterialTheme.typography.labelSmall,
                color = NudgyCharcoal.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )
        }

        // Performant Lazy Task Items
        if (displayedTasks.isEmpty()) {
            item(key = "tasks_empty_state") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = NudgyCard,
                    border = androidx.compose.foundation.BorderStroke(1.dp, NudgyCardBorder)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Checklist,
                            contentDescription = null,
                            tint = NudgyPrimary.copy(alpha = 0.7f),
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            text = when (selectedTab) {
                                0 -> "No tasks due today"
                                1 -> "No upcoming tasks scheduled"
                                else -> "No completed tasks yet"
                            },
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = NudgyCharcoal
                        )
                        Text(
                            text = when (selectedTab) {
                                0 -> "Enjoy your free time or tap + above to schedule a task."
                                1 -> "Plan ahead by tapping + above to add future priorities."
                                else -> "Completed tasks will show up here as you check them off."
                            },
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                            color = NudgyMuted,
                            textAlign = TextAlign.Center
                        )

                        if (selectedTab == 0 && upcomingTasks.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { selectedTab = 1 },
                                shape = RoundedCornerShape(100.dp)
                            ) {
                                Text(
                                    text = "View Upcoming (${upcomingTasks.size})",
                                    fontSize = 12.sp,
                                    color = NudgyAccent,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        } else {
            items(
                items = displayedTasks,
                key = { it.id }
            ) { task ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = NudgyCard,
                    border = androidx.compose.foundation.BorderStroke(1.dp, NudgyCardBorder)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        NudgyCheckbox(
                            checked = task.isCompleted,
                            onCheckedChange = { onToggleTask(task) }
                        )

                        // Icon container with checklist glyph
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (task.isCompleted) Color(0xFFF1F0FD) else NudgyPeach),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Checklist,
                                contentDescription = null,
                                tint = if (task.isCompleted) Color(0xFF7A68E6) else NudgyPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onTaskClick(task) }
                        ) {
                            Text(
                                text = task.title,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    textDecoration = if (task.isCompleted) TextDecoration.LineThrough else TextDecoration.None
                                ),
                                color = if (task.isCompleted) NudgyMuted else NudgyCharcoal
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            val categoryLabel = task.category.name.lowercase().replaceFirstChar { it.uppercase() }
                            Text(
                                text = if (task.priority == Priority.HIGH) "High priority · $categoryLabel" else if (task.reminderSet) "Reminder set · $categoryLabel" else categoryLabel,
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                                color = NudgyMuted
                            )
                            // Group v1: origin badge — a group task must be
                            // visibly distinct from a personal task.
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

                        Text(
                            text = timeFormatter.format(task.dueDateEpochMillis),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 12.sp,
                                color = NudgyMuted
                            )
                        )
                    }
                }
            }
        }

        // Streak & Motivation Card
        item(key = "tasks_insight_card") {
            NudgyInsightCard(
                title = "Build a good rhythm",
                subtitle = "Small steps count. Keep your daily streak going."
            )
        }

        // Generous bottom spacer
        item(key = "tasks_bottom_spacer") {
            Spacer(modifier = Modifier.height(84.dp))
        }
    }
}

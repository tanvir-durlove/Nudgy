package com.nextgentool.nudgy.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Home
import androidx.compose.ui.graphics.vector.ImageVector

enum class NudgyNavDestination(
    val title: String,
    val icon: ImageVector
) {
    HOME("Home", Icons.Outlined.Home),
    TASKS("Tasks", Icons.Outlined.Checklist),
    CHAT("Chat", Icons.Outlined.ChatBubbleOutline),
    CALENDAR("Calendar", Icons.Outlined.CalendarMonth),
    GROUPS("Groups", Icons.Outlined.Group)
}

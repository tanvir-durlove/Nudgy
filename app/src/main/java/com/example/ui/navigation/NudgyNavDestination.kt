package com.example.ui.navigation

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
    CHAT("Chat", Icons.Outlined.ChatBubbleOutline),
    TASKS("Tasks", Icons.Outlined.Checklist),
    CALENDAR("Calendar", Icons.Outlined.CalendarMonth),
    GROUPS("Groups", Icons.Outlined.Group)
}

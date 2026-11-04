package com.example.ui.navigation

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.ui.theme.NudgyCanvas

// M3 bottom bar. Same call signature as before, so MainActivity's Scaffold
// bottomBar call site is untouched. Order comes from NudgyNavDestination
// declaration order (HOME, TASKS, CHAT, CALENDAR, GROUPS — Chat in the middle).
// NavigationBarItem supplies the selection-indicator pill and the selected
// semantics the custom bar lacked; labels stay always visible.
@Composable
fun NudgyBottomBar(
    currentDestination: NudgyNavDestination,
    onNavigate: (NudgyNavDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationBar(
        modifier = modifier,
        containerColor = NudgyCanvas
    ) {
        NudgyNavDestination.values().forEach { destination ->
            val isSelected = destination == currentDestination
            NavigationBarItem(
                selected = isSelected,
                onClick = { onNavigate(destination) },
                icon = {
                    Icon(
                        imageVector = destination.icon,
                        contentDescription = destination.title
                    )
                },
                label = {
                    Text(
                        text = destination.title,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    )
                },
                alwaysShowLabel = true
            )
        }
    }
}

package com.example.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
import com.example.R
import com.example.ui.screens.CalendarShell
import com.example.ui.screens.ChatShell
import com.example.ui.screens.GroupsShell
import com.example.ui.screens.SettingsShell
import com.example.ui.screens.TodayShell

/**
 * Sprint 0 — NavHost + bottom navigation for the scratch rebuild (v2).
 *
 * NOT the app root yet: MainActivity still runs the old state-based
 * navigation. Sprint 1 swaps the root to NudgyApp() screen by screen.
 * This file compiles and is ready to be the root — nothing here touches
 * the old screens.
 *
 * Bottom nav (locked): Today | Calendar | centre Quick Chat (+) |
 * Groups | Settings. The centre button IS the chat: first a compact
 * Quick Chat sheet, expanding to full-screen chat as the conversation
 * grows (Sprint 3). Here it navigates to the chat destination.
 */
@Composable
fun NudgyApp() {
    val navController = rememberNavController()
    Scaffold(
        bottomBar = { NudgyBottomNav(navController) }
    ) { innerPadding ->
        NudgyNavHost(navController, Modifier.padding(innerPadding))
    }
}

@Composable
fun NudgyNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = NudgyRoute.startDestination.route,
        modifier = modifier
    ) {
        composable(NudgyRoute.Today.route) { TodayShell() }
        composable(NudgyRoute.Calendar.route) { CalendarShell() }
        composable(NudgyRoute.Chat.route) { ChatShell() }
        composable(
            route = NudgyRoute.Groups.route,
            deepLinks = listOf(navDeepLink { uriPattern = GROUP_JOIN_DEEP_LINK })
        ) { GroupsShell() }
        composable(NudgyRoute.Settings.route) { SettingsShell() }
    }
}

private data class BottomNavEntry(
    val route: NudgyRoute,
    val title: String,
    val icon: ImageVector,
    val isCentreChat: Boolean = false
)

private val bottomNavEntries = listOf(
    BottomNavEntry(NudgyRoute.Today, "Today", Icons.Outlined.Home),
    BottomNavEntry(NudgyRoute.Calendar, "Calendar", Icons.Outlined.CalendarMonth),
    BottomNavEntry(NudgyRoute.Chat, "Chat", Icons.Filled.Add, isCentreChat = true),
    BottomNavEntry(NudgyRoute.Groups, "Groups", Icons.Outlined.Group),
    BottomNavEntry(NudgyRoute.Settings, "Settings", Icons.Outlined.Settings)
)

@Composable
fun NudgyBottomNav(navController: NavController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = NudgyRoute.fromRoute(navBackStackEntry?.destination?.route)
    NavigationBar {
        bottomNavEntries.forEach { entry ->
            val selected = currentRoute == entry.route
            if (entry.isCentreChat) {
                NavigationBarItem(
                    selected = selected,
                    onClick = { navigateTo(navController, entry.route) },
                    icon = {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(48.dp)
                                .background(
                                    color = colorResource(R.color.nudgy_teal),
                                    shape = CircleShape
                                )
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = "Quick Chat",
                                tint = colorResource(R.color.nudgy_cream)
                            )
                        }
                    },
                    label = { Text("Chat") }
                )
            } else {
                NavigationBarItem(
                    selected = selected,
                    onClick = { navigateTo(navController, entry.route) },
                    icon = { Icon(entry.icon, contentDescription = entry.title) },
                    label = { Text(entry.title) }
                )
            }
        }
    }
}

private fun navigateTo(navController: NavController, route: NudgyRoute) {
    navController.navigate(route.route) {
        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

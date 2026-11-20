package com.nextgentool.nudgy.ui.navigation

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.intent.IncomingIntent
import com.nextgentool.nudgy.ui.screens.CalendarScreen
import com.nextgentool.nudgy.ui.screens.ChatShell
import com.nextgentool.nudgy.ui.screens.GroupsShell
import com.nextgentool.nudgy.ui.screens.PlanScreen
import com.nextgentool.nudgy.ui.screens.SettingsShell
import com.nextgentool.nudgy.ui.screens.TasksScreen
import com.nextgentool.nudgy.ui.screens.TemplatesScreen
import com.nextgentool.nudgy.ui.screens.TodayScreen
import com.nextgentool.nudgy.ui.sheets.CreateTaskSheet
import com.nextgentool.nudgy.ui.theme.NudgyTheme
import com.nextgentool.nudgy.ui.viewmodel.CalendarViewModel
import com.nextgentool.nudgy.ui.viewmodel.ShareIntentViewModel

/**
 * Sprint 0 — NavHost + bottom navigation for the scratch rebuild (v2).
 * Sprint 1-0 made this the app root: MainActivity only calls
 * NudgyApp(). NudgyApp() also owns the theme (NudgyTheme, system
 * dark/light) so MainActivity stays a one-line root.
 *
 * Bottom nav (locked): Today | Calendar | centre Quick Chat (+) |
 * Groups | Settings. The centre button IS the chat: first a compact
 * Quick Chat sheet, expanding to full-screen chat as the conversation
 * grows (Sprint 3). Here it navigates to the chat destination, which
 * keeps its shell until Sprint 3.
 *
 * Today is a nested graph ("today_graph"): TodayScreen + the Plan
 * route ("today/plan"). Back from Plan returns to Today.
 */
@Composable
fun NudgyApp() {
    val navController = rememberNavController()
    // Sprint 2-3 — share-to-task (Feature 12): the Activity-scoped
    // view model (the Activity is the ViewModelStoreOwner at this
    // root, so this is the SAME instance MainActivity feeds intents
    // into). The sheet renders over whatever tab the user was on —
    // the share flow is route-independent.
    val shareViewModel: ShareIntentViewModel = hiltViewModel()
    val pendingShare by shareViewModel.pendingShare.collectAsState()
    // Sprint 2-4 — date links + create-event intents: they open the
    // Calendar, so a pending one navigates there first (singleTop —
    // a no-op when already on Calendar); the Calendar destination
    // acts on the link and consumes it.
    val pendingDateLink by shareViewModel.pendingDateLink.collectAsState()
    val pendingInsertEvent by shareViewModel.pendingInsertEvent.collectAsState()
    LaunchedEffect(pendingDateLink, pendingInsertEvent) {
        if (pendingDateLink != null || pendingInsertEvent != null) {
            navigateTo(navController, NudgyRoute.Calendar)
        }
    }
    NudgyTheme {
        Scaffold(
            bottomBar = { NudgyBottomNav(navController) }
        ) { innerPadding ->
            NudgyNavHost(
                navController,
                Modifier.padding(innerPadding),
                pendingDateLink = pendingDateLink,
                onDateLinkConsumed = { shareViewModel.consumeDateLink() },
                pendingInsertEvent = pendingInsertEvent,
                onInsertEventConsumed = { shareViewModel.consumeInsertEvent() }
            )
        }
        val share = pendingShare
        if (share != null) {
            CreateTaskSheet(
                onDismiss = { shareViewModel.dismissShare() },
                onSaveTask = { title, description, due, priority, category, reminderSet ->
                    shareViewModel.confirmShare(
                        title = title,
                        description = description,
                        dueEpochMillis = due,
                        priority = priority,
                        category = category,
                        reminderSet = reminderSet
                    )
                },
                headerTitle = "New Task",
                headerSubtitle = "Shared from another app",
                initialTitle = share.title,
                initialDescription = share.description ?: "",
                initialHasNoDueDate = true,
                initialReminderSet = false
            )
        }
    }
}

@Composable
fun NudgyNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    // Sprint 2-4 — a pending date link / create-event intent, passed
    // to the Calendar destination (which acts on it, then consumes
    // it). Null in every other flow.
    pendingDateLink: com.nextgentool.nudgy.intent.IncomingIntent.DateLink? = null,
    onDateLinkConsumed: () -> Unit = {},
    pendingInsertEvent: com.nextgentool.nudgy.intent.IncomingIntent.InsertEvent? = null,
    onInsertEventConsumed: () -> Unit = {}
) {
    NavHost(
        navController = navController,
        startDestination = TODAY_GRAPH_ROUTE,
        modifier = modifier
    ) {
        navigation(
            startDestination = NudgyRoute.Today.route,
            route = TODAY_GRAPH_ROUTE
        ) {
            composable(NudgyRoute.Today.route) {
                TodayScreen(
                    onPlanClick = { navController.navigate(NudgyRoute.Plan.route) },
                    onAllTasksClick = { navController.navigate(NudgyRoute.Tasks.route) }
                )
            }
            composable(NudgyRoute.Plan.route) {
                PlanScreen(onBackClick = { navController.popBackStack() })
            }
        }
        // Sprint 1-1 — Tasks list. NOT a bottom destination: no tab is
        // highlighted while it is open (see NudgyRoute.all).
        composable(NudgyRoute.Tasks.route) {
            TasksScreen(
                onBackClick = { navController.popBackStack() },
                onNavigateToTemplates = { navController.navigate(NudgyRoute.Templates.route) }
            )
        }
        // Sprint 1-4 — Templates. NOT a bottom destination either.
        composable(NudgyRoute.Templates.route) {
            TemplatesScreen(onBack = { navController.popBackStack() })
        }
        // Sprint 2-1 — Calendar is wired: the real screen on the
        // CalendarViewModel (Hilt). The route owns the permission
        // launcher; the ViewModel owns sync triggers and state. The
        // screen only renders.
        composable(NudgyRoute.Calendar.route) {
            val viewModel: CalendarViewModel = hiltViewModel()
            val uiState by viewModel.uiState.collectAsState()
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { grants ->
                if (grants.values.all { it }) {
                    viewModel.onCalendarPermissionGranted()
                }
            }
            CalendarScreen(
                events = uiState.events,
                suggestedSlot = uiState.suggestedSlot,
                isCalendarConnected = uiState.isCalendarConnected,
                isSyncing = uiState.isSyncing,
                errorMessage = uiState.errorMessage,
                onDismissError = { viewModel.dismissError() },
                onConnectGoogleCalendar = {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.READ_CALENDAR,
                            Manifest.permission.WRITE_CALENDAR
                        )
                    )
                },
                onSyncNow = { viewModel.syncNow() },
                onBlockFocusTime = { start, end, title ->
                    viewModel.blockFocusTime(title, start, end)
                },
                onAddEvent = { title, start, end, location, type ->
                    viewModel.addEvent(title, start, end, location, type)
                },
                onUpdateEvent = { eventId, title, start, end, location, type ->
                    viewModel.updateEvent(eventId, title, start, end, location, type)
                },
                onDeleteEvent = { eventId -> viewModel.deleteEvent(eventId) },
                onSelectDay = { dayMillis -> viewModel.selectDay(dayMillis) },
                groupNames = uiState.groupNames,
                // Sprint 2-2 — drag-to-slot: the strip + grid state and
                // the drop callbacks (the ViewModel writes plannedStart
                // / plannedDate and owns the busy-slot warn, Q3(a)).
                stripTasks = uiState.stripTasks,
                placedTasks = uiState.placedTasks,
                pendingConflict = uiState.pendingConflict,
                onDropTask = { task, slotStart -> viewModel.dropTaskAtSlot(task, slotStart) },
                onAcceptSuggestedSlot = { viewModel.acceptSuggestedSlot() },
                onDismissConflict = { viewModel.dismissConflict() },
                // Sprint 2-4 — date link / create-event: the screen
                // jumps to the link's day (+ slot highlight) and opens
                // the add-event dialog prefilled, then consumes them.
                pendingDateLink = pendingDateLink,
                onDateLinkConsumed = onDateLinkConsumed,
                pendingInsertEvent = pendingInsertEvent,
                onInsertEventConsumed = onInsertEventConsumed
            )
        }
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

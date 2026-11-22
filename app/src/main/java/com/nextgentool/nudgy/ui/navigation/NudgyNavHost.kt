package com.nextgentool.nudgy.ui.navigation

import android.Manifest
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
import com.nextgentool.nudgy.ui.screens.ChatScreen
import com.nextgentool.nudgy.ui.screens.FocusScreen
import com.nextgentool.nudgy.ui.screens.GroupsShell
import com.nextgentool.nudgy.ui.screens.PlanScreen
import com.nextgentool.nudgy.ui.screens.SettingsShell
import com.nextgentool.nudgy.ui.screens.TasksScreen
import com.nextgentool.nudgy.ui.screens.TemplatesScreen
import com.nextgentool.nudgy.ui.screens.TodayScreen
import com.nextgentool.nudgy.ui.screens.WeeklyReviewScreen
import com.nextgentool.nudgy.ui.sheets.CreateTaskSheet
import com.nextgentool.nudgy.ui.sheets.QuickChatSheet
import com.nextgentool.nudgy.ui.theme.NudgyTheme
import com.nextgentool.nudgy.ui.viewmodel.CalendarViewModel
import com.nextgentool.nudgy.ui.viewmodel.ChatViewModel
import com.nextgentool.nudgy.ui.viewmodel.HabitViewModel
import com.nextgentool.nudgy.ui.viewmodel.ShareIntentViewModel

/**
 * Sprint 0 — NavHost + bottom navigation for the scratch rebuild (v2).
 * Sprint 1-0 made this the app root: MainActivity only calls
 * NudgyApp(). NudgyApp() also owns the theme (NudgyTheme, system
 * dark/light) so MainActivity stays a one-line root.
 *
 * Bottom nav (locked): Today | Calendar | centre Quick Chat (+) |
 * Groups | Settings. The centre button IS the chat: it opens the
 * compact Quick Chat sheet (Sprint 3-1); the first message sent
 * there, or the expand button, moves to the full-screen chat
 * destination. The sheet and the full chat share one Activity-scoped
 * ChatViewModel (owned by NudgyApp), so history survives leaving and
 * re-entering the sheet.
 *
 * Today is a nested graph ("today_graph"): TodayScreen + the Plan
 * route ("today/plan"). Back from Plan returns to Today.
 */
@OptIn(ExperimentalLayoutApi::class)
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
        // Sprint 3-1 — the ChatViewModel is Activity-scoped (same
        // instance the Chat route uses): the Quick Chat sheet and the
        // full chat share it, so history survives leaving and
        // re-entering the sheet. Same precedent as ShareIntentViewModel
        // above (the Activity is the ViewModelStoreOwner at this root).
        val chatViewModel: ChatViewModel = hiltViewModel()
        var quickChatOpen by rememberSaveable { mutableStateOf(false) }
        // Q7(a): the bottom nav hides while the keyboard is open — the
        // chat gets the full height, one less inset layer in the
        // keyboard-gap equation (S3-8 diagnoses the gap itself).
        val isImeVisible = WindowInsets.isImeVisible
        Scaffold(
            bottomBar = {
                if (shouldShowBottomNav(isImeVisible)) {
                    NudgyBottomNav(
                        navController,
                        onCentreChatClick = { quickChatOpen = true }
                    )
                }
            }
        ) { innerPadding ->
            NudgyNavHost(
                navController,
                Modifier.padding(innerPadding),
                chatViewModel = chatViewModel,
                pendingDateLink = pendingDateLink,
                onDateLinkConsumed = { shareViewModel.consumeDateLink() },
                pendingInsertEvent = pendingInsertEvent,
                onInsertEventConsumed = { shareViewModel.consumeInsertEvent() }
            )
        }
        // Sprint 3-1 — Quick Chat sheet over the current tab.
        if (quickChatOpen) {
            val sheetMessages by chatViewModel.messages.collectAsState()
            val sheetThinking by chatViewModel.isThinking.collectAsState()
            val sheetListening by chatViewModel.isListening.collectAsState()
            // Sprint 3-4 — the sheet's mic shows Stop while a dump
            // session is open, even between utterances.
            val sheetBrainDumpActive by chatViewModel.isBrainDumpActive.collectAsState()
            // Sprint 3-7 — the EN/বাংলা voice toggle, shared with the
            // full chat through the same ViewModel.
            val sheetVoiceLanguage by chatViewModel.voiceLanguage.collectAsState()
            QuickChatSheet(
                messages = sheetMessages,
                isThinking = sheetThinking,
                isListening = sheetListening || sheetBrainDumpActive,
                onSendMessage = { chatViewModel.sendMessage(it) },
                onExpandToFullChat = {
                    quickChatOpen = false
                    navigateTo(navController, NudgyRoute.Chat)
                },
                onDismiss = { quickChatOpen = false },
                onStartVoice = { chatViewModel.startVoice(sheetVoiceLanguage.code) },
                onStopVoice = { chatViewModel.stopVoice() },
                voiceLanguage = sheetVoiceLanguage,
                onToggleVoiceLanguage = { chatViewModel.toggleVoiceLanguage() }
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
    // Sprint 3-1 — the shared Activity-scoped ChatViewModel (owned by
    // NudgyApp): the same instance the Quick Chat sheet uses, so the
    // sheet and the full chat never diverge.
    chatViewModel: ChatViewModel,
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
                // Sprint 4-2 — the Habits section's ViewModel lives in
                // the NavHost (same as the share/chat ViewModels above):
                // TodayScreen takes it nullable so non-Hilt tests that
                // render Today without it keep working.
                val habitViewModel: HabitViewModel = hiltViewModel()
                TodayScreen(
                    onPlanClick = { navController.navigate(NudgyRoute.Plan.route) },
                    onAllTasksClick = { navController.navigate(NudgyRoute.Tasks.route) },
                    onFocusClick = { navController.navigate(NudgyRoute.Focus.route) },
                    // Sprint 4-4 — the progress ring opens the week's
                    // summary (spec §7, Q9=(a)).
                    onWeeklyReviewClick = { navController.navigate(NudgyRoute.WeeklyReview.route) },
                    habitViewModel = habitViewModel
                )
            }
            composable(NudgyRoute.Plan.route) {
                PlanScreen(onBackClick = { navController.popBackStack() })
            }
        }
        // Sprint 4-1 — Focus timer. NOT a bottom destination: no tab is
        // highlighted while it is open (see NudgyRoute.all).
        composable(NudgyRoute.Focus.route) {
            FocusScreen(onBackClick = { navController.popBackStack() })
        }
        // Sprint 4-4 — Weekly review. NOT a bottom destination either:
        // the same screen from the Today progress ring and from
        // Settings (spec §7, Q9=(a)).
        composable(NudgyRoute.WeeklyReview.route) {
            WeeklyReviewScreen(onBackClick = { navController.popBackStack() })
        }
        // Sprint 1-1 — Tasks list. NOT a bottom destination: no tab is
        // highlighted while it is open (see NudgyRoute.all).
        composable(NudgyRoute.Tasks.route) {
            TasksScreen(
                onBackClick = { navController.popBackStack() },
                onNavigateToTemplates = { navController.navigate(NudgyRoute.Templates.route) },
                // Sprint 3-5 — the detail sheet's "Break down" button:
                // the split starts at once on the shared ChatViewModel
                // and the user lands on Chat, where the preview card
                // appears. The sheet's task is dismissed by the
                // navigation itself (leaving Tasks pops the sheet).
                onSplitTask = { taskId ->
                    chatViewModel.startSplit(taskId)
                    navigateTo(navController, NudgyRoute.Chat)
                }
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
        // Sprint 3-0 — Chat is wired: the real ChatScreen on the
        // shared Activity-scoped ChatViewModel (owned by NudgyApp —
        // the same instance the Quick Chat sheet uses). The route owns
        // no state; the ViewModel owns the send pipeline (guardrails →
        // prefilter → local fallback) and the reply sound. The
        // ChatShell placeholder is deleted (S3-1); the sheet lives in
        // NudgyApp and the approved restyle is in ChatScreen.
        composable(NudgyRoute.Chat.route) {
            val viewModel = chatViewModel
            val messages by viewModel.messages.collectAsState()
            val isThinking by viewModel.isThinking.collectAsState()
            val isSpeaking by viewModel.isSpeaking.collectAsState()
            val isListening by viewModel.isListening.collectAsState()
            val thinkingText by viewModel.thinkingText.collectAsState()
            // Sprint 3-2 — the pending draft lives in the ViewModel; the
            // screen only renders its preview card and reports the
            // card's actions back.
            val pendingDraft by viewModel.pendingDraft.collectAsState()
            val pendingDraftMessageId by viewModel.pendingDraftMessageId.collectAsState()
            // Sprint 3-4 — the brain-dump card, the live transcript,
            // and the rewarded entry. The rewarded show call needs an
            // Activity: this route reads it from LocalContext (used
            // for this call only, never stored in the ViewModel).
            val isBrainDumpActive by viewModel.isBrainDumpActive.collectAsState()
            val brainDumpTranscript by viewModel.brainDumpTranscript.collectAsState()
            val pendingBrainDumpDrafts by viewModel.pendingBrainDumpDrafts.collectAsState()
            val pendingBrainDumpMessageId by viewModel.pendingBrainDumpMessageId.collectAsState()
            // Sprint 3-6 — the day-plan card and the priority Apply
            // card, same pattern as the brain-dump card above.
            val pendingPlanDrafts by viewModel.pendingPlanDrafts.collectAsState()
            val pendingPlanMessageId by viewModel.pendingPlanMessageId.collectAsState()
            val pendingPriorityMessageId by viewModel.pendingPriorityMessageId.collectAsState()
            val chatCapState by viewModel.chatCapState.collectAsState()
            val rewardedAdReady by viewModel.rewardedAdReady.collectAsState()
            val rewardedLoadError by viewModel.rewardedLoadError.collectAsState()
            // Sprint 3-7 — the EN/বাংলা voice toggle.
            val voiceLanguage by viewModel.voiceLanguage.collectAsState()
            val activity = LocalContext.current as? Activity
            ChatScreen(
                user = viewModel.user,
                messages = messages,
                isListening = isListening,
                isSpeaking = isSpeaking,
                isThinking = isThinking,
                thinkingText = thinkingText,
                onSendMessage = { viewModel.sendMessage(it) },
                onStartVoice = { viewModel.startVoice(voiceLanguage.code) },
                onStopVoice = { viewModel.stopVoice() },
                voiceLanguage = voiceLanguage,
                onToggleVoiceLanguage = { viewModel.toggleVoiceLanguage() },
                onBlockFocusTime = { start, end, title, messageId ->
                    viewModel.onBlockFocusTime(start, end, title, messageId)
                },
                pendingDraft = pendingDraft,
                pendingDraftMessageId = pendingDraftMessageId,
                onConfirmDraft = { viewModel.confirmDraft() },
                onDiscardDraft = { viewModel.discardDraft() },
                onDraftEdited = { viewModel.editDraft(it) },
                // Sprint 3-5 — per-item remove on the split preview card.
                onRemoveSplitSubtask = { viewModel.removeSplitSubtask(it) },
                isBrainDumpActive = isBrainDumpActive,
                brainDumpTranscript = brainDumpTranscript,
                pendingBrainDumpDrafts = pendingBrainDumpDrafts,
                pendingBrainDumpMessageId = pendingBrainDumpMessageId,
                onConfirmBrainDump = { viewModel.confirmBrainDump() },
                onDiscardBrainDump = { viewModel.discardBrainDump() },
                onRemoveBrainDumpDraft = { viewModel.removeBrainDumpDraft(it) },
                // Sprint 3-6 — day-plan card + priority Apply card.
                pendingPlanDrafts = pendingPlanDrafts,
                pendingPlanMessageId = pendingPlanMessageId,
                onConfirmPlan = { viewModel.confirmPlan() },
                onDiscardPlan = { viewModel.discardPlan() },
                onRemovePlanDraft = { viewModel.removePlanDraft(it) },
                pendingPriorityMessageId = pendingPriorityMessageId,
                onApplyPriorityOrder = { viewModel.applyPriorityOrder() },
                onDismissPriorityOrder = { viewModel.dismissPriorityOrder() },
                chatCapState = chatCapState,
                rewardedAdReady = rewardedAdReady,
                rewardedLoadError = rewardedLoadError,
                onWatchRewardedAd = { activity?.let { viewModel.watchRewardedAd(it) } },
                onRetryRewardedAd = { viewModel.retryRewardedAd() }
            )
        }
        composable(
            route = NudgyRoute.Groups.route,
            deepLinks = listOf(navDeepLink { uriPattern = GROUP_JOIN_DEEP_LINK })
        ) { GroupsShell() }
        composable(NudgyRoute.Settings.route) {
            SettingsShell(
                // Sprint 4-4 — the Settings entry to the same weekly
                // review screen the progress ring opens (spec §7).
                onWeeklyReviewClick = { navController.navigate(NudgyRoute.WeeklyReview.route) }
            )
        }
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

/**
 * Sprint 3-1 — Q7(a): the bottom nav hides while the keyboard is open,
 * on every tab (the chat gets the full height). A pure function so the
 * rule is unit-testable: a live IME cannot be driven in Robolectric,
 * so the flip itself is a device-test item (spec §10.15).
 */
internal fun shouldShowBottomNav(isImeVisible: Boolean): Boolean = !isImeVisible

@Composable
fun NudgyBottomNav(
    navController: NavController,
    // Sprint 3-1 — the centre + button opens the Quick Chat sheet; it
    // does not navigate (Q8(a)). The full chat is reached from the
    // sheet (first message / expand).
    onCentreChatClick: () -> Unit = {}
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = NudgyRoute.fromRoute(navBackStackEntry?.destination?.route)
    NavigationBar {
        bottomNavEntries.forEach { entry ->
            val selected = currentRoute == entry.route
            if (entry.isCentreChat) {
                NavigationBarItem(
                    selected = selected,
                    onClick = {
                        // Already in the full chat — nothing to open.
                        if (currentRoute != NudgyRoute.Chat) onCentreChatClick()
                    },
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

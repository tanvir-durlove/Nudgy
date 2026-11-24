package com.nextgentool.nudgy.ui.navigation

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
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
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.intent.IncomingIntent
import com.nextgentool.nudgy.settings.AppLocale
import com.nextgentool.nudgy.settings.SettingsPreferences
import com.nextgentool.nudgy.ui.screens.CalendarScreen
import com.nextgentool.nudgy.ui.screens.ChatScreen
import com.nextgentool.nudgy.ui.screens.FocusScreen
import com.nextgentool.nudgy.ui.screens.GroupsScreen
import com.nextgentool.nudgy.ui.screens.HELP_FEEDBACK_EMAIL
import com.nextgentool.nudgy.ui.screens.PRIVACY_POLICY_URL
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
import com.nextgentool.nudgy.ui.viewmodel.GroupsViewModel
import com.nextgentool.nudgy.ui.viewmodel.HabitViewModel
import com.nextgentool.nudgy.ui.viewmodel.SettingsViewModel
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
fun NudgyApp(
    // Sprint 6-0 — the live theme mode ("system" | "light" |
    // "dark"), collected from the SettingsPreferences flow by
    // MainActivity. Default "system" keeps older call sites on the
    // previous behaviour (follow the system); the store is the only
    // writer, so this value is never stale.
    themeMode: String = SettingsPreferences.THEME_SYSTEM,
    // Sprint 5-3 — a pending group-notification tap: MainActivity
    // feeds the notified group id (from the poll worker's launch
    // intent); the Groups destination opens that group's detail and
    // consumes the id. Null in every other flow.
    openGroupId: String? = null,
    onOpenGroupIdConsumed: () -> Unit = {}
) {
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
    // Sprint 5-3 — a group-notification tap navigates to the Groups
    // tab first (singleTop — a no-op when already on Groups); the
    // Groups destination acts on the id and consumes it.
    LaunchedEffect(openGroupId) {
        if (openGroupId != null) {
            navigateTo(navController, NudgyRoute.Groups)
        }
    }
    // Sprint 6-0 — the theme is resolved here from the live mode
    // (v1's rule: "light" never dark, "dark" always dark, anything
    // else follows the system). Before this, NudgyTheme was called
    // with no argument, so the app was system-only even though the
    // dark tokens were complete.
    NudgyTheme(darkTheme = SettingsPreferences.resolveDarkTheme(themeMode, isSystemInDarkTheme())) {
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
                onInsertEventConsumed = { shareViewModel.consumeInsertEvent() },
                pendingOpenGroupId = openGroupId,
                onOpenGroupIdConsumed = onOpenGroupIdConsumed
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
    onInsertEventConsumed: () -> Unit = {},
    // Sprint 5-3 — a pending group-notification tap, passed to the
    // Groups destination (which opens the detail, then consumes it).
    // Null in every other flow.
    pendingOpenGroupId: String? = null,
    onOpenGroupIdConsumed: () -> Unit = {}
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
            // Sprint 5 fix round 4 — ChatViewModel.user is a StateFlow
            // now, so the screen follows a sign-in flip.
            val chatUser by viewModel.user.collectAsState()
            val activity = LocalContext.current as? Activity
            ChatScreen(
                user = chatUser,
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
            deepLinks = GROUP_JOIN_DEEP_LINKS
        ) { backStackEntry ->
            // Sprint 5-1 — Groups tab (spec §4): the v1 screen wired to
            // the S5-0 repository through GroupsViewModel. The deep-link
            // join code is a one-shot prefill (the user still taps Join).
            // Sprint 5-2 — group tasks (spec §5, Q3=(b) admin-only):
            // the task/schedule sections are on; delete/leave stay
            // gated for S5-4. Failed group-task writes toast (the
            // sheets close on submit, so there is no inline result).
            val groupsViewModel: GroupsViewModel = hiltViewModel()
            val joinCode = backStackEntry.arguments?.getString("code")
            LaunchedEffect(joinCode) {
                if (joinCode != null) groupsViewModel.setPrefillJoinCode(joinCode)
            }
            val context = LocalContext.current
            LaunchedEffect(groupsViewModel) {
                groupsViewModel.groupTaskError.collect { message ->
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            }
            // Sprint 5 fix round — the Groups "Sign in" button was dead
            // (onSignInClick defaulted to {}): the Activity + scope the
            // Credential Manager flow needs.
            val signInScope = rememberCoroutineScope()
            val groupsUiState by groupsViewModel.uiState.collectAsState()
            val completions by groupsViewModel.observeCompletions()
                .collectAsState(initial = emptyMap())
            GroupsScreen(
                user = groupsUiState.user,
                groups = groupsUiState.groups,
                allTasks = groupsUiState.allTasks,
                allEvents = groupsUiState.allEvents,
                onCreateGroup = { name -> groupsViewModel.createGroup(name) },
                onJoinGroup = { code -> groupsViewModel.joinGroup(code) },
                onSignInClick = {
                    (context as? Activity)?.let { groupsViewModel.signIn(it, signInScope) }
                },
                onCreateGroupTask = { groupId, title, description, due, priority, category, reminderSet ->
                    groupsViewModel.createGroupTask(
                        groupId, title, description, due, priority, category, reminderSet
                    )
                },
                onCreateGroupSchedule = { groupId, title, start, end, location, type ->
                    groupsViewModel.createGroupSchedule(
                        groupId, title, start, end, location, type
                    )
                },
                onUpdateGroupTask = { groupId, task ->
                    groupsViewModel.updateGroupTask(groupId, task)
                },
                onDeleteGroupItem = { groupId, itemId, isTask ->
                    groupsViewModel.deleteGroupItem(groupId, itemId, isTask)
                },
                onObserveMembers = { groupId -> groupsViewModel.observeMembers(groupId) },
                onRefreshMembers = { groupId -> groupsViewModel.refreshMembers(groupId) },
                onToggleTask = { task -> groupsViewModel.toggleGroupTask(task) },
                completions = completions,
                groupTasksEnabled = true,
                // Sprint 5-4 — delete/leave (feature 33, spec §7):
                // creator delete cascade + member leave/rejoin, with
                // confirm dialogs. The buttons live behind
                // groupWorkEnabled in the screen.
                groupWorkEnabled = true,
                onDeleteGroup = { groupsViewModel.deleteGroup(it) },
                onLeaveGroup = { groupsViewModel.leaveGroup(it) },
                // Sprint 5-3 — notices (feature 32, Q2=(a) text-only)
                // + the notification-tap entry point (feature 34).
                groupNoticesEnabled = true,
                onObserveNotices = { groupId -> groupsViewModel.observeNotices(groupId) },
                onRefreshNotices = { groupId -> groupsViewModel.refreshNotices(groupId) },
                onSendNotice = { groupId, text -> groupsViewModel.sendNotice(groupId, text) },
                // Sprint 5-5 — assign + activity history (feature 36,
                // Q10=(a)): creator-only assign + the Activity card.
                groupAssignEnabled = true,
                onObserveActivity = { groupId -> groupsViewModel.observeActivity(groupId) },
                onRefreshActivity = { groupId -> groupsViewModel.refreshActivity(groupId) },
                onAssignGroupTask = { groupId, itemId, assigneeUid ->
                    groupsViewModel.assignGroupTask(groupId, itemId, assigneeUid)
                },
                openGroupId = pendingOpenGroupId,
                onOpenGroupIdConsumed = onOpenGroupIdConsumed,
                prefillJoinCode = groupsUiState.prefillJoinCode,
                onPrefillJoinCodeConsumed = { groupsViewModel.consumePrefillJoinCode() }
            )
        }
        composable(NudgyRoute.Settings.route) {
            // Sprint 6-1 — Settings core (spec §4–§5): the wired rows
            // (profile/sign-in, Memories, BYOK, Privacy, Help &
            // feedback, calendar, delete). Sprint 6-2 adds the
            // notifications / appearance / language rows below.
            val viewModel: SettingsViewModel = hiltViewModel()
            val user by viewModel.user.collectAsState()
            val memories by viewModel.memories.collectAsState()
            val byokKey by viewModel.byokKey.collectAsState()
            val calendarConnected by viewModel.calendarConnected.collectAsState()
            val isCalendarSyncing by viewModel.isCalendarSyncing.collectAsState()
            val isDeleting by viewModel.isDeleting.collectAsState()
            // Sprint 6-2 — the notification toggles, theme mode and
            // language (spec §2 Q8/Q9/Q10), live from the store.
            val morningBriefEnabled by viewModel.morningBriefEnabled.collectAsState()
            val soundEnabled by viewModel.soundEnabled.collectAsState()
            val settingsThemeMode by viewModel.themeMode.collectAsState()
            val settingsLanguage by viewModel.language.collectAsState()
            val context = LocalContext.current
            LaunchedEffect(viewModel) {
                viewModel.settingsError.collect { message ->
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            }
            // The Activity + scope the Credential Manager flow needs
            // (Groups route pattern).
            val signInScope = rememberCoroutineScope()
            // The Calendar route's permission launcher pattern: the
            // route owns the launcher, the ViewModel owns the state.
            val calendarPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { grants ->
                if (grants.values.all { it }) {
                    viewModel.onCalendarPermissionGranted()
                }
            }
            SettingsShell(
                // Sprint 4-4 — the Settings entry to the same weekly
                // review screen the progress ring opens (spec §7).
                onWeeklyReviewClick = { navController.navigate(NudgyRoute.WeeklyReview.route) },
                user = user,
                memories = memories,
                byokKey = byokKey,
                calendarConnected = calendarConnected,
                isCalendarSyncing = isCalendarSyncing,
                isDeleting = isDeleting,
                deleteCompleted = viewModel.deleteCompleted,
                onSignInClick = {
                    (context as? Activity)?.let { viewModel.signIn(it, signInScope) }
                },
                onSignOutClick = { viewModel.signOut() },
                onDeleteAccountClick = { viewModel.deleteAccount() },
                onCalendarConnectClick = {
                    calendarPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.READ_CALENDAR,
                            Manifest.permission.WRITE_CALENDAR
                        )
                    )
                },
                onCalendarSyncClick = { viewModel.syncCalendarNow() },
                onSaveByokKey = { key -> viewModel.saveByokKey(key) },
                onResetByokKey = { viewModel.resetByokKey() },
                onPrivacyClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL))
                    )
                },
                onHelpFeedbackClick = {
                    val mailIntent = Intent(
                        Intent.ACTION_SENDTO,
                        Uri.parse("mailto:$HELP_FEEDBACK_EMAIL")
                    ).apply {
                        putExtra(Intent.EXTRA_SUBJECT, "Nudgy feedback")
                    }
                    context.startActivity(mailIntent)
                },
                onAddMemory = { content -> viewModel.addMemory(content) },
                onDeleteMemory = { memoryId -> viewModel.deleteMemory(memoryId) },
                // Sprint 6-2 — notifications / appearance / language.
                morningBriefEnabled = morningBriefEnabled,
                soundEnabled = soundEnabled,
                themeMode = settingsThemeMode,
                language = settingsLanguage,
                onMorningBriefChange = { enabled -> viewModel.setMorningBriefEnabled(enabled) },
                onSoundChange = { enabled -> viewModel.setSoundEnabled(enabled) },
                onThemeModeChange = { mode -> viewModel.setThemeMode(mode) },
                onLanguageChange = { code ->
                    viewModel.setLanguage(code)
                    // The new locale only reaches already-inflated
                    // resources through a restart (AppLocale).
                    (context as? Activity)?.let { AppLocale.applyAndRecreate(it, code) }
                }
            )
        }
    }
}

private data class BottomNavEntry(
    val route: NudgyRoute,
    // Sprint 6-2 — the label is a string resource (the language
    // setting renders it in বাংলা when "bn" is chosen).
    val titleRes: Int,
    val icon: ImageVector,
    val isCentreChat: Boolean = false
)

private val bottomNavEntries = listOf(
    BottomNavEntry(NudgyRoute.Today, R.string.nav_today, Icons.Outlined.Home),
    BottomNavEntry(NudgyRoute.Calendar, R.string.nav_calendar, Icons.Outlined.CalendarMonth),
    BottomNavEntry(NudgyRoute.Chat, R.string.nav_chat, Icons.Filled.Add, isCentreChat = true),
    BottomNavEntry(NudgyRoute.Groups, R.string.nav_groups, Icons.Outlined.Group),
    BottomNavEntry(NudgyRoute.Settings, R.string.nav_settings, Icons.Outlined.Settings)
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
                    label = { Text(stringResource(R.string.nav_chat)) }
                )
            } else {
                NavigationBarItem(
                    selected = selected,
                    onClick = { navigateTo(navController, entry.route) },
                    icon = { Icon(entry.icon, contentDescription = stringResource(entry.titleRes)) },
                    label = { Text(stringResource(entry.titleRes)) }
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

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
import androidx.fragment.app.FragmentActivity
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
import com.nextgentool.nudgy.MainActivity
import com.nextgentool.nudgy.domain.startOfDayMillis
import com.nextgentool.nudgy.intent.IncomingIntent
import com.nextgentool.nudgy.settings.AppLocale
import com.nextgentool.nudgy.settings.AppLockPrompt
import com.nextgentool.nudgy.settings.DataExporter
import com.nextgentool.nudgy.settings.DataRestorer
import com.nextgentool.nudgy.settings.SettingsPreferences
import com.nextgentool.nudgy.ui.screens.CalendarScreen
import com.nextgentool.nudgy.ui.screens.ChatScreen
import com.nextgentool.nudgy.ui.screens.FocusScreen
import com.nextgentool.nudgy.ui.screens.MatrixScreen
import com.nextgentool.nudgy.ui.screens.GroupsScreen
import com.nextgentool.nudgy.ui.screens.HELP_FEEDBACK_EMAIL
import com.nextgentool.nudgy.ui.screens.OnboardingScreen
import com.nextgentool.nudgy.ui.screens.PRIVACY_POLICY_URL
import com.nextgentool.nudgy.ui.screens.PlanScreen
import com.nextgentool.nudgy.ui.screens.PrivacyPolicyScreen
import com.nextgentool.nudgy.ui.screens.SearchScreen
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
import kotlinx.coroutines.launch
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
    onOpenGroupIdConsumed: () -> Unit = {},
    // Sprint 6-5 (step 3b) — a pending widget "+" tap: MainActivity
    // feeds the one-shot flag (from the widget's launch action); the
    // app navigates to Today and the quick-add field focuses there,
    // then the Today destination consumes the flag. False in every
    // other flow.
    openQuickAdd: Boolean = false,
    onOpenQuickAddConsumed: () -> Unit = {},
    // Sprint 6-5 (step 3d) — a pending widget agenda-row tap:
    // MainActivity feeds the tapped task id (from the row's launch
    // action); the app navigates to Tasks and the detail sheet
    // opens there, then the Tasks destination consumes the id —
    // the same 2e deep-link path the search tap uses. Null in
    // every other flow.
    openTaskId: String? = null,
    onOpenTaskIdConsumed: () -> Unit = {}
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
    // Sprint 6-5 (step 3b) — a widget "+" tap navigates to the Today
    // tab first (singleTop — a no-op when already on Today); the
    // Today destination focuses the quick-add field and consumes it.
    LaunchedEffect(openQuickAdd) {
        if (openQuickAdd) {
            navigateTo(navController, NudgyRoute.Today)
        }
    }
    // Sprint 6-5 (step 3d) — a widget agenda-row tap navigates to
    // the Tasks screen first (singleTop — a no-op when already on
    // Tasks); the Tasks destination opens the detail sheet and
    // consumes the id. Same pattern as the group and "+" effects
    // above, and the same 2e deep-link path the search tap uses.
    LaunchedEffect(openTaskId) {
        if (openTaskId != null) {
            navigateTo(navController, NudgyRoute.Tasks)
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
        // Sprint 6-3 — the tutorial re-view (spec §6): the Settings
        // row opens the same OnboardingScreen over the whole app
        // (re-view mode — no sample offer, no re-seed, the flag is
        // already true). The first launch shows it from MainActivity
        // instead; this state only ever opens from Settings.
        var showOnboarding by rememberSaveable { mutableStateOf(false) }
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
                onOpenGroupIdConsumed = onOpenGroupIdConsumed,
                // Sprint 6-5 (step 3b) — the widget "+" quick-add
                // (the Today destination focuses the field, then
                // consumes the flag).
                pendingOpenQuickAdd = openQuickAdd,
                onOpenQuickAddConsumed = onOpenQuickAddConsumed,
                // Sprint 6-5 (step 3d) — the widget agenda-row tap
                // (the Tasks destination opens the detail sheet,
                // then consumes the id).
                pendingOpenTaskId = openTaskId,
                onOpenTaskIdConsumed = onOpenTaskIdConsumed,
                // Sprint 6-3 — the Settings row opens the tutorial
                // re-view (no sample offer, no re-seed).
                onShowOnboardingClick = { showOnboarding = true }
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
        // Sprint 6-3 — the tutorial re-view, full-screen over the
        // app (same overlay pattern as the Quick Chat sheet above):
        // finishing or skipping only dismisses it — no seeding, no
        // flag change (the flag is already true; the first launch
        // never reaches this path).
        if (showOnboarding) {
            OnboardingScreen(
                isReView = true,
                onFinish = { showOnboarding = false }
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
    onOpenGroupIdConsumed: () -> Unit = {},
    // Sprint 6-5 (step 3b) — a pending widget "+" tap, passed to the
    // Today destination (which focuses the quick-add field, then
    // consumes it). False in every other flow.
    pendingOpenQuickAdd: Boolean = false,
    onOpenQuickAddConsumed: () -> Unit = {},
    // Sprint 6-5 (step 3d) — a pending widget agenda-row tap, passed
    // to the Tasks destination (which opens the detail sheet, then
    // consumes it). Null in every other flow.
    pendingOpenTaskId: String? = null,
    onOpenTaskIdConsumed: () -> Unit = {},
    // Sprint 6-3 — the Settings row opens the tutorial re-view
    // (NudgyApp owns the state; this route only reports the tap).
    onShowOnboardingClick: () -> Unit = {}
) {
    // Sprint 6-4 (step 2c) — a search tap on a group opens that
    // group's detail: the same openGroupId mechanism as the
    // notification tap (S5-3), but set locally by the Search
    // destination instead of by MainActivity's launch intent.
    // Cleared when the Groups destination consumes it (below).
    var searchOpenGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    // Sprint 6-4 (step 2e) — a search tap on a task opens that
    // task's detail sheet: the same state pattern as the group
    // deep-link above. The Tasks destination selects the task and
    // consumes the id (below); the NavHost never clears it itself.
    var searchOpenTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    // Sprint 6-4 (step 2f) — a search tap on a schedule opens that
    // event's edit dialog: the same state pattern as the task
    // deep-link above. The id alone is not enough — the Calendar
    // list is day-scoped, so the event's day travels with it (the
    // screen jumps the strip to that day, then selects the event
    // once the day's list emits). Both are cleared when the
    // Calendar destination consumes them (below).
    var searchOpenEventId by rememberSaveable { mutableStateOf<String?>(null) }
    var searchOpenEventDayMillis by rememberSaveable { mutableStateOf<Long?>(null) }
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
                    // Sprint 7-2a — the Templates ghost button on Today.
                    onTemplatesClick = { navController.navigate(NudgyRoute.Templates.route) },
                    onFocusClick = { navController.navigate(NudgyRoute.Focus.route) },
                    // Sprint 4-4 — the progress ring opens the week's
                    // summary (spec §7, Q9=(a)).
                    onWeeklyReviewClick = { navController.navigate(NudgyRoute.WeeklyReview.route) },
                    // Sprint 6-4 — the hero card's magnifier opens the
                    // global search screen (Q5). Plain navigate like
                    // the sibling entries: back pops to Today.
                    onSearchClick = { navController.navigate(NudgyRoute.Search.route) },
                    // Sprint 6-5 (step 3b) — the widget "+" quick-add:
                    // the quick-add field focuses on entry and
                    // consumes the flag once.
                    focusQuickAdd = pendingOpenQuickAdd,
                    onFocusQuickAddConsumed = onOpenQuickAddConsumed,
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
        // Sprint 7 — Priority matrix. NOT a bottom destination either:
        // reached from the "More in Nudgy" tile on Today (S7-2b wires
        // it); no tab is highlighted while it is open (see
        // NudgyRoute.all). Tap on a task opens that task's detail
        // sheet on the Tasks screen, through the same searchOpenTaskId
        // deep-link the Search results use (S6-4 step 2e) — the sheet
        // lives on Tasks, so the matrix stays a thin host.
        composable(NudgyRoute.Matrix.route) {
            MatrixScreen(
                onBackClick = { navController.popBackStack() },
                onTaskClick = { task ->
                    searchOpenTaskId = task.id
                    navController.navigate(NudgyRoute.Tasks.route)
                }
            )
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
                // Sprint 6-4 (step 2e) — search deep-link: the id a
                // search tap set (above) opens that task's detail
                // sheet on entry; TasksScreen consumes it once.
                // Sprint 6-5 (step 3d) — the widget agenda-row tap
                // feeds the SAME openTaskId (pendingOpenTaskId): one
                // source wins when both are set, and consuming
                // clears both — the two can never be set at once
                // (one user tap at a time), so nothing is lost.
                openTaskId = searchOpenTaskId ?: pendingOpenTaskId,
                onOpenTaskIdConsumed = {
                    searchOpenTaskId = null
                    onOpenTaskIdConsumed()
                },
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
        // Sprint 6-4 — Global search (S6-4): the query lives in the
        // SearchViewModel and the groups show the Room LIKE results
        // (tasks in step 2b; schedules, notes and groups in step 2c).
        // Tap on a task result opens that task's detail sheet (step
        // 2e): the id is set here and the Tasks destination selects
        // it on entry, through the same state pattern as the group
        // deep-link below — there is no task-detail route (the sheet
        // lives in TasksScreen behind TasksViewModel.selectTask).
        // Tap on a schedule result opens that event's edit dialog
        // (step 2f): the id + the event's day are set here and the
        // Calendar destination jumps to that day and selects the
        // event once the day's list emits, through the same state
        // pattern. Tap on a group result opens that group's detail
        // through the S5-3 openGroupId mechanism (set here, consumed
        // by the Groups destination). NOT a bottom destination (see
        // NudgyRoute.all).
        composable(NudgyRoute.Search.route) {
            SearchScreen(
                onBackClick = { navController.popBackStack() },
                onTaskClick = { task ->
                    searchOpenTaskId = task.id
                    navController.navigate(NudgyRoute.Tasks.route)
                },
                onScheduleClick = { event ->
                    searchOpenEventId = event.id
                    searchOpenEventDayMillis = startOfDayMillis(event.startEpochMillis)
                    navigateTo(navController, NudgyRoute.Calendar)
                },
                onGroupClick = { group ->
                    searchOpenGroupId = group.groupId
                    navigateTo(navController, NudgyRoute.Groups)
                }
            )
        }
        // Step 5b — Privacy Policy as an in-app screen (polish
        // item 2): the Settings privacy row navigates here instead
        // of opening the browser. NOT a bottom destination (see
        // NudgyRoute.all). Back pops to Settings; the footer row
        // opens the hosted page in the browser as the canonical
        // reference.
        composable(NudgyRoute.PrivacyPolicy.route) {
            val privacyContext = LocalContext.current
            PrivacyPolicyScreen(
                onBackClick = { navController.popBackStack() },
                onWebClick = {
                    privacyContext.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL))
                    )
                },
            )
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
                // Sprint 7 — month-grid dots (daysWithItems).
                daysWithItems = uiState.daysWithItems,
                onDropTask = { task, slotStart -> viewModel.dropTaskAtSlot(task, slotStart) },
                onAcceptSuggestedSlot = { viewModel.acceptSuggestedSlot() },
                onDismissConflict = { viewModel.dismissConflict() },
                // Sprint 2-4 — date link / create-event: the screen
                // jumps to the link's day (+ slot highlight) and opens
                // the add-event dialog prefilled, then consumes them.
                pendingDateLink = pendingDateLink,
                onDateLinkConsumed = onDateLinkConsumed,
                pendingInsertEvent = pendingInsertEvent,
                onInsertEventConsumed = onInsertEventConsumed,
                // Sprint 6-4 (step 2f) — search deep-link: the id +
                // day a search tap set (above) opens that event's
                // edit dialog on entry; CalendarScreen consumes
                // them once.
                openEventId = searchOpenEventId,
                openEventDayMillis = searchOpenEventDayMillis,
                onOpenEventIdConsumed = {
                    searchOpenEventId = null
                    searchOpenEventDayMillis = null
                }
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
                onRetryRewardedAd = { viewModel.retryRewardedAd() },
                // Sprint 7 — the backbar goes to the Today tab
                // (prototype scr-chat: back → Today).
                onNavigateBack = { navigateTo(navController, NudgyRoute.Today) }
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
                openGroupId = pendingOpenGroupId ?: searchOpenGroupId,
                onOpenGroupIdConsumed = {
                    // Sprint 6-4 (step 2c) — the search tap's id is
                    // consumed the same way as the notification tap's
                    // (S5-3): once, when the detail opens (or the id
                    // proves stale).
                    searchOpenGroupId = null
                    onOpenGroupIdConsumed()
                },
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
            // Sprint 6-6 (step 4d) — the app lock state (spec §2 Q6),
            // live from the store.
            val appLockEnabled by viewModel.appLockEnabled.collectAsState()
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
            // Sprint 6-6 (step 4d) — the backup & restore pickers
            // (spec §2 Q7): the route owns the launchers (the
            // calendar permission launcher's pattern), the ViewModel
            // owns the export/restore work. The pickers themselves
            // are device-gated — CI compile + the batch-end device
            // test.
            val dataScope = rememberCoroutineScope()
            val exportLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { result ->
                if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
                val uri = result.data?.data ?: return@rememberLauncherForActivityResult
                dataScope.launch {
                    val ok = viewModel.exportToUri(context, uri)
                    Toast.makeText(
                        context,
                        if (ok) context.getString(R.string.settings_export_done)
                        else context.getString(R.string.settings_export_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            val restoreLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { result ->
                if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
                val uri = result.data?.data ?: return@rememberLauncherForActivityResult
                dataScope.launch {
                    val message = when (val outcome = viewModel.restoreFromUri(context, uri)) {
                        is DataRestorer.RestoreOutcome.Restored -> context.getString(
                            R.string.settings_restore_done,
                            outcome.summary.totalInserted,
                            outcome.summary.totalSkipped
                        )
                        DataRestorer.RestoreOutcome.NotAnExportFile ->
                            context.getString(R.string.settings_restore_not_export)
                        DataRestorer.RestoreOutcome.ReadFailed ->
                            context.getString(R.string.settings_restore_failed)
                    }
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
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
                // Step 5b — the privacy row opens the in-app
                // screen now (the hosted page stays reachable from
                // the screen's footer row as the canonical reference).
                onPrivacyClick = {
                    navController.navigate(NudgyRoute.PrivacyPolicy.route)
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
                },
                // Sprint 6-6 (step 4d) — the app lock toggle + the
                // backup & restore taps. Turning the lock ON asks
                // for biometric/PIN once (the 4a KDoc's enable-time
                // prompt — prove it is the owner); on success the
                // session counts as unlocked, so the lock overlay
                // does not appear at once. OFF writes at once: the
                // user is already inside an unlocked session. When
                // the device has no usable authenticator the toggle
                // stays OFF with a toast — enabling a lock nobody
                // can open would be worse than no lock.
                appLockEnabled = appLockEnabled,
                onAppLockChange = { enabled ->
                    if (!enabled) {
                        viewModel.setAppLockEnabled(false)
                    } else {
                        val activity = context as? FragmentActivity
                        if (activity == null || !AppLockPrompt.canAuthenticate(activity)) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.settings_app_lock_unavailable),
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            AppLockPrompt.show(activity, onSuccess = {
                                viewModel.setAppLockEnabled(true)
                                (context as? MainActivity)?.markAppLockUnlocked()
                            })
                        }
                    }
                },
                onExportClick = {
                    exportLauncher.launch(
                        DataExporter.createExportDocumentIntent(DataExporter.defaultFileName())
                    )
                },
                onRestoreClick = { restoreLauncher.launch(DataRestorer.createOpenDocumentIntent()) },
                // Sprint 6-3 — the tutorial re-view (spec §6): the
                // row opens the same OnboardingScreen over the app.
                onShowOnboardingClick = onShowOnboardingClick
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

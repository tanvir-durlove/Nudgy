package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Scaffold
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.example.ai.ClientIntentPreFilter
import com.example.ai.GeminiClient
import com.example.ai.PreFilterResult
import com.example.ai.VoiceAssistantManager
import com.example.ads.ChatTurnCap
import com.example.ads.ChatTurnConsumeResult
import com.example.ads.NudgyAds
import com.example.ads.NudgyBannerAd
import com.example.analytics.NudgyAnalytics
import com.example.analytics.NudgyRemoteConfig
import com.example.analytics.RemoteConfigValues
import com.example.auth.AuthManager
import com.example.auth.NudgyUser
import com.example.auth.SignInScreen
import com.example.data.model.Priority
import com.example.data.model.TaskCategory
import com.example.data.repository.CalendarRepository
import com.example.data.repository.ChatRepository
import com.example.data.repository.DailyTaskStats
import com.example.data.repository.GroupRepository
import com.example.data.repository.GroupResult
import com.example.work.GroupPollWorker
import com.example.data.repository.MemoryRepository
import com.example.data.repository.TaskRepository
import com.example.reminders.ReminderManager
import com.example.ui.components.NudgyTopBar
import com.example.ui.dialogs.NudgyFloatingBubbleDialog
import com.example.ui.navigation.NudgyBottomBar
import com.example.ui.navigation.NudgyNavDestination
import com.example.ui.screens.CalendarScreen
import com.example.ui.screens.ChatMessage
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.GroupsScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.TasksScreen
import com.example.data.local.entity.TaskEntity
import com.example.ui.sheets.CreateTaskSheet
import com.example.ui.sheets.EditTaskSheet
import com.example.ui.sheets.GroupTaskDetailSheet
import com.example.ui.sheets.NotificationsSheet
import com.example.ui.sheets.YourNudgySheet
import com.example.ui.theme.NudgyCanvas
import com.example.ui.theme.NudgyTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

// SharedPreferences key for a group-join code stashed while signed out
// (App Links); consumed right after sign-in, then cleared.
private const val KEY_PENDING_GROUP_CODE = "pending_group_code"

// Splash screen: longest it may stay up while the Remote Config fetch
// (started in Application.onCreate) is still in flight, and the shortest
// it is shown at all — without a minimum the splash is invisible when the
// fetch completes from cache before the first frame. The splash hides as
// soon as the fetch completes AND the minimum has passed, OR the timeout
// elapses, whichever comes first — the launch is never held hostage to
// the network.
private const val SPLASH_MIN_SHOW_MS = 1200L
private const val SPLASH_MAX_WAIT_MS = 1500L

// Ads: tabs that show the bottom banner. Chat never shows one (the banner
// must not sit over the chat input), and neither do sheets/dialogs, which
// render above the scaffold content.
private val BANNER_DESTINATIONS = setOf(
    NudgyNavDestination.HOME,
    NudgyNavDestination.TASKS,
    NudgyNavDestination.CALENDAR,
    NudgyNavDestination.GROUPS
)

class MainActivity : ComponentActivity() {
    private lateinit var authManager: AuthManager
    private lateinit var taskRepository: TaskRepository
    private lateinit var calendarRepository: CalendarRepository
    private lateinit var groupRepository: GroupRepository
    private lateinit var memoryRepository: MemoryRepository
    private lateinit var chatRepository: ChatRepository
    private lateinit var preFilter: ClientIntentPreFilter
    private lateinit var geminiClient: GeminiClient
    private lateinit var voiceAssistantManager: VoiceAssistantManager
    private lateinit var reminderManager: ReminderManager
    private lateinit var analytics: NudgyAnalytics
    private lateinit var remoteConfig: NudgyRemoteConfig
    private val deepLinkJoinCodeFlow = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Splash screen: install BEFORE super.onCreate (required). It stays
        // up for at least SPLASH_MIN_SHOW_MS (measured from install, so it
        // is actually visible even when the fetch completes from cache
        // before the first frame) and at most SPLASH_MAX_WAIT_MS while the
        // Remote Config fetch is in flight; ads preload with test-ID
        // defaults in the meantime and swap to the fetched ID when it
        // arrives (see NudgyAds / the guarded LaunchedEffect below).
        val splashScreen = installSplashScreen()
        val splashStartMs = SystemClock.elapsedRealtime()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        authManager = AuthManager(this)
        taskRepository = TaskRepository(this)
        calendarRepository = CalendarRepository(this)
        groupRepository = GroupRepository(this)
        memoryRepository = MemoryRepository(this)
        chatRepository = ChatRepository(this)
        reminderManager = ReminderManager(this)
        analytics = NudgyAnalytics(this)
        // Remote Config lives in the Application (fetch started in
        // Application.onCreate); use that single shared instance.
        remoteConfig = (application as NudgyApplication).remoteConfig
        splashScreen.setKeepOnScreenCondition {
            val elapsed = SystemClock.elapsedRealtime() - splashStartMs
            elapsed < SPLASH_MIN_SHOW_MS ||
                (!remoteConfig.fetchCompleted.value && elapsed < SPLASH_MAX_WAIT_MS)
        }

        // Ads: consent (UMP) + Mobile Ads SDK. Ads themselves stay OFF
        // until Remote Config ads_enabled is flipped on.
        NudgyAds.initialize(this)

        preFilter = ClientIntentPreFilter(taskRepository, calendarRepository, memoryRepository)
        geminiClient = GeminiClient(applicationContext)
        voiceAssistantManager = VoiceAssistantManager(this)

        authManager.attemptSilentSignIn(lifecycleScope)

        // Group-notification tap: open the Groups tab. The tap intent uses
        // CLEAR_TASK, so this onCreate read handles it; App Links intents
        // are additionally routed via onNewIntent (see parseGroupJoinCode).
        val initialDestination = if (intent.getBooleanExtra(GroupPollWorker.EXTRA_OPEN_GROUPS, false)) {
            NudgyNavDestination.GROUPS
        } else {
            NudgyNavDestination.HOME
        }

        // App Links group-join: the launch intent may already carry a
        // /nudgy/join code (cold start, or a fresh instance on top).
        parseGroupJoinCode(intent)?.let { deepLinkJoinCodeFlow.value = it }

        setContent {
            val themePrefs = remember { getSharedPreferences("nudgy_settings", Context.MODE_PRIVATE) }
            var themeMode by remember { mutableStateOf(themePrefs.getString("theme_mode", "system") ?: "system") }
            NudgyTheme(
                darkTheme = when (themeMode) {
                    "light" -> false
                    "dark" -> true
                    else -> isSystemInDarkTheme()
                }
            ) {
                NudgyMainFlow(
                    authManager = authManager,
                    taskRepository = taskRepository,
                    calendarRepository = calendarRepository,
                    groupRepository = groupRepository,
                    memoryRepository = memoryRepository,
                    chatRepository = chatRepository,
                    reminderManager = reminderManager,
                    preFilter = preFilter,
                    geminiClient = geminiClient,
                    voiceAssistantManager = voiceAssistantManager,
                    analytics = analytics,
                    remoteConfig = remoteConfig,
                    initialDestination = initialDestination,
                    deepLinkJoinCodeFlow = deepLinkJoinCodeFlow,
                    onDeepLinkCodeConsumed = { deepLinkJoinCodeFlow.value = null },
                    themeMode = themeMode,
                    onThemeModeChange = { mode ->
                        themeMode = mode
                        themePrefs.edit().putString("theme_mode", mode).apply()
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parseGroupJoinCode(intent)?.let { deepLinkJoinCodeFlow.value = it }
    }

    /**
     * App Links group-join parser. Returns the code only for an exact
     * https://tanvir-durlove.github.io/nudgy/join?code=... VIEW intent;
     * anything else is ignored (never a crash, never a silent join).
     */
    private fun parseGroupJoinCode(intent: Intent?): String? {
        return try {
            if (intent?.action != Intent.ACTION_VIEW) return null
            val uri = intent.data ?: return null
            if (uri.scheme != "https") return null
            if (uri.host != "tanvir-durlove.github.io") return null
            if (uri.path != "/nudgy/join") return null
            uri.getQueryParameter("code")?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceAssistantManager.destroy()
    }

    override fun onResume() {
        super.onResume()
        // Minimization: drives the poll worker's 30-day inactive pause.
        // Every foregrounding counts as an app open; a fresh timestamp
        // resumes polling on the next tick.
        GroupPollWorker.recordAppOpen(this)
    }
}

@Composable
fun NudgyMainFlow(
    authManager: AuthManager,
    taskRepository: TaskRepository,
    calendarRepository: CalendarRepository,
    groupRepository: GroupRepository,
    memoryRepository: MemoryRepository,
    chatRepository: ChatRepository,
    reminderManager: ReminderManager,
    preFilter: ClientIntentPreFilter,
    geminiClient: GeminiClient,
    voiceAssistantManager: VoiceAssistantManager,
    analytics: NudgyAnalytics,
    remoteConfig: NudgyRemoteConfig,
    initialDestination: NudgyNavDestination = NudgyNavDestination.HOME,
    deepLinkJoinCodeFlow: StateFlow<String?>,
    onDeepLinkCodeConsumed: () -> Unit,
    themeMode: String = "system",
    onThemeModeChange: (String) -> Unit = {}
) {
    val user by authManager.userState.collectAsStateWithLifecycle()
    val deepLinkCode by deepLinkJoinCodeFlow.collectAsStateWithLifecycle()
    // Stashed deep-link code, delivered to the scaffold once per user.
    var stashedJoinCode by remember { mutableStateOf<String?>(null) }
    val isLoading by authManager.isLoading.collectAsStateWithLifecycle()
    val errorMessage by authManager.errorMessage.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* Permission callback */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNotificationPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasNotificationPermission) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    if (user == null) {
        // Signed out (or not yet signed in): stop group polling. This
        // is the ONLY cancel path — never cancel in onDispose, or
        // polling would die whenever the activity is destroyed.
        LaunchedEffect(Unit) {
            GroupPollWorker.cancel(context)
        }
        // App Links while signed out: stash the code; the signed-in branch
        // below completes the join right after sign-in, then clears it.
        LaunchedEffect(deepLinkCode) {
            if (deepLinkCode != null) {
                context.getSharedPreferences("nudgy_settings", Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_PENDING_GROUP_CODE, deepLinkCode)
                    .apply()
                onDeepLinkCodeConsumed()
            }
        }
        SignInScreen(
            authManager = authManager,
            scope = scope,
            isLoading = isLoading,
            errorMessage = errorMessage,
            onSignInSuccess = {}
        )
    } else {
        val currentUser = user!!

        LaunchedEffect(currentUser.uid) {
            chatRepository.checkAndSeedWelcomeMessage(currentUser.uid, currentUser.displayName)
            if (!currentUser.isGuest) {
                taskRepository.startCloudSync(currentUser.uid)
                memoryRepository.startCloudSync(currentUser.uid)
                calendarRepository.startCloudSync(currentUser.uid)
                // Restore first: a fresh install has no local groups yet,
                // so sync alone would attach nothing.
                groupRepository.restoreGroupsFromMirror(currentUser.uid)
                groupRepository.startGroupSync(currentUser.uid)
                // KEEP policy: re-scheduling never duplicates the work.
                GroupPollWorker.schedule(context)
                // App Links stashed while signed out: complete the join now
                // (prefilled sheet, never silent), then clear the stash.
                val stashed = context.getSharedPreferences("nudgy_settings", Context.MODE_PRIVATE)
                    .getString(KEY_PENDING_GROUP_CODE, null)
                if (!stashed.isNullOrBlank()) {
                    context.getSharedPreferences("nudgy_settings", Context.MODE_PRIVATE)
                        .edit()
                        .remove(KEY_PENDING_GROUP_CODE)
                        .apply()
                    stashedJoinCode = stashed
                }
            }
        }

        DisposableEffect(currentUser.uid) {
            onDispose {
                if (!currentUser.isGuest) {
                    taskRepository.stopCloudSync()
                    calendarRepository.stopCloudSync()
                    memoryRepository.stopCloudSync()
                    groupRepository.stopGroupSync()
                }
            }
        }

        NudgyAppScaffold(
            user = currentUser,
            authManager = authManager,
            taskRepository = taskRepository,
            calendarRepository = calendarRepository,
            groupRepository = groupRepository,
            memoryRepository = memoryRepository,
            chatRepository = chatRepository,
            reminderManager = reminderManager,
            preFilter = preFilter,
            geminiClient = geminiClient,
            voiceAssistantManager = voiceAssistantManager,
            analytics = analytics,
            remoteConfig = remoteConfig,
            initialDestination = initialDestination,
            pendingJoinCode = stashedJoinCode ?: deepLinkCode,
            onPendingJoinCodeConsumed = {
                stashedJoinCode = null
                onDeepLinkCodeConsumed()
            },
            themeMode = themeMode,
            onThemeModeChange = onThemeModeChange
        )
    }
}

@Composable
fun NudgyAppScaffold(
    user: NudgyUser,
    authManager: AuthManager,
    taskRepository: TaskRepository,
    calendarRepository: CalendarRepository,
    groupRepository: GroupRepository,
    memoryRepository: MemoryRepository,
    chatRepository: ChatRepository,
    reminderManager: ReminderManager,
    preFilter: ClientIntentPreFilter,
    geminiClient: GeminiClient,
    voiceAssistantManager: VoiceAssistantManager,
    analytics: NudgyAnalytics,
    remoteConfig: NudgyRemoteConfig,
    initialDestination: NudgyNavDestination = NudgyNavDestination.HOME,
    pendingJoinCode: String? = null,
    onPendingJoinCodeConsumed: () -> Unit = {},
    themeMode: String = "system",
    onThemeModeChange: (String) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var currentDestination by remember { mutableStateOf(initialDestination) }
    // App Links join: one-shot prefill for the Groups Join sheet. Guests get
    // the Groups tab's sign-in prompt instead (code stashed for after sign-in).
    var groupJoinPrefill by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingJoinCode) {
        if (pendingJoinCode != null) {
            currentDestination = NudgyNavDestination.GROUPS
            if (user.isGuest) {
                context.getSharedPreferences("nudgy_settings", Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_PENDING_GROUP_CODE, pendingJoinCode)
                    .apply()
            } else {
                groupJoinPrefill = pendingJoinCode
            }
            onPendingJoinCodeConsumed()
        }
    }
    var showProfileSheet by remember { mutableStateOf(false) }
    var showNotificationsSheet by remember { mutableStateOf(false) }
    var showCreateTaskSheet by remember { mutableStateOf(false) }
    var taskToEdit by remember { mutableStateOf<TaskEntity?>(null) }
    var selectedCalendarDayMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var showFloatingBubbleDialog by remember { mutableStateOf(false) }
    var isThinking by remember { mutableStateOf(false) }
    var isCalendarConnected by remember { mutableStateOf(calendarRepository.hasCalendarPermissions()) }

    // System Back: dismiss the topmost open surface first, then fall back
    // to the HOME tab. Only on HOME with nothing open does Back exit the
    // app (previously Back quit from any tab or sheet).
    BackHandler(
        enabled = showFloatingBubbleDialog || taskToEdit != null || showCreateTaskSheet ||
            showNotificationsSheet || showProfileSheet ||
            currentDestination != NudgyNavDestination.HOME
    ) {
        when {
            showFloatingBubbleDialog -> showFloatingBubbleDialog = false
            taskToEdit != null -> taskToEdit = null
            showCreateTaskSheet -> showCreateTaskSheet = false
            showNotificationsSheet -> showNotificationsSheet = false
            showProfileSheet -> showProfileSheet = false
            else -> currentDestination = NudgyNavDestination.HOME
        }
    }

    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val readGranted = permissions[Manifest.permission.READ_CALENDAR] == true
        val writeGranted = permissions[Manifest.permission.WRITE_CALENDAR] == true
        isCalendarConnected = readGranted && writeGranted
        if (isCalendarConnected) {
            scope.launch {
                calendarRepository.syncGoogleCalendar(user.uid, taskRepository)
            }
        }
    }

    LaunchedEffect(user.uid) {
        if (calendarRepository.hasCalendarPermissions()) {
            isCalendarConnected = true
            calendarRepository.syncGoogleCalendar(user.uid, taskRepository)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, user.uid) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                taskRepository.syncDirtyTasksToCloud(user.uid, scope)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val todayTasks by taskRepository.observeTodayTasks(user.uid).collectAsStateWithLifecycle(emptyList())
    val upcomingTasks by taskRepository.observeUpcomingTasks(user.uid).collectAsStateWithLifecycle(emptyList())
    val completedTasks by taskRepository.observeCompletedTasks(user.uid).collectAsStateWithLifecycle(emptyList())
    val allTasks by taskRepository.observeAllTasks(user.uid).collectAsStateWithLifecycle(emptyList())
    val dailyStats by taskRepository.observeTodayStats(user.uid).collectAsStateWithLifecycle(DailyTaskStats())

    // Ads: show one interstitial per N completed tasks (N and the on/off
    // switch come from Remote Config; ads_enabled defaults to OFF, so
    // nothing shows until it is deliberately enabled in the console).
    val remoteValues by remoteConfig.configState.collectAsStateWithLifecycle()
    // Ads: the interstitial unit ID comes from Remote Config. When the
    // fetched ID changes, reload the preloaded ad with the new ID — once
    // per ID, never on every recomposition. Starts at the test fallback,
    // so the initial (default) value is a no-op; the preload stays owned
    // by NudgyAds.initialize until a real ID arrives.
    var appliedInterstitialUnitId by remember { mutableStateOf(NudgyAds.TEST_INTERSTITIAL_UNIT_ID) }
    LaunchedEffect(remoteValues.adInterstitialUnitId) {
        val fetchedId = remoteValues.adInterstitialUnitId
        if (appliedInterstitialUnitId != fetchedId) {
            appliedInterstitialUnitId = fetchedId
            NudgyAds.loadInterstitial(context, fetchedId)
        }
    }
    // Ads: rewarded ad for Chat — preloaded with the Remote Config unit
    // ID, same once-per-ID pattern as the interstitial. The reward (+1
    // chat turn) is granted only by the onUserEarnedReward callback in
    // showRewarded, never on load or on show.
    var appliedRewardedUnitId by remember { mutableStateOf(NudgyAds.TEST_REWARDED_UNIT_ID) }
    LaunchedEffect(remoteValues.adRewardedUnitId) {
        val fetchedId = remoteValues.adRewardedUnitId
        if (appliedRewardedUnitId != fetchedId) {
            appliedRewardedUnitId = fetchedId
            NudgyAds.loadRewarded(context, fetchedId)
        }
    }
    // Ads: free daily chat-turn cap (RC: chat_free_turns_per_day, default
    // 5) + rewarded bonus turns (RC: ad_rewarded_max_turns_per_day,
    // default 3). The cap gates ONLY Gemini calls (see handleUserChat) —
    // local intent handling stays free and unblocked.
    var chatCapState by remember {
        mutableStateOf(
            ChatTurnCap.snapshot(
                context,
                remoteValues.chatFreeTurnsPerDay,
                remoteValues.adRewardedMaxTurnsPerDay
            )
        )
    }
    fun refreshChatCapState() {
        chatCapState = ChatTurnCap.snapshot(
            context,
            remoteValues.chatFreeTurnsPerDay,
            remoteValues.adRewardedMaxTurnsPerDay
        )
    }
    LaunchedEffect(remoteValues.chatFreeTurnsPerDay, remoteValues.adRewardedMaxTurnsPerDay) {
        refreshChatCapState()
    }
    val rewardedReady by NudgyAds.rewardedReady.collectAsStateWithLifecycle()
    val rewardedLoadError by NudgyAds.rewardedLoadError.collectAsStateWithLifecycle()
    // Ads: rewarded entry in Chat. The +1 turn is banked ONLY when the ad
    // reports the reward (completed viewing); a closed or skipped ad
    // grants nothing. The granted turn does not resend the pending
    // prompt — the user sends their message again.
    fun watchRewardedAdForChatTurn() {
        val activity = context as? Activity ?: return
        val shown = NudgyAds.showRewarded(activity) {
            val granted = ChatTurnCap.grantRewardedTurn(
                context,
                remoteValues.chatFreeTurnsPerDay,
                remoteValues.adRewardedMaxTurnsPerDay
            )
            refreshChatCapState()
            if (!granted) {
                Toast.makeText(
                    context,
                    "Today's rewarded turns are used up.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        if (!shown) {
            Toast.makeText(
                context,
                "The ad isn't ready yet — please try again in a moment.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // Ads: retry a failed rewarded load. The Chat button stays tappable
    // while a load error is present, so a failed load never strands it.
    fun retryRewardedAdLoad() {
        NudgyAds.loadRewarded(context, remoteValues.adRewardedUnitId)
    }
    val adsPrefs = remember { context.getSharedPreferences("nudgy_ads", android.content.Context.MODE_PRIVATE) }
    var lastCompletedCount by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(dailyStats.completed) {
        val previous = lastCompletedCount
        lastCompletedCount = dailyStats.completed
        if (previous == null || dailyStats.completed <= previous) return@LaunchedEffect
        if (!remoteValues.adsEnabled) return@LaunchedEffect
        val sinceLastAd = adsPrefs.getInt("completions_since_ad", 0) + (dailyStats.completed - previous)
        if (sinceLastAd >= remoteValues.adFrequencyTasks) {
            adsPrefs.edit().putInt("completions_since_ad", 0).apply()
            (context as? Activity)?.let { NudgyAds.showInterstitial(it) }
        } else {
            adsPrefs.edit().putInt("completions_since_ad", sinceLastAd).apply()
        }
    }
    val todayEvents by calendarRepository.observeEventsForDay(user.uid, System.currentTimeMillis()).collectAsStateWithLifecycle(emptyList())
    val allEvents by calendarRepository.observeAllEvents(user.uid).collectAsStateWithLifecycle(emptyList())

    // Group v1: joined groups + a groupId -> name map for origin badges.
    val groups by groupRepository.observeGroups().collectAsStateWithLifecycle(emptyList())
    val groupNames = groups.associate { it.groupId to it.name }

    // Group writes go through Firestore first (GroupRepository); a failure
    // (offline, not the creator, bad rules) must surface, not crash.
    fun launchGroupWrite(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    "Couldn't save that. Check your connection and try again.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    val memories by memoryRepository.observeMemories(user.uid).collectAsStateWithLifecycle(emptyList())

    val isListening by voiceAssistantManager.isListening.collectAsStateWithLifecycle()
    val isSpeaking by voiceAssistantManager.isSpeaking.collectAsStateWithLifecycle()
    val remoteConfigValues by remoteConfig.configState.collectAsStateWithLifecycle(RemoteConfigValues())

    val chatMessages by chatRepository.observeMessages(user.uid).collectAsStateWithLifecycle(emptyList())

    fun handleUserChat(prompt: String) {
        val userMsg = ChatMessage(UUID.randomUUID().toString(), true, prompt)
        isThinking = true

        scope.launch {
            try {
                chatRepository.addMessage(user.uid, userMsg)

                val preFilterResult = preFilter.tryHandle(
                    userId = user.uid,
                    userPrompt = prompt,
                    todayTasks = todayTasks,
                    events = todayEvents
                )

                when (preFilterResult) {
                    is PreFilterResult.Handled -> {
                        preFilterResult.createdTask?.let { created ->
                            analytics.logTaskCreated(created.title, created.priority.name, created.category.name, created.reminderSet)
                        }
                        val handledMsg = ChatMessage(
                            id = UUID.randomUUID().toString(),
                            senderIsUser = false,
                            text = preFilterResult.responseText,
                            actionType = preFilterResult.actionType,
                            associatedTask = preFilterResult.createdTask,
                            associatedEvent = preFilterResult.createdEvent,
                            focusStartMillis = preFilterResult.focusStartMillis,
                            focusEndMillis = preFilterResult.focusEndMillis,
                            focusDurationMinutes = preFilterResult.focusDurationMinutes,
                            quickSuggestions = preFilterResult.quickSuggestions
                        )
                        chatRepository.addMessage(user.uid, handledMsg)
                        voiceAssistantManager.speak(preFilterResult.responseText)
                    }
                    is PreFilterResult.Unhandled -> {
                        // Ads: the free daily cap gates Gemini turns only.
                        // A local (preFilter-handled) reply above never
                        // consumes a turn and is never blocked by the cap.
                        when (ChatTurnCap.tryConsumeTurn(
                            context,
                            remoteValues.chatFreeTurnsPerDay,
                            remoteValues.adRewardedMaxTurnsPerDay
                        )) {
                            ChatTurnConsumeResult.CAPPED -> {
                                refreshChatCapState()
                                chatRepository.addMessage(
                                    user.uid,
                                    ChatMessage(
                                        id = UUID.randomUUID().toString(),
                                        senderIsUser = false,
                                        text = "That's all 5 free answers for today. Watch one short ad and I'll answer 1 more question."
                                    )
                                )
                            }
                            else -> {
                                refreshChatCapState()
                                val history = chatMessages.map { Pair(it.text, it.senderIsUser) }
                                val responseText = geminiClient.generateNudgyResponse(
                                    userMessage = prompt,
                                    recentHistory = history,
                                    userMemories = memories.map { it.content },
                                    modelVersion = remoteConfigValues.geminiModelVersion
                                )
                                val geminiMsg = ChatMessage(
                                    id = UUID.randomUUID().toString(),
                                    senderIsUser = false,
                                    text = responseText,
                                    quickSuggestions = listOf("Plan my day", "Show today's tasks", "Find free time")
                                )
                                chatRepository.addMessage(user.uid, geminiMsg)
                                voiceAssistantManager.speak(responseText)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error processing chat prompt", e)
                val errorMsg = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    senderIsUser = false,
                    text = "Sorry, I had trouble processing that. Please try again."
                )
                chatRepository.addMessage(user.uid, errorMsg)
            } finally {
                isThinking = false
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = NudgyCanvas,
            topBar = {
                NudgyTopBar(
                    userInitials = user.initials,
                    isGuest = user.isGuest,
                    hasNotifications = todayTasks.any { !it.isCompleted },
                    onNotificationsClick = { showNotificationsSheet = true },
                    onProfileClick = { showProfileSheet = true },
                    onGuestBadgeClick = { showProfileSheet = true }
                )
            },
            bottomBar = {
                NudgyBottomBar(
                    currentDestination = currentDestination,
                    onNavigate = { destination ->
                        currentDestination = destination
                        analytics.logScreenViewed(destination.name.lowercase())
                    }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    when (currentDestination) {
                    NudgyNavDestination.HOME -> {
                        HomeScreen(
                            user = user,
                            todayTasks = todayTasks,
                            events = todayEvents,
                            dailyStats = dailyStats,
                            remoteConfigValues = remoteConfigValues,
                            onToggleTask = { task ->
                                scope.launch {
                                    taskRepository.toggleTaskCompletion(task)
                                    analytics.logTaskCompleted(task.id, dailyStats.completed + 1)
                                }
                            },
                            onAddTaskClick = { showCreateTaskSheet = true },
                            onTaskClick = { task -> taskToEdit = task },
                            onViewAllTasksClick = { currentDestination = NudgyNavDestination.TASKS },
                            groupNames = groupNames,
                            onPlanMyDayClick = {
                                analytics.logMorningBriefViewed()
                                currentDestination = NudgyNavDestination.CHAT
                                handleUserChat("Plan my day")
                            },
                            onCalendarClick = {
                                analytics.logCalendarConnected()
                                currentDestination = NudgyNavDestination.CALENDAR
                            },
                            onFreeTimeSuggestionClick = {
                                currentDestination = NudgyNavDestination.CHAT
                                handleUserChat("Find free time")
                            },
                            onPromoCardClick = { headline ->
                                analytics.logPromoCardClicked(headline)
                                currentDestination = NudgyNavDestination.CHAT
                                handleUserChat("How can I share Nudgy and invite friends?")
                            }
                        )
                    }

                    NudgyNavDestination.CHAT -> {
                        ChatScreen(
                            user = user,
                            messages = chatMessages,
                            isListening = isListening,
                            isSpeaking = isSpeaking,
                            isThinking = isThinking,
                            onSendMessage = { prompt -> handleUserChat(prompt) },
                            onStartVoice = {
                                analytics.logVoiceUsed("voice_input")
                                voiceAssistantManager.startListening(
                                    languageCode = "en-US",
                                    onResult = { voiceText -> handleUserChat(voiceText) }
                                )
                            },
                            onStopVoice = { voiceAssistantManager.stopListening() },
                            onTaskClick = { task -> taskToEdit = task },
                            onBlockFocusTime = { start, end, title, messageId ->
                                scope.launch {
                                    calendarRepository.blockFocusTime(user.uid, start, end, title)
                                    chatRepository.markFocusActionCompleted(messageId)
                                    analytics.logFocusTimeBlocked(45)
                                }
                            },
                            chatCapState = chatCapState,
                            rewardedAdReady = rewardedReady,
                            onWatchRewardedAd = { watchRewardedAdForChatTurn() },
                            rewardedLoadError = rewardedLoadError,
                            onRetryRewardedAd = { retryRewardedAdLoad() }
                        )
                    }

                    NudgyNavDestination.TASKS -> {
                        TasksScreen(
                            todayTasks = todayTasks,
                            upcomingTasks = upcomingTasks,
                            completedTasks = completedTasks,
                            dailyStats = dailyStats,
                            onToggleTask = { task ->
                                scope.launch {
                                    taskRepository.toggleTaskCompletion(task)
                                    analytics.logTaskCompleted(task.id, dailyStats.completed + 1)
                                }
                            },
                            onAddTaskClick = { showCreateTaskSheet = true },
                            onTaskClick = { task -> taskToEdit = task },
                            groupNames = groupNames
                        )
                    }

                    NudgyNavDestination.GROUPS -> {
                        GroupsScreen(
                            user = user,
                            groups = groups,
                            allTasks = allTasks,
                            allEvents = allEvents,
                            onCreateGroup = { name ->
                                groupRepository.createGroup(user.uid, user.displayName, name)
                            },
                            onJoinGroup = { code ->
                                groupRepository.joinGroup(user.uid, user.displayName, code)
                            },
                            onCreateGroupTask = { groupId, title, description, due, priority, category, reminderSet ->
                                launchGroupWrite {
                                    groupRepository.createGroupTask(
                                        groupId, user.uid, title, description, due,
                                        priority, category, reminderSet
                                    )
                                }
                            },
                            onCreateGroupSchedule = { groupId, title, start, end, location, type ->
                                launchGroupWrite {
                                    groupRepository.createGroupSchedule(
                                        groupId, user.uid, title, start, end, location, type
                                    )
                                }
                            },
                            onUpdateGroupTask = { groupId, task ->
                                launchGroupWrite {
                                    groupRepository.updateGroupTask(groupId, task)
                                }
                            },
                            onDeleteGroupItem = { groupId, itemId, isTask ->
                                launchGroupWrite {
                                    groupRepository.deleteGroupItem(groupId, user.uid, itemId, isTask)
                                }
                            },
                            onDeleteGroup = { groupId ->
                                scope.launch {
                                    when (val result = groupRepository.deleteGroup(user.uid, groupId)) {
                                        is GroupResult.Err -> Toast.makeText(
                                            context, result.message, Toast.LENGTH_LONG
                                        ).show()
                                        is GroupResult.Ok -> Unit
                                    }
                                }
                            },
                            onLeaveGroup = { groupId ->
                                scope.launch {
                                    when (val result = groupRepository.leaveGroup(user.uid, groupId)) {
                                        is GroupResult.Err -> Toast.makeText(
                                            context, result.message, Toast.LENGTH_LONG
                                        ).show()
                                        is GroupResult.Ok -> Unit
                                    }
                                }
                            },
                            onObserveMembers = { groupId ->
                                groupRepository.observeGroupMembers(groupId)
                            },
                            onRefreshMembers = { groupId ->
                                groupRepository.refreshGroupMembers(groupId)
                            },
                            onToggleTask = { task ->
                                scope.launch {
                                    taskRepository.toggleTaskCompletion(task)
                                    analytics.logTaskCompleted(task.id, dailyStats.completed + 1)
                                }
                            },
                            prefillJoinCode = groupJoinPrefill,
                            onPrefillJoinCodeConsumed = { groupJoinPrefill = null },
                            onSignInClick = { showProfileSheet = true }
                        )
                    }

                    NudgyNavDestination.CALENDAR -> {
                        CalendarScreen(
                            events = allEvents,
                            tasks = allTasks,
                            isCalendarConnected = isCalendarConnected,
                            onConnectGoogleCalendar = {
                                calendarPermissionLauncher.launch(
                                    arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
                                )
                            },
                            onSyncNow = {
                                scope.launch {
                                    calendarRepository.syncGoogleCalendar(user.uid, taskRepository)
                                }
                            },
                            onBlockFocusTime = { start, end, title ->
                                scope.launch {
                                    calendarRepository.blockFocusTime(user.uid, start, end, title)
                                    analytics.logFocusTimeBlocked(45)
                                }
                            },
                            onAddEvent = { title, start, end, loc, type ->
                                scope.launch {
                                    val conflict = calendarRepository.checkConflict(user.uid, start, end)
                                    if (conflict.hasConflict) {
                                        Toast.makeText(
                                            context,
                                            "Heads up: this overlaps with '${conflict.conflictingEvent?.title}'",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                    calendarRepository.addEvent(user.uid, title, start, end, loc, type)
                                }
                            },
                            onDeleteEvent = { eventId ->
                                scope.launch {
                                    calendarRepository.deleteEvent(eventId, user.uid)
                                }
                            },
                            onSelectDay = { dayMillis ->
                                selectedCalendarDayMillis = dayMillis
                            },
                            groupNames = groupNames
                        )
                    }
                }
                }

                // Ads: anchored adaptive banner, bottom of the tab content.
                // One RC unit ID reused on all four tabs; never on Chat (no
                // banner over the chat input), and never when ads are off
                // or consent does not allow ads — no empty gap then. The
                // slot reserves real layout space, so the banner never
                // floats over content or the bottom navigation bar.
                if (remoteValues.adsEnabled &&
                    currentDestination in BANNER_DESTINATIONS &&
                    NudgyAds.canRequestAds(context)
                ) {
                    NudgyBannerAd(unitId = remoteValues.adBannerUnitId)
                }
            }
        }

        if (showFloatingBubbleDialog) {
            val pendingTask = todayTasks.firstOrNull { !it.isCompleted }
            NudgyFloatingBubbleDialog(
                reminderTitle = pendingTask?.title ?: "No pending tasks right now",
                onDoneClick = {
                    showFloatingBubbleDialog = false
                    analytics.logNudgeInteracted("done")
                    pendingTask?.let {
                        scope.launch { taskRepository.toggleTaskCompletion(it) }
                    }
                },
                onSnoozeClick = {
                    showFloatingBubbleDialog = false
                    analytics.logNudgeInteracted("snooze_30m")
                    pendingTask?.let {
                        reminderManager.scheduleReminder(
                            taskId = it.id,
                            taskTitle = it.title,
                            triggerEpochMillis = System.currentTimeMillis() + 30 * 60 * 1000L
                        )
                    }
                },
                onDismiss = { showFloatingBubbleDialog = false }
            )
        }

        if (showNotificationsSheet) {
            val pendingAlertTasks = todayTasks.filter { !it.isCompleted }
            NotificationsSheet(
                pendingTasks = pendingAlertTasks,
                onToggleTask = { task ->
                    scope.launch {
                        taskRepository.toggleTaskCompletion(task)
                        analytics.logTaskCompleted(task.id, dailyStats.completed + 1)
                    }
                },
                onSnoozeTask = { task ->
                    reminderManager.scheduleReminder(
                        taskId = task.id,
                        taskTitle = task.title,
                        triggerEpochMillis = System.currentTimeMillis() + 30 * 60 * 1000L
                    )
                },
                onDismiss = { showNotificationsSheet = false }
            )
        }

        if (showProfileSheet) {
            YourNudgySheet(
                user = user,
                authManager = authManager,
                scope = scope,
                memories = memories,
                onAddMemory = { content ->
                    scope.launch {
                        memoryRepository.addMemory(user.uid, content)
                    }
                },
                onDeleteMemory = { memoryId ->
                    scope.launch {
                        memoryRepository.deleteMemory(user.uid, memoryId)
                    }
                },
                onDismiss = { showProfileSheet = false },
                onNavigateCalendar = { currentDestination = NudgyNavDestination.CALENDAR },
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange
            )
        }

        if (showCreateTaskSheet) {
            CreateTaskSheet(
                onDismiss = { showCreateTaskSheet = false },
                onSaveTask = { title, description, dueEpochMillis, priority, category, reminderSet ->
                    scope.launch {
                        val task = taskRepository.createTask(
                            userId = user.uid,
                            title = title,
                            description = description,
                            dueEpochMillis = dueEpochMillis,
                            priority = priority,
                            category = category,
                            reminderSet = reminderSet
                        )
                        // Tasks live in the Tasks list only — no calendar event is
                        // created for a task (keeps Tasks and Calendar separate).
                        analytics.logTaskCreated(title, priority.name, category.name, reminderSet)
                    }
                }
            )
        }

        taskToEdit?.let { task ->
            // Group v1 routing: a group row tapped from Home / Tasks / Chat
            // opens the READ-ONLY detail — never EditTaskSheet. Only the
            // creator edits/deletes, and only from the Groups tab detail view
            // (GroupsScreen routes those through GroupRepository). This keeps
            // exactly one edit path for shared items.
            if (task.groupId != null) {
                GroupTaskDetailSheet(
                    task = task,
                    groupName = groupNames[task.groupId] ?: "Group",
                    onDismiss = { taskToEdit = null },
                    onToggleComplete = { toggledTask ->
                        scope.launch {
                            taskRepository.toggleTaskCompletion(toggledTask)
                        }
                    }
                )
            } else {
                EditTaskSheet(
                    task = task,
                    onDismiss = { taskToEdit = null },
                    onUpdateTask = { updated ->
                        scope.launch {
                            taskRepository.updateTask(updated)
                        }
                    },
                    onDeleteTask = { taskId ->
                        scope.launch {
                            taskRepository.deleteTask(user.uid, taskId)
                        }
                    },
                    onToggleComplete = { toggledTask ->
                        scope.launch {
                            taskRepository.toggleTaskCompletion(toggledTask)
                        }
                    }
                )
            }
        }
    }
}

package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.example.ai.ClientIntentPreFilter
import com.example.ai.GeminiClient
import com.example.ai.PreFilterResult
import com.example.ai.VoiceAssistantManager
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
import com.example.data.repository.MemoryRepository
import com.example.data.repository.TaskRepository
import com.example.reminders.ReminderManager
import com.example.ui.components.NudgyTopBar
import com.example.ui.dialogs.NudgyFloatingBubbleDialog
import com.example.ui.navigation.FloatingMascotButton
import com.example.ui.navigation.NudgyBottomBar
import com.example.ui.navigation.NudgyNavDestination
import com.example.ui.screens.CalendarScreen
import com.example.ui.screens.ChatMessage
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.TasksScreen
import com.example.data.local.entity.TaskEntity
import com.example.ui.sheets.CreateTaskSheet
import com.example.ui.sheets.EditTaskSheet
import com.example.ui.sheets.NotificationsSheet
import com.example.ui.sheets.YourNudgySheet
import com.example.ui.theme.NudgyCanvas
import com.example.ui.theme.NudgyTheme
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {
    private lateinit var authManager: AuthManager
    private lateinit var taskRepository: TaskRepository
    private lateinit var calendarRepository: CalendarRepository
    private lateinit var memoryRepository: MemoryRepository
    private lateinit var chatRepository: ChatRepository
    private lateinit var preFilter: ClientIntentPreFilter
    private lateinit var geminiClient: GeminiClient
    private lateinit var voiceAssistantManager: VoiceAssistantManager
    private lateinit var reminderManager: ReminderManager
    private lateinit var analytics: NudgyAnalytics
    private lateinit var remoteConfig: NudgyRemoteConfig

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        authManager = AuthManager(this)
        taskRepository = TaskRepository(this)
        calendarRepository = CalendarRepository(this)
        memoryRepository = MemoryRepository(this)
        chatRepository = ChatRepository(this)
        reminderManager = ReminderManager(this)
        analytics = NudgyAnalytics(this)
        remoteConfig = NudgyRemoteConfig()

        preFilter = ClientIntentPreFilter(taskRepository, calendarRepository, memoryRepository)
        geminiClient = GeminiClient(applicationContext)
        voiceAssistantManager = VoiceAssistantManager(this)

        authManager.attemptSilentSignIn(lifecycleScope)

        setContent {
            NudgyTheme {
                NudgyMainFlow(
                    authManager = authManager,
                    taskRepository = taskRepository,
                    calendarRepository = calendarRepository,
                    memoryRepository = memoryRepository,
                    chatRepository = chatRepository,
                    reminderManager = reminderManager,
                    preFilter = preFilter,
                    geminiClient = geminiClient,
                    voiceAssistantManager = voiceAssistantManager,
                    analytics = analytics,
                    remoteConfig = remoteConfig
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceAssistantManager.destroy()
    }
}

@Composable
fun NudgyMainFlow(
    authManager: AuthManager,
    taskRepository: TaskRepository,
    calendarRepository: CalendarRepository,
    memoryRepository: MemoryRepository,
    chatRepository: ChatRepository,
    reminderManager: ReminderManager,
    preFilter: ClientIntentPreFilter,
    geminiClient: GeminiClient,
    voiceAssistantManager: VoiceAssistantManager,
    analytics: NudgyAnalytics,
    remoteConfig: NudgyRemoteConfig
) {
    val user by authManager.userState.collectAsStateWithLifecycle()
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
            }
        }

        DisposableEffect(currentUser.uid) {
            onDispose {
                if (!currentUser.isGuest) {
                    taskRepository.stopCloudSync()
                    calendarRepository.stopCloudSync()
                    memoryRepository.stopCloudSync()
                }
            }
        }

        NudgyAppScaffold(
            user = currentUser,
            authManager = authManager,
            taskRepository = taskRepository,
            calendarRepository = calendarRepository,
            memoryRepository = memoryRepository,
            chatRepository = chatRepository,
            reminderManager = reminderManager,
            preFilter = preFilter,
            geminiClient = geminiClient,
            voiceAssistantManager = voiceAssistantManager,
            analytics = analytics,
            remoteConfig = remoteConfig
        )
    }
}

@Composable
fun NudgyAppScaffold(
    user: NudgyUser,
    authManager: AuthManager,
    taskRepository: TaskRepository,
    calendarRepository: CalendarRepository,
    memoryRepository: MemoryRepository,
    chatRepository: ChatRepository,
    reminderManager: ReminderManager,
    preFilter: ClientIntentPreFilter,
    geminiClient: GeminiClient,
    voiceAssistantManager: VoiceAssistantManager,
    analytics: NudgyAnalytics,
    remoteConfig: NudgyRemoteConfig
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var currentDestination by remember { mutableStateOf(NudgyNavDestination.HOME) }
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
    val todayEvents by calendarRepository.observeEventsForDay(user.uid, System.currentTimeMillis()).collectAsStateWithLifecycle(emptyList())
    val allEvents by calendarRepository.observeAllEvents(user.uid).collectAsStateWithLifecycle(emptyList())
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
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
                                val deviceLocale = Locale.getDefault()
                                val speechLanguage = if (deviceLocale.language == "bn") "bn-BD" else deviceLocale.toLanguageTag()
                                voiceAssistantManager.startListening(
                                    languageCode = speechLanguage,
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
                            }
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
                            onTaskClick = { task -> taskToEdit = task }
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
                            }
                        )
                    }
                }

                if (currentDestination != NudgyNavDestination.CHAT) {
                    FloatingMascotButton(
                        onClick = { showFloatingBubbleDialog = true },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 20.dp, bottom = 16.dp)
                    )
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
                onOpenChatClick = {
                    showFloatingBubbleDialog = false
                    analytics.logNudgeInteracted("chat")
                    currentDestination = NudgyNavDestination.CHAT
                    handleUserChat(if (pendingTask != null) "How should I approach '${pendingTask.title}'?" else "Plan my day")
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
                onNavigateCalendar = { currentDestination = NudgyNavDestination.CALENDAR }
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

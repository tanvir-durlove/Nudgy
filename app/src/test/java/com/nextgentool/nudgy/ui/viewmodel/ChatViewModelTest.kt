package com.nextgentool.nudgy.ui.viewmodel

import android.app.Activity
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.ads.ChatTurnCap
import com.nextgentool.nudgy.ads.RewardedAdGateway
import com.nextgentool.nudgy.ai.BrainDumpSession
import com.nextgentool.nudgy.ai.ClientIntentPreFilter
import com.nextgentool.nudgy.ai.DraftKind
import com.nextgentool.nudgy.ai.GeminiClient
import com.nextgentool.nudgy.ai.LlmConfig
import com.nextgentool.nudgy.ai.LlmConfigProvider
import com.nextgentool.nudgy.ai.LlmConnectivity
import com.nextgentool.nudgy.ai.LlmFallbackHandler
import com.nextgentool.nudgy.ai.LlmServerCounter
import com.nextgentool.nudgy.ai.LlmUsageCounter
import com.nextgentool.nudgy.ai.ReplySoundPlayer
import com.nextgentool.nudgy.ai.VoiceInput
import com.nextgentool.nudgy.ai.VoiceInputError
import com.nextgentool.nudgy.auth.AuthManager
import com.nextgentool.nudgy.auth.GroupAuth
import com.nextgentool.nudgy.auth.NudgyUser
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.local.entity.CalendarEventEntity
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.repository.CalendarRepository
import com.nextgentool.nudgy.data.repository.ChatRepository
import com.nextgentool.nudgy.data.repository.MemoryRepository
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.endOfDayMillis
import com.nextgentool.nudgy.domain.startOfDayMillis
import com.nextgentool.nudgy.domain.usecase.PlanDayUseCase
import com.nextgentool.nudgy.ui.screens.ChatActionType
import com.nextgentool.nudgy.ui.screens.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

/**
 * Sprint 3-0 — ChatViewModel send-pipeline tests over real in-memory
 * Room + the real prefilter (same harness as
 * ClientIntentPreFilterTest / CalendarViewModelTest, no Hilt).
 *
 * What is pinned here (spec §8, S3-0 half):
 *  - guardrails run FIRST: a refused message stores nothing (not even
 *    the user's own message) and creates nothing.
 *  - a handled message stores user + assistant messages and the
 *    prefilter's creation lands in the repository.
 *  - an Unhandled message gets a LOCAL fallback answer — no LLM call
 *    (the ViewModel takes no GeminiClient at all), no crash.
 *  - every assistant reply plays the reply sound exactly once
 *    (FakeReplySoundPlayer counts it); the welcome seed is silent.
 *  - isSpeaking stays false: the chat never speaks (§4.4).
 *
 * Sprint 3-3 — the LLM fallback runs through the same send entry
 * point, on a recording GeminiClient (the transport methods are
 * `protected open` for exactly this). No BYOK key + central gate
 * closed (the default) behaves exactly like S3-0: an Unhandled
 * message gets the local fallback and spends no turn. With a BYOK
 * key, a structured reply becomes a pending draft and Confirm saves
 * it; a Gemini failure refunds the local turn.
 *
 * The Main dispatcher is set per test (CalendarViewModelTest pattern):
 * StandardTestDispatcher(testScheduler), ViewModel built after setMain,
 * assertions via `messages.first { ... }`, resetMainAfterDrain()
 * in finally (run-1071 fix: the drain must precede resetMain).
 * Calendar permissions are deliberately NOT granted: the chat flow
 * never syncs, and blockFocusTime's Google export no-ops without them
 * while the local event is still inserted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatViewModelTest {

    private class FakeReplySoundPlayer : ReplySoundPlayer {
        var playCount = 0
        override fun playReplySound() {
            playCount++
        }
    }

    // Sprint 3-4 — a fake recognizer: records start/stop calls and
    // delivers canned results and errors on demand, so the brain-dump
    // restart loop is testable without a real SpeechRecognizer
    // (Robolectric cannot drive one). Delivering a result drops
    // isListening first, exactly like the real manager's onResults.
    private class FakeVoiceInput : VoiceInput {
        override val isListening = MutableStateFlow(false)
        var startCalls = 0
        var stopCalls = 0
        var lastLanguageCode: String? = null
        private var onResult: ((String) -> Unit)? = null
        private var onError: ((VoiceInputError) -> Unit)? = null

        override fun startListening(
            languageCode: String,
            onResult: (String) -> Unit,
            onError: (VoiceInputError) -> Unit
        ) {
            startCalls++
            lastLanguageCode = languageCode
            this.onResult = onResult
            this.onError = onError
            isListening.value = true
        }

        override fun stopListening() {
            stopCalls++
            isListening.value = false
        }

        fun deliverResult(text: String) {
            isListening.value = false
            onResult?.invoke(text)
        }

        fun deliverError(error: VoiceInputError) {
            isListening.value = false
            onError?.invoke(error)
        }
    }

    // Sprint 3-4 — a fake rewarded ad: records show/retry calls and
    // fires the reward on demand, so the cap-entry grant path is
    // testable with no AdMob SDK.
    private class FakeRewardedAdGateway : RewardedAdGateway {
        override val isReady = MutableStateFlow(false)
        override val loadError = MutableStateFlow<String?>(null)
        var showCalls = 0
        var retryCalls = 0
        var showResult = true
        private var rewardCallback: (() -> Unit)? = null

        override fun show(activity: Activity, onRewardEarned: () -> Unit): Boolean {
            showCalls++
            rewardCallback = onRewardEarned
            return showResult
        }

        override fun retryLoad() {
            retryCalls++
        }

        fun simulateRewardEarned() {
            rewardCallback?.invoke()
        }
    }

    // Sprint 3-3 — records which Gemini transport ran and answers
    // with a canned structured response (or null = the call failed).
    private class RecordingGeminiClient(context: Context) : GeminiClient(context) {
        var restCalls = 0
        var aiLogicCalls = 0
        var restResponse: String? = null
        var aiLogicResponse: String? = null

        override suspend fun generateViaRest(
            userMessage: String,
            recentHistory: List<Pair<String, Boolean>>,
            sanitizedModel: String,
            apiKey: String,
            systemInstruction: String,
            maxOutputTokens: Int
        ): String? {
            restCalls++
            return restResponse
        }

        override suspend fun generateViaAiLogic(
            userMessage: String,
            recentHistory: List<Pair<String, Boolean>>,
            modelId: String,
            systemInstruction: String,
            maxOutputTokens: Int
        ): String? {
            aiLogicCalls++
            return aiLogicResponse
        }
    }

    private lateinit var context: Context
    private lateinit var db: NudgyDatabase
    private val viewModelStore = ViewModelStore()
    private lateinit var chatRepository: ChatRepository
    private lateinit var preFilter: ClientIntentPreFilter
    private lateinit var taskRepository: TaskRepository
    private lateinit var calendarRepository: CalendarRepository
    private lateinit var fakeVoice: FakeVoiceInput
    private lateinit var fakeRewarded: FakeRewardedAdGateway
    private lateinit var fakeSound: FakeReplySoundPlayer
    private lateinit var memoryRepository: MemoryRepository
    private lateinit var recordingGemini: RecordingGeminiClient
    private var online = true
    private var config = LlmConfig(
        centralEnabled = false,
        freeTurnsPerDay = 5L,
        maxRewardedTurnsPerDay = 3L,
        modelVersion = "gemini-3.5-flash-lite"
    )

    private val userId = "test-user-1"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("nudgy_auth_prefs", Context.MODE_PRIVATE)
            .edit().putString("active_user_id", userId).apply()
        context.getSharedPreferences("nudgy_ads", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("nudgy_settings", Context.MODE_PRIVATE).edit().clear().apply()
        db = Room.inMemoryDatabaseBuilder(context, NudgyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        chatRepository = ChatRepository(context, db)
        taskRepository = TaskRepository(context, db)
        calendarRepository = CalendarRepository(context, db)
        memoryRepository = MemoryRepository(context, db)
        preFilter = ClientIntentPreFilter(
            taskRepository, calendarRepository, memoryRepository, context.assets
        )
        fakeVoice = FakeVoiceInput()
        fakeRewarded = FakeRewardedAdGateway()
        fakeSound = FakeReplySoundPlayer()
        recordingGemini = RecordingGeminiClient(context)
        online = true
        config = LlmConfig(
            centralEnabled = false,
            freeTurnsPerDay = 5L,
            maxRewardedTurnsPerDay = 3L,
            modelVersion = "gemini-3.5-flash-lite"
        )
    }

    @After
    fun tearDown() {
        // PR #86 rule: cancel the ViewModel scopes before the database
        // closes, so no viewModelScope coroutine can run Room work
        // against the closed DB and poison the next test's TestScope.
        viewModelStore.clear()
        // S4-1 round 7 — drain in-flight Room IO before closing the DB.
        // clear() cancels the ViewModels' scopes, but a Room call already
        // running on a real IO thread (a ViewModel init sync, or a
        // never-ending Room Flow collection re-querying after a write)
        // does not notice the cancellation until it returns; closing the
        // DB first makes that call throw IllegalStateException, which the
        // global coroutines-test ExceptionCollector buffers and replays
        // into the next test's runTest enter() as UncaughtExceptionsBeforeTest
        // (the compose rule wraps every test method in runTest, so the
        // victim is the next compose test — BoardDragTest). 500ms is ample
        // for in-memory Room calls.
        Thread.sleep(500)
        db.close()
        context.getSharedPreferences("nudgy_ads", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("nudgy_settings", Context.MODE_PRIVATE).edit().clear().apply()
    }

    /**
     * Run-1071 fix — the resetMain race, shared by all 61 tests below.
     * resetMain() must be the LAST step, not the first: the ViewModel is
     * still alive in a test's finally, and its init coroutines dispatch
     * Room work to real Dispatchers.IO threads, which resume back onto
     * Main from those threads. resetMain() while such a resume is in
     * flight throws "Dispatchers.Main is used concurrently with setting
     * it" — the two run-1071 failures on merge head cc004507 (different
     * victim tests each time; the same tree passed PR run 1070, so it is
     * a race, not a product bug). Cancel the scope first, then drain the
     * real IO the same way tearDown used to — the drain lived in
     * tearDown, after resetMain: too late to protect it. Same proven
     * pattern as FocusViewModelTest (PR #128) and SettingsViewModelTest
     * (PR #133); PR #133 round 3 had already applied it inline at TWO
     * sites in this file, which is why the race survived at the other
     * 59. tearDown keeps its own idempotent clear + drain + DB close
     * as the safety net.
     */
    private fun resetMainAfterDrain() {
        viewModelStore.clear()
        Thread.sleep(500)
        Dispatchers.resetMain()
    }

    private fun newViewModel(fakeAuth: GroupAuth? = null) = ChatViewModel(
        chatRepository, preFilter, taskRepository, calendarRepository,
        fakeVoice, fakeSound,
        LlmFallbackHandler(
            geminiClient = recordingGemini,
            context = context,
            configProvider = LlmConfigProvider { config },
            serverCounter = LlmServerCounter { _, _, _, _ -> LlmUsageCounter.CounterResult.ALLOWED },
            connectivity = LlmConnectivity { online }
        ),
        memoryRepository,
        context,
        LlmConfigProvider { config },
        fakeRewarded,
        PlanDayUseCase(),
        fakeAuth
    ).also { viewModelStore.put("chat", it) }

    private fun setByokKey() {
        context.getSharedPreferences("nudgy_settings", Context.MODE_PRIVATE)
            .edit().putString("custom_gemini_api_key", "test-byok-key").apply()
    }

    private fun freeTurnsUsed(): Int =
        ChatTurnCap.snapshot(context, 5L, 3L).freeTurnsUsed

    private fun hourOf(millis: Long): Int =
        Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.HOUR_OF_DAY)

    @Test
    fun `welcome message greets neutrally with no hardcoded name`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            val messages = viewModel.messages.first { it.size == 1 }
            assertEquals("Hi! What can I help you get done today?", messages.single().text)
            assertFalse(messages.single().senderIsUser)
            // The welcome seed is silent — no reply sound for it.
            assertEquals(0, fakeSound.playCount)
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `guardrails refusal stores only the refusal and creates nothing`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.sendMessage("i want to kill somebody")
            val messages = viewModel.messages.first { it.size == 2 }
            // The refused user's message is NOT stored: only the
            // welcome + the refusal are in history.
            assertTrue(messages.none { it.senderIsUser })
            assertEquals("I can't help with that.", messages.last().text)
            assertTrue(taskRepository.getAllTasksOnce(userId).isEmpty())
            assertEquals(1, fakeSound.playCount)
        } finally {
            resetMainAfterDrain()
        }
    }

    // Sprint 3-2 (Q2 = (a)) — draft-then-confirm: the send now returns
    // a PREVIEW, not a creation. The task exists only after Confirm.
    // The old name asserted immediate creation; the new name states
    // the new flow, for the same reason as the prefilter tests.
    @Test
    fun `handled task creation drafts first and creates only on confirm`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.sendMessage("call tasnim at 5pm")
            val messages = viewModel.messages.first { it.size == 3 }
            assertTrue(messages[1].senderIsUser)
            assertEquals("call tasnim at 5pm", messages[1].text)
            assertFalse(messages[2].senderIsUser)
            assertEquals(ChatActionType.DRAFT_PREVIEW, messages[2].actionType)
            assertNotNull(viewModel.pendingDraft.value)
            assertEquals(17, hourOf(viewModel.pendingDraft.value!!.startEpochMillis))
            // Nothing is in the DB yet — the preview is only a question.
            assertTrue(taskRepository.getAllTasksOnce(userId).isEmpty())
            assertEquals(1, fakeSound.playCount)
            // Confirm saves it and posts the receipt.
            viewModel.confirmDraft()
            val afterConfirm = viewModel.messages.first { it.size == 4 }
            assertEquals(ChatActionType.TASK_PREVIEW, afterConfirm[3].actionType)
            assertTrue(afterConfirm[3].text.contains("asnim", ignoreCase = true))
            val tasks = taskRepository.getAllTasksOnce(userId)
            assertEquals(1, tasks.size)
            assertEquals(17, hourOf(tasks.single().dueDateEpochMillis))
            assertTrue(tasks.single().title.contains("tasnim", ignoreCase = true))
            assertNull(viewModel.pendingDraft.value)
            assertEquals(2, fakeSound.playCount)
        } finally {
            resetMainAfterDrain()
        }
    }

    // Chat isVoice — a typed message is not voice: its bubble keeps
    // reading "YOU".
    @Test
    fun `typed message carries isVoice false`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.sendMessage("call tasnim at 5pm")
            val messages = viewModel.messages.first { it.size == 3 }
            assertTrue(messages[1].senderIsUser)
            assertEquals("call tasnim at 5pm", messages[1].text)
            assertFalse(messages[1].isVoice)
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `discarding a draft creates nothing and posts a discarded note`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.sendMessage("call tasnim at 5pm")
            viewModel.messages.first { it.size == 3 }
            assertNotNull(viewModel.pendingDraft.value)
            viewModel.discardDraft()
            val messages = viewModel.messages.first { it.size == 4 }
            assertTrue(messages.last().text.contains("discarded", ignoreCase = true))
            assertTrue(taskRepository.getAllTasksOnce(userId).isEmpty())
            assertNull(viewModel.pendingDraft.value)
            // A second discard is a no-op — no extra message appears.
            viewModel.discardDraft()
            testScheduler.advanceUntilIdle()
            assertEquals(4, viewModel.messages.value.size)
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `editing the draft changes what confirm creates`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.sendMessage("call tasnim at 5pm")
            viewModel.messages.first { it.size == 3 }
            val draft = viewModel.pendingDraft.value!!
            viewModel.editDraft(draft.withTitle("Call Tasnim about the launch"))
            assertEquals("Call Tasnim about the launch", viewModel.pendingDraft.value!!.title)
            viewModel.confirmDraft()
            viewModel.messages.first { it.size == 4 }
            val tasks = taskRepository.getAllTasksOnce(userId)
            assertEquals(1, tasks.size)
            assertEquals("Call Tasnim about the launch", tasks.single().title)
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `a new message abandons the pending draft`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.sendMessage("call tasnim at 5pm")
            viewModel.messages.first { it.size == 3 }
            assertNotNull(viewModel.pendingDraft.value)
            // The first send must fully finish (isThinking false)
            // before the second send, or the thinking guard ignores it.
            viewModel.isThinking.first { !it }
            viewModel.sendMessage("show today's tasks")
            viewModel.messages.first { it.size == 5 }
            assertNull(viewModel.pendingDraft.value)
            // Confirm now is a no-op: nothing was ever created.
            viewModel.confirmDraft()
            testScheduler.advanceUntilIdle()
            assertTrue(taskRepository.getAllTasksOnce(userId).isEmpty())
            assertEquals(5, viewModel.messages.value.size)
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `unhandled small talk gets a local fallback and creates nothing`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.sendMessage("hello")
            val messages = viewModel.messages.first { it.size == 3 }
            assertTrue(messages[1].senderIsUser)
            assertTrue(messages[2].text.contains("didn't quite catch"))
            assertTrue(taskRepository.getAllTasksOnce(userId).isEmpty())
            assertEquals(1, fakeSound.playCount)
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `unhandled Bengali small talk gets a Bengali fallback`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.sendMessage("হ্যালো")
            val messages = viewModel.messages.first { it.size == 3 }
            assertTrue(messages[2].text.contains("বুঝতে পারিনি"))
            assertTrue(taskRepository.getAllTasksOnce(userId).isEmpty())
        } finally {
            resetMainAfterDrain()
        }
    }

    // Sprint 3-3 — the LLM fallback through the real send entry
    // point: an Unhandled question with a BYOK key returns a
    // structured draft (preview card, nothing saved), and Confirm
    // saves exactly the validated task.
    @Test
    fun `unhandled question with BYOK drafts from structured JSON and confirm creates the task`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            setByokKey()
            recordingGemini.restResponse =
                """{"action": "create_tasks", "tasks": [{"title": "Buy milk", "due": "2026-11-22T09:00:00", "priority": "HIGH"}], "events": [], "subtasks": [], "reply": "Got it, I'll add it."}"""
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.sendMessage("what is the meaning of life?")
            val messages = viewModel.messages.first { it.size == 3 }
            assertTrue(messages[1].senderIsUser)
            assertFalse(messages[2].senderIsUser)
            assertEquals(ChatActionType.DRAFT_PREVIEW, messages[2].actionType)
            assertEquals("Got it, I'll add it.", messages[2].text)
            val draft = viewModel.pendingDraft.value
            assertNotNull(draft)
            assertEquals("Buy milk", draft!!.title)
            assertEquals(9, hourOf(draft.startEpochMillis))
            // Nothing is in the DB yet — the preview is only a question.
            assertTrue(taskRepository.getAllTasksOnce(userId).isEmpty())
            assertEquals(1, recordingGemini.restCalls)
            assertEquals(0, recordingGemini.aiLogicCalls)
            assertEquals(1, freeTurnsUsed())
            // Confirm saves it and posts the receipt.
            viewModel.confirmDraft()
            val afterConfirm = viewModel.messages.first { it.size == 4 }
            assertEquals(ChatActionType.TASK_PREVIEW, afterConfirm[3].actionType)
            val tasks = taskRepository.getAllTasksOnce(userId)
            assertEquals(1, tasks.size)
            assertEquals("Buy milk", tasks.single().title)
            assertEquals(9, hourOf(tasks.single().dueDateEpochMillis))
            assertNull(viewModel.pendingDraft.value)
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `Gemini failure on an unhandled message refunds the turn and answers locally`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            setByokKey()
            recordingGemini.restResponse = null // The transport failed.
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.sendMessage("what is the meaning of life?")
            val messages = viewModel.messages.first { it.size == 3 }
            assertTrue(messages[2].text.contains("didn't quite catch"))
            assertNull(viewModel.pendingDraft.value)
            assertTrue(taskRepository.getAllTasksOnce(userId).isEmpty())
            // The local turn is refunded (the server turn is not — the
            // handler test pins that side of the limit).
            assertEquals(0, freeTurnsUsed())
            assertEquals(1, recordingGemini.restCalls)
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `offline unhandled message answers locally with zero cap consumed`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            setByokKey() // Even with a key, offline means no LLM call.
            online = false
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.sendMessage("what is the meaning of life?")
            val messages = viewModel.messages.first { it.size == 3 }
            assertTrue(messages[2].text.contains("didn't quite catch"))
            assertEquals(0, freeTurnsUsed())
            assertEquals(0, recordingGemini.restCalls)
            assertEquals(0, recordingGemini.aiLogicCalls)
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `unhandled message with no BYOK and the central gate closed spends no turn`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            // No key set, gate closed (the default config): the S3-0
            // behaviour, now running through the handler.
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.sendMessage("what is the meaning of life?")
            val messages = viewModel.messages.first { it.size == 3 }
            assertTrue(messages[2].text.contains("didn't quite catch"))
            assertNull(viewModel.pendingDraft.value)
            assertEquals(0, freeTurnsUsed())
            assertEquals(0, recordingGemini.restCalls)
            assertEquals(0, recordingGemini.aiLogicCalls)
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `isSpeaking stays false - the chat never speaks`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            assertFalse(viewModel.isSpeaking.value)
            viewModel.sendMessage("call tasnim at 5pm")
            viewModel.messages.first { it.size == 3 }
            // A reply arrived and the mascot still never talks (§4.4).
            assertFalse(viewModel.isSpeaking.value)
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `focus proposal can be blocked from the message`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.sendMessage("find free time")
            val messages = viewModel.messages.first { it.size == 3 }
            val proposal = messages[2]
            assertEquals(ChatActionType.FOCUS_TIME_PROPOSAL, proposal.actionType)
            assertNotNull(proposal.focusStartMillis)
            assertNotNull(proposal.focusEndMillis)
            viewModel.onBlockFocusTime(
                proposal.focusStartMillis!!, proposal.focusEndMillis!!,
                "Focus Time", proposal.id
            )
            // S4-1 round 4 — the proposal's slot can cross midnight:
            // after 23:20 the empty-day slot (next :00/:30 after
            // now + 10 min) starts at 00:00 tomorrow, so the blocked
            // event lands on the proposal's own day, not "today".
            // (Round-3 CI 23:26 UTC failed here; round-2 CI 23:10
            // passed with a 23:30 slot that still started today.)
            val dayOfProposal = startOfDayMillis(proposal.focusStartMillis!!)
            val events = calendarRepository.observeEventsForDay(userId, dayOfProposal)
                .first { it.any { event -> event.title == "Focus Time" } }
            assertTrue(events.any { it.title == "Focus Time" })
            val updated = viewModel.messages.first { list ->
                list.any { it.id == proposal.id && it.focusActionCompleted }
            }
            assertTrue(updated.any { it.id == proposal.id && it.focusActionCompleted })
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `blank message is ignored`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.sendMessage("   ")
            // No new message appears and thinking never engages.
            assertFalse(viewModel.isThinking.value)
            assertEquals(1, viewModel.messages.value.size)
            assertEquals(0, fakeSound.playCount)
        } finally {
            resetMainAfterDrain()
        }
    }

    // ==================== Sprint 3-4 — voice brain dump ================
    //
    // The mic is a toggle that opens a continuous listening session:
    // every utterance restarts the recognizer until Stop, the
    // transcript splits locally, and ONE combined preview card offers
    // Confirm-all / per-item remove / Discard. Nothing reaches the DB
    // before Confirm. The real SpeechRecognizer is never driven here —
    // FakeVoiceInput delivers canned utterances and errors.

    /** Drives a 3-utterance dump and stops it; returns the ViewModel
     *  with the combined preview pending. */
    private suspend fun threeItemDump(viewModel: ChatViewModel) {
        viewModel.messages.first { it.size == 1 } // welcome seeded
        viewModel.startVoice()
        assertTrue(viewModel.isBrainDumpActive.value)
        fakeVoice.deliverResult("buy milk")
        fakeVoice.deliverResult("call tasnim at 5pm")
        fakeVoice.deliverResult("doctor appointment tomorrow at 6am")
        viewModel.stopVoice()
        // The transcript (user message) + the preview (assistant).
        viewModel.messages.first { it.size == 3 }
    }

    @Test
    fun `brain dump previews three drafts and creates nothing yet`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            threeItemDump(viewModel)
            // The restart loop: initial start + one restart per
            // utterance (3). Stop ends the session (1 stop call).
            assertEquals(4, fakeVoice.startCalls)
            assertEquals(1, fakeVoice.stopCalls)
            assertFalse(viewModel.isBrainDumpActive.value)
            assertEquals("buy milk. call tasnim at 5pm. doctor appointment tomorrow at 6am", viewModel.messages.value[1].text)
            assertTrue(viewModel.messages.value[1].senderIsUser)
            val drafts = viewModel.pendingBrainDumpDrafts.value
            assertEquals(3, drafts.size)
            assertTrue(drafts.any { it.title.contains("buy milk", ignoreCase = true) })
            assertTrue(drafts.any { it.title.contains("tasnim", ignoreCase = true) })
            assertTrue(drafts.any { it.title.contains("doctor", ignoreCase = true) })
            assertEquals(ChatActionType.DRAFT_PREVIEW, viewModel.messages.value[2].actionType)
            // Nothing is in the DB yet — the preview is only a question.
            assertTrue(taskRepository.getAllTasksOnce(userId).isEmpty())
            // No LLM turn was spent: every piece parsed locally.
            assertEquals(0, freeTurnsUsed())
        } finally {
            resetMainAfterDrain()
        }
    }

    // Chat isVoice — the brain-dump transcript IS the voice message:
    // its bubble reads "YOU · VOICE". The flag survives the Room
    // round-trip (entity + migration), so this asserts the message
    // the flow re-emits, not the transient object.
    @Test
    fun `brain dump transcript carries isVoice true`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.startVoice()
            fakeVoice.deliverResult("buy milk")
            viewModel.stopVoice()
            val messages = viewModel.messages.first { it.size == 3 }
            assertTrue(messages[1].senderIsUser)
            assertEquals("buy milk", messages[1].text)
            assertTrue(messages[1].isVoice)
            // The welcome seed and the assistant preview are not voice.
            assertFalse(messages[0].isVoice)
            assertFalse(messages[2].isVoice)
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `brain dump confirm-all creates every draft`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            threeItemDump(viewModel)
            viewModel.confirmBrainDump()
            val afterConfirm = viewModel.messages.first { it.size == 4 }
            assertTrue(afterConfirm[3].text.contains("Done — created 3:"))
            assertTrue(afterConfirm[3].text.contains("buy milk", ignoreCase = true))
            // Two tasks (buy milk, call tasnim) + one calendar event
            // (doctor appointment is a SCHEDULE draft).
            val tasks = taskRepository.getAllTasksOnce(userId)
            assertEquals(2, tasks.size)
            assertTrue(tasks.any { it.title.contains("tasnim", ignoreCase = true) })
            val tomorrow = startOfDayMillis(System.currentTimeMillis()) + 24 * 60 * 60 * 1000L
            val events = calendarRepository.observeEventsForDay(userId, tomorrow).first()
            assertTrue(events.any { it.title.contains("doctor", ignoreCase = true) })
            assertTrue(viewModel.pendingBrainDumpDrafts.value.isEmpty())
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `brain dump remove one then confirm creates the rest`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            threeItemDump(viewModel)
            val tasnimIndex = viewModel.pendingBrainDumpDrafts.value
                .indexOfFirst { it.title.contains("tasnim", ignoreCase = true) }
            assertTrue(tasnimIndex >= 0)
            viewModel.removeBrainDumpDraft(tasnimIndex)
            assertEquals(2, viewModel.pendingBrainDumpDrafts.value.size)
            viewModel.confirmBrainDump()
            viewModel.messages.first { it.size == 4 }
            val tasks = taskRepository.getAllTasksOnce(userId)
            assertEquals(1, tasks.size)
            assertTrue(tasks.single().title.contains("buy milk", ignoreCase = true))
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `brain dump discard creates nothing`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            threeItemDump(viewModel)
            viewModel.discardBrainDump()
            val afterDiscard = viewModel.messages.first { it.size == 4 }
            assertTrue(afterDiscard[3].text.contains("nothing was created"))
            assertTrue(taskRepository.getAllTasksOnce(userId).isEmpty())
            assertTrue(viewModel.pendingBrainDumpDrafts.value.isEmpty())
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `brain dump removing the last item discards the card`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.startVoice()
            fakeVoice.deliverResult("buy milk")
            viewModel.stopVoice()
            viewModel.messages.first { it.size == 3 }
            assertEquals(1, viewModel.pendingBrainDumpDrafts.value.size)
            viewModel.removeBrainDumpDraft(0)
            assertTrue(viewModel.pendingBrainDumpDrafts.value.isEmpty())
            assertNull(viewModel.pendingBrainDumpMessageId.value)
            assertTrue(taskRepository.getAllTasksOnce(userId).isEmpty())
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `brain dump with nothing parseable asks a question and creates nothing`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.startVoice()
            // A QUESTION, not a statement: the prefilter catch-all
            // deliberately captures any short non-question statement
            // as a to-do ("um yeah hmm" becomes a draft titled
            // "Um yeah hmm"), so only a question reaches Unhandled
            // here — then the no-key local Reply counts as unparsed
            // and the dump asks one clarifying question.
            fakeVoice.deliverResult("why is the sky blue?")
            viewModel.stopVoice()
            val messages = viewModel.messages.first { it.size == 3 }
            assertTrue(messages[2].text.contains("couldn't split"))
            assertTrue(viewModel.pendingBrainDumpDrafts.value.isEmpty())
            assertTrue(taskRepository.getAllTasksOnce(userId).isEmpty())
            // The LLM fallback ran with no BYOK key and the central
            // gate closed: a local answer, zero turns spent.
            assertEquals(0, freeTurnsUsed())
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `brain dump bare-hour piece stays unresolved while the other draft previews`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.startVoice()
            fakeVoice.deliverResult("call mom at 3")
            fakeVoice.deliverResult("buy milk")
            viewModel.stopVoice()
            viewModel.messages.first { it.size == 3 }
            // One draft previews; the bare-hour piece is recorded as
            // unresolved in the preview text (never guessed).
            val drafts = viewModel.pendingBrainDumpDrafts.value
            assertEquals(1, drafts.size)
            assertTrue(drafts.single().title.contains("buy milk", ignoreCase = true))
            assertTrue(viewModel.messages.value[2].text.contains("still need"))
            // The clarification was cleared before the next piece, so
            // "buy milk" was parsed on its own — and nothing lingers
            // for the next typed message to trip over.
            assertFalse(preFilter.hasPendingClarification())
            assertTrue(taskRepository.getAllTasksOnce(userId).isEmpty())
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `brain dump restarts after each utterance and after silence then gives up`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.startVoice()
            assertEquals(1, fakeVoice.startCalls)
            fakeVoice.deliverResult("buy milk")
            assertEquals(2, fakeVoice.startCalls)
            // Five silent restarts, then the sixth silence ends the
            // session and the heard transcript is processed.
            repeat(BrainDumpSession.MAX_SILENT_RESTARTS) {
                fakeVoice.deliverError(VoiceInputError.NO_MATCH)
            }
            assertEquals(2 + BrainDumpSession.MAX_SILENT_RESTARTS, fakeVoice.startCalls)
            assertTrue(viewModel.isBrainDumpActive.value)
            fakeVoice.deliverError(VoiceInputError.NO_MATCH)
            assertEquals(2 + BrainDumpSession.MAX_SILENT_RESTARTS, fakeVoice.startCalls)
            assertFalse(viewModel.isBrainDumpActive.value)
            viewModel.messages.first { it.size == 3 }
            assertEquals(1, viewModel.pendingBrainDumpDrafts.value.size)
            assertEquals(1, fakeVoice.stopCalls)
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `brain dump network error ends the session but still processes what was heard`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.startVoice()
            fakeVoice.deliverResult("buy milk")
            fakeVoice.deliverError(VoiceInputError.NETWORK)
            assertFalse(viewModel.isBrainDumpActive.value)
            viewModel.messages.first { it.size == 3 }
            assertEquals(1, viewModel.pendingBrainDumpDrafts.value.size)
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `brain dump with nothing heard posts a short note and creates nothing`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.startVoice()
            viewModel.stopVoice()
            val messages = viewModel.messages.first { it.size == 2 }
            assertTrue(messages[1].text.contains("didn't catch anything"))
            assertTrue(viewModel.pendingBrainDumpDrafts.value.isEmpty())
            assertTrue(taskRepository.getAllTasksOnce(userId).isEmpty())
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `brain dump offline uses local parse only and spends no turn`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            online = false
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.startVoice()
            fakeVoice.deliverResult("buy milk")
            fakeVoice.deliverResult("call tasnim at 5pm")
            viewModel.stopVoice()
            viewModel.messages.first { it.size == 3 }
            assertEquals(2, viewModel.pendingBrainDumpDrafts.value.size)
            assertEquals(0, freeTurnsUsed())
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `typed message abandons a pending brain dump`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            threeItemDump(viewModel)
            assertEquals(3, viewModel.pendingBrainDumpDrafts.value.size)
            viewModel.sendMessage("show today's tasks")
            viewModel.messages.first { it.size == 5 }
            assertTrue(viewModel.pendingBrainDumpDrafts.value.isEmpty())
            assertNull(viewModel.pendingBrainDumpMessageId.value)
        } finally {
            resetMainAfterDrain()
        }
    }

    // ==================== Sprint 3-4 — rewarded entry ====================

    @Test
    fun `cap exhausted shows capped state and a rewarded grant banks a turn`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            // Spend all 5 free turns, then refresh: capped.
            repeat(5) { ChatTurnCap.tryConsumeTurn(context, 5L, 3L) }
            viewModel.refreshCapState()
            val capped = viewModel.chatCapState.value
            assertNotNull(capped)
            assertTrue(capped!!.isCapped)
            // Show the ad and earn the reward: +1 bonus turn, no
            // longer capped. A show that never earns grants nothing.
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
            viewModel.watchRewardedAd(activity)
            assertEquals(1, fakeRewarded.showCalls)
            assertTrue(viewModel.chatCapState.value!!.isCapped)
            fakeRewarded.simulateRewardEarned()
            val afterGrant = viewModel.chatCapState.value
            assertNotNull(afterGrant)
            assertEquals(1, afterGrant!!.bonusTurns)
            assertFalse(afterGrant.isCapped)
            // Retry goes through the gateway, not the SDK.
            viewModel.retryRewardedAd()
            assertEquals(1, fakeRewarded.retryCalls)
        } finally {
            resetMainAfterDrain()
        }
    }

    // ---- Sprint 3-5 — subtask split (feature 20) ----

    private val splitJson =
        """{"action": "split_task", "subtasks": [{"title": "Buy flour"}, {"title": "Mix the dough"}, {"title": "Bake for 30 minutes"}], "tasks": [], "events": [], "reply": "Here is how I'd break it down."}"""

    private suspend fun createParentTask(title: String) =
        taskRepository.createTask(
            userId = userId,
            title = title,
            dueEpochMillis = System.currentTimeMillis() + 86_400_000L
        )

    @Test
    fun `split by title with BYOK drafts subtasks and confirm creates them and updates progress`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            setByokKey()
            recordingGemini.restResponse = splitJson
            val parent = createParentTask("Plan Diwali party")
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.sendMessage("break down Plan Diwali party")
            val messages = viewModel.messages.first { it.size == 3 }
            assertEquals(ChatActionType.DRAFT_PREVIEW, messages[2].actionType)
            assertEquals("Here is how I'd break it down.", messages[2].text)
            val draft = viewModel.pendingDraft.value
            assertNotNull(draft)
            assertEquals(DraftKind.SUBTASK_SPLIT, draft!!.kind)
            assertEquals("Plan Diwali party", draft.title)
            assertEquals(parent.id, draft.parentTaskId)
            assertEquals(
                listOf("Buy flour", "Mix the dough", "Bake for 30 minutes"),
                draft.subtaskTitles
            )
            // Nothing is in the DB yet — the preview is only a question.
            assertTrue(taskRepository.observeSubtasks(parent.id).first().isEmpty())
            assertEquals(0 to 0, taskRepository.subtaskProgress(parent.id))
            assertEquals(1, recordingGemini.restCalls)
            assertEquals(0, recordingGemini.aiLogicCalls)
            assertEquals(1, freeTurnsUsed())
            // Confirm creates the subtasks and posts the receipt.
            viewModel.confirmDraft()
            val afterConfirm = viewModel.messages.first { it.size == 4 }
            assertTrue(afterConfirm[3].text.contains("Buy flour"))
            assertNull(viewModel.pendingDraft.value)
            val subtasks = taskRepository.observeSubtasks(parent.id).first()
            assertEquals(3, subtasks.size)
            assertEquals(
                listOf("Buy flour", "Mix the dough", "Bake for 30 minutes"),
                subtasks.map { it.title }
            )
            assertTrue(subtasks.all { it.parentTaskId == parent.id })
            // The detail sheet's progress label reads this: 0 done of 3.
            assertEquals(0 to 3, taskRepository.subtaskProgress(parent.id))
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `split by title with no BYOK names the manual path and creates nothing`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val parent = createParentTask("Plan Diwali party")
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.sendMessage("break down Plan Diwali party")
            val messages = viewModel.messages.first { it.size == 3 }
            assertTrue(messages[2].text.contains("detail sheet"))
            assertNull(viewModel.pendingDraft.value)
            assertTrue(taskRepository.observeSubtasks(parent.id).first().isEmpty())
            assertEquals(0, freeTurnsUsed())
            assertEquals(0, recordingGemini.restCalls)
            assertEquals(0, recordingGemini.aiLogicCalls)
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `split with an unknown title asks nothing of the LLM`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            setByokKey()
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.sendMessage("break down Plan Diwali party")
            val messages = viewModel.messages.first { it.size == 3 }
            assertTrue(messages[2].text.contains("couldn't find"))
            assertNull(viewModel.pendingDraft.value)
            assertEquals(0, recordingGemini.restCalls)
            assertEquals(0, freeTurnsUsed())
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `split with an ambiguous title lists the matches and calls no LLM`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            setByokKey()
            createParentTask("Plan Diwali party")
            createParentTask("Plan Diwali decorations")
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.sendMessage("break down Plan Diwali")
            val messages = viewModel.messages.first { it.size == 3 }
            assertTrue(messages[2].text.contains("Plan Diwali party"))
            assertTrue(messages[2].text.contains("Plan Diwali decorations"))
            assertNull(viewModel.pendingDraft.value)
            assertEquals(0, recordingGemini.restCalls)
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `bare break this down asks which task and calls no LLM`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            setByokKey()
            createParentTask("Plan Diwali party")
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.sendMessage("break this down")
            val messages = viewModel.messages.first { it.size == 3 }
            assertTrue(messages[2].text.contains("Which task"))
            assertNull(viewModel.pendingDraft.value)
            assertEquals(0, recordingGemini.restCalls)
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `split from the detail sheet starts at once for the selected task`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            setByokKey()
            recordingGemini.restResponse = splitJson
            val parent = createParentTask("Plan Diwali party")
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            // No typed message: the sheet's button starts the split.
            viewModel.startSplit(parent.id)
            val messages = viewModel.messages.first { it.size == 2 }
            assertEquals(ChatActionType.DRAFT_PREVIEW, messages[1].actionType)
            val draft = viewModel.pendingDraft.value
            assertNotNull(draft)
            assertEquals(DraftKind.SUBTASK_SPLIT, draft!!.kind)
            assertEquals(parent.id, draft.parentTaskId)
            assertEquals(3, draft.subtaskTitles.size)
            // Confirm creates the subtasks, as from a typed command.
            viewModel.confirmDraft()
            viewModel.messages.first { it.size == 3 }
            assertEquals(3, taskRepository.observeSubtasks(parent.id).first().size)
            assertEquals(0 to 3, taskRepository.subtaskProgress(parent.id))
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `split of a subtask is refused with no LLM call`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            setByokKey()
            recordingGemini.restResponse = splitJson
            val parent = createParentTask("Plan Diwali party")
            val subtask = taskRepository.createSubtask(parent.id, "Buy flour")
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            // From the detail sheet: a subtask id is refused outright.
            viewModel.startSplit(subtask.id)
            val messages = viewModel.messages.first { it.size == 2 }
            assertTrue(messages[1].text.contains("already a subtask"))
            assertNull(viewModel.pendingDraft.value)
            assertEquals(0, recordingGemini.restCalls)
            // Typed, by the subtask's title: the same refusal.
            viewModel.sendMessage("break down Buy flour")
            val typed = viewModel.messages.first { it.size == 4 }
            assertTrue(typed[3].text.contains("already a subtask"))
            assertNull(viewModel.pendingDraft.value)
            assertEquals(0, recordingGemini.restCalls)
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `split with invalid split JSON answers in plain text and creates nothing`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            setByokKey()
            recordingGemini.restResponse =
                """{"action": "split_task", "subtasks": [{"title": "Buy flour"}, {"title": ""}], "reply": "I tried to split it."}"""
            val parent = createParentTask("Plan Diwali party")
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.sendMessage("break down Plan Diwali party")
            val messages = viewModel.messages.first { it.size == 3 }
            assertEquals("I tried to split it.", messages[2].text)
            assertNull(viewModel.pendingDraft.value)
            assertTrue(taskRepository.observeSubtasks(parent.id).first().isEmpty())
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `removeSplitSubtask drops one item and the last removal clears the draft`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            setByokKey()
            recordingGemini.restResponse = splitJson
            createParentTask("Plan Diwali party")
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.sendMessage("break down Plan Diwali party")
            viewModel.messages.first { it.size == 3 }
            assertEquals(3, viewModel.pendingDraft.value!!.subtaskTitles.size)
            viewModel.removeSplitSubtask(0)
            assertEquals(
                listOf("Mix the dough", "Bake for 30 minutes"),
                viewModel.pendingDraft.value!!.subtaskTitles
            )
            viewModel.removeSplitSubtask(0)
            viewModel.removeSplitSubtask(0)
            assertNull(viewModel.pendingDraft.value)
            // The empty-card note says nothing was created.
            val messages = viewModel.messages.first { it.size == 4 }
            assertTrue(messages[3].text.contains("nothing was created"))
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `bengali split command drafts bengali subtasks`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            setByokKey()
            recordingGemini.restResponse =
                """{"action": "split_task", "subtasks": [{"title": "ময়দা কেনা"}, {"title": "ময়দা মাখা"}], "tasks": [], "events": [], "reply": "এভাবে ভাগ করেছি।"}"""
            val parent = createParentTask("পার্টির আয়োজন")
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.sendMessage("পার্টির আয়োজনটা ভেঙে দাও")
            val messages = viewModel.messages.first { it.size == 3 }
            assertEquals(ChatActionType.DRAFT_PREVIEW, messages[2].actionType)
            val draft = viewModel.pendingDraft.value
            assertNotNull(draft)
            assertEquals(DraftKind.SUBTASK_SPLIT, draft!!.kind)
            assertEquals(parent.id, draft.parentTaskId)
            assertEquals(listOf("ময়দা কেনা", "ময়দা মাখা"), draft.subtaskTitles)
            viewModel.confirmDraft()
            viewModel.messages.first { it.size == 4 }
            val subtasks = taskRepository.observeSubtasks(parent.id).first()
            assertEquals(listOf("ময়দা কেনা", "ময়দা মাখা"), subtasks.map { it.title })
            assertEquals(0 to 2, taskRepository.subtaskProgress(parent.id))
        } finally {
            resetMainAfterDrain()
        }
    }

    // ---- Sprint 3-6 — auto-schedule (feature 21) + priority (feature 22) ----

    private fun at(dayOffset: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, dayOffset)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private suspend fun seedTask(
        title: String,
        due: Long,
        priority: Priority = Priority.MEDIUM,
        estimatedMinutes: Int? = null
    ) = taskRepository.createTask(
        userId = userId,
        title = title,
        dueEpochMillis = due,
        priority = priority,
        estimatedMinutes = estimatedMinutes
    )

    private suspend fun seedMeeting(title: String, start: Long, end: Long) =
        calendarRepository.addEvent(userId, title, start, end)

    /** True after 22:00. Only ever asserted in an empty-drafts
     *  branch — and empty drafts can only happen after 22:50 (see
     *  focusBlocksThatFitTonight), so the check always holds there.
     *  It is NOT the "no block fits" boundary: a block still fits
     *  until 22:50. */
    private fun isLateNight(): Boolean =
        Calendar.getInstance().get(Calendar.HOUR_OF_DAY) >= 22

    /**
     * Sprint 4-0 — how many 45-minute focus blocks still fit before
     * midnight (the slot math in findSuggestedFocusSlot +
     * handleAutoScheduleCommand): the first block starts at the next
     * :00/:30 after now + 10 min, each next block needs 45 min + a
     * 15-min gap, and a block that would end past midnight is never
     * proposed. So the second block fits only while now < 21:50
     * (first block 22:00–22:45), the first only while now < 22:50
     * (23:00–23:45), and none fit after that.
     *
     * The old isLateNight() (>= 22:00) covers neither boundary: from
     * 21:50 to 22:50 exactly ONE block fits, so a test that seeds two
     * tasks and demands two drafts fails there — and after 22:50 the
     * answer is "no free slot", not a preview. The two-task tests
     * below pin their count against this, never a hard-coded 2.
     */
    private fun focusBlocksThatFitTonight(): Int {
        val cal = Calendar.getInstance()
        val nowMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return when {
            nowMinutes < 21 * 60 + 50 -> 2
            nowMinutes < 22 * 60 + 50 -> 1
            else -> 0
        }
    }

    @Test
    fun `plan my day previews focus slots and creates nothing yet`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            val morning = seedTask("Morning report", at(0, 9))
            val anytime = seedTask("Buy printer paper", TaskRepository.NO_DUE_DATE)
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.sendMessage("plan my day")
            val messages = viewModel.messages.first { it.size == 3 }
            val drafts = viewModel.pendingPlanDrafts.value
            val expected = focusBlocksThatFitTonight()
            if (drafts.isEmpty()) {
                // After 22:50: no 45-minute block ends tonight — the
                // plain answer, not a preview.
                assertEquals(0, expected)
                assertTrue(isLateNight())
                assertEquals(ChatActionType.NONE, messages[2].actionType)
                assertTrue(messages[2].text.contains("No free slot"))
            } else {
                assertEquals(ChatActionType.DRAFT_PREVIEW, messages[2].actionType)
                assertEquals(expected, drafts.size)
                assertTrue(drafts.all { it.kind == DraftKind.FOCUS_EVENT })
                // scheduleOrder is due-first, timeless last: the
                // morning task takes the first slot, so a partial
                // evening fit keeps it and drops the timeless one.
                assertEquals(
                    listOf(morning.title, anytime.title).take(expected).toSet(),
                    drafts.map { it.title }.toSet()
                )
                val now = System.currentTimeMillis()
                val todayEnd = endOfDayMillis(now)
                // Every proposed slot is in the future, inside today,
                // and no two overlap.
                assertTrue(drafts.all { it.startEpochMillis > now && it.endEpochMillis!! <= todayEnd })
                val sorted = drafts.sortedBy { it.startEpochMillis }
                for (i in 0 until sorted.size - 1) {
                    assertTrue(sorted[i].endEpochMillis!! <= sorted[i + 1].startEpochMillis)
                }
                assertTrue(messages[2].text.contains("Here's your day plan"))
            }
            // Nothing is in the DB yet — the preview is only a question.
            assertTrue(calendarRepository.observeEventsForDay(userId, System.currentTimeMillis()).first().isEmpty())
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `plan my day never double-books a busy slot`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            seedMeeting("Team sync", at(0, 14), at(0, 15))
            seedTask("Write proposal", at(0, 16))
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.sendMessage("schedule my day")
            val messages = viewModel.messages.first { it.size == 3 }
            val drafts = viewModel.pendingPlanDrafts.value
            if (drafts.isEmpty()) {
                assertTrue(isLateNight())
                assertTrue(messages[2].text.contains("No free slot"))
            } else {
                assertEquals(1, drafts.size)
                val draft = drafts.single()
                // The proposed block must not touch 14:00–15:00.
                assertFalse(draft.startEpochMillis < at(0, 15) && draft.endEpochMillis!! > at(0, 14))
                assertTrue(draft.startEpochMillis > System.currentTimeMillis())
            }
            assertTrue(calendarRepository.observeEventsForDay(userId, System.currentTimeMillis()).first().size == 1)
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `plan my day leaves already-placed tasks alone`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            val placed = seedTask("Placed task", at(0, 10))
            val todayString = com.nextgentool.nudgy.domain.todayDateString()
            taskRepository.updateTask(
                placed.copy(plannedDate = todayString, plannedStartEpochMillis = at(0, 10))
            )
            seedTask("Unplaced task", at(0, 12))
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.sendMessage("আমার দিনটা সাজাও")
            viewModel.messages.first { it.size == 3 }
            val drafts = viewModel.pendingPlanDrafts.value
            if (drafts.isNotEmpty()) {
                assertEquals(listOf("Unplaced task"), drafts.map { it.title })
                viewModel.confirmPlan()
                viewModel.messages.first { it.size == 4 }
                // The placed task never moved; exactly one focus block
                // was created, for the unplaced task.
                val after = taskRepository.getAllTasksOnce(userId).first { it.id == placed.id }
                assertEquals(at(0, 10), after.plannedStartEpochMillis)
                val events = calendarRepository.observeEventsForDay(userId, System.currentTimeMillis()).first()
                assertEquals(1, events.filter { it.eventType == "FOCUS_TIME" }.size)
                assertEquals("Unplaced task", events.single { it.eventType == "FOCUS_TIME" }.title)
            } else {
                // Late night only: nothing could be proposed, so
                // Confirm-all has nothing to do and creates nothing.
                assertTrue(isLateNight())
                val after = taskRepository.getAllTasksOnce(userId).first { it.id == placed.id }
                assertEquals(at(0, 10), after.plannedStartEpochMillis)
            }
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `plan my day with an all-day holiday still proposes slots`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            val todayStart = startOfDayMillis(System.currentTimeMillis())
            db.calendarDao().insertEvent(
                CalendarEventEntity(
                    userId = userId,
                    title = "Victory Day",
                    startEpochMillis = todayStart,
                    endEpochMillis = todayStart + 24 * 60 * 60 * 1000L,
                    isAllDay = true
                )
            )
            seedTask("Write report", at(0, 11))
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.sendMessage("plan my day")
            val messages = viewModel.messages.first { it.size == 3 }
            val drafts = viewModel.pendingPlanDrafts.value
            if (drafts.isEmpty()) {
                // Late night only — the all-day label is not what
                // blocked the plan; the day simply has no room left.
                assertTrue(isLateNight())
                assertTrue(messages[2].text.contains("No free slot"))
            } else {
                // The 00:00–24:00 label never counts as busy (S2-1b).
                assertEquals(1, drafts.size)
                assertEquals("Write report", drafts.single().title)
            }
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `plan my day with a full calendar says no free slot`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            val todayStart = startOfDayMillis(System.currentTimeMillis())
            seedMeeting("Morning block", todayStart, todayStart + 12 * 60 * 60 * 1000L)
            seedMeeting("Afternoon block", todayStart + 12 * 60 * 60 * 1000L, todayStart + 24 * 60 * 60 * 1000L - 60 * 1000L)
            seedTask("Write report", at(0, 11))
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.sendMessage("plan my day")
            val messages = viewModel.messages.first { it.size == 3 }
            // Deterministic at any hour: the fallback slot lands
            // tomorrow, so nothing can be proposed today.
            assertTrue(viewModel.pendingPlanDrafts.value.isEmpty())
            assertTrue(messages[2].text.contains("No free slot"))
            assertEquals(ChatActionType.NONE, messages[2].actionType)
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `plan my day with nothing unplaced says so and creates nothing`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.sendMessage("plan my day")
            val messages = viewModel.messages.first { it.size == 3 }
            assertTrue(viewModel.pendingPlanDrafts.value.isEmpty())
            assertTrue(messages[2].text.contains("Nothing to schedule"))
            assertTrue(calendarRepository.observeEventsForDay(userId, System.currentTimeMillis()).first().isEmpty())
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `plan my day confirm-all creates one focus block per draft`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            seedTask("Task one", at(0, 9))
            seedTask("Task two", at(0, 15))
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.sendMessage("plan my day")
            viewModel.messages.first { it.size == 3 }
            val drafts = viewModel.pendingPlanDrafts.value
            val expected = focusBlocksThatFitTonight()
            if (drafts.isEmpty()) {
                // Late night only: nothing was proposed, so there is
                // nothing to confirm.
                assertEquals(0, expected)
                assertTrue(isLateNight())
                return@runTest
            }
            assertEquals(expected, drafts.size)
            viewModel.confirmPlan()
            val afterConfirm = viewModel.messages.first { it.size == 4 }
            assertTrue(
                afterConfirm[3].text.contains(
                    "scheduled $expected ${if (expected == 1) "focus block" else "focus blocks"}"
                )
            )
            val events = calendarRepository.observeEventsForDay(userId, System.currentTimeMillis()).first()
                .filter { it.eventType == "FOCUS_TIME" }
            assertEquals(expected, events.size)
            // scheduleOrder is due-first: "Task one" (09:00) takes
            // the first slot, so a partial evening fit keeps it.
            assertEquals(
                listOf("Task one", "Task two").take(expected).toSet(),
                events.map { it.title }.toSet()
            )
            assertTrue(viewModel.pendingPlanDrafts.value.isEmpty())
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `plan my day remove one then confirm creates the rest`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            seedTask("Keep me", at(0, 9))
            seedTask("Drop me", at(0, 15))
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.sendMessage("plan my day")
            viewModel.messages.first { it.size == 3 }
            val drafts = viewModel.pendingPlanDrafts.value
            val expected = focusBlocksThatFitTonight()
            if (drafts.isEmpty()) {
                assertEquals(0, expected)
                assertTrue(isLateNight())
                return@runTest
            }
            if (expected == 1) {
                // Late evening: only "Keep me" (due first) got a
                // slot — there is no second draft to remove, so
                // confirm the one there is.
                assertEquals(listOf("Keep me"), drafts.map { it.title })
                viewModel.confirmPlan()
                viewModel.messages.first { it.size == 4 }
                val single = calendarRepository.observeEventsForDay(userId, System.currentTimeMillis()).first()
                    .filter { it.eventType == "FOCUS_TIME" }
                assertEquals(listOf("Keep me"), single.map { it.title })
                return@runTest
            }
            val dropIndex = drafts.indexOfFirst { it.title == "Drop me" }
            assertTrue(dropIndex >= 0)
            viewModel.removePlanDraft(dropIndex)
            assertEquals(1, viewModel.pendingPlanDrafts.value.size)
            viewModel.confirmPlan()
            viewModel.messages.first { it.size == 4 }
            val events = calendarRepository.observeEventsForDay(userId, System.currentTimeMillis()).first()
                .filter { it.eventType == "FOCUS_TIME" }
            assertEquals(listOf("Keep me"), events.map { it.title })
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `plan my day discard creates nothing`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            seedTask("Task one", at(0, 9))
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.sendMessage("plan my day")
            val messages = viewModel.messages.first { it.size == 3 }
            val drafts = viewModel.pendingPlanDrafts.value
            if (drafts.isEmpty()) {
                // S4-1 round 3 — the 5th late-evening flake of the
                // class S4-0 round 3 fixed for 4 sibling tests: after
                // 22:50 no 45-minute block ends tonight, so the plan
                // reply is the plain "No free slot" answer, not a
                // preview — there is nothing to discard, and
                // discardPlan() correctly does nothing.
                assertEquals(0, focusBlocksThatFitTonight())
                assertEquals(ChatActionType.NONE, messages[2].actionType)
                assertTrue(messages[2].text.contains("No free slot"))
                assertTrue(calendarRepository.observeEventsForDay(userId, System.currentTimeMillis()).first().isEmpty())
                assertTrue(viewModel.pendingPlanDrafts.value.isEmpty())
                return@runTest
            }
            viewModel.discardPlan()
            val afterDiscard = viewModel.messages.first { it.size == 4 }
            assertTrue(afterDiscard[3].text.contains("didn't schedule anything"))
            assertTrue(calendarRepository.observeEventsForDay(userId, System.currentTimeMillis()).first().isEmpty())
            assertTrue(viewModel.pendingPlanDrafts.value.isEmpty())
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `priority order replies numbered with reasons and Apply stamps My Day order`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            val overdue = seedTask("Overdue report", at(-1, 18))
            val morning = seedTask("Morning email", at(0, 9))
            val high = seedTask("Urgent fix", at(0, 15), priority = Priority.HIGH)
            val medium = seedTask("Routine review", at(0, 15))
            val timeless = seedTask("Read article", TaskRepository.NO_DUE_DATE, priority = Priority.LOW)
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.sendMessage("what should I do first?")
            val messages = viewModel.messages.first { it.size == 3 }
            val reply = messages[2]
            assertEquals(ChatActionType.DAILY_PLAN, reply.actionType)
            // The order is wall-clock independent: overdue first, then
            // due time, HIGH before MEDIUM at the same due, timeless last.
            assertEquals(
                listOf(overdue.id, morning.id, high.id, medium.id, timeless.id),
                viewModel.pendingPriorityOrder.value
            )
            // The scoring is visible in the text: overdue is named,
            // HIGH is named, and the numbers follow the order.
            val text = reply.text
            assertTrue(text.contains("overdue"))
            assertTrue(text.contains("HIGH priority"))
            val positions = listOf(overdue.title, morning.title, high.title, medium.title, timeless.title)
                .map { text.indexOf(it) }
            assertTrue(positions.all { it >= 0 })
            assertEquals(positions.sorted(), positions)
            assertTrue(text.startsWith("Here's the order I'd tackle today:"))
            // Apply stamps plannedDate + sequential plannedStart in
            // priority order — My Day sorts by plannedStart.
            viewModel.applyPriorityOrder()
            val afterApply = viewModel.messages.first { it.size == 4 }
            assertTrue(afterApply[3].text.contains("Done — your My Day now follows this order:"))
            val todayString = com.nextgentool.nudgy.domain.todayDateString()
            val stamped = taskRepository.getAllTasksOnce(userId)
                .filter { it.id in listOf(overdue.id, morning.id, high.id, medium.id, timeless.id) }
                .sortedBy { it.plannedStartEpochMillis }
            assertTrue(stamped.all { it.plannedDate == todayString && it.plannedStartEpochMillis != null })
            assertEquals(
                listOf(overdue.id, morning.id, high.id, medium.id, timeless.id),
                stamped.map { it.id }
            )
            assertNull(viewModel.pendingPriorityOrder.value)
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `priority order demotes a task that conflicts with a meeting`() = runTest {
        // Needs room today for the meeting to end before midnight:
        // after 21:00 the meeting window would cross into tomorrow,
        // so the check cannot run — the pure DayPlannerTest covers
        // the ranking itself.
        org.junit.Assume.assumeTrue(Calendar.getInstance().get(Calendar.HOUR_OF_DAY) < 21)
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            val now = System.currentTimeMillis()
            val todayEnd = endOfDayMillis(now)
            val soonDue = minOf(now + 2 * 60 * 60 * 1000L, todayEnd - 60 * 60 * 1000L)
            val overdue = seedTask("Overdue report", at(-1, 18))
            // Both tasks share the same due time and priority, so the
            // conflict key alone decides their order. The meeting
            // starts 20 minutes after the due time: the 15-minute
            // "Free task" ends before it (genuinely free) while the
            // default 30-minute "Clashing task" runs into it
            // (genuinely conflicting). The old version seeded the
            // meeting exactly on the due time, so BOTH tasks
            // conflicted and the order proved nothing — and it
            // asserted the English "conflicts with" wording on a
            // Bengali reply, which can never match.
            val free = seedTask("Free task", soonDue, estimatedMinutes = 15)
            val conflict = seedTask("Clashing task", soonDue)
            seedMeeting("Team sync", soonDue + 20 * 60 * 1000L, soonDue + 50 * 60 * 1000L)
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.sendMessage("দিনটা সাজিয়ে দাও")
            val messages = viewModel.messages.first { it.size == 3 }
            assertEquals(ChatActionType.DAILY_PLAN, messages[2].actionType)
            assertEquals(
                listOf(overdue.id, free.id, conflict.id),
                viewModel.pendingPriorityOrder.value
            )
            // The command is Bengali, so the reply is Bengali: the
            // conflict reason is worded "…-এর সঙ্গে ধাক্কা খায়",
            // and it appears on the clashing task's line only.
            val replyText = messages[2].text
            assertTrue(replyText.contains("\"Team sync\"-এর সঙ্গে ধাক্কা খায়"))
            val freeLine = replyText.lines().first { it.contains("Free task") }
            assertTrue(!freeLine.contains("ধাক্কা খায়"))
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `priority Apply with no pending order is a no-op`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.applyPriorityOrder()
            assertEquals(1, viewModel.messages.value.size)
            assertNull(viewModel.pendingPriorityOrder.value)
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `priority with nothing unplaced says so`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.sendMessage("prioritize my day")
            val messages = viewModel.messages.first { it.size == 3 }
            assertNull(viewModel.pendingPriorityOrder.value)
            assertTrue(messages[2].text.contains("Nothing to order"))
        } finally {
            resetMainAfterDrain()
        }
    }

    // ---- Sprint 3-7 — Bengali voice toggle + Bengali commands ----

    @Test
    fun `voice toggle defaults to English and flips to Bengali and back`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            // Default is English; the toggle is explicit (Q6 = (a)) —
            // no auto-detect, the user picks.
            assertEquals(ChatVoiceLanguage.ENGLISH, viewModel.voiceLanguage.value)
            assertEquals("en-US", viewModel.voiceLanguage.value.code)
            assertEquals("EN", viewModel.voiceLanguage.value.label)
            viewModel.toggleVoiceLanguage()
            assertEquals(ChatVoiceLanguage.BENGALI, viewModel.voiceLanguage.value)
            assertEquals("bn-BD", viewModel.voiceLanguage.value.code)
            assertEquals("বাংলা", viewModel.voiceLanguage.value.label)
            viewModel.toggleVoiceLanguage()
            assertEquals(ChatVoiceLanguage.ENGLISH, viewModel.voiceLanguage.value)
            // The toggle is ViewModel state (a StateFlow), so it
            // survives a configuration change by construction — the
            // screen re-collects the same flow after rotation.
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `voice toggle passes bn-BD to the voice session and Bengali replies follow`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.toggleVoiceLanguage()
            // The NavHost wires the toggle code into startVoice —
            // this call is exactly what the sheet / chat route does.
            viewModel.startVoice(viewModel.voiceLanguage.value.code)
            assertEquals("bn-BD", fakeVoice.lastLanguageCode)
            fakeVoice.deliverResult("কাল সকাল ৯টায় বাজার করতে হবে")
            // "বিকেলে" (with ে) is a daypart key — it resolves to
            // 16:00 and drafts. Bare "বিকেল" is deliberately NOT a
            // key (pinned §11 behaviour: it asks AM/PM instead), so
            // the dump uses the suffixed form here.
            fakeVoice.deliverResult("কাল বিকেলে ৪টায় মাকে কল করো")
            viewModel.stopVoice()
            val messages = viewModel.messages.first { it.size == 3 }
            val drafts = viewModel.pendingBrainDumpDrafts.value
            assertEquals(2, drafts.size)
            assertTrue(drafts.any { it.title.contains("বাজার") })
            assertTrue(drafts.any { it.title.contains("কল") })
            // The preview and the receipt are in Bengali — the toggle
            // decided the reply language for this voice-driven flow.
            assertTrue(messages[2].text.contains("শুনেছি"))
            viewModel.confirmBrainDump()
            val afterConfirm = viewModel.messages.first { it.size == 4 }
            assertTrue(afterConfirm[3].text.contains("তৈরি করেছি"))
            val created = taskRepository.getAllTasksOnce(userId)
            assertEquals(2, created.size)
            // Local parse only, no LLM turn spent.
            assertEquals(0, freeTurnsUsed())
            assertEquals(0, recordingGemini.restCalls)
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `bank-only split phrasing asks which task and calls no LLM`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.sendMessage("টাস্কটা ভেঙে ছোট ছোট subtask বানিয়ে দাও")
            val messages = viewModel.messages.first { it.size == 3 }
            // The bank is the second net (the parsers cannot read
            // this phrasing) — the Command asks, it never guesses a
            // parent and never calls the LLM.
            assertTrue(messages[2].text.contains("কোন টাস্কটা ভেঙে দেব?"))
            assertNull(viewModel.pendingDraft.value)
            assertTrue(taskRepository.getAllTasksOnce(userId).isEmpty())
            assertEquals(0, recordingGemini.restCalls)
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `split the bill asks which task instead of creating a draft`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            // Documented behaviour change (spec §8): the bank matches
            // "split the bill" to SPLIT_TASK at 0.420, so the old
            // catch-all draft titled "Split the bill" is gone — the
            // Command asks which task, and creates nothing.
            viewModel.sendMessage("split the bill")
            val messages = viewModel.messages.first { it.size == 3 }
            assertTrue(messages[2].text.contains("Which task should I break down?"))
            assertNull(viewModel.pendingDraft.value)
            assertTrue(taskRepository.getAllTasksOnce(userId).isEmpty())
            assertEquals(0, recordingGemini.restCalls)
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `bengali priority order replies in Bengali and Apply receipt is Bengali`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            val overdue = seedTask("গতকালের রিপোর্ট", at(-1, 18))
            val morning = seedTask("সকালের মেইল", at(0, 9))
            val high = seedTask("জরুরি ফিক্স", at(0, 15), priority = Priority.HIGH)
            val medium = seedTask("রুটিন রিভিউ", at(0, 15))
            val timeless = seedTask("প্রবন্ধ পড়া", TaskRepository.NO_DUE_DATE, priority = Priority.LOW)
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.sendMessage("কোনটা আগে করবো")
            val messages = viewModel.messages.first { it.size == 3 }
            val reply = messages[2]
            assertEquals(ChatActionType.DAILY_PLAN, reply.actionType)
            assertEquals(
                listOf(overdue.id, morning.id, high.id, medium.id, timeless.id),
                viewModel.pendingPriorityOrder.value
            )
            assertTrue(reply.text.startsWith("আজ এভাবে করলে ভালো হবে:"))
            assertTrue(reply.text.contains("সময় পেরিয়ে গেছে"))
            assertTrue(reply.text.contains("বেশি জরুরি"))
            val positions = listOf(overdue.title, morning.title, high.title, medium.title, timeless.title)
                .map { reply.text.indexOf(it) }
            assertTrue(positions.all { it >= 0 })
            assertEquals(positions.sorted(), positions)
            viewModel.applyPriorityOrder()
            val afterApply = viewModel.messages.first { it.size == 4 }
            assertTrue(afterApply[3].text.contains("হয়ে গেছে — My Day এখন এই ক্রমে সাজবে:"))
            val todayString = com.nextgentool.nudgy.domain.todayDateString()
            val stamped = taskRepository.getAllTasksOnce(userId)
                .filter { it.id in listOf(overdue.id, morning.id, high.id, medium.id, timeless.id) }
                .sortedBy { it.plannedStartEpochMillis }
            assertTrue(stamped.all { it.plannedDate == todayString && it.plannedStartEpochMillis != null })
            assertEquals(
                listOf(overdue.id, morning.id, high.id, medium.id, timeless.id),
                stamped.map { it.id }
            )
            assertNull(viewModel.pendingPriorityOrder.value)
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `bengali plan my day previews in Bengali and confirm-all creates focus blocks`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            val morning = seedTask("সকালের রিপোর্ট", at(0, 9))
            val anytime = seedTask("প্রিন্টারের কাগজ কেনা", TaskRepository.NO_DUE_DATE)
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.sendMessage("আমার দিনটা সাজাও")
            val messages = viewModel.messages.first { it.size == 3 }
            val drafts = viewModel.pendingPlanDrafts.value
            val expected = focusBlocksThatFitTonight()
            if (drafts.isEmpty()) {
                // After 22:50: no 45-minute block ends tonight — the
                // plain answer, not a preview.
                assertEquals(0, expected)
                assertTrue(isLateNight())
                assertEquals(ChatActionType.NONE, messages[2].actionType)
                assertTrue(messages[2].text.contains("আজ আর ফাঁকা সময় নেই"))
            } else {
                assertEquals(ChatActionType.DRAFT_PREVIEW, messages[2].actionType)
                assertEquals(expected, drafts.size)
                assertTrue(drafts.all { it.kind == DraftKind.FOCUS_EVENT })
                // scheduleOrder is due-first, timeless last: the
                // morning task takes the first slot, so a partial
                // evening fit keeps it.
                assertEquals(
                    listOf(morning.title, anytime.title).take(expected).toSet(),
                    drafts.map { it.title }.toSet()
                )
                assertTrue(messages[2].text.contains("দিনের প্ল্যান"))
                // Nothing is in the DB yet — the preview is only a
                // question.
                assertTrue(
                    calendarRepository.observeEventsForDay(userId, System.currentTimeMillis()).first().isEmpty()
                )
                viewModel.confirmPlan()
                val afterConfirm = viewModel.messages.first { it.size == 4 }
                assertTrue(afterConfirm[3].text.contains("ফোকাস ব্লক বসিয়েছি"))
                val events = calendarRepository.observeEventsForDay(userId, System.currentTimeMillis()).first()
                assertEquals(expected, events.size)
            }
        } finally {
            resetMainAfterDrain()
        }
    }

    @Test
    fun `command piece inside a Bengali brain dump is skipped, the task still previews`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.startVoice("bn-BD")
            // One utterance, two sentences: the first is a plan
            // command (a Command, not a task), the second is a task.
            fakeVoice.deliverResult("দিনটা সাজাও। কাল সকাল ৯টায় বাজার করতে হবে")
            viewModel.stopVoice()
            val messages = viewModel.messages.first { it.size == 3 }
            val drafts = viewModel.pendingBrainDumpDrafts.value
            assertEquals(1, drafts.size)
            assertTrue(drafts.single().title.contains("বাজার"))
            assertTrue(messages[2].text.contains("বাদ দিয়েছি"))
            // The command piece was skipped, never executed mid-dump.
            assertTrue(taskRepository.getAllTasksOnce(userId).isEmpty())
            assertTrue(
                calendarRepository.observeEventsForDay(userId, System.currentTimeMillis()).first().isEmpty()
            )
            viewModel.confirmBrainDump()
            // confirmBrainDump saves asynchronously — wait for the
            // receipt before reading the DB (the existing dump tests
            // do the same; reading straight away races the save).
            viewModel.messages.first { it.size == 4 }
            val created = taskRepository.getAllTasksOnce(userId)
            assertEquals(1, created.size)
            assertTrue(created.single().title.contains("বাজার"))
        } finally {
            resetMainAfterDrain()
        }
    }

    // ---- Sprint 5 fix round 4: live user ----

    @Test
    fun `messages follow the guest to signed-in flip`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            // setUp wrote test-user-1; this test starts as guest, like a
            // fresh install before sign-in.
            context.getSharedPreferences("nudgy_auth_prefs", Context.MODE_PRIVATE)
                .edit().putString("active_user_id", AuthManager.GUEST_USER_ID).apply()
            // A message on the signed-in uid's history, seeded before
            // the ViewModel exists.
            chatRepository.addMessage(
                userId,
                ChatMessage(id = "signed-in-note", senderIsUser = true, text = "Signed-in note")
            )
            val fakeAuth = FlipGroupAuth()
            val viewModel = newViewModel(fakeAuth)

            // Guest phase: only the guest's own welcome message.
            val guestMessages = viewModel.messages.first { it.size == 1 }
            assertEquals("Hi! What can I help you get done today?", guestMessages.single().text)

            fakeAuth.userStateFlow.value =
                NudgyUser(uid = userId, displayName = "Ayesha", email = "ayesha@example.com")

            // After the flip the signed-in user's history appears (the
            // welcome is NOT re-seeded: history already exists) and the
            // guest's welcome disappears with the guest flow.
            val signedInMessages = viewModel.messages.first { messages ->
                messages.any { it.text == "Signed-in note" }
            }
            assertFalse(signedInMessages.any { it.text == "Hi! What can I help you get done today?" })

            // The screen's user follows the flip too.
            val signedInUser = viewModel.user.first { !it.isGuest }
            assertEquals(userId, signedInUser.uid)
            assertEquals("Ayesha", signedInUser.displayName)
        } finally {
            resetMainAfterDrain()
        }
    }
}

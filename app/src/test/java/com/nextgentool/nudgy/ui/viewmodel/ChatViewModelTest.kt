package com.nextgentool.nudgy.ui.viewmodel

import android.app.Activity
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.ads.ChatTurnCap
import com.nextgentool.nudgy.ads.RewardedAdGateway
import com.nextgentool.nudgy.ai.BrainDumpSession
import com.nextgentool.nudgy.ai.ClientIntentPreFilter
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
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.repository.CalendarRepository
import com.nextgentool.nudgy.data.repository.ChatRepository
import com.nextgentool.nudgy.data.repository.MemoryRepository
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.startOfDayMillis
import com.nextgentool.nudgy.ui.screens.ChatActionType
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
 * assertions via `messages.first { ... }`, resetMain() in finally.
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
        db.close()
        context.getSharedPreferences("nudgy_ads", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("nudgy_settings", Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun newViewModel() = ChatViewModel(
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
        fakeRewarded
    )

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
            Dispatchers.resetMain()
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
            Dispatchers.resetMain()
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
            Dispatchers.resetMain()
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
            Dispatchers.resetMain()
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
            Dispatchers.resetMain()
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
            Dispatchers.resetMain()
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
            Dispatchers.resetMain()
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
            Dispatchers.resetMain()
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
            Dispatchers.resetMain()
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
            Dispatchers.resetMain()
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
            Dispatchers.resetMain()
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
            Dispatchers.resetMain()
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
            Dispatchers.resetMain()
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
            val today = startOfDayMillis(System.currentTimeMillis())
            val events = calendarRepository.observeEventsForDay(userId, today)
                .first { it.any { event -> event.title == "Focus Time" } }
            assertTrue(events.any { it.title == "Focus Time" })
            val updated = viewModel.messages.first { list ->
                list.any { it.id == proposal.id && it.focusActionCompleted }
            }
            assertTrue(updated.any { it.id == proposal.id && it.focusActionCompleted })
        } finally {
            Dispatchers.resetMain()
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
            Dispatchers.resetMain()
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
            Dispatchers.resetMain()
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
            Dispatchers.resetMain()
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
            Dispatchers.resetMain()
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
            Dispatchers.resetMain()
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
            Dispatchers.resetMain()
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
            fakeVoice.deliverResult("um yeah hmm")
            viewModel.stopVoice()
            val messages = viewModel.messages.first { it.size == 3 }
            assertTrue(messages[2].text.contains("couldn't split"))
            assertTrue(viewModel.pendingBrainDumpDrafts.value.isEmpty())
            assertTrue(taskRepository.getAllTasksOnce(userId).isEmpty())
            // The LLM fallback ran with no BYOK key and the central
            // gate closed: a local answer, zero turns spent.
            assertEquals(0, freeTurnsUsed())
        } finally {
            Dispatchers.resetMain()
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
            Dispatchers.resetMain()
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
            Dispatchers.resetMain()
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
            Dispatchers.resetMain()
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
            Dispatchers.resetMain()
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
            Dispatchers.resetMain()
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
            Dispatchers.resetMain()
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
            Dispatchers.resetMain()
        }
    }
}

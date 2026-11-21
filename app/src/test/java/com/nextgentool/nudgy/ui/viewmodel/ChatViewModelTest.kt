package com.nextgentool.nudgy.ui.viewmodel

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.ads.ChatTurnCap
import com.nextgentool.nudgy.ai.ClientIntentPreFilter
import com.nextgentool.nudgy.ai.GeminiClient
import com.nextgentool.nudgy.ai.LlmConfig
import com.nextgentool.nudgy.ai.LlmConfigProvider
import com.nextgentool.nudgy.ai.LlmConnectivity
import com.nextgentool.nudgy.ai.LlmFallbackHandler
import com.nextgentool.nudgy.ai.LlmServerCounter
import com.nextgentool.nudgy.ai.LlmUsageCounter
import com.nextgentool.nudgy.ai.ReplySoundPlayer
import com.nextgentool.nudgy.ai.VoiceAssistantManager
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.repository.CalendarRepository
import com.nextgentool.nudgy.data.repository.ChatRepository
import com.nextgentool.nudgy.data.repository.MemoryRepository
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.startOfDayMillis
import com.nextgentool.nudgy.ui.screens.ChatActionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    private lateinit var voiceAssistantManager: VoiceAssistantManager
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
        voiceAssistantManager = VoiceAssistantManager(context)
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
        voiceAssistantManager, fakeSound,
        LlmFallbackHandler(
            geminiClient = recordingGemini,
            context = context,
            configProvider = LlmConfigProvider { config },
            serverCounter = LlmServerCounter { _, _, _, _ -> LlmUsageCounter.CounterResult.ALLOWED },
            connectivity = LlmConnectivity { online }
        ),
        memoryRepository
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
}

package com.nextgentool.nudgy.ui.screens

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.ads.ChatCapState
import com.nextgentool.nudgy.ai.ChatDraft
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
import com.nextgentool.nudgy.ai.VoiceAssistantManager
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.repository.CalendarRepository
import com.nextgentool.nudgy.data.repository.ChatRepository
import com.nextgentool.nudgy.data.repository.MemoryRepository
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 3-0 — ChatScreen render + send smoke test.
 *
 * Renders the REAL ChatScreen (not the old ChatShell placeholder) on a
 * MANUALLY-constructed ChatViewModel (real in-memory Room + real
 * prefilter, no Hilt — same pattern as TasksScreenSmokeTest) and
 * drives one full send: type → send → assistant reply appears. It
 * does NOT test Hilt injection itself — that is covered by the
 * MainActivityEntryPointTest guard + the device test.
 *
 * The reply sound is a counting fake (same 4-line fake as
 * ChatViewModelTest, duplicated — test sources share no fakes file);
 * voice input is never started here, so the real
 * VoiceAssistantManager is construction-only.
 *
 * Sprint 3-3 — the LLM-fallback path renders end to end here: an
 * Unhandled message shows the local fallback answer, and with a BYOK
 * key a structured reply shows the preview card and Confirm creates
 * the task. The Gemini transport is a recording fake (no network);
 * the cap starts empty and the gate is closed by default.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatScreenSmokeTest {

    @get:Rule
    val composeRule = createComposeRule()

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

    private lateinit var db: NudgyDatabase
    private lateinit var context: Context
    private lateinit var viewModel: ChatViewModel
    private lateinit var taskRepository: TaskRepository
    private lateinit var recordingGemini: RecordingGeminiClient

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("nudgy_auth_prefs", Context.MODE_PRIVATE)
            .edit().putString("active_user_id", "test-user-1").apply()
        context.getSharedPreferences("nudgy_ads", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("nudgy_settings", Context.MODE_PRIVATE).edit().clear().apply()
        db = Room.inMemoryDatabaseBuilder(context, NudgyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        taskRepository = TaskRepository(context, db)
        val calendarRepository = CalendarRepository(context, db)
        val memoryRepository = MemoryRepository(context, db)
        val preFilter = ClientIntentPreFilter(
            taskRepository, calendarRepository, memoryRepository, context.assets
        )
        recordingGemini = RecordingGeminiClient(context)
        viewModel = ChatViewModel(
            ChatRepository(context, db),
            preFilter,
            taskRepository,
            calendarRepository,
            VoiceAssistantManager(context),
            FakeReplySoundPlayer(),
            LlmFallbackHandler(
                geminiClient = recordingGemini,
                context = context,
                configProvider = LlmConfigProvider {
                    LlmConfig(
                        centralEnabled = false,
                        freeTurnsPerDay = 5L,
                        maxRewardedTurnsPerDay = 3L,
                        modelVersion = "gemini-3.5-flash-lite"
                    )
                },
                serverCounter = LlmServerCounter { _, _, _, _ -> LlmUsageCounter.CounterResult.ALLOWED },
                connectivity = LlmConnectivity { true }
            ),
            memoryRepository,
            context,
            LlmConfigProvider {
                LlmConfig(
                    centralEnabled = false,
                    freeTurnsPerDay = 5L,
                    maxRewardedTurnsPerDay = 3L,
                    modelVersion = "gemini-3.5-flash-lite"
                )
            },
            com.nextgentool.nudgy.ads.NudgyAdsRewardedGateway(context)
        )
    }

    @After
    fun tearDown() {
        db.close()
        context.getSharedPreferences("nudgy_ads", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("nudgy_settings", Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun setChatContent() {
        composeRule.setContent {
            val messages by viewModel.messages.collectAsState()
            val isThinking by viewModel.isThinking.collectAsState()
            val isListening by viewModel.isListening.collectAsState()
            val isSpeaking by viewModel.isSpeaking.collectAsState()
            val thinkingText by viewModel.thinkingText.collectAsState()
            // Sprint 3-2 — the preview card renders from the ViewModel's
            // pending draft; without these the Confirm/Discard buttons
            // would be dead in the test.
            val pendingDraft by viewModel.pendingDraft.collectAsState()
            val pendingDraftMessageId by viewModel.pendingDraftMessageId.collectAsState()
            ChatScreen(
                user = viewModel.user,
                messages = messages,
                isListening = isListening,
                isSpeaking = isSpeaking,
                isThinking = isThinking,
                thinkingText = thinkingText,
                onSendMessage = { viewModel.sendMessage(it) },
                onStartVoice = { viewModel.startVoice() },
                onStopVoice = { viewModel.stopVoice() },
                onBlockFocusTime = { start, end, title, messageId ->
                    viewModel.onBlockFocusTime(start, end, title, messageId)
                },
                pendingDraft = pendingDraft,
                pendingDraftMessageId = pendingDraftMessageId,
                onConfirmDraft = { viewModel.confirmDraft() },
                onDiscardDraft = { viewModel.discardDraft() },
                onDraftEdited = { viewModel.editDraft(it) },
                // Sprint 3-5 — per-item remove on the split preview card.
                onRemoveSplitSubtask = { viewModel.removeSplitSubtask(it) }
            )
        }
    }

    @Test
    fun `ChatScreen renders input and welcome message`() {
        setChatContent()
        // The input bar sits outside the LazyColumn, so it is always
        // displayed; the welcome message is the newest item at the
        // bottom of the reversed list, so it is displayed too once
        // seeded. The hero is NOT asserted here: it lives at the far
        // end of the reversed list and LazyColumn only composes
        // near-viewport items, so its presence is timing/layout
        // dependent (round 1 failed it on visibility, round 2 on
        // existence — both flakes of the same cause, not the product).
        composeRule.onNodeWithText("Ask Nudgy anything...").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Voice input").assertExists()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Hi! What can I help you get done today?")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Hi! What can I help you get done today?").assertIsDisplayed()
    }

    @Test
    fun `sending a creation shows the preview card and confirm creates the task`() {
        setChatContent()
        // The welcome message is seeded asynchronously — wait for it
        // before typing, so the send is not racing the seed.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Hi! What can I help you get done today?")
                .fetchSemanticsNodes().isNotEmpty()
        }
        // The input is the screen's only editable field — target it by
        // its SetTextAction, not by the placeholder text (the
        // placeholder is a separate, non-editable Text node).
        composeRule.onNode(hasSetTextAction()).performTextInput("call tasnim at 5pm")
        composeRule.onNodeWithContentDescription("Send").performClick()
        // Sprint 3-2 — the reply is a preview card, and NOTHING is in
        // the DB yet: the task exists only after Confirm.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("draft_preview_card")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("draft_preview_card").assertIsDisplayed()
        runBlocking {
            assertTrue(taskRepository.getAllTasksOnce("test-user-1").isEmpty())
        }
        composeRule.onNodeWithTag("draft_confirm_button").performClick()
        // The receipt arrives with the TASK_PREVIEW card. "View Task"
        // exists ONLY on the post-save receipt — the preview card has
        // Confirm / Edit / Discard instead, so this waitUntil only
        // passes when Confirm actually saved. (The receipt text
        // "remind you about" cannot be used: the preview question
        // contains the same words, so onNode would throw on
        // duplicates — the race the old test's comment warned about.)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("View Task")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("View Task").assertIsDisplayed()
        runBlocking {
            val tasks = taskRepository.getAllTasksOnce("test-user-1")
            assertEquals(1, tasks.size)
            assertTrue(tasks.single().title.contains("tasnim", ignoreCase = true))
        }
    }

    @Test
    fun `discarding the preview card creates nothing`() {
        setChatContent()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Hi! What can I help you get done today?")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasSetTextAction()).performTextInput("call tasnim at 5pm")
        composeRule.onNodeWithContentDescription("Send").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("draft_preview_card")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("draft_discard_button").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("discarded", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        runBlocking {
            assertTrue(taskRepository.getAllTasksOnce("test-user-1").isEmpty())
        }
    }

    // Sprint 3-3 — the LLM-fallback path end to end: an Unhandled
    // message with no BYOK key and the gate closed shows the local
    // fallback answer (no LLM call, no turn spent).
    @Test
    fun `unhandled message shows the local fallback answer`() {
        setChatContent()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Hi! What can I help you get done today?")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasSetTextAction()).performTextInput("what is the meaning of life?")
        composeRule.onNodeWithContentDescription("Send").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("didn't quite catch", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("didn't quite catch", substring = true).assertIsDisplayed()
    }

    // Sprint 3-3 — with a BYOK key, the same Unhandled message gets a
    // structured reply: the preview card renders, and Confirm creates
    // the validated task (the full draft → preview → confirm flow,
    // rendered on the real screen).
    @Test
    fun `BYOK structured reply shows the preview card and confirm creates the task`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("nudgy_settings", Context.MODE_PRIVATE)
            .edit().putString("custom_gemini_api_key", "test-byok-key").apply()
        recordingGemini.restResponse =
            """{"action": "create_tasks", "tasks": [{"title": "Buy milk", "due": "2026-11-22T09:00:00", "priority": "HIGH"}], "events": [], "subtasks": [], "reply": "Got it, I'll add it."}"""
        setChatContent()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Hi! What can I help you get done today?")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasSetTextAction()).performTextInput("what is the meaning of life?")
        composeRule.onNodeWithContentDescription("Send").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("draft_preview_card")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("draft_preview_card").assertIsDisplayed()
        runBlocking {
            assertTrue(taskRepository.getAllTasksOnce("test-user-1").isEmpty())
        }
        composeRule.onNodeWithTag("draft_confirm_button").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("View Task")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("View Task").assertIsDisplayed()
        runBlocking {
            val tasks = taskRepository.getAllTasksOnce("test-user-1")
            assertEquals(1, tasks.size)
            assertTrue(tasks.single().title.contains("milk", ignoreCase = true))
        }
    }

    @Test
    fun `restyled chat renders message bubbles and input from a plain list`() {
        // Sprint 3-1 — restyle render check: the screen renders from a
        // plain message list (no ViewModel, no seed race), with the
        // shared input bar. The hero is NOT asserted here: it sits at
        // the far end of the reversed list and LazyColumn only
        // composes near-viewport items (the S3-0 flake). Mascot
        // absence is a static check (zero NudgyMascot references in
        // ChatScreen.kt) — the mascot carries no semantics node to
        // assert on.
        val messages = listOf(
            ChatMessage(id = "u1", senderIsUser = true, text = "Water the plants at 6pm"),
            ChatMessage(id = "a1", senderIsUser = false, text = "Done — I will remind you about the plants.")
        )
        composeRule.setContent {
            ChatScreen(
                user = viewModel.user,
                messages = messages,
                isListening = false,
                isSpeaking = false,
                isThinking = false,
                thinkingText = "Nudgy is thinking...",
                onSendMessage = {},
                onStartVoice = {},
                onStopVoice = {},
                onBlockFocusTime = { _, _, _, _ -> }
            )
        }
        // Unique texts on purpose: "Plan my day" would also match the
        // quick-prompt pill of the same words, and onNode throws on
        // more than one match (that was the round-2 failure).
        composeRule.onNodeWithText("Water the plants at 6pm").assertExists()
        composeRule.onNodeWithText("Done — I will remind you about the plants.").assertExists()
        composeRule.onNodeWithText("Ask Nudgy anything...").assertExists()
        composeRule.onNodeWithContentDescription("Voice input").assertExists()
    }

    // Sprint 3-4 — the brain-dump combined preview card renders from
    // a plain list (no recognizer: Robolectric cannot drive one):
    // three drafts in ONE card, Confirm-all + Discard, and per-item
    // remove. Removing a row drops it; Confirm-all fires once.
    @Test
    fun `brain dump combined card lists drafts and remove drops one`() {
        val now = System.currentTimeMillis()
        val drafts = listOf(
            ChatDraft(kind = DraftKind.TASK, title = "Buy milk", startEpochMillis = now + 2 * 60 * 60 * 1000L),
            ChatDraft(kind = DraftKind.TASK, title = "Call Tasnim", startEpochMillis = now + 5 * 60 * 60 * 1000L),
            ChatDraft(kind = DraftKind.SCHEDULE, title = "Doctor appointment", startEpochMillis = now + 24 * 60 * 60 * 1000L, endEpochMillis = now + 24 * 60 * 60 * 1000L + 45 * 60 * 1000L)
        )
        val messages = listOf(
            ChatMessage(id = "u1", senderIsUser = true, text = "buy milk. call tasnim at 5pm. doctor appointment tomorrow at 6am"),
            ChatMessage(id = "dump-msg-1", senderIsUser = false, text = "Here's what I heard — 3 tasks:", actionType = ChatActionType.DRAFT_PREVIEW)
        )
        var confirmClicks = 0
        var discardClicks = 0
        composeRule.setContent {
            var remaining by remember { mutableStateOf(drafts) }
            ChatScreen(
                user = viewModel.user,
                messages = messages,
                isListening = false,
                isSpeaking = false,
                isThinking = false,
                thinkingText = "Nudgy is thinking...",
                onSendMessage = {},
                onStartVoice = {},
                onStopVoice = {},
                onBlockFocusTime = { _, _, _, _ -> },
                pendingBrainDumpDrafts = remaining,
                pendingBrainDumpMessageId = "dump-msg-1",
                onConfirmBrainDump = { confirmClicks++ },
                onDiscardBrainDump = { discardClicks++ },
                onRemoveBrainDumpDraft = { index ->
                    remaining = remaining.toMutableList().also { it.removeAt(index) }
                }
            )
        }
        composeRule.onNodeWithTag("brain_dump_preview_card").assertIsDisplayed()
        composeRule.onNodeWithText("Brain dump — 3 tasks").assertIsDisplayed()
        composeRule.onNodeWithText("Buy milk").assertIsDisplayed()
        composeRule.onNodeWithText("Call Tasnim").assertIsDisplayed()
        composeRule.onNodeWithText("Doctor appointment").assertIsDisplayed()
        composeRule.onNodeWithTag("brain_dump_confirm_button").assertIsDisplayed()
        composeRule.onNodeWithTag("brain_dump_discard_button").assertIsDisplayed()
        // Removing the middle row drops it and renumbers the card.
        composeRule.onNodeWithTag("brain_dump_remove_1").performClick()
        composeRule.onNodeWithText("Call Tasnim").assertDoesNotExist()
        composeRule.onNodeWithText("Brain dump — 2 tasks").assertIsDisplayed()
        composeRule.onNodeWithText("Buy milk").assertIsDisplayed()
        composeRule.onNodeWithText("Doctor appointment").assertIsDisplayed()
        composeRule.onNodeWithTag("brain_dump_confirm_button").performClick()
        assertEquals(1, confirmClicks)
        assertEquals(0, discardClicks)
    }

    // Sprint 3-4 — the rewarded entry: a capped cap state renders the
    // "+1 answer" offer, and tapping it calls the watch callback (the
    // ad itself never plays in Robolectric — the gateway is the seam).
    @Test
    fun `capped cap state shows the rewarded offer and watch fires`() {
        val messages = listOf(
            ChatMessage(id = "a1", senderIsUser = false, text = "You've used your 5 free AI answers for today.")
        )
        var watchClicks = 0
        var retryClicks = 0
        composeRule.setContent {
            ChatScreen(
                user = viewModel.user,
                messages = messages,
                isListening = false,
                isSpeaking = false,
                isThinking = false,
                thinkingText = "Nudgy is thinking...",
                onSendMessage = {},
                onStartVoice = {},
                onStopVoice = {},
                onBlockFocusTime = { _, _, _, _ -> },
                chatCapState = ChatCapState(
                    freeTurnsUsed = 5,
                    freeTurnsPerDay = 5L,
                    bonusTurns = 0,
                    rewardedTurnsGrantedToday = 0,
                    maxRewardedTurnsPerDay = 3L
                ),
                rewardedAdReady = true,
                onWatchRewardedAd = { watchClicks++ },
                onRetryRewardedAd = { retryClicks++ }
            )
        }
        composeRule.onNodeWithText("Watch ad for +1 answer").assertIsDisplayed()
        composeRule.onNodeWithTag("rewarded_watch_button").performClick()
        assertEquals(1, watchClicks)
        assertEquals(0, retryClicks)
    }

    // ---- Sprint 3-5 — subtask split (feature 20) ----

    private val splitJson =
        """{"action": "split_task", "subtasks": [{"title": "Buy flour"}, {"title": "Mix the dough"}, {"title": "Bake for 30 minutes"}], "tasks": [], "events": [], "reply": "Here is how I'd break it down."}"""

    private fun seedParentTask(title: String) = runBlocking {
        taskRepository.createTask(
            userId = "test-user-1",
            title = title,
            dueEpochMillis = System.currentTimeMillis() + 86_400_000L
        )
    }

    @Test
    fun `split flow renders the subtask preview card and confirm creates the subtasks`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("nudgy_settings", Context.MODE_PRIVATE)
            .edit().putString("custom_gemini_api_key", "test-byok-key").apply()
        recordingGemini.restResponse = splitJson
        val parent = seedParentTask("Plan Diwali party")
        setChatContent()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Hi! What can I help you get done today?")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasSetTextAction()).performTextInput("break down Plan Diwali party")
        composeRule.onNodeWithContentDescription("Send").performClick()
        // The split preview card lists the subtasks (no Edit button:
        // a split has no time to edit and no title to change here).
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("draft_preview_card")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("draft_preview_card").assertIsDisplayed()
        composeRule.onNodeWithText("Buy flour").assertIsDisplayed()
        composeRule.onNodeWithText("Mix the dough").assertIsDisplayed()
        composeRule.onNodeWithText("Bake for 30 minutes").assertIsDisplayed()
        composeRule.onNodeWithTag("draft_edit_button").assertDoesNotExist()
        runBlocking {
            assertTrue(taskRepository.observeSubtasks(parent.id).first().isEmpty())
        }
        composeRule.onNodeWithTag("draft_confirm_button").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("draft_preview_card")
                .fetchSemanticsNodes().isEmpty()
        }
        runBlocking {
            val subtasks = taskRepository.observeSubtasks(parent.id).first()
            assertEquals(3, subtasks.size)
            assertEquals(
                listOf("Buy flour", "Mix the dough", "Bake for 30 minutes"),
                subtasks.map { it.title }
            )
            assertEquals(0 to 3, taskRepository.subtaskProgress(parent.id))
        }
    }

    @Test
    fun `split with no LLM available shows the manual path message`() {
        // No BYOK key, central gate closed (the default): the split
        // names the manual path instead of a generic fallback.
        val parent = seedParentTask("Plan Diwali party")
        setChatContent()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Hi! What can I help you get done today?")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasSetTextAction()).performTextInput("break down Plan Diwali party")
        composeRule.onNodeWithContentDescription("Send").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("detail sheet", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("detail sheet", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("draft_preview_card").assertDoesNotExist()
        runBlocking {
            assertTrue(taskRepository.observeSubtasks(parent.id).first().isEmpty())
        }
    }
}

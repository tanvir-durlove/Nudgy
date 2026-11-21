package com.nextgentool.nudgy.ui.screens

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.ai.ClientIntentPreFilter
import com.nextgentool.nudgy.ai.ReplySoundPlayer
import com.nextgentool.nudgy.ai.VoiceAssistantManager
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.repository.CalendarRepository
import com.nextgentool.nudgy.data.repository.ChatRepository
import com.nextgentool.nudgy.data.repository.MemoryRepository
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.ui.viewmodel.ChatViewModel
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

    private lateinit var db: NudgyDatabase
    private lateinit var viewModel: ChatViewModel
    private lateinit var taskRepository: TaskRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("nudgy_auth_prefs", Context.MODE_PRIVATE)
            .edit().putString("active_user_id", "test-user-1").apply()
        db = Room.inMemoryDatabaseBuilder(context, NudgyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        taskRepository = TaskRepository(context, db)
        val calendarRepository = CalendarRepository(context, db)
        val preFilter = ClientIntentPreFilter(
            taskRepository, calendarRepository, MemoryRepository(context, db), context.assets
        )
        viewModel = ChatViewModel(
            ChatRepository(context, db),
            preFilter,
            taskRepository,
            calendarRepository,
            VoiceAssistantManager(context),
            FakeReplySoundPlayer()
        )
    }

    @After
    fun tearDown() {
        db.close()
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
                onDraftEdited = { viewModel.editDraft(it) }
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
}

package com.nextgentool.nudgy.ui.screens

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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
import org.junit.After
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

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("nudgy_auth_prefs", Context.MODE_PRIVATE)
            .edit().putString("active_user_id", "test-user-1").apply()
        db = Room.inMemoryDatabaseBuilder(context, NudgyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val taskRepository = TaskRepository(context, db)
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
                }
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
    fun `sending a message shows the assistant reply`() {
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
        // The prefilter creates the task and the reply names it.
        // Assert on "remind you about": it occurs ONLY in the reply
        // text. Call tasks build with reminderAtDue=true, so the
        // reply is "Done! I'll remind you about …" — never "Created
        // task". And "asnim" would also match the TASK_PREVIEW card
        // title, so onNode would throw on duplicates — whether the
        // card has composed yet is a race between the chat flow and
        // the task flow in observeMessages (that race is what flipped
        // this test between round 1 and round 2).
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("remind you about", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("remind you about", substring = true).assertIsDisplayed()
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
            ChatMessage(id = "u1", senderIsUser = true, text = "Plan my day"),
            ChatMessage(id = "a1", senderIsUser = false, text = "Here is your plan for today.")
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
        composeRule.onNodeWithText("Plan my day").assertExists()
        composeRule.onNodeWithText("Here is your plan for today.").assertExists()
        composeRule.onNodeWithText("Ask Nudgy anything...").assertExists()
        composeRule.onNodeWithContentDescription("Voice input").assertExists()
    }
}

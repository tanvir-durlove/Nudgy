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
    fun `ChatScreen renders hero and input`() {
        setChatContent()
        composeRule.onNodeWithText("What can I help you", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Ask Nudgy anything...").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Voice input").assertExists()
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
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("asnim", substring = true, ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("asnim", substring = true, ignoreCase = true).assertIsDisplayed()
    }
}

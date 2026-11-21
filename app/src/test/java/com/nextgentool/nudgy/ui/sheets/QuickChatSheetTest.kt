package com.nextgentool.nudgy.ui.sheets

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import com.nextgentool.nudgy.ui.screens.ChatMessage
import com.nextgentool.nudgy.ui.viewmodel.ChatViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 3-1 — Quick Chat sheet (Q8(a)): the centre + button opens
 * this compact sheet; the first message sent here, or the expand
 * button, moves to the full chat. The sheet and the full chat share
 * the same ChatViewModel, so history survives leaving and re-entering.
 *
 * The sheet is tested in isolation with a MANUALLY-constructed
 * ChatViewModel (real in-memory Room + real prefilter, no Hilt — same
 * pattern as ChatScreenSmokeTest) and counting lambdas for the
 * navigation callbacks. The centre-button → sheet wiring itself lives
 * in NudgyApp (Hilt), so it is covered by the code-path trace + the
 * device test (§10.7), not by a Robolectric test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class QuickChatSheetTest {

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
        val memoryRepository = MemoryRepository(context, db)
        val preFilter = ClientIntentPreFilter(
            taskRepository, calendarRepository, memoryRepository, context.assets
        )
        // Sprint 3-3 — the ViewModel takes the LLM fallback handler
        // and the memory repository now. The sheet never sends through
        // the LLM path in these tests (sends are captured lambdas),
        // so the handler runs on the real client with the gate closed
        // and no BYOK key: it can only answer locally.
        viewModel = ChatViewModel(
            ChatRepository(context, db),
            preFilter,
            taskRepository,
            calendarRepository,
            VoiceAssistantManager(context),
            FakeReplySoundPlayer(),
            LlmFallbackHandler(
                geminiClient = GeminiClient(context),
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
            memoryRepository
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun renderSheet(
        messages: List<ChatMessage>,
        sent: MutableList<String> = mutableListOf(),
        expandCount: IntArray = intArrayOf(0),
        onSendMessage: (String) -> Unit = { sent.add(it) },
        onExpandToFullChat: () -> Unit = { expandCount[0]++ }
    ) {
        composeRule.setContent {
            QuickChatSheet(
                messages = messages,
                isThinking = false,
                isListening = false,
                onSendMessage = onSendMessage,
                onExpandToFullChat = onExpandToFullChat,
                onDismiss = {},
                onStartVoice = {},
                onStopVoice = {}
            )
        }
    }

    private fun renderSheetOnViewModel(
        sent: MutableList<String> = mutableListOf(),
        expandCount: IntArray = intArrayOf(0)
    ) {
        composeRule.setContent {
            val messages by viewModel.messages.collectAsState()
            val isThinking by viewModel.isThinking.collectAsState()
            val isListening by viewModel.isListening.collectAsState()
            QuickChatSheet(
                messages = messages,
                isThinking = isThinking,
                isListening = isListening,
                onSendMessage = { viewModel.sendMessage(it) },
                onExpandToFullChat = { expandCount[0]++ },
                onDismiss = {},
                onStartVoice = {},
                onStopVoice = {}
            )
        }
    }

    private fun waitForWelcome() {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Hi! What can I help you get done today?")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `sheet shows input and recent messages`() {
        renderSheetOnViewModel()
        waitForWelcome()
        composeRule.onNodeWithText("Quick Chat").assertIsDisplayed()
        composeRule.onNodeWithText("Ask Nudgy anything...").assertIsDisplayed()
        composeRule.onNodeWithText("Hi! What can I help you get done today?").assertExists()
        composeRule.onNodeWithContentDescription("Open full chat").assertExists()
    }

    @Test
    fun `sending the first message sends it and expands to full chat`() {
        val sent = mutableListOf<String>()
        val expandCount = intArrayOf(0)
        renderSheet(messages = emptyList(), sent = sent, expandCount = expandCount)
        composeRule.onNode(hasSetTextAction()).performTextInput("call tasnim at 5pm")
        composeRule.onNodeWithContentDescription("Send").performClick()
        assertEquals(listOf("call tasnim at 5pm"), sent)
        assertEquals(1, expandCount[0])
    }

    @Test
    fun `expand button opens full chat without sending`() {
        val sent = mutableListOf<String>()
        val expandCount = intArrayOf(0)
        renderSheet(messages = emptyList(), sent = sent, expandCount = expandCount)
        composeRule.onNodeWithContentDescription("Open full chat").performClick()
        assertEquals(emptyList<String>(), sent)
        assertEquals(1, expandCount[0])
    }

    @Test
    fun `sheet shows only the last three messages`() {
        val messages = listOf(
            ChatMessage(id = "m1", senderIsUser = true, text = "first message one"),
            ChatMessage(id = "m2", senderIsUser = false, text = "second message two"),
            ChatMessage(id = "m3", senderIsUser = true, text = "third message three"),
            ChatMessage(id = "m4", senderIsUser = false, text = "fourth message four"),
            ChatMessage(id = "m5", senderIsUser = true, text = "fifth message five")
        )
        renderSheet(messages = messages)
        composeRule.onNodeWithText("first message one").assertDoesNotExist()
        composeRule.onNodeWithText("second message two").assertDoesNotExist()
        composeRule.onNodeWithText("third message three").assertExists()
        composeRule.onNodeWithText("fourth message four").assertExists()
        composeRule.onNodeWithText("fifth message five").assertExists()
    }

    @Test
    fun `history survives leaving and re-entering the sheet`() {
        val expandCount = intArrayOf(0)
        // The sheet is conditionally composed on a state flag — the
        // same shape as NudgyApp's `if (quickChatOpen)`. Toggling the
        // flag is the leave / re-enter path; replacing the whole
        // content with setContent { } while the sheet's popup is
        // attached throws IllegalStateException (round-2 failure).
        var showSheet by mutableStateOf(true)
        composeRule.setContent {
            if (showSheet) {
                val messages by viewModel.messages.collectAsState()
                val isThinking by viewModel.isThinking.collectAsState()
                val isListening by viewModel.isListening.collectAsState()
                QuickChatSheet(
                    messages = messages,
                    isThinking = isThinking,
                    isListening = isListening,
                    onSendMessage = { viewModel.sendMessage(it) },
                    onExpandToFullChat = { expandCount[0]++ },
                    onDismiss = {},
                    onStartVoice = {},
                    onStopVoice = {}
                )
            }
        }
        waitForWelcome()
        // Send through the shared ViewModel (the real send path), then
        // the sent text is in the sheet's recent messages.
        composeRule.onNode(hasSetTextAction()).performTextInput("call tasnim at 5pm")
        composeRule.onNodeWithContentDescription("Send").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("call tasnim at 5pm")
                .fetchSemanticsNodes().isNotEmpty()
        }
        // Leave the sheet and come back on the SAME ViewModel instance
        // — the message must still be there.
        composeRule.runOnIdle { showSheet = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle { showSheet = true }
        composeRule.onNodeWithText("call tasnim at 5pm").assertExists()
    }
}

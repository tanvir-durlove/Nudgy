package com.nextgentool.nudgy.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.ads.ChatTurnCap
import com.nextgentool.nudgy.auth.AuthManager
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 3-3 — the LLM fallback cap chain (spec §8, step 4).
 *
 * The chain order is enforced by the fakes, not just read from the
 * code: the server fake asserts the local turn is already spent when
 * it runs, and the Gemini fake asserts the server counter already ran.
 * The central-gate proof lives here at the handler level (gate closed
 * → the AI Logic path is never entered, zero cap spent) and in
 * GeminiClientGateTest at the client level.
 *
 * Fakes: a recording GeminiClient subclass (the transport methods are
 * `protected open` for exactly this), a recording server counter, and
 * lambdas for config + connectivity. SharedPreferences "nudgy_ads"
 * (the cap) and "nudgy_settings" (the BYOK key) are cleared per test
 * so the cap starts empty.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LlmFallbackHandlerTest {

    private open class RecordingGeminiClient(context: Context) : GeminiClient(context) {
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

    private class RecordingServerCounter : LlmServerCounter {
        var calls = 0
        var result: LlmUsageCounter.CounterResult = LlmUsageCounter.CounterResult.ALLOWED
        var seenBucket: LlmUsageCounter.Bucket? = null
        var seenUid: String? = null
        /** Set by the test to assert the local cap was already spent. */
        var assertLocalSpent: (() -> Unit)? = null

        override suspend fun tryConsumeServerTurn(
            uid: String,
            bucket: LlmUsageCounter.Bucket,
            freeCapPerDay: Long,
            bonusCapPerDay: Long
        ): LlmUsageCounter.CounterResult {
            calls++
            seenBucket = bucket
            seenUid = uid
            assertLocalSpent?.invoke()
            return result
        }
    }

    private lateinit var context: Context
    private lateinit var gemini: RecordingGeminiClient
    private lateinit var server: RecordingServerCounter
    private var online = true
    private var config = LlmConfig(
        centralEnabled = false,
        freeTurnsPerDay = 5L,
        maxRewardedTurnsPerDay = 3L,
        modelVersion = "gemini-3.5-flash-lite"
    )

    private val userId = "test-user-1"
    private val validTaskJson =
        """{"action": "create_tasks", "tasks": [{"title": "Buy milk", "due": "2026-11-22T09:00:00", "priority": "HIGH"}], "events": [], "subtasks": [], "reply": "Got it, I'll add it."}"""
    private val validEventJson =
        """{"action": "create_event", "events": [{"title": "Dentist", "start": "2026-11-23T15:00:00", "end": "2026-11-23T15:45:00"}], "tasks": [], "subtasks": [], "reply": "Scheduled."}"""

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("nudgy_ads", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("nudgy_settings", Context.MODE_PRIVATE).edit().clear().apply()
        gemini = RecordingGeminiClient(context)
        server = RecordingServerCounter()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("nudgy_ads", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("nudgy_settings", Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun newHandler() = LlmFallbackHandler(
        geminiClient = gemini,
        context = context,
        configProvider = LlmConfigProvider { config },
        serverCounter = server,
        connectivity = LlmConnectivity { online }
    )

    private fun setByokKey() {
        context.getSharedPreferences("nudgy_settings", Context.MODE_PRIVATE)
            .edit().putString("custom_gemini_api_key", "test-byok-key").apply()
    }

    private fun freeTurnsUsed(): Int =
        ChatTurnCap.snapshot(context, 5L, 3L).freeTurnsUsed

    @Test
    fun `offline returns local fallback and consumes no cap`() = runTest {
        online = false
        val result = newHandler().handleUnhandled(userId, "what is the meaning of life?")
        assertTrue(result is LlmFallbackResult.Reply)
        assertTrue((result as LlmFallbackResult.Reply).text.contains("didn't quite catch"))
        assertEquals(0, freeTurnsUsed())
        assertEquals(0, gemini.restCalls)
        assertEquals(0, gemini.aiLogicCalls)
        assertEquals(0, server.calls)
    }

    @Test
    fun `no BYOK and central gate closed returns local fallback with zero cap spent`() = runTest {
        // The critical gate proof at handler level: no key, gate off —
        // the chain never starts. No local turn, no server call, and
        // the AI Logic transport is never entered.
        val result = newHandler().handleUnhandled(userId, "what is the meaning of life?")
        assertTrue(result is LlmFallbackResult.Reply)
        assertTrue((result as LlmFallbackResult.Reply).text.contains("didn't quite catch"))
        assertEquals(0, freeTurnsUsed())
        assertEquals(0, server.calls)
        assertEquals(0, gemini.restCalls)
        assertEquals(0, gemini.aiLogicCalls)
    }

    @Test
    fun `cap chain order is local then server then Gemini`() = runTest {
        setByokKey()
        // The server fake asserts the local turn is already spent when
        // it runs; the Gemini fake asserts the server already ran.
        // Together with the final order list, the chain order is
        // enforced by the test, not just read from the code.
        val order = mutableListOf<String>()
        server.assertLocalSpent = {
            assertEquals(1, freeTurnsUsed())
            order.add("server")
        }
        val recordingRest = object : RecordingGeminiClient(context) {
            override suspend fun generateViaRest(
                userMessage: String,
                recentHistory: List<Pair<String, Boolean>>,
                    sanitizedModel: String,
                apiKey: String,
                systemInstruction: String,
                maxOutputTokens: Int
            ): String? {
                assertEquals(1, server.calls)
                order.add("gemini")
                return restResponse
            }
        }
        recordingRest.restResponse = validTaskJson
        val handler = LlmFallbackHandler(
            geminiClient = recordingRest,
            context = context,
            configProvider = LlmConfigProvider { config },
            serverCounter = server,
            connectivity = LlmConnectivity { true }
        )
        val result = handler.handleUnhandled(userId, "what is the meaning of life?")
        assertTrue(result is LlmFallbackResult.Draft)
        assertEquals(listOf("server", "gemini"), order)
        assertEquals(1, freeTurnsUsed())
        assertEquals(LlmUsageCounter.Bucket.FREE, server.seenBucket)
        assertEquals(userId, server.seenUid)
    }

    @Test
    fun `BYOK key routes to REST and never touches AI Logic`() = runTest {
        setByokKey()
        gemini.restResponse = validEventJson
        val result = newHandler().handleUnhandled(userId, "what is the meaning of life?")
        assertTrue(result is LlmFallbackResult.Draft)
        val draft = (result as LlmFallbackResult.Draft).draft
        assertEquals(DraftKind.SCHEDULE, draft.kind)
        assertEquals("Dentist", draft.title)
        assertEquals("Scheduled.", result.previewText)
        assertEquals(1, gemini.restCalls)
        assertEquals(0, gemini.aiLogicCalls)
        assertEquals(1, server.calls)
        assertEquals(1, freeTurnsUsed())
    }

    @Test
    fun `central gate on with no BYOK calls AI Logic and parses a structured draft`() = runTest {
        config = config.copy(centralEnabled = true)
        gemini.aiLogicResponse = validTaskJson
        val result = newHandler().handleUnhandled(userId, "what is the meaning of life?")
        assertTrue(result is LlmFallbackResult.Draft)
        val draft = (result as LlmFallbackResult.Draft).draft
        assertEquals(DraftKind.TASK, draft.kind)
        assertEquals("Buy milk", draft.title)
        assertEquals(com.nextgentool.nudgy.data.model.Priority.HIGH, draft.priority)
        assertEquals("Got it, I'll add it.", result.previewText)
        assertEquals(1, gemini.aiLogicCalls)
        assertEquals(0, gemini.restCalls)
        assertEquals(1, server.calls)
        assertEquals(1, freeTurnsUsed())
    }

    @Test
    fun `invalid JSON returns plain text and no draft`() = runTest {
        setByokKey()
        gemini.restResponse =
            """{"action": "create_tasks", "tasks": [{"title": "", "due": "2026-11-22T09:00:00"}], "reply": "I tried to add it."}"""
        val result = newHandler().handleUnhandled(userId, "what is the meaning of life?")
        assertTrue(result is LlmFallbackResult.Reply)
        assertEquals("I tried to add it.", (result as LlmFallbackResult.Reply).text)
        assertEquals(1, freeTurnsUsed()) // The turn was spent; the answer is plain text.
    }

    @Test
    fun `unparseable date returns plain text and no draft`() = runTest {
        setByokKey()
        gemini.restResponse =
            """{"action": "create_tasks", "tasks": [{"title": "Buy milk", "due": "tomorrow-ish"}], "reply": "Noted."}"""
        val result = newHandler().handleUnhandled(userId, "what is the meaning of life?")
        assertTrue(result is LlmFallbackResult.Reply)
        assertEquals("Noted.", (result as LlmFallbackResult.Reply).text)
    }

    @Test
    fun `unknown action returns plain-text reply`() = runTest {
        setByokKey()
        gemini.restResponse =
            """{"action": "teleport", "tasks": [], "reply": "I cannot teleport you."}"""
        val result = newHandler().handleUnhandled(userId, "what is the meaning of life?")
        assertTrue(result is LlmFallbackResult.Reply)
        assertEquals("I cannot teleport you.", (result as LlmFallbackResult.Reply).text)
    }

    @Test
    fun `Gemini failure refunds the local turn`() = runTest {
        setByokKey()
        gemini.restResponse = null // The transport itself failed.
        val result = newHandler().handleUnhandled(userId, "what is the meaning of life?")
        assertTrue(result is LlmFallbackResult.Reply)
        assertTrue((result as LlmFallbackResult.Reply).text.contains("didn't quite catch"))
        // The local turn is refunded; the server turn is NOT (its
        // transaction already committed — the documented limit).
        assertEquals(0, freeTurnsUsed())
        assertEquals(1, server.calls)
        assertEquals(1, gemini.restCalls)
    }

    @Test
    fun `guest uses the local cap only and never the server counter`() = runTest {
        setByokKey()
        gemini.restResponse = validTaskJson
        val result = newHandler().handleUnhandled(AuthManager.GUEST_USER_ID, "what is the meaning of life?")
        assertTrue(result is LlmFallbackResult.Draft)
        assertEquals(0, server.calls)
        assertEquals(1, freeTurnsUsed())
        assertEquals(1, gemini.restCalls)
    }

    @Test
    fun `capped local cap returns Capped and never calls Gemini`() = runTest {
        setByokKey()
        repeat(5) { ChatTurnCap.tryConsumeTurn(context, 5L, 3L) }
        assertEquals(5, freeTurnsUsed())
        val result = newHandler().handleUnhandled(userId, "what is the meaning of life?")
        assertTrue(result is LlmFallbackResult.Capped)
        assertTrue((result as LlmFallbackResult.Capped).text.contains("5 free AI answers"))
        assertEquals(0, gemini.restCalls)
        assertEquals(0, gemini.aiLogicCalls)
        assertEquals(0, server.calls)
    }

    @Test
    fun `server CAPPED keeps the local turn spent and returns Capped`() = runTest {
        setByokKey()
        server.result = LlmUsageCounter.CounterResult.CAPPED
        val result = newHandler().handleUnhandled(userId, "what is the meaning of life?")
        assertTrue(result is LlmFallbackResult.Capped)
        // The documented trade-off: the local turn stays spent.
        assertEquals(1, freeTurnsUsed())
        assertEquals(0, gemini.restCalls)
    }

    @Test
    fun `server ERROR refunds the local turn and answers locally`() = runTest {
        setByokKey()
        server.result = LlmUsageCounter.CounterResult.ERROR
        val result = newHandler().handleUnhandled(userId, "what is the meaning of life?")
        assertTrue(result is LlmFallbackResult.Reply)
        assertTrue((result as LlmFallbackResult.Reply).text.contains("didn't quite catch"))
        assertEquals(0, freeTurnsUsed())
        assertEquals(0, gemini.restCalls)
    }

    @Test
    fun `Bengali prompt gets a Bengali fallback when no LLM is available`() = runTest {
        val result = newHandler().handleUnhandled(userId, "আমার দিনটা কেমন যাবে?")
        assertTrue(result is LlmFallbackResult.Reply)
        assertTrue((result as LlmFallbackResult.Reply).text.contains("বুঝতে পারিনি"))
        assertEquals(0, freeTurnsUsed())
    }

    @Test
    fun `bonus turn is spent when the free allowance is gone`() = runTest {
        setByokKey()
        gemini.restResponse = validTaskJson
        repeat(5) { ChatTurnCap.tryConsumeTurn(context, 5L, 3L) }
        assertTrue(ChatTurnCap.grantRewardedTurn(context, 5L, 3L))
        val result = newHandler().handleUnhandled(userId, "what is the meaning of life?")
        assertTrue(result is LlmFallbackResult.Draft)
        assertEquals(LlmUsageCounter.Bucket.BONUS, server.seenBucket)
        assertEquals(0, ChatTurnCap.snapshot(context, 5L, 3L).bonusTurns)
    }
}

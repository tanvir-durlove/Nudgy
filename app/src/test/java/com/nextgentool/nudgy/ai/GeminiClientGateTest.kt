package com.nextgentool.nudgy.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 3-3 — the central activation gate at the GeminiClient level
 * (Q1 = (a), spec §8). The gate lives IN the client, at the only
 * entry points to the central path, so no caller can reach Firebase
 * AI Logic while it is closed — even a caller that forgets to check.
 *
 * The test subclasses the client and records which transport ran:
 * with the gate closed, `generateViaAiLogic` is NEVER invoked (the
 * test would fail if the real Firebase.ai() call were attempted —
 * there is no google-services.json in the test environment, so it
 * would throw). With the gate open, it IS invoked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GeminiClientGateTest {

    private class RecordingGeminiClient(context: Context) : GeminiClient(context) {
        var restCalls = 0
        var aiLogicCalls = 0
        var restResponse: String? = "rest answer"
        var aiLogicResponse: String? = "ai logic answer"

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
    private lateinit var client: RecordingGeminiClient

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("nudgy_settings", Context.MODE_PRIVATE).edit().clear().apply()
        client = RecordingGeminiClient(context)
    }

    private fun setByokKey() {
        context.getSharedPreferences("nudgy_settings", Context.MODE_PRIVATE)
            .edit().putString("custom_gemini_api_key", "test-byok-key").apply()
    }

    @Test
    fun `gate closed and no BYOK - plain response is the local fallback and AI Logic never runs`() =
        kotlinx.coroutines.test.runTest {
            val text = client.generateNudgyResponse("hello", centralEnabled = false)
            // The local fallback for a "hello" prompt.
            assertTrue(text.contains("Ready to get things done"))
            assertEquals(0, client.aiLogicCalls)
            assertEquals(0, client.restCalls)
        }

    @Test
    fun `gate closed and no BYOK - structured response is null and AI Logic never runs`() =
        kotlinx.coroutines.test.runTest {
            val text = client.generateStructuredResponse("hello", centralEnabled = false)
            assertNull(text)
            assertEquals(0, client.aiLogicCalls)
            assertEquals(0, client.restCalls)
        }

    @Test
    fun `gate closed is the default - omitting the flag never reaches central`() =
        kotlinx.coroutines.test.runTest {
            val text = client.generateStructuredResponse("hello")
            assertNull(text)
            assertEquals(0, client.aiLogicCalls)
        }

    @Test
    fun `gate open and no BYOK - AI Logic runs for both entry points`() =
        kotlinx.coroutines.test.runTest {
            val plain = client.generateNudgyResponse("hello", centralEnabled = true)
            assertEquals("ai logic answer", plain)
            assertEquals(1, client.aiLogicCalls)
            assertEquals(0, client.restCalls)

            val structured = client.generateStructuredResponse("hello", centralEnabled = true)
            assertEquals("ai logic answer", structured)
            assertEquals(2, client.aiLogicCalls)
            assertEquals(0, client.restCalls)
        }

    @Test
    fun `BYOK key routes to REST and never touches AI Logic even with the gate open`() =
        kotlinx.coroutines.test.runTest {
            setByokKey()
            val structured = client.generateStructuredResponse("hello", centralEnabled = true)
            assertEquals("rest answer", structured)
            assertEquals(1, client.restCalls)
            assertEquals(0, client.aiLogicCalls)

            val plain = client.generateNudgyResponse("hello", centralEnabled = true)
            assertEquals("rest answer", plain)
            assertEquals(2, client.restCalls)
            assertEquals(0, client.aiLogicCalls)
        }

    @Test
    fun `a failed transport returns the local fallback for plain and null for structured`() =
        kotlinx.coroutines.test.runTest {
            setByokKey()
            client.restResponse = null
            val plain = client.generateNudgyResponse("hello", centralEnabled = false)
            assertTrue(plain.contains("couldn't process"))
            val structured = client.generateStructuredResponse("hello", centralEnabled = false)
            assertNull(structured)
        }

    @Test
    fun `hasCustomKey reflects the stored BYOK key`() {
        assertTrue(!client.hasCustomKey())
        setByokKey()
        assertTrue(client.hasCustomKey())
        context.getSharedPreferences("nudgy_settings", Context.MODE_PRIVATE)
            .edit().putString("custom_gemini_api_key", "   ").apply()
        assertTrue(!client.hasCustomKey())
    }
}

package com.nextgentool.nudgy.ai

import android.content.Context
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Gemini chat backend.
 *
 * Key chain, in order:
 * 1. BYOK — the user's own key (`custom_gemini_api_key` in
 *    SharedPreferences `nudgy_settings`) goes through the existing
 *    OkHttp REST path, unchanged. The user pays for their own usage.
 * 2. No BYOK — the central key via Firebase AI Logic (Gemini Developer
 *    API backend). The billing key lives server-side in the Firebase
 *    console and NEVER ships in the APK.
 *
 * Sprint 3-3 — CENTRAL ACTIVATION GATE (Q1 = (a)): the central path
 * is OFF by default. [generateNudgyResponse] and
 * [generateStructuredResponse] take `centralEnabled = false` unless
 * the caller passes the live Remote Config value
 * (`llm_central_enabled`, default false — see NudgyRemoteConfig).
 * With the gate closed and no BYOK key, both methods return the local
 * fallback / null WITHOUT touching Firebase AI Logic — wiring the
 * chain as-is can never go live with billing. Central can be flipped
 * on later WITHOUT a code change, but ONLY by a separate decision +
 * billing + alerts + quota setup. This class links no billing itself.
 *
 * The baked API key from BuildConfig is deliberately NOT read anywhere
 * here: an APK-baked key is extractable, and removing its use is the
 * point of the central key. Any failure on either path returns the
 * local fallback — never a crash, never a retry loop.
 *
 * Test seam: the class is `open` and the two transport methods are
 * `protected open` so tests can subclass and record which path ran
 * (the gate proof: with the gate closed, the AI Logic method is never
 * invoked). Production code must never subclass this client.
 */
open class GeminiClient(private val context: Context? = null) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun generateNudgyResponse(
        userMessage: String,
        recentHistory: List<Pair<String, Boolean>> = emptyList(), // Pair(text, isUser)
        userMemories: List<String> = emptyList(),
        modelVersion: String = "gemini-3.5-flash-lite",
        callContext: Context? = null,
        // Sprint 3-3 — the central activation gate (Q1 = (a)): false
        // unless the caller passes the live `llm_central_enabled`
        // Remote Config value. Closed + no BYOK → local fallback, and
        // the AI Logic path is never entered (no billing possible).
        centralEnabled: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        val effectiveContext = callContext ?: context
        val customKey = effectiveContext?.getSharedPreferences("nudgy_settings", Context.MODE_PRIVATE)
            ?.getString("custom_gemini_api_key", null)
            ?.trim()

        val sanitizedModel = sanitizeModel(modelVersion)
        val plainInstruction = systemInstructionText(userMemories)
        return@withContext try {
            val text = when {
                !customKey.isNullOrBlank() -> generateViaRest(
                    userMessage, recentHistory, sanitizedModel, customKey,
                    plainInstruction, PLAIN_REST_MAX_OUTPUT_TOKENS
                )
                centralEnabled -> generateViaAiLogic(
                    userMessage, recentHistory, sanitizedModel,
                    plainInstruction, PLAIN_AI_LOGIC_MAX_OUTPUT_TOKENS
                )
                else -> null // Gate closed, no BYOK: no LLM at all.
            }
            text?.trim()?.ifEmpty { getLocalFallback(userMessage) } ?: getLocalFallback(userMessage)
        } catch (e: Exception) {
            Log.e("GeminiClient", "Gemini call failed", e)
            getLocalFallback(userMessage)
        }
    }

    /**
     * Sprint 3-3 — structured-output call (spec §8). Same key chain
     * and the same central gate as [generateNudgyResponse], but the
     * model is instructed to answer with ONLY the JSON contract
     * (action/tasks/events/subtasks/reply) and the output ceiling is
     * [STRUCTURED_MAX_OUTPUT_TOKENS].
     *
     * Returns the raw model text, or null when there is NO LLM to call
     * (gate closed + no BYOK — the caller checks availability first,
     * this is the backstop) or when the call itself failed (exception,
     * empty response). A null here means "the turn produced no answer":
     * the caller refunds the local turn and answers locally. It NEVER
     * substitutes the local fallback text — that would be
     * indistinguishable from a successful plain-text reply, and the
     * caller must be able to tell the two apart.
     */
    suspend fun generateStructuredResponse(
        userMessage: String,
        recentHistory: List<Pair<String, Boolean>> = emptyList(),
        userMemories: List<String> = emptyList(),
        modelVersion: String = "gemini-3.5-flash-lite",
        centralEnabled: Boolean = false,
        callContext: Context? = null
    ): String? = withContext(Dispatchers.IO) {
        val effectiveContext = callContext ?: context
        val customKey = effectiveContext?.getSharedPreferences("nudgy_settings", Context.MODE_PRIVATE)
            ?.getString("custom_gemini_api_key", null)
            ?.trim()
        val sanitizedModel = sanitizeModel(modelVersion)
        val structuredInstruction = structuredSystemInstructionText(userMemories)
        return@withContext try {
            when {
                !customKey.isNullOrBlank() -> generateViaRest(
                    userMessage, recentHistory, sanitizedModel, customKey,
                    structuredInstruction, STRUCTURED_MAX_OUTPUT_TOKENS
                )?.trim()?.ifEmpty { null }
                centralEnabled -> generateViaAiLogic(
                    userMessage, recentHistory, sanitizedModel,
                    structuredInstruction, STRUCTURED_MAX_OUTPUT_TOKENS
                )?.trim()?.ifEmpty { null }
                else -> null // Gate closed, no BYOK: no LLM at all.
            }
        } catch (e: Exception) {
            Log.e("GeminiClient", "Gemini structured call failed", e)
            null
        }
    }

    /**
     * Sprint 3-3 — true when the user supplied their own Gemini key
     * (BYOK). The cap chain checks this BEFORE consuming any turn: a
     * user with no key and a closed central gate must never spend a
     * turn on a local fallback answer.
     */
    fun hasCustomKey(callContext: Context? = null): Boolean {
        val effectiveContext = callContext ?: context ?: return false
        return !effectiveContext.getSharedPreferences("nudgy_settings", Context.MODE_PRIVATE)
            .getString("custom_gemini_api_key", null)?.trim().isNullOrBlank()
    }

    /**
     * Model sanitizer: blank, retired (1.5 / 2.0) or superseded
     * (3.1-flash-lite-preview, which retires 7 May 2027) values all fall
     * back to the current cheap model, gemini-3.5-flash-lite. A stale
     * Remote Config value can never point at a dead model.
     */
    private fun sanitizeModel(modelVersion: String): String = when {
        modelVersion.isBlank() || modelVersion.contains("1.5")
            || modelVersion.contains("2.0") || modelVersion.contains("3.1") ->
            "gemini-3.5-flash-lite"
        else -> modelVersion
    }

    /**
     * Sliding context window, shared by both paths: last 6 turns,
     * strictly alternating user/model roles, starting with a user turn.
     */
    private fun sanitizeHistory(recentHistory: List<Pair<String, Boolean>>): List<Pair<String, Boolean>> {
        val sanitizedHistory = mutableListOf<Pair<String, Boolean>>()
        val rawWindow = recentHistory.takeLast(6)
        for (item in rawWindow) {
            if (sanitizedHistory.isEmpty()) {
                if (item.second) { // must start with user
                    sanitizedHistory.add(item)
                }
            } else {
                // Only add if role alternates from the last entry
                val lastIsUser = sanitizedHistory.last().second
                if (item.second != lastIsUser) {
                    sanitizedHistory.add(item)
                }
            }
        }

        // Ensure history ends with a model turn before appending the new user message
        if (sanitizedHistory.isNotEmpty() && sanitizedHistory.last().second) {
            sanitizedHistory.removeAt(sanitizedHistory.size - 1)
        }
        return sanitizedHistory
    }

    private fun memorySuffix(userMemories: List<String>): String =
        if (userMemories.isNotEmpty()) {
            " User preferences you remember: " + userMemories.joinToString("; ") + "."
        } else ""

    private fun systemInstructionText(userMemories: List<String>): String =
        "You are Nudgy, a warm, energetic, and supportive AI productivity assistant. Tagline: 'Things Get Done.' Keep answers friendly, motivating, and strictly concise (1-2 sentences). You are fully bilingual in English and Bengali; always respond in the language the user spoke/wrote in." + memorySuffix(userMemories)

    /**
     * Sprint 3-3 — the structured-output contract (spec §8) as a system
     * instruction. The model MUST answer with ONLY the JSON object —
     * no prose, no markdown fences, no explanation outside the JSON.
     * At most ONE task or ONE event per answer: the S3-2 preview card
     * holds one draft, so several items come back as plain text
     * instead (the S3-4 combined card unlocks multi-item confirm).
     * Dates are ISO-8601 ("2026-11-22T15:00:00"); when the time is
     * unknown, use null for `due` and say so in `reply` — never invent
     * a time. `reply` is the one-sentence answer shown to the user, in
     * the language they wrote in.
     */
    private fun structuredSystemInstructionText(userMemories: List<String>): String =
        "You are Nudgy, a warm, energetic, and supportive AI productivity assistant. " +
            "Tagline: 'Things Get Done.' You are fully bilingual in English and Bengali; " +
            "always reply in the language the user wrote in." + memorySuffix(userMemories) +
            " Answer with ONLY this JSON object, no other text, no markdown fences: " +
            "{\"action\": \"create_tasks\" | \"create_event\" | \"split_task\" | \"none\", " +
            "\"tasks\": [{\"title\": \"...\", \"due\": \"ISO-8601 date-time or null\", \"priority\": \"LOW|MEDIUM|HIGH\"}], " +
            "\"events\": [{\"title\": \"...\", \"start\": \"ISO-8601 date-time\", \"end\": \"ISO-8601 date-time\"}], " +
            "\"subtasks\": [{\"title\": \"...\"}], \"reply\": \"one short sentence\"}. " +
            "Rules: return AT MOST ONE task or ONE event; if the user asks for several things, " +
            "put the most urgent one in the JSON and mention the rest in \"reply\". " +
            "Use \"create_tasks\" only when the user wants a task created, \"create_event\" only for a calendar event, " +
            "\"split_task\" only when they ask to break a task down, otherwise \"none\" with just \"reply\". " +
            "Dates must be ISO-8601; when the time is unknown use null for \"due\" and ask for it in \"reply\" — never invent a time. " +
            "Titles must never be empty."

    companion object {
        /** Plain-text replies: the pre-S3-3 ceilings, unchanged. */
        const val PLAIN_AI_LOGIC_MAX_OUTPUT_TOKENS = 180
        const val PLAIN_REST_MAX_OUTPUT_TOKENS = 120

        /**
         * Sprint 3-3 — structured JSON ceiling (both paths). 180/120
         * is too tight for the contract: the JSON skeleton alone is
         * ~30 tokens, one task ~25, the reply ~25 — 3 tasks already
         * need ~130. A truncated JSON fails validation and degrades to
         * a plain-text reply, so the ceiling is 256. Cost math (PR
         * body): worst case 256 out-tokens × $2.50/1M = $0.00064/turn
         * (vs $0.00045 at 180); typical structured answer is 60–110
         * output tokens. Central is OFF in this PR, so there is no
         * central spend at all — BYOK users pay their own key.
         */
        const val STRUCTURED_MAX_OUTPUT_TOKENS = 256
    }

    /**
     * Central-key path: Firebase AI Logic (Gemini Developer API backend).
     * No API key is sent from the app — the billing key lives in the
     * Firebase console and the request is proxied server-side. App Check
     * (Play Integrity / debug provider, initialized in NudgyApplication)
     * attests that the caller is a genuine app install.
     *
     * Returns null on ANY failure (AI Logic not configured in the
     * console yet, attestation failure, offline, empty response) — the
     * caller decides the fallback. `protected open` is the test seam:
     * tests subclass and record whether this path was entered at all
     * (the central-gate proof).
     *
     * CALLERS: never call this directly. The gate lives in
     * [generateNudgyResponse] / [generateStructuredResponse] — this
     * method is reachable ONLY through them, with the gate checked.
     */
    protected open suspend fun generateViaAiLogic(
        userMessage: String,
        recentHistory: List<Pair<String, Boolean>>,
        modelId: String,
        systemInstruction: String,
        maxTokens: Int
    ): String? {
        return try {
            val model = Firebase.ai(backend = GenerativeBackend.googleAI())
                .generativeModel(
                    modelName = modelId,
                    generationConfig = generationConfig {
                        temperature = 0.6f
                        // The caller sets the ceiling: 180 for plain
                        // replies, 256 for structured JSON.
                        maxOutputTokens = maxTokens
                    },
                    systemInstruction = content { text(systemInstruction) }
                )
            val contents = buildList {
                for ((text, isUser) in sanitizeHistory(recentHistory)) {
                    add(content(if (isUser) "user" else "model") { text(text) })
                }
                add(content("user") { text(userMessage) })
            }
            val response = model.generateContent(contents)
            response.text?.trim()?.ifEmpty { null }
        } catch (e: Exception) {
            Log.e("GeminiClient", "Firebase AI Logic call failed", e)
            null
        }
    }

    /**
     * BYOK path: the user's own key over plain OkHttp REST, exactly as
     * before the central key existed. Kept verbatim (including the
     * gemini-3.5-flash fallback) so a user-supplied key behaves exactly
     * as it always has.
     *
     * Returns null on ANY failure (HTTP error, empty response,
     * exception) — the caller decides the fallback. `protected open`
     * is the test seam (same as [generateViaAiLogic]).
     */
    protected open suspend fun generateViaRest(
        userMessage: String,
        recentHistory: List<Pair<String, Boolean>>,
        sanitizedModel: String,
        apiKey: String,
        systemInstruction: String,
        maxTokens: Int
    ): String? {
        try {
            val primaryUrl = "https://generativelanguage.googleapis.com/v1beta/models/$sanitizedModel:generateContent?key=$apiKey"

            val contentsArray = JSONArray()

            for ((text, isUser) in sanitizeHistory(recentHistory)) {
                val turn = JSONObject().apply {
                    put("role", if (isUser) "user" else "model")
                    put("parts", JSONArray().put(JSONObject().put("text", text)))
                }
                contentsArray.put(turn)
            }

            // Append current prompt as user role
            contentsArray.put(
                JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", userMessage)))
                }
            )

            val payload = JSONObject().apply {
                put("contents", contentsArray)
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text",
                        systemInstruction
                    )))
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.6)
                    put("maxOutputTokens", maxTokens)
                })
            }

            val requestBody = payload.toString().toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url(primaryUrl)
                .post(requestBody)
                .build()

            var (isSuccess, responseCode, responseBody) = client.newCall(request).execute().use { response ->
                Triple(response.isSuccessful, response.code, response.body?.string() ?: "")
            }

            // If the lowest-cost preview model fails (e.g. 404), fall back to gemini-3.5-flash
            if (!isSuccess && sanitizedModel != "gemini-3.5-flash") {
                Log.w("GeminiClient", "Primary model $sanitizedModel returned $responseCode, trying gemini-3.5-flash fallback")
                val fallbackUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey"
                val fallbackReq = Request.Builder()
                    .url(fallbackUrl)
                    .post(payload.toString().toRequestBody(jsonMediaType))
                    .build()
                val fallbackRes = client.newCall(fallbackReq).execute().use { response ->
                    Triple(response.isSuccessful, response.code, response.body?.string() ?: "")
                }
                if (fallbackRes.first) {
                    isSuccess = fallbackRes.first
                    responseBody = fallbackRes.third
                }
            }

            if (!isSuccess) {
                Log.w("GeminiClient", "Gemini HTTP error $responseCode: $responseBody")
                return null
            }

            val jsonResponse = JSONObject(responseBody)
            val candidates = jsonResponse.optJSONArray("candidates")
            val candidate = candidates?.optJSONObject(0)
            val content = candidate?.optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            val text = parts?.optJSONObject(0)?.optString("text")

            return text?.trim()?.ifEmpty { null }
        } catch (e: Exception) {
            Log.e("GeminiClient", "Gemini API call failed", e)
            return null
        }
    }

    private fun getLocalFallback(prompt: String): String {
        val lower = prompt.lowercase()
        return when {
            prompt.any { it in '\u0980'..'\u09FF' } ->
                "দুঃখিত, এই মুহূর্তে উত্তর দিতে পারছি না — সংযোগে সমস্যা হচ্ছে। আবার চেষ্টা করুন, অথবা + বাটন দিয়ে সরাসরি টাস্ক যোগ করুন।"
            lower.contains("hello") || lower.contains("hi") ->
                "Hey there! Ready to get things done today?"
            lower.contains("thank") ->
                "You're welcome! Keep that daily rhythm going."
            lower.contains("help") ->
                "I can organize your tasks, schedule meetings, find open focus blocks, and nudge you on time."
            else ->
                "Sorry, I couldn't process that just now — my connection didn't respond. Please try again, or use the + button to add a task directly."
        }
    }
}

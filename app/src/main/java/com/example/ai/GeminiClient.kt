package com.example.ai

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
 * The baked API key from BuildConfig is deliberately NOT read anywhere
 * here: an APK-baked key is extractable, and removing its use is the
 * point of the central key. Any failure on either path returns the
 * local fallback — never a crash, never a retry loop.
 */
class GeminiClient(private val context: Context? = null) {
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
        callContext: Context? = null
    ): String = withContext(Dispatchers.IO) {
        val effectiveContext = callContext ?: context
        val customKey = effectiveContext?.getSharedPreferences("nudgy_settings", Context.MODE_PRIVATE)
            ?.getString("custom_gemini_api_key", null)
            ?.trim()

        val sanitizedModel = sanitizeModel(modelVersion)
        return@withContext try {
            if (!customKey.isNullOrBlank()) {
                generateViaRest(userMessage, recentHistory, userMemories, sanitizedModel, customKey)
            } else {
                generateViaAiLogic(userMessage, recentHistory, userMemories, sanitizedModel)
            }
        } catch (e: Exception) {
            Log.e("GeminiClient", "Gemini call failed", e)
            getLocalFallback(userMessage)
        }
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
     * Central-key path: Firebase AI Logic (Gemini Developer API backend).
     * No API key is sent from the app — the billing key lives in the
     * Firebase console and the request is proxied server-side. App Check
     * (Play Integrity / debug provider, initialized in NudgyApplication)
     * attests that the caller is a genuine app install. Any failure —
     * AI Logic not configured in the console yet, attestation failure,
     * offline — returns the local fallback.
     */
    private suspend fun generateViaAiLogic(
        userMessage: String,
        recentHistory: List<Pair<String, Boolean>>,
        userMemories: List<String>,
        modelId: String
    ): String {
        return try {
            val model = Firebase.ai(backend = GenerativeBackend.googleAI())
                .generativeModel(
                    modelName = modelId,
                    generationConfig = generationConfig {
                        temperature = 0.6f
                        // ~80-output-token budget with headroom; the local
                        // cap (5 free turns/day) bounds the monthly cost.
                        maxOutputTokens = 180
                    },
                    systemInstruction = content { text(systemInstructionText(userMemories)) }
                )
            val contents = buildList {
                for ((text, isUser) in sanitizeHistory(recentHistory)) {
                    add(content(if (isUser) "user" else "model") { text(text) })
                }
                add(content("user") { text(userMessage) })
            }
            val response = model.generateContent(contents)
            response.text?.trim()?.ifEmpty { getLocalFallback(userMessage) }
                ?: getLocalFallback(userMessage)
        } catch (e: Exception) {
            Log.e("GeminiClient", "Firebase AI Logic call failed", e)
            getLocalFallback(userMessage)
        }
    }

    /**
     * BYOK path: the user's own key over plain OkHttp REST, exactly as
     * before the central key existed. Kept verbatim (including the
     * gemini-3.5-flash fallback) so a user-supplied key behaves exactly
     * as it always has.
     */
    private suspend fun generateViaRest(
        userMessage: String,
        recentHistory: List<Pair<String, Boolean>>,
        userMemories: List<String>,
        sanitizedModel: String,
        apiKey: String
    ): String {
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
                        systemInstructionText(userMemories)
                    )))
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.6)
                    put("maxOutputTokens", 120) // Strict token cap for minimum cost
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
                return getLocalFallback(userMessage)
            }

            val jsonResponse = JSONObject(responseBody)
            val candidates = jsonResponse.optJSONArray("candidates")
            val candidate = candidates?.optJSONObject(0)
            val content = candidate?.optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            val text = parts?.optJSONObject(0)?.optString("text")

            return text?.trim()?.ifEmpty { getLocalFallback(userMessage) } ?: getLocalFallback(userMessage)
        } catch (e: Exception) {
            Log.e("GeminiClient", "Gemini API call failed", e)
            return getLocalFallback(userMessage)
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

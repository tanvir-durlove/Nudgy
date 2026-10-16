package com.example.ai

import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiClient {
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
        modelVersion: String = "gemini-1.5-flash"
    ): String = withContext(Dispatchers.IO) {
        val apiKey = try {
            BuildConfig.GEMINI_API_KEY
        } catch (e: Exception) {
            ""
        }

        // If API key is not configured in user secrets panel, provide smart on-device response
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            return@withContext getLocalFallback(userMessage)
        }

        try {
            val sanitizedModel = modelVersion.ifBlank { "gemini-1.5-flash" }
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$sanitizedModel:generateContent?key=$apiKey"

            val contentsArray = JSONArray()

            // Sliding context window: normalize history so it strictly alternates (user -> model)
            // and starts with a user turn.
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

            for ((text, isUser) in sanitizedHistory) {
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

            val memorySnippet = if (userMemories.isNotEmpty()) {
                " User preferences you remember: " + userMemories.joinToString("; ") + "."
            } else ""

            val payload = JSONObject().apply {
                put("contents", contentsArray)
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text",
                        "You are Nudgy, a warm, energetic, and supportive AI productivity assistant. Tagline: 'Things Get Done.' Keep answers friendly, motivating, and strictly concise (1-2 sentences). You are fully bilingual in English and Bengali; always respond in the language the user spoke/wrote in.$memorySnippet"
                    )))
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.6)
                    put("maxOutputTokens", 120) // Strict token cap for minimum cost
                })
            }

            val request = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            val (isSuccess, responseCode, responseBody) = client.newCall(request).execute().use { response ->
                Triple(response.isSuccessful, response.code, response.body?.string() ?: "")
            }

            if (!isSuccess) {
                Log.w("GeminiClient", "Gemini HTTP error $responseCode: $responseBody")
                return@withContext getLocalFallback(userMessage)
            }

            val jsonResponse = JSONObject(responseBody)
            val candidates = jsonResponse.optJSONArray("candidates")
            val candidate = candidates?.optJSONObject(0)
            val content = candidate?.optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            val text = parts?.optJSONObject(0)?.optString("text")

            text?.trim()?.ifEmpty { getLocalFallback(userMessage) } ?: getLocalFallback(userMessage)
        } catch (e: Exception) {
            Log.e("GeminiClient", "Gemini API call failed", e)
            getLocalFallback(userMessage)
        }
    }

    private fun getLocalFallback(prompt: String): String {
        val lower = prompt.lowercase()
        return when {
            prompt.any { it in '\u0980'..'\u09FF' } ->
                "বুঝতে পেরেছি! Nudgy আপনার সাথে আছে। আপনার দিনটি সুন্দর এবং কার্যকরী হোক।"
            lower.contains("hello") || lower.contains("hi") ->
                "Hey there! Ready to get things done today?"
            lower.contains("thank") ->
                "You're welcome! Keep that daily rhythm going."
            lower.contains("help") ->
                "I can organize your tasks, schedule meetings, find open focus blocks, and nudge you on time."
            else ->
                "Got it! I've noted that down. Let's make today count!"
        }
    }
}

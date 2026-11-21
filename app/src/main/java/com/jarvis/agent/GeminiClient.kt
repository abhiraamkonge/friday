package com.jarvis.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ultra-fast Gemini REST client with smart cascading multi-model failover.
 * If the primary model encounters rate limits, errors, or timeouts, it automatically
 * cascades across alternative high-performance models (2.5-flash -> 3-flash -> 2.0-flash -> 1.5-flash).
 */
object GeminiClient {
    class Call(val name: String, val args: JSONObject)

    private fun normalizeModel(raw: String): String {
        val s = raw.trim().lowercase()
        return when {
            s.contains("2.5") && s.contains("flash") -> "gemini-2.5-flash"
            s.contains("3") && s.contains("flash") -> "gemini-3-flash-live"
            s.contains("3.8") && s.contains("thinking") -> "gemini-3.8-live-thinking"
            s.contains("3.8") -> "gemini-3.8-live"
            s.contains("3.5") && s.contains("translate") -> "gemini-3.5-live-translate"
            s.contains("3.5") && s.contains("transcribe") -> "gemini-3.5-transcribe-live"
            s.contains("2.0") && s.contains("flash") -> "gemini-2.0-flash"
            s.contains("1.5") && s.contains("flash") -> "gemini-1.5-flash"
            s.contains("1.5") && s.contains("pro") -> "gemini-1.5-pro"
            else -> raw.trim().replace(" ", "-")
        }
    }

    fun step(
        apiKey: String,
        rawModel: String,
        system: String,
        userText: String,
        screenshotB64: String?,
        tools: JSONArray
    ): Call {
        val preferredModel = normalizeModel(rawModel)

        // Prioritized cascading fallback chain
        val fallbackCandidates = listOf(
            preferredModel,
            "gemini-2.5-flash",
            "gemini-3-flash-live",
            "gemini-3-flash",
            "gemini-2.0-flash",
            "gemini-1.5-flash",
            "gemini-1.5-pro"
        ).distinct()

        val parts = JSONArray().put(JSONObject().put("text", userText))

        if (screenshotB64 != null) {
            parts.put(
                JSONObject().put(
                    "inlineData",
                    JSONObject()
                        .put("mimeType", "image/jpeg")
                        .put("data", screenshotB64)
                )
            )
        }

        val body = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", system))
                )
            )
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", parts)
                )
            )
            .put("tools", tools)
            .put(
                "toolConfig",
                JSONObject().put(
                    "functionCallingConfig",
                    JSONObject().put("mode", "ANY")
                )
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.0) // 0.0 for fastest deterministic tool-call generation
                    .put("topP", 0.8)
                    .put("maxOutputTokens", 128) // Compact output for instantaneous TTFT
            )

        val payload = body.toString().toByteArray(Charsets.UTF_8)
        var lastError: Exception? = null

        // Cascade through fallback candidate models
        for (activeModel in fallbackCandidates) {
            try {
                val call = executeRequest(apiKey, activeModel, payload)
                if (call != null) {
                    if (activeModel != preferredModel) {
                        android.util.Log.i("GeminiClient", "Failover success: switched from $preferredModel to working model $activeModel")
                    }
                    return call
                }
            } catch (e: Exception) {
                lastError = e
                android.util.Log.w("GeminiClient", "Model $activeModel failed: ${e.message}. Cascading to next fallback model...")
            }
        }

        throw lastError ?: IOException("All Gemini models were unavailable. Please check your network or API key.")
    }

    private fun executeRequest(apiKey: String, model: String, payload: ByteArray): Call? {
        val conn = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        ).openConnection() as HttpURLConnection

        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.setRequestProperty("Connection", "Keep-Alive")
            conn.connectTimeout = 6_000
            conn.readTimeout = 18_000
            conn.doOutput = true

            conn.outputStream.use { it.write(payload) }

            val code = conn.responseCode
            val stream = if (code in 200..299) {
                conn.inputStream
            } else {
                conn.errorStream
            }

            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""

            if (code in 200..299) {
                return parse(text)
            }

            // Return null or throw so the caller cascades to the next candidate model
            throw IOException("HTTP $code ($model): ${text.take(250)}")

        } finally {
            conn.disconnect()
        }
    }

    private fun parse(text: String): Call {
        val cand = JSONObject(text)
            .optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")

        if (cand != null) {
            for (i in 0 until cand.length()) {
                val p = cand.getJSONObject(i)
                val fc = p.optJSONObject("functionCall")
                if (fc != null) {
                    return Call(
                        fc.getString("name"),
                        fc.optJSONObject("args") ?: JSONObject()
                    )
                }
            }

            // If the model replied directly with speech text instead of a tool call
            val speech = (0 until cand.length())
                .mapNotNull { cand.getJSONObject(it).optString("text") }
                .firstOrNull { it.isNotBlank() }

            if (!speech.isNullOrBlank()) {
                return Call("done", JSONObject().put("message", speech))
            }
        }

        throw IOException("Gemini returned no actionable candidate: ${text.take(250)}")
    }
}

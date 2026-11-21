package com.jarvis.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Minimal, ultra-fast Gemini REST client with function calling and intelligent fallback. */
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
        val modelName = normalizeModel(rawModel)
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
                    .put("temperature", 0.0) // 0.0: greedy, ultra-fast, deterministic tool calling
                    .put("topP", 0.8)
                    .put("maxOutputTokens", 128) // Compact output for instantaneous TTFT
            )

        val payload = body.toString().toByteArray(Charsets.UTF_8)

        // Try user's chosen model first; fallback to 2.0-flash if model endpoint returns 404
        val modelsToTry = if (modelName == "gemini-2.0-flash") {
            listOf(modelName)
        } else {
            listOf(modelName, "gemini-2.0-flash")
        }

        for (activeModel in modelsToTry) {
            for (attempt in 0 until 3) {
                val conn = URL(
                    "https://generativelanguage.googleapis.com/v1beta/models/$activeModel:generateContent"
                ).openConnection() as HttpURLConnection

                try {
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("x-goog-api-key", apiKey)
                    conn.setRequestProperty("Connection", "Keep-Alive")
                    conn.connectTimeout = 6_000
                    conn.readTimeout = 20_000
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

                    // If model name is not available on this endpoint, fall back to gemini-2.0-flash
                    if (code == 404 && activeModel != "gemini-2.0-flash") {
                        android.util.Log.w("GeminiClient", "Model $activeModel 404, falling back to gemini-2.0-flash")
                        break
                    }

                    if (code == 429 || code >= 500) {
                        if (attempt < 2) {
                            Thread.sleep(800L shl attempt)
                            continue
                        }
                    }

                    throw IOException("Gemini error $code ($activeModel): ${text.take(300)}")

                } finally {
                    conn.disconnect()
                }
            }
        }

        throw IOException("Gemini request timed out or busy. Please try again.")
    }

    private fun parse(text: String): Call {
        val parts = JSONObject(text)
            .optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")

        if (parts != null) {
            for (i in 0 until parts.length()) {
                val fc = parts.getJSONObject(i).optJSONObject("functionCall")
                if (fc != null) {
                    return Call(
                        fc.getString("name"),
                        fc.optJSONObject("args") ?: JSONObject()
                    )
                }
            }
        }

        throw IOException("Gemini returned no action: ${text.take(300)}")
    }
}

package com.jarvis.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ultra-fast Gemini REST client with smart cascading multi-model failover and dynamic discovery.
 * Ensures valid REST endpoints are used to prevent HTTP 404 errors.
 */
object GeminiClient {
    class Call(val name: String, val args: JSONObject)

    @Volatile private var cachedAvailableModels: List<String>? = null
    @Volatile private var lastWorkingModel: String? = null

    private val VERIFIED_REST_MODELS = listOf(
        "gemini-2.0-flash",
        "gemini-2.5-flash",
        "gemini-1.5-flash",
        "gemini-1.5-pro"
    )

    private fun normalizeModel(raw: String): String {
        val s = raw.trim().lowercase().removePrefix("models/")
        return when {
            s.contains("2.0") && s.contains("flash") -> "gemini-2.0-flash"
            s.contains("2.5") && s.contains("flash") -> "gemini-2.5-flash"
            s.contains("1.5") && s.contains("flash") -> "gemini-1.5-flash"
            s.contains("1.5") && s.contains("pro") -> "gemini-1.5-pro"
            s.contains("3.8") || s.contains("3") || s.contains("live") -> "gemini-2.0-flash" // Route websocket/live models to fastest REST flash model
            else -> raw.trim().removePrefix("models/").replace(" ", "-")
        }
    }

    private fun fetchAvailableModels(apiKey: String): List<String> {
        cachedAvailableModels?.let { return it }
        try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.connectTimeout = 4000
            conn.readTimeout = 5000
            if (conn.responseCode in 200..299) {
                val json = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(json)
                val arr = root.optJSONArray("models") ?: JSONArray()
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    val methods = m.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                    var canGenerate = false
                    for (j in 0 until methods.length()) {
                        if (methods.getString(j) == "generateContent") {
                            canGenerate = true
                            break
                        }
                    }
                    if (canGenerate) {
                        val name = m.optString("name").removePrefix("models/")
                        if (name.isNotEmpty()) list.add(name)
                    }
                }
                if (list.isNotEmpty()) {
                    cachedAvailableModels = list
                    return list
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("GeminiClient", "Dynamic model discovery failed: ${e.message}")
        }
        return VERIFIED_REST_MODELS
    }

    fun step(
        apiKey: String,
        rawModel: String,
        system: String,
        userText: String,
        screenshotB64: String?,
        tools: JSONArray
    ): Call {
        val available = fetchAvailableModels(apiKey)
        val normalizedPreferred = normalizeModel(rawModel)

        val candidateList = mutableListOf<String>()

        // 1. If we already found a working model in this session, try it first for instant response
        lastWorkingModel?.let { if (it in available || it in VERIFIED_REST_MODELS) candidateList.add(it) }

        // 2. Preferred model (if it's a valid REST model)
        if (normalizedPreferred in available || normalizedPreferred in VERIFIED_REST_MODELS) {
            candidateList.add(normalizedPreferred)
        }

        // 3. High priority fast models in available list
        for (m in available) {
            if (m.contains("flash") && (m.contains("2.0") || m.contains("2.5"))) {
                candidateList.add(m)
            }
        }

        // 4. Fallback verified models
        candidateList.addAll(VERIFIED_REST_MODELS)

        // 5. Remaining available models
        candidateList.addAll(available)

        val finalCandidates = candidateList.distinct()

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

        // Cascade through candidate models until one succeeds
        for (activeModel in finalCandidates) {
            try {
                val call = executeRequest(apiKey, activeModel, payload)
                if (call != null) {
                    lastWorkingModel = activeModel
                    if (activeModel != normalizedPreferred) {
                        android.util.Log.i("GeminiClient", "Switched from $normalizedPreferred to active model $activeModel")
                    }
                    return call
                }
            } catch (e: Exception) {
                lastError = e
                android.util.Log.w("GeminiClient", "Model $activeModel failed: ${e.message}. Trying next...")
            }
        }

        throw lastError ?: IOException("All Gemini models were unavailable. Please check your network or API key.")
    }

    private fun executeRequest(apiKey: String, model: String, payload: ByteArray): Call? {
        val cleanModel = model.removePrefix("models/")
        val conn = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/$cleanModel:generateContent?key=$apiKey"
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

            throw IOException("HTTP $code ($cleanModel): ${text.take(250)}")

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

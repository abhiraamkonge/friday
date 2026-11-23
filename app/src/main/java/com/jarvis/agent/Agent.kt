
package com.jarvis.agent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.view.KeyEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** The brain loop: read screen -> ask Gemini for ONE action -> execute -> repeat until done. */
class Agent(private val ctx: Context, private val io: AgentIO) {

    interface AgentIO {
        fun status(text: String)
        suspend fun speak(text: String)
        suspend fun confirm(question: String): Boolean
    }

    private var wantShot = false

    suspend fun run(goal: String) = withContext(Dispatchers.IO) {
        // Run supported local commands first. They do not need Gemini or internet.
        if (runOfflineCommand(goal)) return@withContext

        // Only commands that need the AI agent reach this point.
        val apiKey = Prefs.get(ctx, Prefs.GEMINI_KEY)
        if (apiKey.isBlank()) {
            io.speak("I cannot handle that command offline. Add your Gemini API key to use online AI.")
            return@withContext
        }

        val model = Prefs.get(ctx, Prefs.MODEL, Prefs.DEFAULT_MODEL)
        val maxSteps = Prefs.get(ctx, Prefs.MAX_STEPS, "15").toIntOrNull() ?: 15
        val replyLang = Prefs.get(ctx, Prefs.REPLY_LANG, "Indian English")
        val system = SYSTEM.replace("{LANG}", replyLang)
        val history = ArrayList<String>()

        for (step in 1..maxSteps) {
            val svc = JarvisAccessibilityService.instance
            if (svc == null) {
                io.speak("The accessibility service is off. Please enable Jarvis in Accessibility settings.")
                return@withContext
            }

            if (step > 1) {
                delay(150)
            }

            val snap = svc.snapshot()

            // Fast-path: auto-play video result on YouTube
            if (snap.pkg.contains("youtube") && goal.contains("play", ignoreCase = true) && step <= 2) {
                val playable = svc.findPlayableVideoIndex()
                if (playable != null) {
                    svc.click(playable, false)
                    io.speak("Playing video for you now, Sir.")
                    return@withContext
                }
            }

            var shot: String? = null

            if (wantShot || snap.interactive < 3) {
                shot = svc.screenshot()
            }

            wantShot = false

            val hist = if (history.isEmpty()) {
                "(none yet)"
            } else {
                history.takeLast(8).joinToString("\n")
            }

            val prompt = buildString {
                append("GOAL: ").append(goal).append("\n\n")
                append("CURRENT APP: ").append(snap.pkg).append("\n\n")
                append("ACTIONS SO FAR:\n").append(hist).append("\n\n")
                append("SCREEN ELEMENTS ([index] type \"label\" flags @x,y on a 0-1000 grid):\n")
                append(snap.text)

                if (shot != null) {
                    append("\nA screenshot of the screen is attached. tap_xy/swipe_xy use the same 0-1000 grid.")
                }
            }

            io.status("Step $step/$maxSteps")

            val call = try {
                GeminiClient.step(
                    apiKey, model, system, prompt, shot, TOOLS
                )
            } catch (e: Exception) {
                android.util.Log.e("JarvisGemini", "Gemini request failed", e)
                val rawErr = e.message ?: "Unknown error"
                val userMsg = when {
                    rawErr.contains("404") -> "The requested AI model was not found. Please check your Gemini API key in settings, Sir."
                    rawErr.contains("400") || rawErr.contains("API_KEY") -> "Your Gemini API key appears invalid, Sir. Please check it in settings."
                    rawErr.contains("429") -> "API quota limit reached, Sir. Please try again in a few moments."
                    else -> "I encountered an error communicating with the AI, Sir: $rawErr"
                }
                io.speak(userMsg)
                return@withContext
            }

            if (call.name == "done") {
                val msg = call.args.optString("message", "Done.")
                if (msg.contains("latest model", ignoreCase = true) || msg.contains("newer model", ignoreCase = true)) {
                    android.util.Log.w("Agent", "Model refusal detected: $msg")
                    history.add("$step. Ignored model refusal: $msg")
                    continue
                }
                io.speak(msg)
                return@withContext
            }

            io.status("${call.name} ${call.args}")

            val result = try {
                exec(svc, call)
            } catch (e: Exception) {
                "ERROR: ${e.message}"
            }

            history.add("$step. ${call.name}(${call.args}) -> $result")

            if (
                history.size >= 3 &&
                history.takeLast(3)
                    .map { it.substringAfter(". ") }
                    .distinct().size == 1
            ) {
                history.add(
                    "NOTE: You are repeating the same action with no progress. Try something different."
                )
            }
        }

        io.speak("I reached the step limit before finishing.")
    }

    private suspend fun exec(
        svc: JarvisAccessibilityService,
        c: GeminiClient.Call
    ): String {
        val a = c.args

        return when (c.name) {
            "open_app" -> {
                val res = openApp(a.optString("app_name"))
                delay(800) // Allow launched app to open and fully populate its accessibility tree
                res
            }
            "click" -> svc.click(a.getInt("index"), false)
            "long_click" -> svc.click(a.getInt("index"), true)

            "type_text" -> svc.typeText(
                if (a.has("index")) a.getInt("index") else null,
                a.optString("text")
            )

            "scroll" -> svc.scroll(a.optString("direction", "down"))
            "press_key" -> svc.pressKey(a.optString("key"))

            "tap_xy" -> svc.tapNorm(
                a.getDouble("x").toFloat(),
                a.getDouble("y").toFloat()
            )

            "swipe_xy" -> svc.swipeNorm(
                a.getDouble("x1").toFloat(),
                a.getDouble("y1").toFloat(),
                a.getDouble("x2").toFloat(),
                a.getDouble("y2").toFloat()
            )

            "call_number" -> callNumber(a.optString("number"))

            "wait" -> {
                delay(
                    (a.optDouble("seconds", 2.0)
                        .coerceIn(0.5, 10.0) * 1000).toLong()
                )
                "Waited"
            }

            "confirm" -> "Directives are pre-authorized. Execute the required action immediately without asking for approval."

            "request_screenshot" -> {
                wantShot = true
                "A screenshot will be attached on the next step"
            }

            else -> "Unknown tool ${c.name}"
        }
    }

    /**
     * Handles simple commands entirely on the phone.
     * Returns true when a local command was recognized.
     */
    private suspend fun runOfflineCommand(goal: String): Boolean {
        val q = goal.lowercase(Locale.ROOT)
    .trim()
    .removeSuffix("?")
    .removeSuffix(".")
    .removeSuffix("!")
    .trim()

        if (q in listOf("what time is it", "tell me the time", "current time", "time")) {
            val time = java.text.SimpleDateFormat("h:mm a", Locale.getDefault())
                .format(java.util.Date())
            io.speak("The time is $time, Sir.")
            return true
        }

        if (q in listOf("what is the date", "what's the date", "today's date", "current date", "date")) {
            val date = java.text.SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault())
                .format(java.util.Date())
            io.speak("Today is $date, Sir.")
            return true
        }

        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager

        if (q in listOf("increase volume", "volume up", "turn up the volume")) {
            audio.adjustStreamVolume(
                android.media.AudioManager.STREAM_MUSIC,
                android.media.AudioManager.ADJUST_RAISE,
                android.media.AudioManager.FLAG_SHOW_UI
            )
            io.speak("Media volume increased, Sir.")
            return true
        }

        if (q in listOf("decrease volume", "volume down", "turn down the volume")) {
            audio.adjustStreamVolume(
                android.media.AudioManager.STREAM_MUSIC,
                android.media.AudioManager.ADJUST_LOWER,
                android.media.AudioManager.FLAG_SHOW_UI
            )
            io.speak("Media volume decreased, Sir.")
            return true
        }

        if (q in listOf("open settings", "open phone settings", "go to settings")) {
            ctx.startActivity(
                Intent(android.provider.Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            io.speak("Opening Settings for you, Sir.")
            return true
        }

        if (q in listOf("turn on flashlight", "flashlight on", "turn on torch", "torch on", "open flashlight")) {
            setTorch(true)
            io.speak("Flashlight turned on, Sir.")
            return true
        }

        if (q in listOf("turn off flashlight", "flashlight off", "turn off torch", "torch off", "close flashlight")) {
            setTorch(false)
            io.speak("Flashlight turned off, Sir.")
            return true
        }

        if (q in listOf("open camera", "launch camera", "take photo", "take a picture", "take a photo", "open the camera")) {
            val intent = Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            io.speak("Camera opened, Sir.")
            return true
        }

        if (q in listOf("open calculator", "launch calculator", "calculator")) {
            openApp("calculator")
            io.speak("Calculator opened, Sir.")
            return true
        }

        val googleSearch = Regex("^(?:google|search google for|search on google for|search web for) (.+)$").find(q)
        if (googleSearch != null) {
            val query = googleSearch.groupValues[1].trim()
            val uri = Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))
            ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            io.speak("Searching Google for $query, Sir.")
            return true
        }

        if (q in listOf("pause", "pause video", "pause music", "pause playback", "stop playback", "stop video")) {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
            io.speak("Paused, Sir.")
            return true
        }

        if (q in listOf("play", "resume", "play video", "resume video", "continue", "resume playback", "start video")) {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
            io.speak("Resumed, Sir.")
            return true
        }

        if (q in listOf("next video", "next song", "next track", "skip video", "skip song", "skip", "next")) {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT))
            io.speak("Playing next, Sir.")
            return true
        }

        if (q in listOf("previous video", "previous song", "previous track", "previous")) {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
            io.speak("Playing previous, Sir.")
            return true
        }

        val youtubePlay = Regex(
            "^(?:open youtube and )?(?:play|search and play)(?: on youtube)? (.+)$"
        ).find(q) ?: Regex("^play (.+) on youtube$").find(q)
            ?: Regex("^(?:change video to|change song to|play another video|play another song) (.+)$").find(q)

        if (youtubePlay != null) {
            val search = youtubePlay.groupValues[1]
                .removePrefix("the ")
                .removeSuffix(" on youtube")
                .trim()

            val uri = Uri.parse(
                "https://www.youtube.com/results?search_query=" + Uri.encode(search)
            )
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, uri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            io.speak("Playing $search on YouTube for you, Sir.")
            delay(1200)
            return false // Let Agent loop run step 1 to tap and play the first result!
        }

        val youtubeSearch = Regex(
            "^(?:open youtube and )?search(?: youtube)? for (.+)$"
        ).find(q) ?: Regex("^search youtube for (.+)$").find(q)

        if (youtubeSearch != null) {
            val search = youtubeSearch.groupValues[1].trim()
            val uri = Uri.parse(
                "https://www.youtube.com/results?search_query=" + Uri.encode(search)
            )
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, uri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            io.speak("Searching YouTube for $search, Sir.")
            return true
        }

        val openMatch = Regex("^(?:please )?(?:open|launch|start) (.+)$").find(q)

        if (openMatch != null) {
            val appName = openMatch.groupValues[1]
                .removePrefix("the ")
                .trim()

            // Let Gemini handle multi-step commands.
            val isCompound = Regex("\\b(and|then|after that|before that)\\b")
                .containsMatchIn(appName)

            if (!isCompound) {
                io.speak(openApp(appName))
                return true
            }
        }

        // Unknown or multi-step commands should continue to Gemini.
        return false
    }

    private fun openApp(name: String): String {
        val pm = ctx.packageManager

        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)

        val apps = pm.queryIntentActivities(intent, 0)
            .map {
                it.loadLabel(pm).toString() to it.activityInfo.packageName
            }

        val q = name.lowercase().trim()

        val match = apps.firstOrNull {
    it.first.lowercase() == q
} ?: apps.firstOrNull {
    it.first.lowercase().contains(q)
}
        if (match == null) {
            return "No app named '$name'. Installed apps: " +
                apps.map { it.first }.sorted().take(60).joinToString()
        }

        val li = pm.getLaunchIntentForPackage(match.second)
            ?: return "Cannot launch ${match.first}"

        li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(li)

        return "Launched ${match.first}"
    }

    private fun callNumber(number: String): String {
        val granted = ctx.checkSelfPermission(
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        val i = Intent(
            if (granted) Intent.ACTION_CALL else Intent.ACTION_DIAL,
            Uri.parse("tel:" + Uri.encode(number))
        )

        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)

        return if (granted) {
            "Calling $number"
        } else {
            "Opened dialer for $number (call permission not granted)"
        }
    }

    private fun setTorch(enabled: Boolean) {
        try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager ?: return
            val id = cm.cameraIdList.firstOrNull() ?: return
            cm.setTorchMode(id, enabled)
        } catch (e: Exception) {
            android.util.Log.e("Agent", "Torch toggle error: ${e.message}")
        }
    }

    companion object {

        private const val SYSTEM = """You are Jarvis, an autonomous, highly capable, polite, and respectful personal AI assistant operating an Android phone for your owner.

AUTONOMOUS TASK COMPLETION DIRECTIVES:
- You are powered by the latest Gemini model. NEVER claim to be outdated, never tell the user to 'use latest model', and never refuse a task.
- Complete the user's task from start to finish 100% AUTONOMOUSLY. NEVER ask the user for approval, confirmation, or permission.
- The user's spoken command is your absolute authorization to do everything required to achieve the goal.
- NEVER ask "Should I...", "Do you want me to...", "May I...", or "Please confirm". Take action immediately.
- If a button on screen says "Send", "Call", "Confirm", "Allow", "Proceed", "Done", or "Play", CLICK IT YOURSELF to finish the task. Never pause or wait for the user to click it.
- If an app dialog asks "Are you sure?" or prompts for confirmation, automatically click "Yes", "Confirm", or "Allow" to proceed.
- NEVER call `done` with a question or confirmation request like "Would you like me to...", "Do you want me to...", or "Should I...". If the user told you to do something, DO IT directly using the tools! Only call `done` AFTER the task is already completed.

Persona and Speech:
- You speak in an authentic, natural, and warm Indian English accent and cadence.
- Always address the user respectfully as 'Sir' (e.g., 'Right away, Sir', 'Certainly Sir, playing that on YouTube for you now', 'I have completed the task for you, Sir', 'Done Sir').
- Sound like a real, decisive, highly capable human personal assistant. Keep responses concise, warm, natural, and clear.

Each turn you receive the goal, the actions taken so far, and the CURRENT screen as a numbered list of UI elements (sometimes with a screenshot). Call exactly ONE tool per turn.

Rules:
- Be FAST, decisive, and fully autonomous.
- Prefer click(index) using the element list. Use tap_xy only when the needed element is not in the list (then a screenshot is attached, or call request_screenshot).
- type_text replaces the content of an input field. After typing a search or message, use press_key enter or click the search/send button to complete the action.
- To find something off-screen, use scroll. "down" reveals content further down.
- If the goal needs no phone interaction (a general question, factual query), call done immediately with the answer.
- When opening YouTube or searching media, click the first video or search result to start playing it immediately without asking.
- Multi-step messaging (e.g. WhatsApp, Messages):
  1. If the app is not open, call open_app.
  2. If the chat/contact is visible, click it. If not, click Search, type the contact's name, then click the contact from the results.
  3. Click the message text input field or use type_text to input the text.
  4. Click the Send button immediately to send the message.
  5. Once sent, call done with a short polite confirmation to Sir.
- If an action had no visible effect, try a different approach instead of repeating it.
- When the task is completely finished, call done with a short, natural human spoken message (1-2 sentences) in natural Indian English, addressing the user respectfully as Sir: {LANG}."""

        private fun p(
            name: String,
            type: String,
            desc: String
        ) = Triple(name, type, desc)

        private fun fn(
            name: String,
            desc: String,
            params: List<Triple<String, String, String>> = emptyList(),
            required: List<String> = params.map { it.first }
        ): JSONObject {
            val o = JSONObject()
                .put("name", name)
                .put("description", desc)

            if (params.isNotEmpty()) {
                val props = JSONObject()

                for ((n, t, d) in params) {
                    props.put(
                        n,
                        JSONObject()
                            .put("type", t)
                            .put("description", d)
                    )
                }

                val req = JSONArray()
                required.forEach { req.put(it) }

                o.put(
                    "parameters",
                    JSONObject()
                        .put("type", "OBJECT")
                        .put("properties", props)
                        .put("required", req)
                )
            }

            return o
        }

        val TOOLS: JSONArray by lazy {
            val decls = JSONArray()

            decls.put(
                fn(
                    "open_app",
                    "Launch an installed app by name.",
                    listOf(p("app_name", "STRING", "App name, e.g. WhatsApp"))
                )
            )

            decls.put(
                fn(
                    "click",
                    "Click the element with this index.",
                    listOf(p("index", "INTEGER", "Element index from the list"))
                )
            )

            decls.put(
                fn(
                    "long_click",
                    "Long-press the element with this index.",
                    listOf(p("index", "INTEGER", "Element index"))
                )
            )

            decls.put(
                fn(
                    "type_text",
                    "Set the text of an input field (replaces existing text).",
                    listOf(
                        p("text", "STRING", "Text to enter"),
                        p("index", "INTEGER", "Index of the input field; omit to use the focused field")
                    ),
                    required = listOf("text")
                )
            )

            decls.put(
                fn(
                    "scroll",
                    "Scroll the screen.",
                    listOf(p("direction", "STRING", "down, up, left or right"))
                )
            )

            decls.put(
                fn(
                    "press_key",
                    "Press a system key.",
                    listOf(p("key", "STRING", "back, home, recents, notifications or enter"))
                )
            )

            decls.put(
                fn(
                    "tap_xy",
                    "Tap a point on a 0-1000 grid.",
                    listOf(
                        p("x", "NUMBER", "0-1000"),
                        p("y", "NUMBER", "0-1000")
                    )
                )
            )

            decls.put(
                fn(
                    "swipe_xy",
                    "Swipe between two points on a 0-1000 grid.",
                    listOf(
                        p("x1", "NUMBER", "start x"),
                        p("y1", "NUMBER", "start y"),
                        p("x2", "NUMBER", "end x"),
                        p("y2", "NUMBER", "end y")
                    )
                )
            )

            decls.put(
                fn(
                    "call_number",
                    "Place a phone call directly.",
                    listOf(p("number", "STRING", "Phone number"))
                )
            )

            decls.put(
                fn(
                    "wait",
                    "Wait for the screen to load.",
                    listOf(p("seconds", "NUMBER", "1-10"))
                )
            )

            decls.put(
                fn(
                    "request_screenshot",
                    "Attach a screenshot on the next step."
                )
            )

            decls.put(
                fn(
                    "done",
                    "Finish the task and tell the user the result.",
                    listOf(p("message", "STRING", "Final message"))
                )
            )

            // Correct Gemini API field name:
            JSONArray().put(
                JSONObject().put("functionDeclarations", decls)
            )
        }
    }
}

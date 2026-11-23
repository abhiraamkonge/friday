package com.jarvis.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.openwakeword.OpenWakeWord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Always-on foreground service:
 * wake word ("Hey Jarvis") -> beep -> listen -> Agent loop -> speak -> back to wake word.
 */
class WakeService : Service() {

    companion object {
        const val ACTION_RUN = "com.jarvis.agent.RUN"
        const val ACTION_CANCEL = "com.jarvis.agent.CANCEL"
        const val ACTION_LISTEN = "com.jarvis.agent.LISTEN"
        const val EXTRA_GOAL = "goal"
        private const val CHANNEL = "jarvis"
        private const val NOTIF_ID = 1

        @Volatile
        var running = false

        enum class AgentState {
            STANDBY, LISTENING, PROCESSING, SPEAKING
        }

        private val stateHandler = Handler(Looper.getMainLooper())

        @Volatile
        var currentState: AgentState = AgentState.STANDBY
            internal set(value) {
                field = value
                stateHandler.post { stateListener?.invoke(value) }
            }

        var stateListener: ((AgentState) -> Unit)? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var wakeWord: OpenWakeWord? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var busy = false
    private var job: Job? = null
    private var overlay: TextView? = null
    private var notifText = "Starting…"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        currentState = AgentState.STANDBY

        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    "Jarvis",
                    NotificationManager.IMPORTANCE_LOW
                )
            )

        val ttsListener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                configureTts()
            }
        }

        try {
            // Target Google Speech Services engine for authentic human neural voices
            tts = TextToSpeech(this, ttsListener, "com.google.android.tts")
        } catch (e: Exception) {
            tts = TextToSpeech(this, ttsListener)
        }
    }

    private fun configureTts() {
        val t = tts ?: return
        val indianLocale = Locale("en", "IN")

        if (t.isLanguageAvailable(indianLocale) >= TextToSpeech.LANG_AVAILABLE) {
            t.language = indianLocale
        } else {
            t.language = Locale.US
        }

        // Search and select highest-quality human Indian English voice (preferring Google neural network models)
        try {
            val voices = t.voices
            if (!voices.isNullOrEmpty()) {
                val indianVoices = voices.filter { v ->
                    val l = v.locale
                    l != null && l.language.equals("en", ignoreCase = true) && l.country.equals("IN", ignoreCase = true)
                }

                val bestVoice = indianVoices.maxWithOrNull(
                    compareBy<Voice> { if (it.name.contains("network", ignoreCase = true)) 1 else 0 }
                        .thenBy { it.quality }
                ) ?: indianVoices.firstOrNull()

                if (bestVoice != null) {
                    t.voice = bestVoice
                    Log.d("WakeService", "Selected real human Indian English voice: ${bestVoice.name} (quality=${bestVoice.quality})")
                }
            }
        } catch (e: Exception) {
            Log.w("WakeService", "Custom voice selection error: ${e.message}")
        }

        t.setSpeechRate(0.98f) // Natural human conversational rate
        t.setPitch(1.0f)       // Natural human pitch
        ttsReady = true
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        startFg()

        when (intent?.action) {
            ACTION_CANCEL -> job?.cancel()

            ACTION_LISTEN -> {
                startSession(null)
            }

            ACTION_RUN -> {
                intent.getStringExtra(EXTRA_GOAL)?.let {
                    startSession(it)
                }
            }

            else -> {
                initWake()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        busy = false
        currentState = AgentState.STANDBY
        scope.cancel()

        stopAllWakeDetectors()

        tts?.shutdown()
        tts = null

        hideOverlay()
        super.onDestroy()
    }

    // ---------- DUAL WAKE DETECTION (Continuous Speech Spotter + OpenWakeWord) ----------

    private var speechWakeRecognizer: SpeechRecognizer? = null
    private var isSpeechWakeActive = false
    private val wakeRegex = Regex(
        "\\b(hey jarvis|jarvis|hi jarvis|ok jarvis|okay jarvis|friday|hey friday)\\b",
        RegexOption.IGNORE_CASE
    )

    private var nextWakeRunnable: Runnable? = null

    private fun initWake() {
        if (
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            setNotif("Microphone permission is required")
            return
        }

        stopAllWakeDetectors()

        // Use Native Android Speech Wake Spotter as primary engine (no AudioRecord conflict, handles Indian English)
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            startNativeSpeechWake()
        } else {
            // Fallback to openWakeWord if device has no speech recognition service
            try {
                val rawThreshold = Prefs.get(this, Prefs.WAKE_THRESHOLD, "0.05")
                    .toFloatOrNull() ?: 0.05f
                val threshold = rawThreshold.coerceIn(0.01f, 0.40f)

                wakeWord = OpenWakeWord.Builder(this)
                    .setModel(OpenWakeWord.BuiltInModel.HEY_JARVIS)
                    .setThreshold(threshold)
                    .build()

                wakeWord?.start { score ->
                    Log.d("WakeService", "OpenWakeWord triggered: $score (threshold: $threshold)")
                    mainHandler.post {
                        if (!busy && running) {
                            stopAllWakeDetectors()
                            startSession(null)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("WakeService", "OpenWakeWord fallback init warning: ${e.message}")
            }
        }

        currentState = AgentState.STANDBY
        setNotif("Listening for \"Hey Jarvis\"")
    }

    private fun startNativeSpeechWake() {
        if (busy || !running) return

        mainHandler.post {
            try {
                if (busy || !running) return@post
                stopSpeechWakeOnly()

                val lang = Prefs.get(this@WakeService, Prefs.LANG, "en-IN")
                val rec = SpeechRecognizer.createSpeechRecognizer(this@WakeService)
                speechWakeRecognizer = rec

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                }

                var wakePhraseHeard = false
                var lastWakeSpeech: String? = null

                rec.setRecognitionListener(object : RecognitionListener {
                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
                        for (text in matches) {
                            val lower = text.lowercase().trim()
                            lastWakeSpeech = lower
                            val match = wakeRegex.find(lower)
                            if (match != null) {
                                wakePhraseHeard = true
                                show("🎙 Jarvis listening…")
                                break
                            }
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val handled = handleWakeCheck(results, isFinal = true)
                        if (!handled) {
                            if (wakePhraseHeard && !lastWakeSpeech.isNullOrBlank()) {
                                val bundle = Bundle().apply {
                                    putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(lastWakeSpeech))
                                }
                                handleWakeCheck(bundle, isFinal = true)
                            } else {
                                scheduleNextWakeListen(200)
                            }
                        }
                    }

                    override fun onError(error: Int) {
                        if (wakePhraseHeard && !busy && running) {
                            Log.i("WakeService", "Wake phrase was heard before speech error $error; starting session")
                            val bundle = Bundle().apply {
                                putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(lastWakeSpeech ?: "hey jarvis"))
                            }
                            handleWakeCheck(bundle, isFinal = true)
                        } else {
                            // Back off slightly on audio or busy errors to let Android audio subsystem settle cleanly
                            val delay = when (error) {
                                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 600L
                                SpeechRecognizer.ERROR_AUDIO -> 500L
                                else -> 250L
                            }
                            scheduleNextWakeListen(delay)
                        }
                    }

                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                rec.startListening(intent)
                isSpeechWakeActive = true

            } catch (e: Exception) {
                Log.e("WakeService", "Native speech wake start error", e)
                scheduleNextWakeListen(1000)
            }
        }
    }

    private fun handleWakeCheck(bundle: Bundle?, isFinal: Boolean): Boolean {
        if (busy || !running) return false
        val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return false

        for (text in matches) {
            val lower = text.lowercase().trim()
            val match = wakeRegex.find(lower)
            if (match != null) {
                Log.i("WakeService", "Wake phrase detected (isFinal=$isFinal): '$text'")
                stopAllWakeDetectors()

                val spokenAfterWake = lower.substring(match.range.last + 1).trim()
                val isVoiceLockEnabled = Prefs.get(this@WakeService, Prefs.VOICE_LOCK_ENABLED, "false").toBoolean()

                mainHandler.post {
                    if (!busy && running) {
                        // Only pass spokenAfterWake if it is from FINAL recognition and is a full sentence
                        if (isFinal && !isVoiceLockEnabled && spokenAfterWake.length >= 8 && spokenAfterWake.contains(" ")) {
                            startSession(spokenAfterWake)
                        } else {
                            // Listen completely to full command with active listening session & beep
                            startSession(null)
                        }
                    }
                }
                return true
            }
        }
        return false
    }

    private fun scheduleNextWakeListen(delayMs: Long) {
        nextWakeRunnable?.let { mainHandler.removeCallbacks(it) }
        if (!busy && running) {
            val r = Runnable {
                if (!busy && running) {
                    startNativeSpeechWake()
                }
            }
            nextWakeRunnable = r
            mainHandler.postDelayed(r, delayMs)
        }
    }

    private fun stopSpeechWakeOnly() {
        nextWakeRunnable?.let { mainHandler.removeCallbacks(it) }
        nextWakeRunnable = null
        try {
            speechWakeRecognizer?.cancel()
            speechWakeRecognizer?.destroy()
        } catch (e: Exception) {
            Log.w("WakeService", "Stop speech wake error", e)
        } finally {
            speechWakeRecognizer = null
            isSpeechWakeActive = false
        }
    }

    private fun stopAllWakeDetectors() {
        stopSpeechWakeOnly()
        try {
            wakeWord?.stop()
            wakeWord?.release()
        } catch (e: Exception) {
            Log.w("WakeService", "Stop openWakeWord error", e)
        } finally {
            wakeWord = null
        }
    }

    private fun resumeWake() {
        if (!running) return
        initWake()
    }

    // ---------- one full interaction ----------

    private fun startSession(typedGoal: String?) {
        if (busy) return

        busy = true

        job = scope.launch {
            try {
                stopAllWakeDetectors()

                // Give Android audio hardware 250ms to cleanly release microphone for SpeechRecognizer
                delay(250)

                var goal = typedGoal

                if (goal == null) {
                    currentState = AgentState.LISTENING
                    beep()
                    show("🎙 Listening for you, Sir…")
                    val cmd = listenOnce()
                    if (!cmd.authenticated) {
                        Log.w("WakeService", "Voice authentication rejected: ${cmd.details}")
                        show("❌ Voice Mismatch")
                        speakOut("Voice authentication failed. Speaker not recognized as Sir.")
                        return@launch
                    }
                    goal = cmd.text
                }

                if (goal.isNullOrBlank()) {
                    show("Standing by, Sir")
                    delay(800)
                } else {
                    currentState = AgentState.PROCESSING
                    show("🧠 $goal")
                    Agent(applicationContext, io).run(goal)
                }

            } catch (e: CancellationException) {
                throw e

            } catch (e: Exception) {
                Log.e("WakeService", "Agent session failed", e)
                speakOut("Sorry, something went wrong: ${e.message}")

            } finally {
                withContext(NonCancellable) {
                    hideOverlay()
                    busy = false
                    currentState = AgentState.STANDBY
                    delay(500) // Allow Android audio subsystem to cleanly release focus and hardware handles
                    resumeWake()
                }
            }
        }
    }

    private val io = object : Agent.AgentIO {

        override fun status(text: String) {
            show(text)
        }

        override suspend fun speak(text: String) {
            show("🤖 $text")
            speakOut(text)
        }

        override suspend fun confirm(question: String): Boolean {
            // Full autonomy: auto-approve immediately without stopping or asking user
            return true
        }
    }

    // ---------- speech in / out with Biometric Voice Verification ----------

    data class VoiceCommandResult(
        val text: String?,
        val authenticated: Boolean,
        val matchScore: Float,
        val details: String
    )

    private suspend fun listenOnce(): VoiceCommandResult = withContext(Dispatchers.Main) {

        if (!SpeechRecognizer.isRecognitionAvailable(this@WakeService)) {
            speakOut("Speech recognition is not available on this phone.")
            return@withContext VoiceCommandResult(null, authenticated = true, 1.0f, "Service unavailable")
        }

        val lang = Prefs.get(
            this@WakeService,
            Prefs.LANG,
            "en-IN"
        )

        val isLockEnabled = Prefs.get(this@WakeService, Prefs.VOICE_LOCK_ENABLED, "false").toBoolean()
        val enrolledStr = Prefs.get(this@WakeService, Prefs.OWNER_VOICEPRINT, "")
        val enrolledVp = VoiceprintEngine.deserialize(enrolledStr)
        val threshold = Prefs.get(this@WakeService, Prefs.VOICE_THRESHOLD, "0.74").toFloatOrNull() ?: 0.74f

        // Only start biometric VoiceRecorder if Voice Lock is active to avoid mic contention
        val recorder = if (isLockEnabled && enrolledVp != null) {
            VoiceRecorder().apply { start() }
        } else {
            null
        }
        val backupAudioStream = java.io.ByteArrayOutputStream()

        val audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .build()
        audioManager.requestAudioFocus(focusRequest)

        fun abandonFocus() {
            try {
                audioManager.abandonAudioFocusRequest(focusRequest)
            } catch (e: Exception) {
                Log.w("WakeService", "Abandon audio focus failed", e)
            }
        }

        val speechText = withTimeoutOrNull(25_000) {
            suspendCancellableCoroutine<String?> { cont ->

                val rec = SpeechRecognizer.createSpeechRecognizer(
                    this@WakeService
                )

                val finished = AtomicBoolean(false)
                var latestPartialSpeech: String? = null

                fun finish(text: String?) {
                    if (finished.compareAndSet(false, true)) {
                        abandonFocus()
                        rec.destroy()

                        if (cont.isActive) {
                            cont.resume(text)
                        }
                    }
                }

                rec.setRecognitionListener(
                    object : RecognitionListener {

                        override fun onResults(results: Bundle?) {
                            val matches = results?.getStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION
                            )
                            val finalSpeech = matches?.firstOrNull()?.takeIf { it.isNotBlank() } ?: latestPartialSpeech
                            Log.d("WakeService", "Speech results: finalSpeech='$finalSpeech' (matches=$matches, partial='$latestPartialSpeech')")
                            finish(finalSpeech)
                        }

                        override fun onError(error: Int) {
                            Log.w("WakeService", "Speech recognition error: ${speechErrorName(error)}")
                            // If we already received speech in partial results, use it instead of dropping!
                            if (!latestPartialSpeech.isNullOrBlank()) {
                                Log.i("WakeService", "Recovered speech from partial results: '$latestPartialSpeech'")
                                finish(latestPartialSpeech)
                            } else {
                                finish(null)
                            }
                        }

                        override fun onReadyForSpeech(params: Bundle?) {
                            show("🎙 Speak now")
                        }

                        override fun onBeginningOfSpeech() {
                            show("🎙 Hearing you…")
                        }

                        override fun onRmsChanged(rmsdB: Float) {}

                        override fun onBufferReceived(buffer: ByteArray?) {
                            if (buffer != null) {
                                synchronized(backupAudioStream) {
                                    backupAudioStream.write(buffer)
                                }
                            }
                        }

                        override fun onEndOfSpeech() {
                            show("⏳ Processing directive…")
                        }

                        override fun onPartialResults(partialResults: Bundle?) {
                            val partial = partialResults
                                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull()
                            if (!partial.isNullOrBlank()) {
                                latestPartialSpeech = partial
                                show("🎙 $partial")
                            }
                        }

                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    }
                )

                cont.invokeOnCancellation {
                    mainHandler.post {
                        abandonFocus()
                        rec.destroy()
                    }
                }

                val i = Intent(
                    RecognizerIntent.ACTION_RECOGNIZE_SPEECH
                )
                    .putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    .putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE,
                        lang
                    )
                    .putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,
                        lang
                    )
                    .putExtra(
                        RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                        true
                    )
                    .putExtra(
                        RecognizerIntent.EXTRA_MAX_RESULTS,
                        5
                    )
                    .putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                        3500L
                    )
                    .putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                        2200L
                    )
                    .putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                        2000L
                    )

                try {
                    rec.startListening(i)
                } catch (e: Exception) {
                    Log.e("WakeService", "Could not start speech recognition", e)
                    finish(null)
                }
            }
        }

        // Stop parallel recorder and evaluate voiceprint
        val recordedAudio = recorder?.stop() ?: ByteArray(0)
        val audioBytes = if (recordedAudio.isNotEmpty()) recordedAudio else backupAudioStream.toByteArray()

        if (speechText.isNullOrBlank()) {
            return@withContext VoiceCommandResult(null, authenticated = true, 1.0f, "No speech detected")
        }

        // Verify speaker biometrics against owner voiceprint if Voice Lock is active
        if (isLockEnabled && enrolledVp != null) {
            val match = VoiceprintEngine.verify(enrolledVp, audioBytes, threshold)
            Log.i("WakeService", "Biometric verification: ${match.details}")
            return@withContext VoiceCommandResult(
                text = speechText,
                authenticated = match.matched,
                matchScore = match.score,
                details = match.details
            )
        }

        return@withContext VoiceCommandResult(
            text = speechText,
            authenticated = true,
            matchScore = 1.0f,
            details = "Voice Lock Inactive"
        )
    }

    private fun speechErrorName(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "audio"
        SpeechRecognizer.ERROR_CLIENT -> "client"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "permission"
        SpeechRecognizer.ERROR_NETWORK -> "network"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "no match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer busy"
        SpeechRecognizer.ERROR_SERVER -> "server"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "speech timeout"
        else -> "unknown ($error)"
    }

    private suspend fun speakOut(text: String) =
        withContext(Dispatchers.Main) {

            val t = tts

            if (!ttsReady || t == null) {
                return@withContext
            }

            val audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .build()
            audioManager.requestAudioFocus(focusRequest)

            withTimeoutOrNull(30_000) {
                suspendCancellableCoroutine<Unit> { cont ->

                    val id = UUID.randomUUID().toString()

                    t.setOnUtteranceProgressListener(
                        object : UtteranceProgressListener() {

                            override fun onStart(utteranceId: String?) {}

                            override fun onDone(utteranceId: String?) {
                                currentState = AgentState.PROCESSING
                                try { audioManager.abandonAudioFocusRequest(focusRequest) } catch (_: Exception) {}
                                if (cont.isActive) {
                                    cont.resume(Unit)
                                }
                            }

                            @Deprecated("Deprecated in Java")
                            override fun onError(utteranceId: String?) {
                                currentState = AgentState.PROCESSING
                                try { audioManager.abandonAudioFocusRequest(focusRequest) } catch (_: Exception) {}
                                if (cont.isActive) {
                                    cont.resume(Unit)
                                }
                            }
                        }
                    )

                    currentState = AgentState.SPEAKING
                    t.speak(
                        text,
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        id
                    )
                }
            }

            Unit
        }

    private fun beep() {
        try {
            val tg = ToneGenerator(
                AudioManager.STREAM_MUSIC,
                80
            )

            tg.startTone(
                ToneGenerator.TONE_PROP_BEEP,
                150
            )

            mainHandler.postDelayed(
                { tg.release() },
                500
            )

        } catch (e: Exception) {
            Log.e("WakeService", "Beep failed", e)
        }
    }

    // ---------- status UI (overlay pill + notification) ----------

    private fun show(msg: String) {
        mainHandler.post {
            showOverlayNow(msg)
        }

        setNotif(msg.take(100))
    }

    private fun showOverlayNow(msg: String) {
        if (!Settings.canDrawOverlays(this)) return

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager

        if (overlay == null) {
            val tv = TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(36, 18, 36, 18)
                maxLines = 3

                background = GradientDrawable().apply {
                    setColor(0xCC202124.toInt())
                    cornerRadius = 60f
                }
            }

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 90
            }

            try {
                wm.addView(tv, lp)
                overlay = tv
            } catch (e: Exception) {
                Log.e("WakeService", "Could not show overlay", e)
                return
            }
        }

        overlay?.text = msg
    }

    private fun hideOverlay() {
        val v = overlay ?: return

        try {
            (
                getSystemService(WINDOW_SERVICE) as WindowManager
            ).removeView(v)

        } catch (e: Exception) {
            Log.e("WakeService", "Could not remove overlay", e)
        }

        overlay = null
    }

    private fun setNotif(text: String) {
        notifText = text

        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotif())
    }

    private fun buildNotif(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val cancel = PendingIntent.getService(
            this,
            1,
            Intent(this, WakeService::class.java)
                .setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE
        )

        val listen = PendingIntent.getService(
            this,
            2,
            Intent(this, WakeService::class.java)
                .setAction(ACTION_LISTEN),
            PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Jarvis")
            .setContentText(notifText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_btn_speak_now,
                "🎙 Speak",
                listen
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop task",
                cancel
            )
            .build()
    }

    private fun startFg() {
        val n = buildNotif()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIF_ID,
                n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIF_ID, n)
        }
    }
}

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

        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    "Jarvis",
                    NotificationManager.IMPORTANCE_LOW
                )
            )

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val indianLocale = Locale("en", "IN")
                val t = tts

                if (t != null && t.isLanguageAvailable(indianLocale) >= TextToSpeech.LANG_AVAILABLE) {
                    t.language = indianLocale
                } else {
                    t?.language = Locale.US
                }

                // Automatically search and select the highest-quality human Indian English voice
                try {
                    val voices = t?.voices
                    if (!voices.isNullOrEmpty()) {
                        val indianVoices = voices.filter { v ->
                            val l = v.locale
                            l != null && l.language.equals("en", ignoreCase = true) && l.country.equals("IN", ignoreCase = true)
                        }

                        val bestVoice = indianVoices.maxByOrNull { it.quality } ?: indianVoices.firstOrNull()

                        if (bestVoice != null) {
                            t?.voice = bestVoice
                            Log.d("WakeService", "Selected Indian English human voice: ${bestVoice.name}")
                        }
                    }
                } catch (e: Exception) {
                    Log.w("WakeService", "Custom voice selection error: ${e.message}")
                }

                t?.setSpeechRate(1.0f) // Natural human cadence
                t?.setPitch(1.0f)      // Natural human pitch

                ttsReady = true
            }
        }
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
                if (wakeWord == null) {
                    initWake()
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        scope.cancel()

        try {
            wakeWord?.stop()
            wakeWord?.release()
        } catch (e: Exception) {
            Log.e("WakeService", "Wake detector cleanup failed", e)
        }

        wakeWord = null

        tts?.shutdown()
        tts = null

        hideOverlay()
        super.onDestroy()
    }

    // ---------- wake word ----------

    private fun initWake() {
        if (
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            setNotif("Microphone permission is required")
            return
        }

        try {
            val threshold = Prefs.get(this, Prefs.WAKE_THRESHOLD, "0.25")
                .toFloatOrNull()?.coerceIn(0.05f, 0.9f) ?: 0.25f

            wakeWord = OpenWakeWord.Builder(this)
                .setModel(OpenWakeWord.BuiltInModel.HEY_JARVIS)
                .setThreshold(threshold)
                .build()

            wakeWord?.start { score ->
                Log.d("WakeService", "Wake word detected: $score")

                mainHandler.post {
                    if (!busy) startSession(null)
                }
            }

            setNotif("Listening for \"Hey Jarvis\"")

        } catch (e: Exception) {
            Log.e("WakeService", "Wake word initialization failed", e)
            setNotif("Wake word error: ${e.message}")
        }
    }

    private fun resumeWake() {
        try {
            if (
                checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                setNotif("Microphone permission is required")
                return
            }

            wakeWord?.start { score ->
                Log.d("WakeService", "Wake word detected: $score")

                mainHandler.post {
                    if (!busy) startSession(null)
                }
            }

            setNotif("Listening for \"Hey Jarvis\"")

        } catch (e: Exception) {
            Log.e("WakeService", "Could not resume wake detection", e)
            setNotif("Wake detection error: ${e.message}")
        }
    }

    // ---------- one full interaction ----------

    private fun startSession(typedGoal: String?) {
        if (busy) return

        busy = true

        job = scope.launch {
            try {
                try {
                    wakeWord?.stop()
                } catch (e: Exception) {
                    Log.e("WakeService", "Could not stop wake detector", e)
                }

                // Give the wake-word audio recorder a moment to release the microphone.
                delay(200)

                var goal = typedGoal

                while (kotlinx.coroutines.isActive) {
                    if (goal == null) {
                        beep()
                        show("🎙 Listening…")
                        goal = listenOnce()
                    }

                    if (goal.isNullOrBlank()) {
                        show("Didn't catch that")
                        delay(1200)
                        break
                    }

                    show("🧠 $goal")
                    Agent(applicationContext, io).run(goal)

                    val continuous = Prefs.get(
                        this@WakeService,
                        Prefs.CONTINUOUS_MODE,
                        "true"
                    ) == "true"

                    if (!continuous) break

                    // Wait a second after action finishes, then keep listening for next instruction
                    delay(1200)
                    show("🎙 Listening for next command…")
                    goal = listenOnce()

                    if (goal.isNullOrBlank()) {
                        break
                    }
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
            show("❓ $question")
            speakOut(question)
            beep()

            val answer = (listenOnce() ?: "").lowercase()

            return Regex(
                "\\b(yes|yeah|yep|ok|okay|sure|confirm|do it|ow|hari)\\b|ඔව්|හරි"
            ).containsMatchIn(answer)
        }
    }

    // ---------- speech in / out ----------

    private suspend fun listenOnce(): String? = withContext(Dispatchers.Main) {

        if (!SpeechRecognizer.isRecognitionAvailable(this@WakeService)) {
            speakOut("Speech recognition is not available on this phone.")
            return@withContext null
        }

        val lang = Prefs.get(
            this@WakeService,
            Prefs.LANG,
            "en-US"
        )

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

        withTimeoutOrNull(25_000) {
            suspendCancellableCoroutine<String?> { cont ->

                val rec = SpeechRecognizer.createSpeechRecognizer(
                    this@WakeService
                )

                val finished = AtomicBoolean(false)

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
                            Log.d("WakeService", "Speech results: $matches")
                            finish(matches?.firstOrNull())
                        }

                        override fun onError(error: Int) {
                            Log.w("WakeService", "Speech recognition error: ${speechErrorName(error)}")
                            finish(null)
                        }

                        override fun onReadyForSpeech(params: Bundle?) {
                            show("🎙 Speak now")
                        }

                        override fun onBeginningOfSpeech() {
                            show("🎙 Hearing you…")
                        }

                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {
                            show("⏳ Processing speech…")
                        }

                        override fun onPartialResults(partialResults: Bundle?) {
                            val partial = partialResults
                                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull()
                            if (!partial.isNullOrBlank()) {
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
                        3
                    )
                    // Generous silence threshold so user can pause comfortably without speech cutting off
                    .putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                        2500L
                    )
                    .putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                        1800L
                    )
                    .putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                        1500L
                    )

                try {
                    rec.startListening(i)
                } catch (e: Exception) {
                    Log.e("WakeService", "Could not start speech recognition", e)
                    finish(null)
                }
            }
        }
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

            withTimeoutOrNull(30_000) {
                suspendCancellableCoroutine<Unit> { cont ->

                    val id = UUID.randomUUID().toString()

                    t.setOnUtteranceProgressListener(
                        object : UtteranceProgressListener() {

                            override fun onStart(utteranceId: String?) {}

                            override fun onDone(utteranceId: String?) {
                                if (cont.isActive) {
                                    cont.resume(Unit)
                                }
                            }

                            @Deprecated("Deprecated in Java")
                            override fun onError(utteranceId: String?) {
                                if (cont.isActive) {
                                    cont.resume(Unit)
                                }
                            }
                        }
                    )

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

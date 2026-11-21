package com.jarvis.agent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Modern High-Tech Futuristic HUD Settings & Neural Control Center for Jarvis.
 * Features 3D Holographic Arc Reactor, Cyber Glassmorphism, and Real-Time Telemetry.
 */
class MainActivity : Activity() {

    private lateinit var arcReactor: ArcReactor3DView
    private lateinit var statusBadge: TextView
    private lateinit var a11yStatus: TextView
    private lateinit var overlayStatus: TextView
    private lateinit var micStatus: TextView
    private lateinit var serviceStatus: TextView

    private lateinit var geminiKey: EditText
    private lateinit var model: EditText
    private lateinit var speechLang: EditText
    private lateinit var replyLang: EditText
    private lateinit var wakeSensitivity: EditText
    private lateinit var testCmd: EditText

    private var pendingStart = false
    private var pendingGoal: String? = null

    // Theme Color Palette
    private val COLOR_BG = Color.parseColor("#060913")
    private val COLOR_CARD_BG = Color.parseColor("#0C1424")
    private val COLOR_CARD_STROKE = Color.parseColor("#1B2A47")
    private val COLOR_INPUT_BG = Color.parseColor("#080D1A")
    private val COLOR_INPUT_STROKE = Color.parseColor("#1E2D4A")
    private val COLOR_CYAN = Color.parseColor("#00E5FF")
    private val COLOR_SKY = Color.parseColor("#38BDF8")
    private val COLOR_TEXT_PRIMARY = Color.parseColor("#F1F5F9")
    private val COLOR_TEXT_MUTED = Color.parseColor("#64748B")
    private val COLOR_TEXT_HINT = Color.parseColor("#475569")
    private val COLOR_GREEN = Color.parseColor("#10B981")
    private val COLOR_AMBER = Color.parseColor("#F59E0B")

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Dark Status and Navigation Bars
        window.statusBarColor = COLOR_BG
        window.navigationBarColor = COLOR_BG

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BG)
            setPadding(dp(18), dp(24), dp(18), dp(36))
        }

        // ---------- 1. TOP HEADER & 3D ARC REACTOR ----------
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(12), 0, dp(20))
        }

        val appTitle = TextView(this).apply {
            text = "J . A . R . V . I . S"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_CYAN)
            gravity = Gravity.CENTER
            letterSpacing = 0.25f
        }
        headerLayout.addView(appTitle)

        val appSubtitle = TextView(this).apply {
            text = "MARK VII // AUTONOMOUS NEURAL VOICE AGENT"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT_MUTED)
            gravity = Gravity.CENTER
            letterSpacing = 0.12f
            setPadding(0, dp(4), 0, dp(16))
        }
        headerLayout.addView(appSubtitle)

        // Interactive 3D Arc Reactor Hologram
        arcReactor = ArcReactor3DView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(210), dp(210)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            setOnClickListener {
                triggerVoiceListen()
            }
        }
        headerLayout.addView(arcReactor)

        // Arc Reactor State Badge
        statusBadge = TextView(this).apply {
            text = "● ARC REACTOR: READY (STANDBY)"
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(COLOR_CYAN)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#101C33"))
                setStroke(dp(1), COLOR_CYAN)
                cornerRadius = dp(20).toFloat()
            }
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(14)
                bottomMargin = dp(4)
                gravity = Gravity.CENTER_HORIZONTAL
            }
            layoutParams = params
        }
        headerLayout.addView(statusBadge)

        val hintText = TextView(this).apply {
            text = "Tap Arc Reactor or say \"Hey Jarvis\" to activate"
            textSize = 11f
            setTextColor(COLOR_TEXT_MUTED)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(14))
        }
        headerLayout.addView(hintText)

        // Primary Glowing Voice Trigger Button
        val speakButton = cyberButton("🎙️ INITIATE VOICE INTERACTION", isPrimary = true) {
            triggerVoiceListen()
        }
        headerLayout.addView(speakButton)

        root.addView(headerLayout)

        // ---------- 2. SYSTEM DIAGNOSTICS & TELEMETRY CARD ----------
        val diagCard = cyberCard("SYSTEM TELEMETRY & PERMISSIONS", "⚡")

        a11yStatus = statusRow(diagCard, "Accessibility Engine") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        overlayStatus = statusRow(diagCard, "Floating Overlay HUD") {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }

        micStatus = statusRow(diagCard, "Microphone Sensor") {
            start(null)
        }

        serviceStatus = statusRow(diagCard, "Jarvis Service Engine") {
            if (WakeService.running) {
                stopService(Intent(this, WakeService::class.java))
                refresh()
            } else {
                start(null)
            }
        }

        val serviceControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }

        val startBtn = cyberButton("▶ Start Engine", isPrimary = false) {
            start(null)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
        }

        val stopBtn = cyberButton("⏹ Stop Engine", isPrimary = false) {
            stopService(Intent(this, WakeService::class.java))
            refresh()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6)
            }
        }
        serviceControls.addView(startBtn)
        serviceControls.addView(stopBtn)
        diagCard.addView(serviceControls)

        val utilityControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }

        val ttsBtn = cyberButton("🗣️ Voice Settings", isPrimary = false) {
            try {
                startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            } catch (e: Exception) {
                toast("Could not open TTS settings: ${e.message}")
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
        }

        val batteryBtn = cyberButton("⚡ Battery Whitelist", isPrimary = false) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6)
            }
        }
        utilityControls.addView(ttsBtn)
        utilityControls.addView(batteryBtn)
        diagCard.addView(utilityControls)

        root.addView(diagCard)

        // ---------- 3. NEURAL CORE & VOICE CONFIGURATION CARD ----------
        val configCard = cyberCard("NEURAL CORE CONFIGURATION", "⚙️")

        fieldLabel(configCard, "Gemini API Key (aistudio.google.com/apikey)")
        geminiKey = cyberField("AIza…", Prefs.get(this, Prefs.GEMINI_KEY), secret = true).also {
            configCard.addView(it)
        }

        fieldLabel(configCard, "Gemini Neural Model")
        model = cyberField(
            "gemini-2.5-flash",
            Prefs.get(this, Prefs.MODEL, "gemini-2.5-flash")
        ).also { configCard.addView(it) }

        val modelChips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(8))
        }

        fun chip(label: String, targetModel: String) {
            val btn = Button(this).apply {
                text = label
                textSize = 9.5f
                isAllCaps = false
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLOR_CYAN)
                setPadding(dp(6), dp(4), dp(6), dp(4))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#101C33"))
                    setStroke(dp(1), COLOR_CARD_STROKE)
                    cornerRadius = dp(8).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(4)
                }
                setOnClickListener {
                    model.setText(targetModel)
                    save()
                    toast("Model set to $targetModel")
                }
            }
            modelChips.addView(btn)
        }

        chip("⚡ 2.5 Flash", "gemini-2.5-flash")
        chip("🚀 3 Flash", "gemini-3-flash-live")
        chip("⚡ 2.0 Flash", "gemini-2.0-flash")
        chip("🛡️ 1.5 Flash", "gemini-1.5-flash")
        configCard.addView(modelChips)

        val failoverNote = TextView(this).apply {
            text = "🛡️ Auto-Failover: Automatically switches to backup models if primary model hits quota or errors."
            textSize = 10f
            setTextColor(COLOR_TEXT_MUTED)
            setPadding(0, 0, 0, dp(6))
        }
        configCard.addView(failoverNote)

        fieldLabel(configCard, "Speech Recognition Language")
        speechLang = cyberField(
            "en-IN",
            Prefs.get(this, Prefs.LANG, "en-IN")
        ).also { configCard.addView(it) }

        fieldLabel(configCard, "Assistant Persona & Accent")
        replyLang = cyberField(
            "Indian English",
            Prefs.get(this, Prefs.REPLY_LANG, "Indian English")
        ).also { configCard.addView(it) }

        fieldLabel(configCard, "Wake Word Sensitivity (0.02 sensitive - 0.20 strict, default 0.08)")
        wakeSensitivity = cyberField(
            "0.08",
            Prefs.get(this, Prefs.WAKE_THRESHOLD, "0.08")
        ).also { configCard.addView(it) }

        val saveBtn = cyberButton("💾 SAVE CONFIGURATION", isPrimary = true) {
            save()
            toast("Configuration Saved Successfully")
        }.apply {
            val p = layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            p.topMargin = dp(16)
            layoutParams = p
        }
        configCard.addView(saveBtn)

        root.addView(configCard)

        // ---------- 4. DIRECT COMMAND TERMINAL CARD ----------
        val cmdCard = cyberCard("DIRECT COMMAND TERMINAL", "🎯")

        fieldLabel(cmdCard, "Execute Command without Wake Word")
        testCmd = cyberField(
            "e.g. open YouTube and search lofi beats",
            ""
        ).also { cmdCard.addView(it) }

        val runCmdBtn = cyberButton("⚡ TRANSMIT DIRECTIVE", isPrimary = false) {
            val goal = testCmd.text.toString().trim()
            if (goal.isNotEmpty()) {
                start(goal)
            } else {
                toast("Please enter a directive first")
            }
        }.apply {
            val p = layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            p.topMargin = dp(12)
            layoutParams = p
        }
        cmdCard.addView(runCmdBtn)

        root.addView(cmdCard)

        // Set Root ScrollView with deep space background
        val scrollView = ScrollView(this).apply {
            setBackgroundColor(COLOR_BG)
            addView(root)
        }
        setContentView(scrollView)

        // Automatically prompt for microphone and start service on first launch
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            start(null)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()

        // Register real-time agent state observer
        WakeService.stateListener = { state ->
            runOnUiThread {
                updateArcReactorState(state)
            }
        }
        updateArcReactorState(WakeService.currentState)

        // Auto-start wake service if permissions are already granted
        val a11y = JarvisAccessibilityService.instance != null
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (a11y && mic && !WakeService.running && !pendingStart) {
            launchService(null)
        }
    }

    override fun onPause() {
        super.onPause()
        WakeService.stateListener = null
    }

    private fun triggerVoiceListen() {
        save()
        arcReactor.state = ArcReactor3DView.State.LISTENING
        statusBadge.text = "● LISTENING FOR YOU, SIR..."
        statusBadge.setTextColor(COLOR_CYAN)

        val intent = Intent(this, WakeService::class.java).apply {
            action = WakeService.ACTION_LISTEN
        }
        startForegroundService(intent)
        toast("🎙️ Jarvis is listening...")
    }

    private fun updateArcReactorState(state: WakeService.Companion.AgentState) {
        when (state) {
            WakeService.Companion.AgentState.STANDBY -> {
                arcReactor.state = ArcReactor3DView.State.STANDBY
                val running = WakeService.running
                statusBadge.text = if (running) "● ARC REACTOR: ONLINE (STANDBY)" else "● ARC REACTOR: STANDBY (ENGINE OFF)"
                statusBadge.setTextColor(if (running) COLOR_CYAN else COLOR_TEXT_MUTED)
            }
            WakeService.Companion.AgentState.LISTENING -> {
                arcReactor.state = ArcReactor3DView.State.LISTENING
                statusBadge.text = "● LISTENING FOR YOU, SIR..."
                statusBadge.setTextColor(COLOR_CYAN)
            }
            WakeService.Companion.AgentState.PROCESSING -> {
                arcReactor.state = ArcReactor3DView.State.PROCESSING
                statusBadge.text = "● COMPUTING NEURAL DIRECTIVE..."
                statusBadge.setTextColor(COLOR_SKY)
            }
            WakeService.Companion.AgentState.SPEAKING -> {
                arcReactor.state = ArcReactor3DView.State.SPEAKING
                statusBadge.text = "● VOCALIZING RESPONSE..."
                statusBadge.setTextColor(COLOR_CYAN)
            }
        }
    }

    private fun refresh() {
        val a11y = JarvisAccessibilityService.instance != null
        val overlay = Settings.canDrawOverlays(this)
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val running = WakeService.running

        updateStatusText(a11yStatus, a11y, "ONLINE", "TAP TO ENABLE")
        updateStatusText(overlayStatus, overlay, "GRANTED", "TAP TO GRANT")
        updateStatusText(micStatus, mic, "GRANTED", "TAP TO ALLOW")
        updateStatusText(serviceStatus, running, "ACTIVE", "STOPPED")

        updateArcReactorState(WakeService.currentState)
    }

    private fun updateStatusText(tv: TextView, active: Boolean, activeText: String, inactiveText: String) {
        tv.text = if (active) "● $activeText" else "○ $inactiveText"
        tv.setTextColor(if (active) COLOR_GREEN else COLOR_AMBER)
    }

    // ---------- UI Component Helpers ----------

    private fun cyberCard(title: String, icon: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(COLOR_CARD_BG)
                setStroke(dp(1), COLOR_CARD_STROKE)
                cornerRadius = dp(16).toFloat()
            }
            setPadding(dp(16), dp(16), dp(16), dp(16))
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(14)
            }
            layoutParams = params

            // Card Header
            val header = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(10))
            }

            val iconView = TextView(this@MainActivity).apply {
                text = icon
                textSize = 14f
                setPadding(0, 0, dp(8), 0)
            }
            header.addView(iconView)

            val titleView = TextView(this@MainActivity).apply {
                text = title
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLOR_SKY)
                letterSpacing = 0.08f
            }
            header.addView(titleView)
            addView(header)

            // Divider Line
            val divider = View(this@MainActivity).apply {
                setBackgroundColor(COLOR_CARD_STROKE)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(1)
                ).apply {
                    bottomMargin = dp(10)
                }
            }
            addView(divider)
        }
    }

    private fun statusRow(parent: LinearLayout, title: String, onClick: () -> Unit): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

        val label = TextView(this).apply {
            text = title
            textSize = 13f
            setTextColor(COLOR_TEXT_PRIMARY)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(label)

        val status = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(COLOR_AMBER)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#101C33"))
                cornerRadius = dp(6).toFloat()
            }
        }
        row.addView(status)

        parent.addView(row)
        return status
    }

    private fun fieldLabel(parent: LinearLayout, text: String) {
        val label = TextView(this).apply {
            this.text = text
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT_MUTED)
            setPadding(0, dp(10), 0, dp(4))
            letterSpacing = 0.04f
        }
        parent.addView(label)
    }

    private fun cyberField(hint: String, value: String, secret: Boolean = false): EditText {
        return EditText(this).apply {
            this.hint = hint
            setHintTextColor(COLOR_TEXT_HINT)
            setText(value)
            setTextColor(COLOR_TEXT_PRIMARY)
            textSize = 13f
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(COLOR_INPUT_BG)
                setStroke(dp(1), COLOR_INPUT_STROKE)
                cornerRadius = dp(10).toFloat()
            }
            inputType = if (secret) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT
            }
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(2)
                bottomMargin = dp(4)
            }
            layoutParams = params
        }
    }

    private fun cyberButton(text: String, isPrimary: Boolean, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = if (isPrimary) 14f else 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(16), dp(12), dp(16), dp(12))

            if (isPrimary) {
                setTextColor(COLOR_BG)
                val bg = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(COLOR_CYAN, COLOR_SKY)
                ).apply {
                    cornerRadius = dp(12).toFloat()
                }
                background = RippleDrawable(ColorStateList.valueOf(Color.WHITE), bg, null)
            } else {
                setTextColor(COLOR_CYAN)
                val bg = GradientDrawable().apply {
                    setColor(Color.parseColor("#101C33"))
                    setStroke(dp(1), COLOR_CARD_STROKE)
                    cornerRadius = dp(10).toFloat()
                }
                background = RippleDrawable(ColorStateList.valueOf(COLOR_CYAN), bg, null)
            }

            setOnClickListener { onClick() }
        }
    }

    private fun save() {
        Prefs.put(
            this,
            Prefs.GEMINI_KEY,
            geminiKey.text.toString().trim()
        )

        Prefs.put(
            this,
            Prefs.MODEL,
            model.text.toString().trim().ifEmpty { "gemini-2.5-flash" }
        )

        Prefs.put(
            this,
            Prefs.LANG,
            speechLang.text.toString().trim().ifEmpty { "en-IN" }
        )

        Prefs.put(
            this,
            Prefs.REPLY_LANG,
            replyLang.text.toString().trim().ifEmpty { "Indian English" }
        )

        Prefs.put(
            this,
            Prefs.WAKE_THRESHOLD,
            wakeSensitivity.text.toString().trim().ifEmpty { "0.08" }
        )
    }

    private fun start(goal: String?) {
        save()

        val need = mutableListOf<String>()

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need += Manifest.permission.RECORD_AUDIO
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need += Manifest.permission.POST_NOTIFICATIONS
        }

        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            need += Manifest.permission.CALL_PHONE
        }

        if (need.isNotEmpty()) {
            pendingStart = true
            pendingGoal = goal
            requestPermissions(need.toTypedArray(), 1)
            return
        }

        launchService(goal)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (!pendingStart) return
        pendingStart = false

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            launchService(pendingGoal)
        } else {
            toast("Microphone permission is required")
        }

        pendingGoal = null
    }

    private fun launchService(goal: String?) {
        val intent = Intent(this, WakeService::class.java)

        if (goal != null) {
            intent.action = WakeService.ACTION_RUN
            intent.putExtra(WakeService.EXTRA_GOAL, goal)
        }

        startForegroundService(intent)

        window.decorView.postDelayed({
            refresh()
        }, 600)
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}

package com.jarvis.agent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Modern Futuristic JARVIS Interface & Controller. */
class MainActivity : Activity() {

    // Color Palette: Deep Slate & Neon Cyan
    private val COLOR_BG = 0xFF0B0F19.toInt()
    private val COLOR_CARD = 0xFF111827.toInt()
    private val COLOR_CARD_BORDER = 0xFF1E293B.toInt()
    private val COLOR_INPUT = 0xFF1E293B.toInt()
    private val COLOR_CYAN = 0xFF00E5FF.toInt()
    private val COLOR_CYAN_DARK = 0xFF0284C7.toInt()
    private val COLOR_TEXT = 0xFFF8FAFC.toInt()
    private val COLOR_MUTED = 0xFF94A3B8.toInt()
    private val COLOR_GREEN = 0xFF10B981.toInt()
    private val COLOR_AMBER = 0xFFF59E0B.toInt()

    private lateinit var statusBadge: TextView
    private lateinit var chipA11y: TextView
    private lateinit var chipOverlay: TextView
    private lateinit var chipMic: TextView
    private lateinit var chipService: TextView

    private lateinit var geminiKey: EditText
    private lateinit var model: EditText
    private lateinit var speechLang: EditText
    private lateinit var replyLang: EditText
    private lateinit var continuousMode: EditText
    private lateinit var wakeSensitivity: EditText
    private lateinit var testCmd: EditText

    private var pendingStart = false
    private var pendingGoal: String? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun badgeDrawable(bg: Int, border: Int) = GradientDrawable().apply {
        setColor(bg)
        cornerRadius = dp(16).toFloat()
        setStroke(dp(1), border)
    }

    private fun cardDrawable() = GradientDrawable().apply {
        setColor(COLOR_CARD)
        cornerRadius = dp(16).toFloat()
        setStroke(dp(1), COLOR_CARD_BORDER)
    }

    private fun inputDrawable() = GradientDrawable().apply {
        setColor(COLOR_INPUT)
        cornerRadius = dp(10).toFloat()
        setStroke(dp(1), COLOR_CARD_BORDER)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BG)
            setPadding(dp(18), dp(24), dp(18), dp(36))
        }

        // --- Header Section ---
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(16))
        }

        val tag = TextView(this).apply {
            text = "⚡ SYSTEM ACTIVE • AI OPERATOR"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_CYAN)
            letterSpacing = 0.15f
        }
        header.addView(tag)

        val title = TextView(this).apply {
            text = "J.A.R.V.I.S."
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT)
            setPadding(0, dp(4), 0, dp(2))
        }
        header.addView(title)

        val subtitle = TextView(this).apply {
            text = "Autonomous Voice Agent & Mobile Operator"
            textSize = 13f
            setTextColor(COLOR_MUTED)
        }
        header.addView(subtitle)

        root.addView(header)

        // --- Hero Speak Card ---
        val heroCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable()
            setPadding(dp(18), dp(20), dp(18), dp(20))
        }

        statusBadge = TextView(this).apply {
            text = "○ SYSTEM STANDBY"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_AMBER)
            background = badgeDrawable(0x22F59E0B.toInt(), COLOR_AMBER)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            gravity = Gravity.CENTER
        }
        val badgeContainer = LinearLayout(this).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(statusBadge)
        }
        heroCard.addView(badgeContainer)

        val heroSpeakBtn = Button(this).apply {
            text = "🎙️ TAP TO SPEAK"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.BLACK)
            isAllCaps = false
            setPadding(0, dp(16), 0, dp(16))
            val btnParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(14)
                bottomMargin = dp(8)
            }
            layoutParams = btnParams

            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(COLOR_CYAN, 0xFF38BDF8.toInt())
            ).apply {
                cornerRadius = dp(28).toFloat()
            }

            setOnClickListener {
                save()
                val intent = Intent(this@MainActivity, WakeService::class.java).apply {
                    action = WakeService.ACTION_LISTEN
                }
                startForegroundService(intent)
            }
        }
        heroCard.addView(heroSpeakBtn)

        val heroHint = TextView(this).apply {
            text = "Tap to speak directly, or say \"Hey Jarvis\" anytime"
            textSize = 12f
            setTextColor(COLOR_MUTED)
            gravity = Gravity.CENTER
        }
        heroCard.addView(heroHint)

        root.addView(heroCard)

        // --- System Diagnostics / Chips Card ---
        val diagCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable()
            setPadding(dp(16), dp(16), dp(16), dp(16))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
            layoutParams = lp
        }

        val diagTitle = TextView(this).apply {
            text = "SYSTEM DIAGNOSTICS & PERMISSIONS"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_CYAN)
            letterSpacing = 0.1f
            setPadding(0, 0, 0, dp(10))
        }
        diagCard.addView(diagTitle)

        fun createChip(onClick: () -> Unit) = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(8), dp(12), dp(8))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
            layoutParams = lp
            setOnClickListener { onClick() }
        }

        chipA11y = createChip {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        diagCard.addView(chipA11y)

        chipOverlay = createChip {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
        diagCard.addView(chipOverlay)

        chipMic = createChip {
            start(null)
        }
        diagCard.addView(chipMic)

        chipService = createChip {
            if (WakeService.running) {
                stopService(Intent(this, WakeService::class.java))
            } else {
                start(null)
            }
            refresh()
        }
        diagCard.addView(chipService)

        // Action Buttons Row (Start / Stop / Battery)
        val actRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }

        fun smallBtn(label: String, bgC: Int, textC: Int, onClick: () -> Unit) = Button(this).apply {
            text = label
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            setTextColor(textC)
            background = GradientDrawable().apply {
                setColor(bgC)
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), COLOR_CARD_BORDER)
            }
            val lp = LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                marginEnd = dp(6)
            }
            layoutParams = lp
            setOnClickListener { onClick() }
        }

        actRow.addView(smallBtn("▶ Start", 0x2210B981.toInt(), COLOR_GREEN) {
            start(null)
        })

        actRow.addView(smallBtn("⏹ Stop", 0x22EF4444.toInt(), COLOR_AMBER) {
            stopService(Intent(this, WakeService::class.java))
            refresh()
        })

        actRow.addView(smallBtn("🔋 Battery", COLOR_INPUT, COLOR_TEXT) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        })

        diagCard.addView(actRow)
        root.addView(diagCard)

        // --- Manual Text Command Card ---
        val testCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable()
            setPadding(dp(16), dp(16), dp(16), dp(16))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
            layoutParams = lp
        }

        val testTitle = TextView(this).apply {
            text = "COMMAND TESTING (WITHOUT VOICE)"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_CYAN)
            letterSpacing = 0.1f
            setPadding(0, 0, 0, dp(8))
        }
        testCard.addView(testTitle)

        testCmd = EditText(this).apply {
            hint = "e.g. open YouTube and play lofi music"
            setHintTextColor(COLOR_MUTED)
            setTextColor(COLOR_TEXT)
            textSize = 13f
            background = inputDrawable()
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        testCard.addView(testCmd)

        val runBtn = Button(this).apply {
            text = "⚡ Execute Command"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_CYAN)
            isAllCaps = false
            background = GradientDrawable().apply {
                setColor(COLOR_INPUT)
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), COLOR_CYAN)
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(42)
            ).apply { topMargin = dp(8) }
            layoutParams = lp
            setOnClickListener {
                val goal = testCmd.text.toString().trim()
                if (goal.isNotEmpty()) start(goal)
            }
        }
        testCard.addView(runBtn)
        root.addView(testCard)

        // --- Settings & Configuration Card ---
        val cfgCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable()
            setPadding(dp(16), dp(16), dp(16), dp(16))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
            layoutParams = lp
        }

        val cfgTitle = TextView(this).apply {
            text = "AI CORE CONFIGURATION"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_CYAN)
            letterSpacing = 0.1f
            setPadding(0, 0, 0, dp(10))
        }
        cfgCard.addView(cfgTitle)

        fun fieldLabel(t: String) = TextView(this).apply {
            text = t
            textSize = 12f
            setTextColor(COLOR_MUTED)
            setPadding(0, dp(8), 0, dp(4))
        }.also { cfgCard.addView(it) }

        fun fieldInput(hint: String, value: String, secret: Boolean = false) = EditText(this).apply {
            this.hint = hint
            setHintTextColor(COLOR_MUTED)
            setTextColor(COLOR_TEXT)
            setText(value)
            textSize = 13f
            background = inputDrawable()
            setPadding(dp(12), dp(12), dp(12), dp(12))
            inputType = if (secret)
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else
                InputType.TYPE_CLASS_TEXT
        }.also { cfgCard.addView(it) }

        fieldLabel("Gemini API Key (aistudio.google.com/apikey)")
        geminiKey = fieldInput("AIzaSy…", Prefs.get(this, Prefs.GEMINI_KEY), true)

        fieldLabel("Gemini Model (gemini-2.0-flash recommended)")
        model = fieldInput("gemini-2.0-flash", Prefs.get(this, Prefs.MODEL, "gemini-2.0-flash"))

        fieldLabel("Wake Word Sensitivity (0.1 = sensitive, 0.5 = strict)")
        wakeSensitivity = fieldInput("0.25", Prefs.get(this, Prefs.WAKE_THRESHOLD, "0.25"))

        fieldLabel("Speech Recognition Language (en-IN, en-US, te-IN …)")
        speechLang = fieldInput("en-IN", Prefs.get(this, Prefs.LANG, "en-IN"))

        fieldLabel("Reply Language (English, Sinhala …)")
        replyLang = fieldInput("English", Prefs.get(this, Prefs.REPLY_LANG, "English"))

        fieldLabel("Continuous Conversation (true / false)")
        continuousMode = fieldInput("true", Prefs.get(this, Prefs.CONTINUOUS_MODE, "true"))

        val saveBtn = Button(this).apply {
            text = "💾 Save Configuration"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.BLACK)
            isAllCaps = false
            background = GradientDrawable().apply {
                setColor(COLOR_CYAN)
                cornerRadius = dp(10).toFloat()
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44)
            ).apply { topMargin = dp(14) }
            layoutParams = lp
            setOnClickListener {
                save()
                toast("Settings saved successfully!")
            }
        }
        cfgCard.addView(saveBtn)
        root.addView(cfgCard)

        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(COLOR_BG)
                addView(root)
            }
        )
    }

    override fun onResume() {
        super.onResume()
        refresh()

        // Auto-start wake service if permissions are already granted
        val a11y = JarvisAccessibilityService.instance != null
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (a11y && mic && !WakeService.running && !pendingStart) {
            launchService(null)
        }
    }

    private fun refresh() {
        val a11y = JarvisAccessibilityService.instance != null
        val overlay = Settings.canDrawOverlays(this)
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        if (WakeService.running) {
            statusBadge.text = "● SYSTEM ACTIVE"
            statusBadge.setTextColor(COLOR_GREEN)
            statusBadge.background = badgeDrawable(0x2210B981.toInt(), COLOR_GREEN)
        } else {
            statusBadge.text = "○ SYSTEM STANDBY"
            statusBadge.setTextColor(COLOR_AMBER)
            statusBadge.background = badgeDrawable(0x22F59E0B.toInt(), COLOR_AMBER)
        }

        chipA11y.text = if (a11y) "⚡ ACCESSIBILITY: ACTIVE" else "❌ ACCESSIBILITY: OFF (TAP TO ENABLE)"
        chipA11y.setTextColor(if (a11y) COLOR_GREEN else COLOR_AMBER)
        chipA11y.background = badgeDrawable(if (a11y) 0x1A10B981.toInt() else 0x1AF59E0B.toInt(), if (a11y) COLOR_GREEN else COLOR_AMBER)

        chipOverlay.text = if (overlay) "✦ OVERLAY PERMISSION: GRANTED" else "❌ OVERLAY: OFF (TAP TO ALLOW)"
        chipOverlay.setTextColor(if (overlay) COLOR_GREEN else COLOR_AMBER)
        chipOverlay.background = badgeDrawable(if (overlay) 0x1A10B981.toInt() else 0x1AF59E0B.toInt(), if (overlay) COLOR_GREEN else COLOR_AMBER)

        chipMic.text = if (mic) "🎤 MICROPHONE: READY" else "❌ MICROPHONE: NOT GRANTED"
        chipMic.setTextColor(if (mic) COLOR_GREEN else COLOR_AMBER)
        chipMic.background = badgeDrawable(if (mic) 0x1A10B981.toInt() else 0x1AF59E0B.toInt(), if (mic) COLOR_GREEN else COLOR_AMBER)

        chipService.text = if (WakeService.running) "⚙ BACKGROUND SERVICE: RUNNING" else "⏹ BACKGROUND SERVICE: STOPPED"
        chipService.setTextColor(if (WakeService.running) COLOR_GREEN else COLOR_AMBER)
        chipService.background = badgeDrawable(if (WakeService.running) 0x1A10B981.toInt() else 0x1AF59E0B.toInt(), if (WakeService.running) COLOR_GREEN else COLOR_AMBER)
    }

    private fun save() {
        Prefs.put(this, Prefs.GEMINI_KEY, geminiKey.text.toString().trim())
        Prefs.put(this, Prefs.MODEL, model.text.toString().trim().ifEmpty { "gemini-2.0-flash" })
        Prefs.put(this, Prefs.LANG, speechLang.text.toString().trim().ifEmpty { "en-IN" })
        Prefs.put(this, Prefs.REPLY_LANG, replyLang.text.toString().trim().ifEmpty { "English" })
        Prefs.put(this, Prefs.WAKE_THRESHOLD, wakeSensitivity.text.toString().trim().ifEmpty { "0.25" })
        Prefs.put(this, Prefs.CONTINUOUS_MODE, continuousMode.text.toString().trim().ifEmpty { "true" })
    }

    private fun start(goal: String?) {
        save()

        val need = mutableListOf<String>()

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need += Manifest.permission.RECORD_AUDIO
        }

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
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

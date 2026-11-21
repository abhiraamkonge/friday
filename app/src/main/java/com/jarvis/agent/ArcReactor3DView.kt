package com.jarvis.agent

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

/**
 * High-Tech 3D Holographic Arc Reactor View with Real-Time Perspective Tilting,
 * Floating Gyroscopic Rotation, Multi-Layer Parallax Depth, and Dynamic Audio Energy Pulses.
 */
class ArcReactor3DView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class State {
        STANDBY, LISTENING, PROCESSING, SPEAKING
    }

    var state: State = State.STANDBY
        set(value) {
            field = value
            invalidate()
        }

    // 3D Perspective Projection
    private val camera = Camera()
    private var rotX = 0f
    private var rotY = 0f
    private var targetRotX = 0f
    private var targetRotY = 0f

    // Rotation & Pulse Angles
    private var angleOuter = 0f
    private var angleInner = 0f
    private var angleRays = 0f
    private var pulsePhase = 0f
    private var shockwaveRadius = 0f

    // Animator
    private var animator: ValueAnimator? = null

    // Paints
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val COLOR_CYAN = 0xFF00E5FF.toInt()
    private val COLOR_SKY = 0xFF38BDF8.toInt()
    private val COLOR_DARK_CYAN = 0xFF0284C7.toInt()
    private val COLOR_GOLD = 0xFFF59E0B.toInt()

    init {
        startAnimation()
    }

    private fun startAnimation() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 16L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val speed = when (state) {
                    State.LISTENING -> 2.5f
                    State.PROCESSING -> 3.5f
                    State.SPEAKING -> 1.9f
                    State.STANDBY -> 0.85f
                }

                angleOuter = (angleOuter + 1.2f * speed) % 360f
                angleInner = (angleInner - 1.8f * speed) % 360f
                angleRays = (angleRays + 0.6f * speed) % 360f
                pulsePhase = (pulsePhase + 0.08f * speed) % (2f * Math.PI.toFloat())

                // Idle 3D floating wobble when not touched
                if (targetRotX == 0f && targetRotY == 0f) {
                    val wobbleX = sin(pulsePhase.toDouble()).toFloat() * 9f
                    val wobbleY = cos((pulsePhase * 0.7f).toDouble()).toFloat() * 11f
                    rotX += (wobbleX - rotX) * 0.06f
                    rotY += (wobbleY - rotY) * 0.06f
                } else {
                    rotX += (targetRotX - rotX) * 0.18f
                    rotY += (targetRotY - rotY) * 0.18f
                }

                // Dynamic shockwave pulse
                shockwaveRadius = (shockwaveRadius + 1.8f * speed)
                if (shockwaveRadius > 100f) {
                    shockwaveRadius = 20f
                }

                invalidate()
            }
            start()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val cx = width / 2f
                val cy = height / 2f
                val dx = ((event.x - cx) / cx).coerceIn(-1.5f, 1.5f)
                val dy = ((event.y - cy) / cy).coerceIn(-1.5f, 1.5f)
                targetRotX = -dy * 26f
                targetRotY = dx * 26f
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val cx = width / 2f
                val cy = height / 2f
                val dx = ((event.x - cx) / cx).coerceIn(-1.5f, 1.5f)
                val dy = ((event.y - cy) / cy).coerceIn(-1.5f, 1.5f)
                targetRotX = -dy * 26f
                targetRotY = dx * 26f
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                targetRotX = 0f
                targetRotY = 0f
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                targetRotX = 0f
                targetRotY = 0f
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = (minOf(width, height) / 2f) * 0.82f
        if (baseRadius <= 0f) return

        canvas.save()

        // 3D Perspective Projection via Camera centered around (cx, cy)
        canvas.translate(cx, cy)
        camera.save()
        camera.rotateX(rotX)
        camera.rotateY(rotY)
        camera.applyToCanvas(canvas)
        camera.restore()
        canvas.translate(-cx, -cy)

        // 1. Holographic Ambient Background Glow
        val pulseIntensity = (0.5f + 0.5f * sin(pulsePhase.toDouble()).toFloat())
        val glowRadius = baseRadius * (1.12f + 0.10f * pulseIntensity)

        val glowColor = when (state) {
            State.LISTENING -> 0x5500E5FF.toInt()
            State.PROCESSING -> 0x5538BDF8.toInt()
            State.SPEAKING -> 0x5500E5FF.toInt()
            State.STANDBY -> 0x3300E5FF.toInt()
        }

        glowPaint.shader = RadialGradient(
            cx, cy, glowRadius,
            intArrayOf(glowColor, 0x120284C7.toInt(), Color.TRANSPARENT),
            floatArrayOf(0f, 0.65f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, glowRadius, glowPaint)

        // 2. Expanding Shockwave Acoustic Waves (Listening & Speaking)
        if (state == State.LISTENING || state == State.SPEAKING) {
            val progress = (shockwaveRadius / 100f).coerceIn(0f, 1f)
            val alpha = ((1f - progress) * 200).toInt()
            ringPaint.color = (COLOR_CYAN and 0x00FFFFFF) or (alpha shl 24)
            ringPaint.strokeWidth = 3f + (1f - progress) * 3f
            canvas.drawCircle(cx, cy, baseRadius * (progress * 1.25f), ringPaint)
        }

        // 3. Outermost Segmented Arc Reactor Containment Ring
        canvas.save()
        canvas.rotate(angleOuter, cx, cy)
        ringPaint.color = COLOR_CYAN
        ringPaint.strokeWidth = 4.5f
        val outerR = baseRadius * 0.92f
        for (i in 0 until 6) {
            val startAngle = i * 60f + 7f
            canvas.drawArc(
                cx - outerR, cy - outerR, cx + outerR, cy + outerR,
                startAngle, 46f, false, ringPaint
            )
        }

        // Outer Radial Power Ticks
        tickPaint.color = COLOR_CYAN
        tickPaint.strokeWidth = 3f
        for (i in 0 until 12) {
            val a = Math.toRadians((i * 30.0))
            val r1 = outerR - 9f
            val r2 = outerR + 6f
            val x1 = cx + (r1 * cos(a)).toFloat()
            val y1 = cy + (r1 * sin(a)).toFloat()
            val x2 = cx + (r2 * cos(a)).toFloat()
            val y2 = cy + (r2 * sin(a)).toFloat()
            canvas.drawLine(x1, y1, x2, y2, tickPaint)
        }
        canvas.restore()

        // 4. Middle Sky Blue Counter-Rotating Segment Ring with Parallax Offset
        canvas.save()
        val parallaxX = rotY * 0.35f
        val parallaxY = -rotX * 0.35f
        canvas.translate(parallaxX, parallaxY)
        canvas.rotate(angleInner, cx, cy)

        val midR = baseRadius * 0.68f
        ringPaint.color = COLOR_SKY
        ringPaint.strokeWidth = 3.5f
        for (i in 0 until 4) {
            val startAngle = i * 90f + 10f
            canvas.drawArc(
                cx - midR, cy - midR, cx + midR, cy + midR,
                startAngle, 70f, false, ringPaint
            )
        }

        // Middle Radial Power Fins
        tickPaint.color = COLOR_SKY
        tickPaint.strokeWidth = 2.5f
        for (i in 0 until 8) {
            val a = Math.toRadians((i * 45.0))
            val r1 = midR - 12f
            val r2 = midR - 2f
            val x1 = cx + (r1 * cos(a)).toFloat()
            val y1 = cy + (r1 * sin(a)).toFloat()
            val x2 = cx + (r2 * cos(a)).toFloat()
            val y2 = cy + (r2 * sin(a)).toFloat()
            canvas.drawLine(x1, y1, x2, y2, tickPaint)
        }
        canvas.restore()

        // 5. Innermost Rotating Triangular Core Cage
        canvas.save()
        val deepParallaxX = rotY * 0.7f
        val deepParallaxY = -rotX * 0.7f
        canvas.translate(deepParallaxX, deepParallaxY)
        canvas.rotate(angleRays, cx, cy)

        val innerR = baseRadius * 0.48f
        ringPaint.color = COLOR_CYAN
        ringPaint.strokeWidth = 2f
        canvas.drawCircle(cx, cy, innerR, ringPaint)

        // Triangular Chevron Anchors
        tickPaint.color = COLOR_CYAN
        tickPaint.strokeWidth = 3f
        for (i in 0 until 3) {
            val a = Math.toRadians((i * 120.0))
            val r1 = innerR - 10f
            val r2 = innerR + 4f
            val x1 = cx + (r1 * cos(a)).toFloat()
            val y1 = cy + (r1 * sin(a)).toFloat()
            val x2 = cx + (r2 * cos(a)).toFloat()
            val y2 = cy + (r2 * sin(a)).toFloat()
            canvas.drawLine(x1, y1, x2, y2, tickPaint)
        }
        canvas.restore()

        // 6. Pulsing Vibranium AI Core Housing
        val corePulse = if (state == State.LISTENING || state == State.SPEAKING) {
            0.08f * sin((pulsePhase * 3f).toDouble()).toFloat()
        } else {
            0.04f * sin((pulsePhase * 1.5f).toDouble()).toFloat()
        }
        val coreR = baseRadius * (0.32f + corePulse)

        val coreCenterColor = when (state) {
            State.LISTENING -> 0xFFFFFFFF.toInt()
            State.PROCESSING -> 0xFFE0F2FE.toInt()
            State.SPEAKING -> 0xFFFFFFFF.toInt()
            State.STANDBY -> 0xFFE0F7FA.toInt()
        }

        corePaint.shader = RadialGradient(
            cx + deepParallaxX, cy + deepParallaxY, coreR,
            intArrayOf(coreCenterColor, COLOR_CYAN, COLOR_DARK_CYAN),
            floatArrayOf(0f, 0.40f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx + deepParallaxX, cy + deepParallaxY, coreR, corePaint)

        // 7. Central High-Intensity Pure White Spark
        val sparkR = baseRadius * 0.10f
        corePaint.shader = null
        corePaint.color = Color.WHITE
        canvas.drawCircle(cx + deepParallaxX, cy + deepParallaxY, sparkR, corePaint)

        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }
}

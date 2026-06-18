package com.volt.terminal

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.*
import kotlin.random.Random

class PauseAnimView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private data class FloatingItem(
        var x: Float,
        var y: Float,
        val baseY: Float,
        val emoji: String,
        val size: Float,
        val phase: Float,
        val speed: Float,
        val amplitude: Float,
        val driftX: Float
    )

    private data class Ripple(
        var x: Float,
        var y: Float,
        var radius: Float,
        val maxRadius: Float,
        val colorBase: Int
    ) {
        val alpha get() = (1f - radius / maxRadius).coerceIn(0f, 1f)
    }

    private val emojiPool = listOf(
        "🍊", "🍋", "🍇", "🍓", "🍑", "🍒", "🥝", "🍍", "🍎", "🍌", "🫐", "💧", "🍹", "🧃"
    )
    private val rippleColors = listOf(
        Color.argb(160, 0, 80, 220),
        Color.argb(120, 30, 120, 255),
        Color.argb(100, 0, 160, 255),
        Color.argb(80,  80, 180, 255)
    )

    private val items   = mutableListOf<FloatingItem>()
    private val ripples = mutableListOf<Ripple>()

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val rippleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var frameT = 0f
    private var running = false
    private var nextRipple = 30

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            invalidate()
            if (running) postDelayed(this, 16L)
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun startAnimation() {
        running = true
        if (width > 0 && height > 0) {
            initItems()
            post(ticker)
        } else {
            post {
                initItems()
                post(ticker)
            }
        }
    }

    fun stopAnimation() {
        running = false
        removeCallbacks(ticker)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) initItems()
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun initItems() {
        items.clear()
        ripples.clear()
        val w = width.toFloat()
        val h = height.toFloat()
        repeat(16) {
            val baseY = h * 0.08f + Random.nextFloat() * h * 0.84f
            items.add(FloatingItem(
                x         = Random.nextFloat() * w,
                y         = baseY,
                baseY     = baseY,
                emoji     = emojiPool.random(),
                size      = Random.nextFloat() * 44f + 28f,
                phase     = Random.nextFloat() * 2f * PI.toFloat(),
                speed     = Random.nextFloat() * 0.025f + 0.008f,
                amplitude = Random.nextFloat() * 28f + 12f,
                driftX    = (Random.nextFloat() - 0.5f) * 0.6f
            ))
        }
    }

    private fun tick() {
        val w = width.toFloat().takeIf { it > 0 } ?: return
        val h = height.toFloat().takeIf { it > 0 } ?: return
        frameT += 1f

        // Float items
        for (item in items) {
            item.y = item.baseY + sin(frameT * item.speed + item.phase) * item.amplitude
            item.x = (item.x + item.driftX + w) % w
        }

        // Spawn ripples
        if (--nextRipple <= 0) {
            nextRipple = Random.nextInt(18, 50)
            ripples.add(Ripple(
                x          = Random.nextFloat() * w,
                y          = Random.nextFloat() * h,
                radius     = 0f,
                maxRadius  = Random.nextFloat() * 70f + 25f,
                colorBase  = rippleColors.random()
            ))
        }

        // Expand ripples
        val dead = mutableListOf<Ripple>()
        for (r in ripples) {
            r.radius += 2.8f
            if (r.radius >= r.maxRadius) dead.add(r)
        }
        ripples.removeAll(dead)
    }

    override fun onDraw(canvas: Canvas) {
        // Ripples (ondulations eau)
        for (r in ripples) {
            val a = r.alpha
            val base = r.colorBase
            ripplePaint.color = Color.argb(
                (Color.alpha(base) * a).toInt(),
                Color.red(base), Color.green(base), Color.blue(base)
            )
            canvas.drawCircle(r.x, r.y, r.radius, ripplePaint)

            // Anneau intérieur plus doux
            rippleFillPaint.color = Color.argb(
                (Color.alpha(base) * a * 0.25f).toInt(),
                Color.red(base), Color.green(base), Color.blue(base)
            )
            canvas.drawCircle(r.x, r.y, r.radius * 0.45f, rippleFillPaint)
        }

        // Fruits / eau flottants
        for (item in items) {
            textPaint.textSize = item.size
            canvas.drawText(item.emoji, item.x, item.y, textPaint)
        }
    }
}

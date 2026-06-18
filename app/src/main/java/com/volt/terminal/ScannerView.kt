package com.volt.terminal

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin

/**
 * Scanner QR animé : 4 coins L lumineux avec glow + laser qui balaie.
 */
class ScannerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val d = context.resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    // ── Coins ──────────────────────────────────────────────────────────────────
    private val CORNER_LEN = dp(52f)

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.parseColor("#FF1166FF")
        strokeWidth = dp(5.5f)
        style       = Paint.Style.STROKE
        strokeCap   = Paint.Cap.ROUND
        strokeJoin  = Paint.Join.ROUND
    }
    private val cornerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.parseColor("#AA0044EE")
        strokeWidth = dp(18f)
        style       = Paint.Style.STROKE
        strokeCap   = Paint.Cap.ROUND
        maskFilter  = BlurMaskFilter(dp(16f), BlurMaskFilter.Blur.NORMAL)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF1166FF")
    }

    // ── Laser ──────────────────────────────────────────────────────────────────
    private val laserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        strokeWidth = dp(2.8f)
    }
    private val laserGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        strokeWidth = dp(20f)
        maskFilter  = BlurMaskFilter(dp(18f), BlurMaskFilter.Blur.NORMAL)
    }

    // ── Animation ──────────────────────────────────────────────────────────────
    private var scanY   = 0f
    private var scanDir = 1
    private var phase   = 0f
    private var running = false

    private val animator = object : Runnable {
        override fun run() {
            if (!running) return
            scanY += scanDir * dp(4.2f)
            phase += 0.055f
            if (scanY >= height.toFloat()) { scanY = height.toFloat(); scanDir = -1 }
            if (scanY <= 0f)              { scanY = 0f;                scanDir =  1 }
            invalidate()
            postDelayed(this, 16L)
        }
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)   // requis pour BlurMaskFilter
    }

    fun startScan() {
        if (running) return
        running = true; scanY = 0f; scanDir = 1; post(animator)
    }

    fun stopScan() {
        running = false; removeCallbacks(animator); invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w == 0f || h == 0f) return

        val pulse = 0.72f + 0.28f * sin(phase.toDouble()).toFloat()

        // ── Glow doux sur les coins ────────────────────────────────────────────
        cornerGlowPaint.alpha = (160 * pulse).toInt()
        drawCorners(canvas, w, h, cornerGlowPaint)

        // ── Coins nets ────────────────────────────────────────────────────────
        cornerPaint.alpha = (255 * pulse).toInt()
        drawCorners(canvas, w, h, cornerPaint)

        // ── Points aux 4 sommets ──────────────────────────────────────────────
        val dotR = dp(5.5f) * pulse
        dotPaint.alpha = (255 * pulse).toInt()
        canvas.drawCircle(0f, 0f, dotR, dotPaint)
        canvas.drawCircle(w,  0f, dotR, dotPaint)
        canvas.drawCircle(0f, h,  dotR, dotPaint)
        canvas.drawCircle(w,  h,  dotR, dotPaint)

        // ── Laser ─────────────────────────────────────────────────────────────
        if (running && h > 0f) {
            laserPaint.shader = LinearGradient(
                0f, scanY, w, scanY,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.parseColor("#FF00DDFF"),
                    Color.parseColor("#FFFFFFFF"),
                    Color.parseColor("#FF00DDFF"),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.2f, 0.5f, 0.8f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawLine(0f, scanY, w, scanY, laserPaint)

            laserGlowPaint.shader = LinearGradient(
                0f, scanY, w, scanY,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.parseColor("#440099CC"),
                    Color.parseColor("#770099CC"),
                    Color.parseColor("#440099CC"),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.2f, 0.5f, 0.8f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawLine(0f, scanY, w, scanY, laserGlowPaint)
        }
    }

    private fun drawCorners(c: Canvas, w: Float, h: Float, p: Paint) {
        val cl = CORNER_LEN
        // Top-left
        c.drawLine(0f, cl, 0f, 0f, p)
        c.drawLine(0f, 0f, cl, 0f, p)
        // Top-right
        c.drawLine(w - cl, 0f, w, 0f, p)
        c.drawLine(w, 0f, w, cl, p)
        // Bottom-left
        c.drawLine(0f, h - cl, 0f, h, p)
        c.drawLine(0f, h, cl, h, p)
        // Bottom-right
        c.drawLine(w - cl, h, w, h, p)
        c.drawLine(w, h - cl, w, h, p)
    }
}

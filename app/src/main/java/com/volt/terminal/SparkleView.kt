package com.volt.terminal

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Particules en étoile à 4 branches qui scintillent aléatoirement partout à l'écran.
 * Effet "lumière sur l'eau" / "reflets diamantés". Pas de BlurMaskFilter
 * (perf) — le halo doux est simulé par des cercles semi-transparents superposés.
 */
class SparkleView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val d = context.resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    companion object {
        private const val MAX_SPARKLES = 60
        private const val TICK_MS      = 22L
    }

    // ── Modèle ────────────────────────────────────────────────────────────────

    private data class Sparkle(
        val x: Float,
        val y: Float,
        val outerRadius: Float,    // rayon externe de l'étoile (dp)
        val r: Int, val g: Int, val b: Int,
        val totalTicks: Int,
        val fadeInTicks: Int,
        val fadeOutTicks: Int,
        val rotDeg: Float,         // rotation de l'étoile (0–45°)
        var tick: Int = 0
    )

    private val sparkles   = mutableListOf<Sparkle>()
    private val starPath   = Path()
    private val paint      = Paint(Paint.ANTI_ALIAS_FLAG)
    private var running    = false
    private var tickCount  = 0

    // Palette : blanc, blanc-bleu, bleu-ciel pâle, blanc chaud, aqua
    private val palette = listOf(
        Triple(255, 255, 255),
        Triple(220, 240, 255),
        Triple(200, 228, 255),
        Triple(255, 248, 218),
        Triple(178, 220, 255),
        Triple(240, 248, 255),
    )

    // ── Animation ─────────────────────────────────────────────────────────────

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            tickCount++
            updateSparkles()
            invalidate()
            postDelayed(this, TICK_MS)
        }
    }

    private fun updateSparkles() {
        val w = width.takeIf  { it > 0 }?.toFloat() ?: return
        val h = height.takeIf { it > 0 }?.toFloat() ?: return

        // Avance chaque sparkle
        val dead = mutableListOf<Sparkle>()
        for (s in sparkles) {
            s.tick++
            if (s.tick > s.totalTicks) dead.add(s)
        }
        sparkles.removeAll(dead)

        // Spawn un nouveau sparkle toutes les 3 frames si < MAX
        if (tickCount % 3 == 0 && sparkles.size < MAX_SPARKLES) {
            val c       = palette.random()
            val fadeIn  = Random.nextInt(6, 16)
            val hold    = Random.nextInt(12, 38)
            val fadeOut = Random.nextInt(6, 14)
            sparkles.add(Sparkle(
                x           = Random.nextFloat() * w,
                y           = Random.nextFloat() * h,
                outerRadius = Random.nextFloat() * dp(7f) + dp(2.5f),
                r = c.first, g = c.second, b = c.third,
                totalTicks  = fadeIn + hold + fadeOut,
                fadeInTicks = fadeIn,
                fadeOutTicks = fadeOut,
                rotDeg      = Random.nextFloat() * 45f
            ))
        }
    }

    // ── API publique ──────────────────────────────────────────────────────────

    fun start() {
        if (!running) { running = true; tickCount = 0; post(ticker) }
    }

    fun stop() {
        running = false; removeCallbacks(ticker); sparkles.clear(); invalidate()
    }

    fun isRunning() = running

    // ── Dessin ────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        for (s in sparkles) {
            val alpha = computeAlpha(s).toFloat()
            if (alpha <= 0.02f) continue
            val size = s.outerRadius * alpha

            // ── Halo doux (cercles concentriques semi-transparents) ─────────────
            for (i in 1..3) {
                paint.color = Color.argb(
                    ((alpha * 55f / i).coerceIn(0f, 255f)).toInt(),
                    s.r, s.g, s.b
                )
                canvas.drawCircle(s.x, s.y, size * (1.4f + i * 0.7f), paint)
            }

            // ── Étoile à 4 branches ────────────────────────────────────────────
            paint.color = Color.argb(
                (alpha * 255f).toInt().coerceIn(0, 255),
                s.r, s.g, s.b
            )
            drawStar(canvas, s.x, s.y, size, s.rotDeg)
        }
    }

    /** Étoile à 4 branches : 8 sommets alternant outerR et innerR (= outerR × 0.18). */
    private fun drawStar(canvas: Canvas, cx: Float, cy: Float, outerR: Float, rotDeg: Float) {
        val innerR = outerR * 0.18f
        starPath.reset()
        canvas.save()
        canvas.rotate(rotDeg, cx, cy)
        val step = Math.PI / 4.0      // 45° entre chaque sommet
        for (i in 0..7) {
            val angle = i * step - Math.PI / 2.0    // commence en haut
            val r = if (i % 2 == 0) outerR else innerR
            val px = cx + cos(angle).toFloat() * r
            val py = cy + sin(angle).toFloat() * r
            if (i == 0) starPath.moveTo(px, py) else starPath.lineTo(px, py)
        }
        starPath.close()
        canvas.drawPath(starPath, paint)
        canvas.restore()
    }

    private fun computeAlpha(s: Sparkle): Double {
        val hold = s.totalTicks - s.fadeInTicks - s.fadeOutTicks
        return when {
            s.tick <= s.fadeInTicks ->
                s.tick.toDouble() / s.fadeInTicks
            s.tick <= s.fadeInTicks + hold ->
                1.0
            else ->
                (s.totalTicks - s.tick).toDouble() / s.fadeOutTicks
        }.coerceIn(0.0, 1.0)
    }
}

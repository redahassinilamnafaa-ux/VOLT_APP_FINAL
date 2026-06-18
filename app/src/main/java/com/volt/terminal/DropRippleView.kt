package com.volt.terminal

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.random.Random

/**
 * Gouttes d'eau autonomes : apparaissent aléatoirement à l'écran et créent
 * 3 anneaux concentriques qui s'élargissent et se fondent (effet surface liquide).
 */
class DropRippleView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val d = context.resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    companion object {
        private const val MAX_RIPPLES = 8
        private const val TICK_MS     = 20L
    }

    // ── Modèle ────────────────────────────────────────────────────────────────

    private data class Ripple(
        val x: Float,
        val y: Float,
        val maxRadius: Float,
        val birthMs: Long,
        val durationMs: Long
    )

    private val ripples  = mutableListOf<Ripple>()
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private var running       = false
    private var lastSpawnMs   = 0L
    private var nextDelayMs   = 2500L

    // ── Animation ─────────────────────────────────────────────────────────────

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.currentTimeMillis()

            // Supprime les ripples terminés
            ripples.removeAll { now - it.birthMs >= it.durationMs }

            // Spawn d'un nouveau ripple si délai écoulé et place disponible
            if (ripples.size < MAX_RIPPLES && now - lastSpawnMs >= nextDelayMs) {
                val w = width.takeIf  { it > 0 }?.toFloat()
                val h = height.takeIf { it > 0 }?.toFloat()
                if (w != null && h != null) {
                    // Évite de spawner pile dans la zone scanner (centre ± 145dp)
                    // → on préfère les bords et les zones autour des bulles
                    val margin = dp(40f)
                    ripples.add(Ripple(
                        x          = Random.nextFloat() * (w - 2 * margin) + margin,
                        y          = Random.nextFloat() * (h - 2 * margin) + margin,
                        maxRadius  = Random.nextFloat() * dp(55f) + dp(48f),
                        birthMs    = now,
                        durationMs = (Random.nextFloat() * 1200f + 1800f).toLong()
                    ))
                    lastSpawnMs  = now
                    nextDelayMs  = (Random.nextFloat() * 2200f + 1600f).toLong()
                }
            }

            invalidate()
            postDelayed(this, TICK_MS)
        }
    }

    // ── API publique ──────────────────────────────────────────────────────────

    fun start() {
        if (!running) { running = true; lastSpawnMs = 0; post(ticker) }
    }

    fun stop() {
        running = false; removeCallbacks(ticker); ripples.clear(); invalidate()
    }

    fun isRunning() = running

    // ── Dessin ────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val now = System.currentTimeMillis()
        for (r in ripples) {
            val progress = ((now - r.birthMs).toFloat() / r.durationMs).coerceIn(0f, 1f)
            // 3 anneaux décalés dans le temps : 0%, 28%, 56% du cycle de vie
            drawRing(canvas, r, progress, 0.00f)
            if (progress > 0.28f) drawRing(canvas, r, progress, 0.28f)
            if (progress > 0.56f) drawRing(canvas, r, progress, 0.56f)
        }
    }

    /**
     * Dessine un seul anneau pour ce ripple.
     * @param delay fraction [0..1] du cycle de vie à partir de laquelle cet anneau démarre
     */
    private fun drawRing(canvas: Canvas, r: Ripple, progress: Float, delay: Float) {
        val adj     = ((progress - delay) / (1f - delay)).coerceIn(0f, 1f)
        val radius  = r.maxRadius * adj
        val fade    = 1f - adj                                    // 1→0 au fil de l'expansion
        val alpha   = (fade * fade * 200f).toInt().coerceIn(0, 255)
        val stroke  = dp(1.8f) * (1f - adj * 0.65f)

        if (alpha < 4 || radius < 1f) return

        // Anneau blanc principal
        ringPaint.color       = Color.argb(alpha, 255, 255, 255)
        ringPaint.strokeWidth = stroke
        canvas.drawCircle(r.x, r.y, radius, ringPaint)

        // Anneau intérieur bleu irisé (70% de taille, 55% d'opacité)
        ringPaint.color       = Color.argb((alpha * 0.55f).toInt(), 160, 218, 255)
        ringPaint.strokeWidth = stroke * 0.45f
        canvas.drawCircle(r.x, r.y, radius * 0.70f, ringPaint)
    }
}

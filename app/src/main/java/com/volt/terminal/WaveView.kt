package com.volt.terminal

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin

/**
 * 3 vagues sinusoïdales superposées qui se déplacent horizontalement,
 * donnant l'illusion d'un liquide qui s'écoule en bas de l'écran.
 * Fond transparent : ne cache pas le contenu au-dessus.
 */
class WaveView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val d = context.resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    // ── Définition des couches ────────────────────────────────────────────────
    private data class WaveLayer(
        val color: Int,
        val amplitude: Float,    // hauteur max d'oscillation (dp)
        val period: Float,       // largeur d'un cycle complet (dp)
        val speed: Float,        // déplacement par frame (dp) — positif = droite
        var phase: Float = 0f,
        val bottomFraction: Float // fraction depuis le bas où la ligne de base se trouve
    )

    // Fond (derrière) → avant (devant)  |  lent/grand → rapide/petit
    private val layers = listOf(
        WaveLayer(Color.argb(38,  0, 75, 210), dp(28f), dp(580f), dp(1.5f), 0f,       0.32f),
        WaveLayer(Color.argb(58,  0, 55, 190), dp(20f), dp(460f), dp(2.4f), dp(140f), 0.24f),
        WaveLayer(Color.argb(84, 10, 38, 168), dp(14f), dp(360f), dp(3.6f), dp(260f), 0.17f),
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path  = Path()
    private var running = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            for (l in layers) {
                l.phase = (l.phase + l.speed) % l.period
            }
            invalidate()
            postDelayed(this, 16L)
        }
    }

    // ── API publique ──────────────────────────────────────────────────────────

    fun start()     { if (!running) { running = true; post(ticker) } }
    fun stop()      { running = false; removeCallbacks(ticker); invalidate() }
    fun isRunning() = running

    // ── Dessin ────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat();  val h = height.toFloat()
        if (w == 0f || h == 0f) return
        val step   = dp(5f)
        val TWO_PI = Math.PI * 2.0

        for (layer in layers) {
            val baseY = h * (1f - layer.bottomFraction)

            path.reset()
            path.moveTo(-step, h)      // départ hors bord gauche, en bas

            var x = -step
            while (x <= w + step) {
                val y = (baseY + sin(TWO_PI * (x - layer.phase) / layer.period) * layer.amplitude).toFloat()
                path.lineTo(x, y)
                x += step
            }
            path.lineTo(w + step, h)   // coin bas-droite
            path.close()               // retour au départ (bas-gauche) → remplit la zone

            paint.color = layer.color
            canvas.drawPath(path, paint)
        }
    }
}

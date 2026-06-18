package com.volt.terminal

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.sin
import kotlin.random.Random

/**
 * Bulles d'eau translucides avec emojis fruits qui flottent vers le haut.
 * Tap → animation pop (explosion + étincelles).
 * Plus grosses que FruitRainView (rayon 56-96dp).
 */
class BubbleFruitView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val d = context.resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    // ── Modèle ────────────────────────────────────────────────────────────────

    private data class Bubble(
        var x: Float,
        var y: Float,
        val emoji: String,
        val radius: Float,
        val riseSpeed: Float,
        var wobblePhase: Float,
        val wobbleSpeed: Float,
        val wobbleAmp: Float,
        var alpha: Float = 0f,        // 0→1 fondu entrant
        var popping: Boolean = false,
        var popProgress: Float = 0f   // 0→1
    )

    private val emojis = listOf(
        "🍊", "🍋", "🍇", "🍓", "🍑", "🍒", "🥝", "🍍",
        "🍎", "🍌", "🫐", "🍉", "🍐", "🍈", "🥭", "🍏"
    )
    private val bubbles = mutableListOf<Bubble>()

    // ── Paints ────────────────────────────────────────────────────────────────

    private val fillPaint  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        strokeWidth = dp(2.8f)
    }
    private val shinePaint  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint   = Paint(Paint.ANTI_ALIAS_FLAG)
    private val popRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        strokeWidth = dp(3.5f)
    }
    private val sparkPaint  = Paint(Paint.ANTI_ALIAS_FLAG)

    // ── État animation ────────────────────────────────────────────────────────

    private var active     = false
    private var spawnTimer = 0

    private val ticker = object : Runnable {
        override fun run() {
            if (!active) return
            tick()
            invalidate()
            postDelayed(this, 16L)
        }
    }

    // ── API publique ──────────────────────────────────────────────────────────

    fun start() {
        if (active) return
        active = true; spawnTimer = 0; post(ticker)
    }

    fun stop() {
        active = false; removeCallbacks(ticker); bubbles.clear(); invalidate()
    }

    fun isRunning() = active

    // ── Logique ───────────────────────────────────────────────────────────────

    private fun tick() {
        val w = width.takeIf  { it > 0 }?.toFloat() ?: return
        val h = height.takeIf { it > 0 }?.toFloat() ?: return

        // Spawn une nouvelle bulle toutes les ~48 frames si < 18 bulles
        spawnTimer++
        if (spawnTimer % 48 == 0 && bubbles.size < 18) {
            val r = Random.nextFloat() * dp(40f) + dp(56f)   // 56–96dp
            bubbles.add(Bubble(
                x          = Random.nextFloat() * (w - 2f * r) + r,
                y          = h + r + Random.nextFloat() * dp(100f),
                emoji      = emojis.random(),
                radius     = r,
                riseSpeed  = Random.nextFloat() * dp(1.0f) + dp(0.6f),
                wobblePhase= Random.nextFloat() * 360f,
                wobbleSpeed= Random.nextFloat() * 1.8f + 0.8f,
                wobbleAmp  = Random.nextFloat() * dp(14f) + dp(6f)
            ))
        }

        val dead = mutableListOf<Bubble>()
        for (b in bubbles) {
            if (b.popping) {
                b.popProgress += 0.065f
                b.alpha = (1f - b.popProgress * 1.4f).coerceAtLeast(0f)
                if (b.popProgress >= 1f) dead.add(b)
                continue
            }
            // Montée + oscillation latérale
            b.y           -= b.riseSpeed
            b.wobblePhase += b.wobbleSpeed
            b.x           += sin(Math.toRadians(b.wobblePhase.toDouble())).toFloat() * b.wobbleAmp * 0.04f
            b.x            = b.x.coerceIn(b.radius, w - b.radius)
            // Fondu entrant
            b.alpha        = (b.alpha + 0.03f).coerceAtMost(1f)
            // Sortie hors écran → mort
            if (b.y + b.radius < -dp(10f)) dead.add(b)
        }
        bubbles.removeAll(dead)
    }

    // ── Touch → pop ──────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return false
        // Parcours inversé : bulles du dessus en premier
        for (b in bubbles.asReversed()) {
            if (!b.popping) {
                val dx = b.x - event.x; val dy = b.y - event.y
                if (dx * dx + dy * dy < (b.radius * 1.2f).let { it * it }) {
                    b.popping = true; invalidate(); return true
                }
            }
        }
        return false
    }

    // ── Dessin ────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        for (b in bubbles) {
            val a = b.alpha.coerceIn(0f, 1f)

            if (b.popping) {
                drawPop(canvas, b, a); continue
            }

            // ── Corps de la bulle ──────────────────────────────────────────────
            // Remplissage bleu translucide
            fillPaint.color = Color.argb((68 * a).toInt(), 160, 215, 255)
            canvas.drawCircle(b.x, b.y, b.radius, fillPaint)

            // Bord nacré (léger)
            rimPaint.color = Color.argb((200 * a).toInt(), 255, 255, 255)
            canvas.drawCircle(b.x, b.y, b.radius, rimPaint)

            // Bord bleu subtil (dédoublement) → effet bulle
            rimPaint.color = Color.argb((80 * a).toInt(), 120, 180, 255)
            rimPaint.strokeWidth = dp(1.5f)
            canvas.drawCircle(b.x, b.y, b.radius - dp(1.5f), rimPaint)
            rimPaint.strokeWidth = dp(2.8f)

            // Reflet principal (haut-gauche)
            shinePaint.color = Color.argb((105 * a).toInt(), 255, 255, 255)
            canvas.drawCircle(
                b.x - b.radius * 0.28f, b.y - b.radius * 0.30f,
                b.radius * 0.30f, shinePaint
            )
            // Petit reflet secondaire (haut-droit)
            shinePaint.color = Color.argb((55 * a).toInt(), 255, 255, 255)
            canvas.drawCircle(
                b.x + b.radius * 0.28f, b.y - b.radius * 0.42f,
                b.radius * 0.13f, shinePaint
            )

            // ── Emoji fruit ────────────────────────────────────────────────────
            val ts = b.radius * 1.08f
            textPaint.textSize = ts
            textPaint.alpha    = (255 * a).toInt()
            canvas.drawText(b.emoji, b.x - ts * 0.44f, b.y + ts * 0.36f, textPaint)
        }
    }

    private fun drawPop(canvas: Canvas, b: Bubble, a: Float) {
        val p = b.popProgress
        // Anneau qui s'élargit
        val r = b.radius * (1f + p * 0.65f)
        popRingPaint.color = Color.argb((180 * a).toInt(), 130, 200, 255)
        canvas.drawCircle(b.x, b.y, r, popRingPaint)

        // Second anneau décalé
        if (p > 0.1f) {
            val r2 = b.radius * (1f + (p - 0.1f) * 0.5f)
            popRingPaint.color = Color.argb((100 * a).toInt(), 200, 230, 255)
            canvas.drawCircle(b.x, b.y, r2, popRingPaint)
        }

        // Étincelles radiales
        val nSpark = 8
        for (i in 0 until nSpark) {
            val angle = i * (360.0 / nSpark) + p * 120.0
            val dist  = b.radius * (0.5f + p * 1.1f)
            val sx    = b.x + Math.cos(Math.toRadians(angle)).toFloat() * dist
            val sy    = b.y + Math.sin(Math.toRadians(angle)).toFloat() * dist
            sparkPaint.color = Color.argb(
                (200 * a).toInt(),
                (160 + (i % 3) * 30),
                225, 255
            )
            canvas.drawCircle(sx, sy, dp(5.5f) * a, sparkPaint)
        }

        // Mini-gouttes intérieures
        val nDrop = 5
        for (i in 0 until nDrop) {
            val angle = i * (360.0 / nDrop) + 30.0 + p * 200.0
            val dist  = b.radius * p * 0.7f
            val sx    = b.x + Math.cos(Math.toRadians(angle)).toFloat() * dist
            val sy    = b.y + Math.sin(Math.toRadians(angle)).toFloat() * dist
            sparkPaint.color = Color.argb((160 * a).toInt(), 200, 240, 255)
            canvas.drawCircle(sx, sy, dp(3f) * a, sparkPaint)
        }
    }
}

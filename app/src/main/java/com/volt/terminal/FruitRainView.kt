package com.volt.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.random.Random

class FruitRainView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private data class Drop(
        var x: Float, var y: Float,
        val emoji: String,
        val size: Float,
        val speed: Float,
        var angle: Float,
        val spin: Float
    )

    private val emojis = listOf("🍊", "🍋", "🍇", "🍓", "🍑", "🍒", "🥝", "🍍", "🍎", "🍌")
    private val drops = mutableListOf<Drop>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var active = false

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            invalidate()
            if (active) postDelayed(this, 16L)
        }
    }

    fun startRain() {
        drops.clear()
        active = true
        post(ticker)
    }

    fun stopRain() {
        active = false
        removeCallbacks(ticker)
        drops.clear()
        invalidate()
    }

    private fun tick() {
        val w = width.takeIf { it > 0 }?.toFloat() ?: return
        val h = height.takeIf { it > 0 }?.toFloat() ?: return
        if (drops.size < 30) {
            repeat(3) {
                drops.add(Drop(
                    x = Random.nextFloat() * w,
                    y = -Random.nextFloat() * 300f - 50f,
                    emoji = emojis.random(),
                    size = Random.nextFloat() * 52f + 36f,
                    speed = Random.nextFloat() * 9f + 5f,
                    angle = Random.nextFloat() * 30f - 15f,
                    spin = (Random.nextFloat() - 0.5f) * 4f
                ))
            }
        }
        val toRemove = mutableListOf<Drop>()
        for (d in drops) {
            d.y += d.speed
            d.angle += d.spin
            if (d.y > h + 120f) toRemove.add(d)
        }
        drops.removeAll(toRemove)
    }

    override fun onDraw(canvas: Canvas) {
        for (d in drops) {
            paint.textSize = d.size
            canvas.save()
            canvas.rotate(d.angle, d.x + d.size / 2f, d.y)
            canvas.drawText(d.emoji, d.x, d.y, paint)
            canvas.restore()
        }
    }
}

package com.volt.terminal

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * Fond dégradé noir-bleu plein écran avec un trou transparent
 * à l'emplacement exact du cadre QR (la caméra est visible derrière).
 */
class ScanOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private var holeRect = RectF()
    private val cornerRadius = context.resources.displayMetrics.density * 6f

    init {
        // Obligatoire pour que CLEAR fonctionne correctement
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    /** Appelé depuis MainActivity après que le viewQrCorners soit mesuré */
    fun setHole(left: Float, top: Float, right: Float, bottom: Float) {
        holeRect.set(left, top, right, bottom)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Fond bleu-ciel très clair — aqua frais pour l'écran de scan
        bgPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(
                Color.parseColor("#E8F4FF"),   // blanc-bleu en haut
                Color.parseColor("#C8E5FF"),   // bleu ciel léger au milieu
                Color.parseColor("#A8D4FF")    // bleu aqua doux en bas
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        // 1. Remplit tout l'écran avec le dégradé noir-bleu
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        // 2. Efface le rectangle QR → la caméra (PreviewView) apparaît derrière
        if (!holeRect.isEmpty) {
            canvas.drawRoundRect(holeRect, cornerRadius, cornerRadius, holePaint)
        }
    }
}

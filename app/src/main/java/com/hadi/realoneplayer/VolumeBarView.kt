package com.videoh.realoneplayer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/** Bar volume bertingkat (9 segmen, makin ke atas makin lebar) dengan tanda + di atas dan - di bawah. */
class VolumeBarView @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : View(ctx, attrs) {

    var level: Int = 0
        set(v) {
            field = v.coerceIn(0, 100)
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val on = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF7DE8D5.toInt() }
    private val off = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x387DE8D5 }
    private val sym = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF7DE8D5.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val areaW = w * 0.76f
        val n = 9
        val pitch = h / n
        val segH = pitch * 0.68f
        val gap = pitch - segH
        val filled = (level * n + 99) / 100

        for (k in 0 until n) {                       // k = 0 paling bawah
            val yBottom = h - k * pitch - gap / 2f
            val yTop = yBottom - segH
            val leftTop = areaW * 0.705f * (1f - k / 8f)
            val leftBottom = areaW * (0.705f * (1f - k / 8f) + 0.056f)
            path.reset()
            path.moveTo(areaW, yTop)
            path.lineTo(leftTop, yTop)
            path.lineTo(leftBottom, yBottom)
            path.lineTo(areaW, yBottom)
            path.close()
            canvas.drawPath(path, if (k < filled) on else off)
        }

        // tanda + (atas) dan - (bawah)
        val sw = w - areaW
        val cx = areaW + sw * 0.58f
        val half = maxOf(sw * 0.30f, 3f * density)
        val yPlus = pitch / 2f
        val yMinus = h - pitch / 2f
        canvas.drawLine(cx - half, yPlus, cx + half, yPlus, sym)
        canvas.drawLine(cx, yPlus - half, cx, yPlus + half, sym)
        canvas.drawLine(cx - half, yMinus, cx + half, yMinus, sym)
    }
}

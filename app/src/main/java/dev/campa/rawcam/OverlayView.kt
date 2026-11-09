package dev.campa.rawcam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Draws viewfinder aids over the preview: zebra/peaking mask, thirds grid, level and histogram. */
class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    var showGrid = false
        set(value) {
            field = value
            invalidate()
        }

    private var histogram: IntArray? = null
    private var mask: Bitmap? = null
    private var levelDeg = 0f

    private val density = resources.displayMetrics.density
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x80FFFFFF.toInt()
        strokeWidth = 1f * density
    }
    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 3f * density }
    private val maskPaint = Paint().apply { isFilterBitmap = false }
    private val histBg = Paint().apply { color = 0x99000000.toInt() }
    private val histPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xDDFFFFFF.toInt() }
    private val clipPaint = Paint().apply { color = 0xFFFF3030.toInt() }
    private val dst = Rect()
    private val histPath = Path()

    fun setAnalysis(hist: IntArray?, mask: Bitmap?) {
        histogram = hist
        this.mask = mask
        invalidate()
    }

    fun setLevel(deg: Float) {
        levelDeg = deg
        if (showGrid) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        mask?.let {
            dst.set(0, 0, width, height)
            canvas.drawBitmap(it, null, dst, maskPaint)
        }
        if (showGrid) drawGrid(canvas)
        histogram?.let { drawHistogram(canvas, it) }
    }

    private fun drawGrid(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        for (i in 1..2) {
            c.drawLine(w * i / 3, 0f, w * i / 3, h, gridPaint)
            c.drawLine(0f, h * i / 3, w, h * i / 3, gridPaint)
        }

        // The line tracks the real horizon; green when within 1° of level (portrait or landscape).
        val deviation = levelDeg - (levelDeg / 90f).roundToInt() * 90f
        levelPaint.color = if (abs(deviation) < 1f) 0xFF4CFF4C.toInt() else 0xFFFFD54F.toInt()
        val half = min(w, h) * 0.3f
        c.save()
        c.rotate(levelDeg, w / 2, h / 2)
        c.drawLine(w / 2 - half, h / 2, w / 2 + half, h / 2, levelPaint)
        c.restore()
    }

    private fun drawHistogram(c: Canvas, bins: IntArray) {
        val bw = width * 0.42f
        val bh = height * 0.16f
        val left = 8f * density
        val bottom = height - 8f * density
        val top = bottom - bh
        val right = left + bw
        c.drawRect(left, top, right, bottom, histBg)

        val max = (bins.maxOrNull() ?: 1).coerceAtLeast(1).toFloat()
        histPath.reset()
        histPath.moveTo(left, bottom)
        bins.forEachIndexed { i, v ->
            val x = left + bw * i / (bins.size - 1)
            val y = bottom - bh * sqrt(v / max)
            histPath.lineTo(x, y)
        }
        histPath.lineTo(right, bottom)
        histPath.close()
        c.drawPath(histPath, histPaint)

        val total = bins.sum().coerceAtLeast(1)
        val edge = 3f * density
        if (bins.last() > total * CLIP_FRACTION) c.drawRect(right - edge, top, right, bottom, clipPaint)
        if (bins.first() > total * CLIP_FRACTION) c.drawRect(left, top, left + edge, bottom, clipPaint)
    }

    private companion object {
        const val CLIP_FRACTION = 0.005
    }
}

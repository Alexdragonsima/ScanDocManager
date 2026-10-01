package com.dragonsima.scandoc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import org.opencv.core.Point

/**
 * Рисует рамку документа поверх превью камеры.
 * Все объекты Paint и Path создаются один раз и переиспользуются.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var corners: Array<Point>? = null

    // Переиспользуемый Path для рамки документа
    private val path = Path()

    // ==================== Paint ====================

    private val borderPaint = Paint().apply {
        color = COLOR_PRIMARY
        strokeWidth = 4f
        style = Paint.Style.STROKE
        isAntiAlias = true
        pathEffect = CornerPathEffect(30f)
    }

    private val cornerPaint = Paint().apply {
        color = COLOR_CORNER
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val fillPaint = Paint().apply {
        color = COLOR_FILL
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val ringPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    // ==================== Публичное API ====================

    fun setCorners(corners: Array<Point>?) {
        this.corners = corners
        invalidate()
    }

    // ==================== Рисование ====================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val corners = corners ?: return
        if (corners.size != 4) return

        // Пересобираем Path (один объект, переиспользуется)
        path.reset()
        path.moveTo(corners[0].x.toFloat(), corners[0].y.toFloat())
        path.lineTo(corners[1].x.toFloat(), corners[1].y.toFloat())
        path.lineTo(corners[2].x.toFloat(), corners[2].y.toFloat())
        path.lineTo(corners[3].x.toFloat(), corners[3].y.toFloat())
        path.close()

        // Полупрозрачная заливка
        canvas.drawPath(path, fillPaint)

        // Рамка
        canvas.drawPath(path, borderPaint)

        // Углы: точка + белое кольцо
        for (i in 0..3) {
            val x = corners[i].x.toFloat()
            val y = corners[i].y.toFloat()

            canvas.drawCircle(x, y, CORNER_RADIUS, cornerPaint)
            canvas.drawCircle(x, y, RING_RADIUS, ringPaint)
        }
    }

    companion object {
        private val COLOR_PRIMARY = Color.parseColor("#4F46E5")
        private val COLOR_CORNER = Color.parseColor("#FF5722")
        private val COLOR_FILL = Color.parseColor("#204F46E5")

        private const val CORNER_RADIUS = 12f
        private const val RING_RADIUS = 16f
    }
}
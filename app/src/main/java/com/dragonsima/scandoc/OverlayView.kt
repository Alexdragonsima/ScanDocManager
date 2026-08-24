package com.dragonsima.scandoc

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import org.opencv.core.Point

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var corners: Array<Point>? = null

    // Основная рамка
    private val borderPaint = Paint().apply {
        color = Color.parseColor("#4F46E5")
        strokeWidth = 4f
        style = Paint.Style.STROKE
        isAntiAlias = true
        pathEffect = CornerPathEffect(30f) // Скругленные углы
    }

    // Угловые точки
    private val cornerPaint = Paint().apply {
        color = Color.parseColor("#FF5722")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // Заливка документа
    private val fillPaint = Paint().apply {
        color = Color.parseColor("#204F46E5")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    fun setCorners(corners: Array<Point>?) {
        this.corners = corners
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val corners = corners ?: return
        if (corners.size != 4) return

        // Путь для заливки
        val path = Path().apply {
            moveTo(corners[0].x.toFloat(), corners[0].y.toFloat())
            lineTo(corners[1].x.toFloat(), corners[1].y.toFloat())
            lineTo(corners[2].x.toFloat(), corners[2].y.toFloat())
            lineTo(corners[3].x.toFloat(), corners[3].y.toFloat())
            close()
        }

        // Рисуем полупрозрачную заливку
        canvas.drawPath(path, fillPaint)

        // Рисуем рамку
        canvas.drawPath(path, borderPaint)

        // Рисуем угловые маркеры
        for (i in 0..3) {
            canvas.drawCircle(
                corners[i].x.toFloat(),
                corners[i].y.toFloat(),
                12f,
                cornerPaint
            )

            // Добавляем белое кольцо вокруг маркера
            val ringPaint = Paint().apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 3f
                isAntiAlias = true
            }
            canvas.drawCircle(
                corners[i].x.toFloat(),
                corners[i].y.toFloat(),
                16f,
                ringPaint
            )
        }
    }
}
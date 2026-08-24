package com.dragonsima.scandoc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.File

class CropActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var originalBitmap: Bitmap
    private lateinit var displayBitmap: Bitmap
    private val corners = Array(4) { PointF() }
    private var activeCorner = -1
    private val cornerRadius = 40f
    private val scaledCorners = Array(4) { PointF() }
    private val paint = Paint().apply {
        color = Color.parseColor("#4F46E5")
        strokeWidth = 6f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val cornerPaint = Paint().apply {
        color = Color.parseColor("#4F46E5")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crop)

        imageView = findViewById(R.id.cropImage)

        // Загружаем изображение
        val imagePath = intent.getStringExtra("imagePath") ?: return
        val mat = Imgcodecs.imread(imagePath)
        originalBitmap = matToBitmap(mat)
        mat.release()

        // Начальные углы — отступ 10% от краёв
        val m = originalBitmap.width * 0.1f
        val mY = originalBitmap.height * 0.1f
        corners[0] = PointF(m, mY)
        corners[1] = PointF(originalBitmap.width - m, mY)
        corners[2] = PointF(originalBitmap.width - m, originalBitmap.height - mY)
        corners[3] = PointF(m, originalBitmap.height - mY)

        // Если переданы углы от детектора — используем их
        val detectedCorners = intent.getFloatArrayExtra("corners")
        if (detectedCorners != null && detectedCorners.size == 8) {
            for (i in 0..3) {
                corners[i].x = detectedCorners[i * 2]
                corners[i].y = detectedCorners[i * 2 + 1]
            }
        }

        imageView.post {
            updateDisplay()
        }

        // Обработчики кнопок
        findViewById<Button>(R.id.applyButton).setOnClickListener {
            val result = FloatArray(8)
            for (i in 0..3) {
                result[i * 2] = corners[i].x
                result[i * 2 + 1] = corners[i].y
            }
            intent.putExtra("corners", result)
            setResult(RESULT_OK, intent)
            finish()
        }

        findViewById<Button>(R.id.cancelButton).setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        findViewById<Button>(R.id.originalButton).setOnClickListener {
            val m2 = originalBitmap.width * 0.1f
            val mY2 = originalBitmap.height * 0.1f
            corners[0] = PointF(m2, mY2)
            corners[1] = PointF(originalBitmap.width - m2, mY2)
            corners[2] = PointF(originalBitmap.width - m2, originalBitmap.height - mY2)
            corners[3] = PointF(m2, originalBitmap.height - mY2)
            updateDisplay()
        }

        findViewById<Button>(R.id.manualButton).setOnClickListener {
            updateDisplay()
        }

        // Обработка касаний
        imageView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    activeCorner = findNearestCorner(event.x, event.y)
                    activeCorner >= 0
                }
                MotionEvent.ACTION_MOVE -> {
                    if (activeCorner >= 0) {
                        // Масштаб и смещение (как в updateDisplay)
                        val w = imageView.width
                        val h = imageView.height
                        val scaleX = w.toFloat() / originalBitmap.width
                        val scaleY = h.toFloat() / originalBitmap.height
                        val scale = minOf(scaleX, scaleY)
                        val offsetX = (w - originalBitmap.width * scale) / 2
                        val offsetY = (h - originalBitmap.height * scale) / 2

                        // Из экранных координат в координаты оригинала
                        val imgX = (event.x - offsetX) / scale
                        val imgY = (event.y - offsetY) / scale

                        corners[activeCorner].x = imgX.coerceIn(0f, originalBitmap.width.toFloat())
                        corners[activeCorner].y = imgY.coerceIn(0f, originalBitmap.height.toFloat())

                        updateDisplay()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    activeCorner = -1
                    true
                }
                else -> false
            }
        }
    }

    private fun findNearestCorner(x: Float, y: Float): Int {

        for (i in 0..3) {
            val dx = x - scaledCorners[i].x
            val dy = y - scaledCorners[i].y
            if (Math.sqrt((dx * dx + dy * dy).toDouble()) < cornerRadius) return i
        }

        return -1
    }

    private fun updateDisplay() {
        val w = imageView.width
        val h = imageView.height
        if (w <= 0 || h <= 0) return

        // Масштаб между оригиналом и ImageView
        val scaleX = w.toFloat() / originalBitmap.width
        val scaleY = h.toFloat() / originalBitmap.height
        val scale = minOf(scaleX, scaleY)
        val offsetX = (w - originalBitmap.width * scale) / 2
        val offsetY = (h - originalBitmap.height * scale) / 2

        for (i in 0..3){
            scaledCorners[i].x=offsetX+corners[i].x*scale
            scaledCorners[i].y=offsetY+corners[i].y*scale
        }

        displayBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(displayBitmap)

        // Рисуем оригинал с масштабом
        val destRect = android.graphics.Rect(
            offsetX.toInt(), offsetY.toInt(),
            (offsetX + originalBitmap.width * scale).toInt(),
            (offsetY + originalBitmap.height * scale).toInt()
        )
        canvas.drawBitmap(originalBitmap, null, destRect, null)

        // Линии и точки — с тем же масштабом
        val scaledCorners = corners.map {
            PointF(offsetX + it.x * scale, offsetY + it.y * scale)
        }

        for (i in 0..3) {
            val next = (i + 1) % 4
            canvas.drawLine(
                scaledCorners[i].x, scaledCorners[i].y,
                scaledCorners[next].x, scaledCorners[next].y, paint
            )
        }

        val colors = listOf(
            Color.parseColor("#FF4444"),
            Color.parseColor("#4488FF"),
            Color.parseColor("#44BB44"),
            Color.parseColor("#FFCC00")
        )
        for (i in 0..3) {
            cornerPaint.color = colors[i]
            canvas.drawCircle(scaledCorners[i].x, scaledCorners[i].y, 16f, cornerPaint)
        }

        imageView.setImageBitmap(displayBitmap)
    }

    private fun matToBitmap(mat: Mat): Bitmap {
        val bitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, bitmap)
        return bitmap
    }
}
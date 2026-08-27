package com.dragonsima.scandoc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class CropActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var originalBitmap: Bitmap
    private var displayBitmap: Bitmap? = null
    private var imagePath: String = ""

    private val corners = Array(4) { PointF() }
    private var activeCorner = -1
    private val cornerRadius = 50f
    private val scaledCorners = Array(4) { PointF() }

    private val isAutoDetecting = AtomicBoolean(false)

    private val borderPaint = Paint().apply {
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
    private val activeCornerPaint = Paint().apply {
        color = Color.parseColor("#FF5722")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val activeCornerRing = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crop)

        imageView = findViewById(R.id.cropImage)

        imagePath = intent.getStringExtra("imagePath") ?: run {
            Toast.makeText(this, "Не указан путь к изображению", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val mat = Imgcodecs.imread(imagePath)
        if (mat.empty()) {
            Toast.makeText(this, "Не удалось загрузить изображение", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        originalBitmap = matToBitmap(mat)
        mat.release()

        initCorners(savedInstanceState)

        imageView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateDisplay()
        }

        setupButtons()
        setupTouchListener()
    }

    private fun initCorners(savedInstanceState: Bundle?) {
        when {
            savedInstanceState?.containsKey("corners") == true -> {
                val saved = savedInstanceState.getFloatArray("corners")
                if (saved != null && saved.size == 8) {
                    for (i in 0..3) {
                        corners[i].x = saved[i * 2]
                        corners[i].y = saved[i * 2 + 1]
                    }
                } else {
                    initDefaultCorners()
                }
            }
            intent.hasExtra("corners") -> {
                val detected = intent.getFloatArrayExtra("corners")
                if (detected != null && detected.size == 8) {
                    for (i in 0..3) {
                        corners[i].x = detected[i * 2]
                        corners[i].y = detected[i * 2 + 1]
                    }
                    Log.d(TAG, "Получены углы из intent: ${corners.joinToString { "(${it.x.toInt()}, ${it.y.toInt()})" }}")
                } else {
                    initDefaultCorners()
                }
            }
            else -> initDefaultCorners()
        }
    }

    private fun initDefaultCorners() {
        val marginX = originalBitmap.width * 0.1f
        val marginY = originalBitmap.height * 0.1f
        corners[0] = PointF(marginX, marginY)
        corners[1] = PointF(originalBitmap.width - marginX, marginY)
        corners[2] = PointF(originalBitmap.width - marginX, originalBitmap.height - marginY)
        corners[3] = PointF(marginX, originalBitmap.height - marginY)
    }

    private fun setupButtons() {
        findViewById<Button>(R.id.applyButton).setOnClickListener {
            // Логируем исходные углы (как их расставил пользователь)
            logCorners("Исходные углы", corners)

            // Сортируем углы в правильном порядке (TL, TR, BR, BL)
            val ordered = orderCorners(corners)
            logCorners("Отсортированные углы", ordered)

            val result = FloatArray(8)
            for (i in 0..3) {
                result[i * 2] = ordered[i].x
                result[i * 2 + 1] = ordered[i].y
            }
            val resultIntent = intent.apply {
                putExtra("corners", result)
            }
            setResult(RESULT_OK, resultIntent)
            finish()
        }

        findViewById<Button>(R.id.cancelButton).setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        findViewById<Button>(R.id.originalButton).setOnClickListener {
            initDefaultCorners()
            updateDisplay()
        }

        findViewById<Button>(R.id.autoDetectButton).setOnClickListener {
            autoDetectCorners()
        }

        findViewById<Button>(R.id.manualButton).setOnClickListener {
            Toast.makeText(this, "Перетащите углы для ручной настройки", Toast.LENGTH_SHORT).show()
        }

        findViewById<ImageView>(R.id.backButton).setOnClickListener { finish() }
    }

    private fun setupTouchListener() {
        imageView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    activeCorner = findNearestCorner(event.x, event.y)
                    activeCorner >= 0
                }
                MotionEvent.ACTION_MOVE -> {
                    if (activeCorner >= 0) {
                        updateCornerPosition(event.x, event.y)
                        updateDisplay()
                    }
                    activeCorner >= 0
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    activeCorner = -1
                    updateDisplay()
                    true
                }
                else -> false
            }
        }
    }

    private fun updateCornerPosition(touchX: Float, touchY: Float) {
        val w = imageView.width
        val h = imageView.height
        if (w <= 0 || h <= 0) return

        val scaleX = w.toFloat() / originalBitmap.width
        val scaleY = h.toFloat() / originalBitmap.height
        val scale = minOf(scaleX, scaleY)
        val offsetX = (w - originalBitmap.width * scale) / 2f
        val offsetY = (h - originalBitmap.height * scale) / 2f

        val imgX = (touchX - offsetX) / scale
        val imgY = (touchY - offsetY) / scale

        corners[activeCorner].x = imgX.coerceIn(0f, originalBitmap.width.toFloat())
        corners[activeCorner].y = imgY.coerceIn(0f, originalBitmap.height.toFloat())
        Log.d(TAG, "Перемещён угол $activeCorner → (${corners[activeCorner].x.toInt()}, ${corners[activeCorner].y.toInt()})")
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

        val scaleX = w.toFloat() / originalBitmap.width
        val scaleY = h.toFloat() / originalBitmap.height
        val scale = minOf(scaleX, scaleY)
        val offsetX = (w - originalBitmap.width * scale) / 2f
        val offsetY = (h - originalBitmap.height * scale) / 2f

        for (i in 0..3) {
            scaledCorners[i].x = offsetX + corners[i].x * scale
            scaledCorners[i].y = offsetY + corners[i].y * scale
        }

        val newBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(newBitmap)

        val destRect = android.graphics.Rect(
            offsetX.toInt(), offsetY.toInt(),
            (offsetX + originalBitmap.width * scale).toInt(),
            (offsetY + originalBitmap.height * scale).toInt()
        )
        canvas.drawBitmap(originalBitmap, null, destRect, null)

        for (i in 0..3) {
            val next = (i + 1) % 4
            canvas.drawLine(
                scaledCorners[i].x, scaledCorners[i].y,
                scaledCorners[next].x, scaledCorners[next].y,
                borderPaint
            )
        }

        val baseRadius = 16f
        val activeRadius = 22f
        for (i in 0..3) {
            val x = scaledCorners[i].x
            val y = scaledCorners[i].y
            if (i == activeCorner) {
                canvas.drawCircle(x, y, activeRadius, activeCornerRing)
                canvas.drawCircle(x, y, activeRadius, activeCornerPaint)
            } else {
                canvas.drawCircle(x, y, baseRadius, cornerPaint)
            }
        }

        val oldBitmap = displayBitmap
        imageView.setImageBitmap(newBitmap)
        oldBitmap?.recycle()
        displayBitmap = newBitmap
    }

    private fun autoDetectCorners() {
        if (isAutoDetecting.getAndSet(true)) return
        if (imagePath.isBlank()) {
            isAutoDetecting.set(false)
            return
        }

        lifecycleScope.launch {
            try {
                val detected = withContext(Dispatchers.IO) {
                    val mat = Imgcodecs.imread(imagePath)
                    if (mat.empty()) {
                        null
                    } else {
                        try {
                            DocumentDetector.findDocumentCorners(mat)
                        } finally {
                            mat.release()
                        }
                    }
                }

                if (isFinishing || isDestroyed) return@launch

                if (detected != null && detected.size == 4) {
                    val detectedPointF = Array(4) { i ->
                        PointF(detected[i].x.toFloat(), detected[i].y.toFloat())
                    }
                    logCorners("Автоопределённые углы", detectedPointF)

                    withContext(Dispatchers.Main) {
                        for (i in 0..3) {
                            corners[i].x = detected[i].x.toFloat()
                            corners[i].y = detected[i].y.toFloat()
                        }
                        updateDisplay()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@CropActivity, "Не удалось найти документ", Toast.LENGTH_SHORT).show()
                    }
                }
            } finally {
                isAutoDetecting.set(false)
            }
        }
    }

    // Добавьте этот метод для сортировки углов
    private fun orderCorners(points: Array<PointF>): Array<PointF> {
        // Сортируем по Y
        val sortedByY = points.sortedBy { it.y }
        val top = sortedByY.take(2).sortedBy { it.x }
        val bottom = sortedByY.takeLast(2).sortedBy { it.x }

        // TL, TR, BR, BL
        return arrayOf(top[0], top[1], bottom[1], bottom[0])
    }

    // Добавьте метод для логирования
    private fun logCorners(prefix: String, pts: Array<PointF>) {
        val sb = StringBuilder("$prefix: ")
        for (i in 0..3) {
            sb.append("(${pts[i].x.toInt()}, ${pts[i].y.toInt()}) ")
        }
        Log.d(TAG, sb.toString())
    }

    private fun matToBitmap(mat: Mat): Bitmap {
        val rgbaMat = Mat()
        Imgproc.cvtColor(mat, rgbaMat, Imgproc.COLOR_BGR2RGBA)
        val bitmap = Bitmap.createBitmap(rgbaMat.cols(), rgbaMat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgbaMat, bitmap)
        rgbaMat.release()
        return bitmap
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val saved = FloatArray(8)
        for (i in 0..3) {
            saved[i * 2] = corners[i].x
            saved[i * 2 + 1] = corners[i].y
        }
        outState.putFloatArray("corners", saved)
    }

    override fun onDestroy() {
        super.onDestroy()
        displayBitmap?.let { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        displayBitmap = null
        if (::originalBitmap.isInitialized && !originalBitmap.isRecycled) {
            originalBitmap.recycle()
        }
        imageView.setImageBitmap(null)
    }

    companion object {
        private const val TAG = "CropActivity"
    }
}
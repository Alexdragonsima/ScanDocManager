package com.dragonsima.scandoc

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
import java.util.concurrent.atomic.AtomicBoolean
import android.annotation.SuppressLint

class CropActivity : AppCompatActivity() {

    // ==================== UI ====================
    private lateinit var imageView: ImageView
    private lateinit var originalBitmap: Bitmap
    private var displayBitmap: Bitmap? = null
    private var imagePath: String = ""

    // ==================== Состояние углов ====================
    private val corners = Array(4) { PointF() }
    private val scaledCorners = Array(4) { PointF() }
    private var activeCorner = -1
    private val cornerRadius = 50f

    private val isAutoDetecting = AtomicBoolean(false)

    // ==================== Кэш ресурсов ====================
    private val density by lazy { resources.displayMetrics.density }
    private val magnifierPath = Path()

    // ==================== Paint ====================
    private val borderPaint = Paint().apply {
        color = COLOR_PRIMARY
        strokeWidth = 6f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val cornerPaint = Paint().apply {
        color = COLOR_PRIMARY
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val activeCornerPaint = Paint().apply {
        color = COLOR_ACTIVE
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val activeCornerRing = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }
    private val magnifierBorderPaint = Paint().apply {
        color = Color.WHITE
        strokeWidth = 5f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val magnifierShadowPaint = Paint().apply {
        color = COLOR_MAGNIFIER_SHADOW
        strokeWidth = 12f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val magnifierCrossPaint = Paint().apply {
        color = COLOR_MAGNIFIER_CROSS
        strokeWidth = 3f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    // ==================== Жизненный цикл ====================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crop)

        imageView = findViewById(R.id.cropImage)

        imagePath = intent.getStringExtra("imagePath") ?: run {
            Toast.makeText(this, getString(R.string.crop_no_image), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val mat = Imgcodecs.imread(imagePath)
        if (mat.empty()) {
            mat.release()
            Toast.makeText(this, getString(R.string.crop_load_error), Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        originalBitmap = matToBitmap(mat)
        mat.release()

        val hadCorners = intent.hasExtra("corners") ||
                (savedInstanceState?.containsKey("corners") == true)

        initCorners(savedInstanceState)

        imageView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateDisplay() }

        if (!hadCorners) {
            imageView.post { autoDetectCorners() }
        }

        setupButtons()
        setupTouchListener()
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
        displayBitmap?.let { if (!it.isRecycled) it.recycle() }
        displayBitmap = null

        if (::originalBitmap.isInitialized && !originalBitmap.isRecycled) {
            originalBitmap.recycle()
        }

        if (::imageView.isInitialized) {
            imageView.setImageBitmap(null)
        }
    }

    // ==================== Инициализация углов ====================

    private fun initCorners(savedInstanceState: Bundle?) {
        when {
            savedInstanceState?.containsKey("corners") == true -> {
                val saved = savedInstanceState.getFloatArray("corners")
                if (saved != null && saved.size == 8) {
                    for (i in 0..3) {
                        corners[i].x = saved[i * 2]
                        corners[i].y = saved[i * 2 + 1]
                    }
                } else initDefaultCorners()
            }
            intent.hasExtra("corners") -> {
                val detected = intent.getFloatArrayExtra("corners")
                if (detected != null && detected.size == 8) {
                    for (i in 0..3) {
                        corners[i].x = detected[i * 2]
                        corners[i].y = detected[i * 2 + 1]
                    }
                    if (BuildConfig.DEBUG) logCorners("Углы из intent", corners)
                } else initDefaultCorners()
            }
            else -> initDefaultCorners()
        }
    }

    private fun initDefaultCorners() {
        corners[0] = PointF(0f, 0f)
        corners[1] = PointF(originalBitmap.width.toFloat(), 0f)
        corners[2] = PointF(originalBitmap.width.toFloat(), originalBitmap.height.toFloat())
        corners[3] = PointF(0f, originalBitmap.height.toFloat())
    }

    // ==================== Кнопки ====================

    private fun setupButtons() {
        findViewById<Button>(R.id.applyButton).setOnClickListener {
            if (BuildConfig.DEBUG) logCorners("Исходные", corners)
            val ordered = orderCorners(corners)
            if (BuildConfig.DEBUG) logCorners("Отсортированные", ordered)

            val result = FloatArray(8)
            for (i in 0..3) {
                result[i * 2] = ordered[i].x
                result[i * 2 + 1] = ordered[i].y
            }
            setResult(RESULT_OK, Intent().putExtra("corners", result))
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
            Toast.makeText(this, getString(R.string.crop_manual_hint), Toast.LENGTH_SHORT).show()
        }

        findViewById<ImageView>(R.id.backButton).setOnClickListener { finish() }
    }

    // ==================== Touch ====================
    @SuppressLint("ClickableViewAccessibility")
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
    }

    private fun findNearestCorner(x: Float, y: Float): Int {
        val radiusSq = cornerRadius * cornerRadius
        for (i in 0..3) {
            val dx = x - scaledCorners[i].x
            val dy = y - scaledCorners[i].y
            if (dx * dx + dy * dy < radiusSq) return i
        }
        return -1
    }

    // ==================== Рендер ====================

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

        // Рамка
        for (i in 0..3) {
            val next = (i + 1) % 4
            canvas.drawLine(
                scaledCorners[i].x, scaledCorners[i].y,
                scaledCorners[next].x, scaledCorners[next].y,
                borderPaint
            )
        }

        // Углы
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

        // Лупа
        if (activeCorner >= 0) drawMagnifier(canvas, activeCorner, scale)

        val oldBitmap = displayBitmap
        imageView.setImageBitmap(newBitmap)
        oldBitmap?.recycle()
        displayBitmap = newBitmap
    }

    /**
     * Рисует круговую лупу с увеличением области вокруг активного угла.
     * Все координаты источника clamp'ятся в границы originalBitmap.
     */
    private fun drawMagnifier(canvas: Canvas, cornerIndex: Int, viewScale: Float) {
        val radiusPx = 60 * density
        val offsetPx = 90 * density
        val zoom = 3f

        val cornerViewX = scaledCorners[cornerIndex].x
        val cornerViewY = scaledCorners[cornerIndex].y

        // Позиция лупы: над углом, если место есть; иначе под ним
        val cx: Float
        val cy: Float
        if (cornerViewY - offsetPx - radiusPx > 0) {
            cx = cornerViewX
            cy = cornerViewY - offsetPx
        } else {
            cx = cornerViewX
            cy = cornerViewY + offsetPx
        }

        val padding = radiusPx + 8
        val clampedCx = cx.coerceIn(padding, canvas.width - padding)
        val clampedCy = cy.coerceIn(padding, canvas.height - padding)

        // Область источника в координатах оригинала
        val origCx = corners[cornerIndex].x
        val origCy = corners[cornerIndex].y
        val srcSize = (radiusPx * 2f) / viewScale / zoom

        // Источник — целые координаты, CLAMP внутрь границ bitmap
        val srcHalf = srcSize / 2f
        val srcLeft = (origCx - srcHalf).toInt().coerceIn(0, originalBitmap.width - 1)
        val srcTop = (origCy - srcHalf).toInt().coerceIn(0, originalBitmap.height - 1)
        val srcRight = (origCx + srcHalf).toInt().coerceIn(srcLeft + 1, originalBitmap.width)
        val srcBottom = (origCy + srcHalf).toInt().coerceIn(srcTop + 1, originalBitmap.height)

        val srcRect = android.graphics.Rect(srcLeft, srcTop, srcRight, srcBottom)
        val destRect = android.graphics.RectF(
            clampedCx - radiusPx,
            clampedCy - radiusPx,
            clampedCx + radiusPx,
            clampedCy + radiusPx
        )

        canvas.save()

        // Обрезка по кругу — переиспользуем Path
        magnifierPath.reset()
        magnifierPath.addCircle(clampedCx, clampedCy, radiusPx, Path.Direction.CW)
        canvas.clipPath(magnifierPath)

        canvas.drawBitmap(originalBitmap, srcRect, destRect, null)

        // Крестик по центру
        val crossSize = 14f * density
        canvas.drawLine(clampedCx - crossSize, clampedCy, clampedCx + crossSize, clampedCy, magnifierCrossPaint)
        canvas.drawLine(clampedCx, clampedCy - crossSize, clampedCx, clampedCy + crossSize, magnifierCrossPaint)

        canvas.restore()

        // Тень и обводка лупы
        canvas.drawCircle(clampedCx, clampedCy, radiusPx + 4f, magnifierShadowPaint)
        canvas.drawCircle(clampedCx, clampedCy, radiusPx, magnifierBorderPaint)
    }

    // ==================== Автодетект ====================

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
                    if (mat.empty()) null
                    else try { DocumentDetector.findDocumentCorners(mat) } finally { mat.release() }
                }

                if (isFinishing || isDestroyed) return@launch

                if (detected != null && detected.size == 4) {
                    if (BuildConfig.DEBUG) {
                        val pts = Array(4) { PointF(detected[it].x.toFloat(), detected[it].y.toFloat()) }
                        logCorners("Автоопределённые", pts)
                    }

                    for (i in 0..3) {
                        corners[i].x = detected[i].x.toFloat()
                            .coerceIn(0f, originalBitmap.width.toFloat())
                        corners[i].y = detected[i].y.toFloat()
                            .coerceIn(0f, originalBitmap.height.toFloat())
                    }
                    updateDisplay()
                } else {
                    Toast.makeText(
                        this@CropActivity,
                        getString(R.string.crop_detect_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } finally {
                isAutoDetecting.set(false)
            }
        }
    }

    // ==================== Утилиты ====================

    private fun orderCorners(points: Array<PointF>): Array<PointF> {
        val sortedByY = points.sortedBy { it.y }
        val top = sortedByY.take(2).sortedBy { it.x }
        val bottom = sortedByY.takeLast(2).sortedBy { it.x }
        return arrayOf(top[0], top[1], bottom[1], bottom[0])
    }

    private fun logCorners(prefix: String, pts: Array<PointF>) {
        val sb = StringBuilder("$prefix: ")
        for (i in 0..3) sb.append("(${pts[i].x.toInt()}, ${pts[i].y.toInt()}) ")
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

    companion object {
        private const val TAG = "CropActivity"

        private val COLOR_PRIMARY = Color.parseColor("#4F46E5")
        private val COLOR_ACTIVE = Color.parseColor("#FF5722")
        private val COLOR_MAGNIFIER_SHADOW = Color.parseColor("#55000000")
        private val COLOR_MAGNIFIER_CROSS = Color.parseColor("#FF3B30")
    }
}
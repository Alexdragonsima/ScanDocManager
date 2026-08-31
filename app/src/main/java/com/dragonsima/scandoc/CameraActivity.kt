package com.dragonsima.scandoc

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import java.util.concurrent.CountDownLatch
import org.opencv.features.ORB
import org.opencv.features.DescriptorMatcher
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfPoint2f
import org.opencv.geometry.Geometry
import org.opencv.imgproc.Imgproc
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import com.google.android.material.button.MaterialButton
import org.opencv.core.CvType
import org.opencv.photo.Photo
import kotlin.time.Duration.Companion.milliseconds

class CameraActivity : AppCompatActivity() {

    // UI
    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var captureButton: Button
    private lateinit var retakeButton: Button
    private lateinit var cropButton: Button
    private lateinit var saveResultButton: Button
    private lateinit var actionsLayout: View
    private lateinit var resultImageView: ImageView
    private lateinit var backButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var hdrButton: MaterialButton

    @Volatile
    private var hdrEnabled = false

    @Volatile
    private var currentFilter: String = "bw"

    private lateinit var cameraProvider: ProcessCameraProvider
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val processingExecutor = Executors.newSingleThreadExecutor()
    private val saveExecutor = Executors.newSingleThreadExecutor()

    private val isProcessing = AtomicBoolean(false)
    private val analysisPaused = AtomicBoolean(false)
    private val lastDetectedCorners = AtomicReference<Array<Point>?>(null)
    private val lastImageWidth = AtomicLong(0)
    private val lastImageHeight = AtomicLong(0)

    private val originalImagePath = AtomicReference<String?>(null)
    private val processedImagePath = AtomicReference<String?>(null)
    private val currentResultBitmap = AtomicReference<Bitmap?>(null)

    private val lastFrameProcessedTime = AtomicLong(0L)
    private val minFrameIntervalMs = 300L

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) startCamera()
        else {
            Toast.makeText(this, "Нет разрешения на камеру", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private val cropResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val tempFilePath = result.data?.getStringExtra("tempFilePath")
        try {
            if (result.resultCode == RESULT_OK) {
                val corners = result.data?.getFloatArrayExtra("corners")
                val originalPath = originalImagePath.get()
                if (corners != null && corners.size == 8 && originalPath != null) {
                    val points = Array(4) { i ->
                        Point(corners[i * 2].toDouble(), corners[i * 2 + 1].toDouble())
                    }
                    processImageFromPath(originalPath, points)
                }
            }
        } finally {
            tempFilePath?.let { File(it).delete() }
        }
    }

    companion object {
        private const val TAG = "CameraActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)
        initViews()
        setupButtons()
        if (hasCameraPermission()) startCamera()
        else requestPermissionLauncher.launch(android.Manifest.permission.CAMERA)
    }

    override fun onDestroy() {
        super.onDestroy()
        shutdownExecutors()
        releaseResources()
        FileManager.cleanCache(this)
        if (::cameraProvider.isInitialized) {
            cameraProvider.unbindAll()
        }
    }

    private fun initViews() {
        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.overlayView)
        resultImageView = findViewById(R.id.resultImageView)
        captureButton = findViewById(R.id.captureButton)
        retakeButton = findViewById(R.id.retakeButton)
        cropButton = findViewById(R.id.cropButton)
        saveResultButton = findViewById(R.id.saveResultButton)
        actionsLayout = findViewById(R.id.actionsLayout)
        backButton = findViewById(R.id.backButton)
        progressBar = findViewById(R.id.progressBar)
        hdrButton = findViewById(R.id.hdrButton)

        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        actionsLayout.visibility = View.GONE
        retakeButton.visibility = View.GONE
        cropButton.visibility = View.GONE
        saveResultButton.visibility = View.GONE
        progressBar.visibility = View.GONE
    }

    private fun setupButtons() {
        backButton.setOnClickListener { finish() }
        saveResultButton.setOnClickListener { saveDocument() }
        retakeButton.setOnClickListener { retakePicture() }
        cropButton.setOnClickListener { cropDocument() }

        hdrButton.setOnClickListener {
            hdrEnabled = !hdrEnabled
            updateHdrButtonState()
        }

        captureButton.setOnClickListener {
            if (hdrEnabled) captureHDRAndProcess()
            else takePicture()
        }

        hdrEnabled = false
        updateHdrButtonState()
    }

    private fun updateHdrButtonState() {
        if (hdrEnabled) {
            hdrButton.isActivated = true
            hdrButton.text = "HDR: ВКЛ"
            hdrButton.setBackgroundColor(android.graphics.Color.parseColor("#4F46E5"))
        } else {
            hdrButton.isActivated = false
            hdrButton.text = "HDR: ВЫКЛ"
            hdrButton.setBackgroundColor(android.graphics.Color.parseColor("#9E9E9E"))
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    android.util.Size(640, 480),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                )
                            )
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                            .build()
                    )
                    .build()

                imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    // Если анализ приостановлен, просто закрываем кадр
                    if (analysisPaused.get()) {
                        imageProxy.close()
                        return@setAnalyzer
                    }

                    val now = System.currentTimeMillis()
                    if (now - lastFrameProcessedTime.get() < minFrameIntervalMs) {
                        imageProxy.close()
                        return@setAnalyzer
                    }
                    lastFrameProcessedTime.set(now)

                    if (isProcessing.compareAndSet(false, true)) {
                        try {
                            processFrame(imageProxy)
                        } catch (e: Exception) {
                            Log.e(TAG, "Frame processing error", e)
                        } finally {
                            isProcessing.set(false)
                        }
                    } else {
                        imageProxy.close()
                    }
                }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()

                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis,
                    imageCapture
                )
                Log.d(TAG, "Camera started")
            } catch (e: Exception) {
                Log.e(TAG, "Camera start error", e)
                runOnUiThread {
                    Toast.makeText(this, "Не удалось запустить камеру", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePicture() {
        val capture = imageCapture ?: run {
            Toast.makeText(this, "Камера не готова", Toast.LENGTH_SHORT).show()
            return
        }

        progressBar.visibility = View.VISIBLE
        captureButton.isEnabled = false

        val photoFile = File(cacheDir, "captured_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    originalImagePath.set(photoFile.absolutePath)
                    processingExecutor.execute {
                        try {
                            val detected = lastDetectedCorners.get()
                            val imgWidth = lastImageWidth.get().toInt()
                            val imgHeight = lastImageHeight.get().toInt()
                            val processedBitmap = processCapturedImage(
                                photoFile.absolutePath,
                                detected,
                                imgWidth,
                                imgHeight
                            )

                            val currentOriginal = originalImagePath.get()
                            runOnUiThread {
                                progressBar.visibility = View.GONE
                                captureButton.isEnabled = true
                                if (processedBitmap != null && currentOriginal == photoFile.absolutePath) {
                                    setResultBitmap(processedBitmap)
                                    showResult()
                                } else {
                                    processedBitmap?.recycle()
                                    if (processedBitmap == null) {
                                        Toast.makeText(
                                            this@CameraActivity,
                                            "Не удалось обработать фото",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        Toast.makeText(
                                            this@CameraActivity,
                                            "Результат устарел",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Processing error", e)
                            runOnUiThread {
                                progressBar.visibility = View.GONE
                                captureButton.isEnabled = true
                                Toast.makeText(
                                    this@CameraActivity,
                                    "Ошибка обработки",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    runOnUiThread {
                        progressBar.visibility = View.GONE
                        captureButton.isEnabled = true
                        Toast.makeText(
                            this@CameraActivity,
                            "Ошибка съёмки: ${exception.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    Log.e(TAG, "Capture error", exception)
                }
            }
        )
    }

    private fun processFrame(imageProxy: ImageProxy) {
        val bitmap = imageProxy.toBitmap()
        if (bitmap == null) {
            imageProxy.close()
            return
        }

        val rotation = imageProxy.imageInfo.rotationDegrees
        val rotatedBitmap = if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                .also { bitmap.recycle() }
        } else bitmap

        val mat = Mat()
        try {
            Utils.bitmapToMat(rotatedBitmap, mat)
            if (mat.empty()) return

            val corners = DocumentDetector.findDocumentCorners(mat)
            if (corners != null && corners.size == 4 && previewView.width > 0 && previewView.height > 0) {
                val cornersCopy = corners.map { Point(it.x, it.y) }.toTypedArray()
                lastDetectedCorners.set(cornersCopy)
                lastImageWidth.set(rotatedBitmap.width.toLong())
                lastImageHeight.set(rotatedBitmap.height.toLong())

                val screenCorners = transformCornersToView(
                    cornersCopy,
                    previewView.width,
                    previewView.height,
                    rotatedBitmap.width,
                    rotatedBitmap.height
                )
                runOnUiThread { overlay.setCorners(screenCorners) }
            } else {
                runOnUiThread { overlay.setCorners(null) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "processFrame error", e)
        } finally {
            mat.release()
            rotatedBitmap.recycle()
            imageProxy.close()
        }
    }

    private fun processCapturedImage(
        imagePath: String,
        detectedCorners: Array<Point>?,
        imgWidth: Int,
        imgHeight: Int
    ): Bitmap? {
        if (!File(imagePath).exists()) {
            Log.w(TAG, "File not found: $imagePath")
            return null
        }

        val image = Imgcodecs.imread(imagePath)
        if (image.empty()) {
            image.release()
            return null
        }

        var resultBitmap: Bitmap? = null

        try {
            val processedMat =
                if (detectedCorners != null && detectedCorners.size == 4 && imgWidth > 0 && imgHeight > 0) {
                    val scaleX = image.cols().toDouble() / imgWidth
                    val scaleY = image.rows().toDouble() / imgHeight
                    val scaledCorners = Array(4) { i ->
                        Point(detectedCorners[i].x * scaleX, detectedCorners[i].y * scaleY)
                    }
                    processImageWithCorners(image, scaledCorners)
                } else {
                    val corners = DocumentDetector.findDocumentCorners(image)
                    if (corners != null && corners.size == 4) {
                        processImageWithCorners(image, corners)
                    } else {
                        DocumentDetector.enhanceScan(image, currentFilter)
                    }
                }

            if (processedMat != null) {
                val processedFile = File(cacheDir, "processed_${System.currentTimeMillis()}.jpg")
                if (Imgcodecs.imwrite(processedFile.absolutePath, processedMat)) {
                    processedImagePath.set(processedFile.absolutePath)
                    resultBitmap = FileManager.matToBitmap(processedMat)
                }
                processedMat.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "processCapturedImage error", e)
        } finally {
            image.release()
        }

        return resultBitmap
    }

    // ===================== ИСПРАВЛЕННАЯ ОБРАБОТКА С УГЛАМИ =====================
    private fun processImageWithCorners(image: Mat, corners: Array<Point>): Mat? {
        var warped: Mat? = null
        var enhanced: Mat? = null

        try {
            warped = DocumentDetector.warpDocument(image, corners)
            if (warped == null) {
                Log.e(TAG, "warpDocument вернул null")
                return null
            }

            if (currentFilter == "shadow") {
                // 1. Бинаризация (1 канал)
                val bw = DocumentDetector.enhanceScan(warped, "bw")
                // 2. Преобразуем в BGR (3 канала) для removeShadows
                val bwBgr = Mat()
                Imgproc.cvtColor(bw, bwBgr, Imgproc.COLOR_GRAY2BGR)
                // 3. Удаляем тени
                val noShadows = DocumentDetector.removeShadows(bwBgr)
                // 4. Обрезаем поля
                val cropped = DocumentDetector.autoCropMargins(noShadows)
                // Освобождаем промежуточные
                bw.release()
                bwBgr.release()
                noShadows.release()
                enhanced = cropped
            } else {
                // Обычные фильтры
                enhanced = DocumentDetector.enhanceScan(warped, currentFilter)
            }

            return enhanced
        } finally {
            warped?.release()
        }
    }

    // ---------------------- ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ UI ----------------------

    private fun showResult() {
        previewView.visibility = View.GONE
        overlay.visibility = View.GONE
        captureButton.visibility = View.GONE
        resultImageView.visibility = View.VISIBLE
        resultImageView.setImageBitmap(currentResultBitmap.get())
        actionsLayout.visibility = View.VISIBLE
        retakeButton.visibility = View.VISIBLE
        cropButton.visibility = View.VISIBLE
        saveResultButton.visibility = View.VISIBLE
        hdrButton.visibility = View.GONE
    }

    private fun setResultBitmap(newBitmap: Bitmap?) {
        val old = currentResultBitmap.getAndSet(null)
        if (old != null && !old.isRecycled) {
            old.recycle()
        }
        currentResultBitmap.set(newBitmap)
    }

    private fun deleteFileIfExists(path: String?) {
        path?.let { File(it).delete() }
    }

    private fun retakePicture() {
        deleteFileIfExists(originalImagePath.get())
        deleteFileIfExists(processedImagePath.get())
        originalImagePath.set(null)
        processedImagePath.set(null)
        setResultBitmap(null)

        lastDetectedCorners.set(null)
        lastImageWidth.set(0)
        lastImageHeight.set(0)

        previewView.visibility = View.VISIBLE
        overlay.visibility = View.VISIBLE
        captureButton.visibility = View.VISIBLE
        resultImageView.visibility = View.GONE
        actionsLayout.visibility = View.GONE
        retakeButton.visibility = View.GONE
        cropButton.visibility = View.GONE
        saveResultButton.visibility = View.GONE
        hdrButton.visibility = View.VISIBLE
        overlay.setCorners(null)
    }

    private fun saveDocument() {
        val path = processedImagePath.get()
        if (path == null || !File(path).exists()) {
            Toast.makeText(this, "Нет изображения для сохранения", Toast.LENGTH_SHORT).show()
            return
        }

        progressBar.visibility = View.VISIBLE
        saveResultButton.isEnabled = false

        saveExecutor.execute {
            val image = Imgcodecs.imread(path)
            if (image.empty()) {
                image.release()
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    saveResultButton.isEnabled = true
                    Toast.makeText(this, "Ошибка чтения изображения", Toast.LENGTH_SHORT).show()
                }
                return@execute
            }

            try {
                val pdfFile = FileManager.saveToPdf(this, image)
                val resultIntent = Intent().apply {
                    putExtra("savedPdfPath", pdfFile.absolutePath)
                    putExtra("savedPdfName", pdfFile.name)
                }
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    saveResultButton.isEnabled = true
                    setResult(RESULT_OK, resultIntent)
                    Toast.makeText(this, "✅ Сохранено: ${pdfFile.name}", Toast.LENGTH_LONG).show()
                    finish()
                }
                deleteFileIfExists(path)
                processedImagePath.set(null)
            } catch (e: Exception) {
                Log.e(TAG, "Save error", e)
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    saveResultButton.isEnabled = true
                    Toast.makeText(this, "Ошибка сохранения: ${e.message}", Toast.LENGTH_LONG)
                        .show()
                }
            } finally {
                image.release()
            }
        }
    }

    private fun cropDocument() {
        val originalPath = originalImagePath.get()
        if (originalPath == null || !File(originalPath).exists()) {
            Toast.makeText(this, "Нет изображения для обрезки", Toast.LENGTH_SHORT).show()
            return
        }

        val tempFile = File(cacheDir, "crop_temp_${System.currentTimeMillis()}.jpg")
        try {
            File(originalPath).copyTo(tempFile, overwrite = true)
            if (!tempFile.exists() || tempFile.length() == 0L) {
                Toast.makeText(this, "Ошибка копирования", Toast.LENGTH_SHORT).show()
                return
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка копирования", Toast.LENGTH_SHORT).show()
            return
        }

        val cropIntent = Intent(this, CropActivity::class.java).apply {
            putExtra("imagePath", tempFile.absolutePath)
            putExtra("tempFilePath", tempFile.absolutePath)
        }
        cropResultLauncher.launch(cropIntent)
    }

    private fun processImageFromPath(originalPath: String, corners: Array<Point>) {
        processingExecutor.execute {
            try {
                if (!File(originalPath).exists()) {
                    Log.w(TAG, "Original file not found")
                    runOnUiThread {
                        Toast.makeText(this, "Файл не найден", Toast.LENGTH_SHORT).show()
                    }
                    return@execute
                }

                val image = Imgcodecs.imread(originalPath)
                if (image.empty()) {
                    image.release()
                    runOnUiThread {
                        Toast.makeText(this, "Ошибка чтения", Toast.LENGTH_SHORT).show()
                    }
                    return@execute
                }

                val processedMat = processImageWithCorners(image, corners)
                image.release()

                if (processedMat != null) {
                    val processedFile = File(cacheDir, "processed_${System.currentTimeMillis()}.jpg")
                    if (Imgcodecs.imwrite(processedFile.absolutePath, processedMat)) {
                        val bitmap = FileManager.matToBitmap(processedMat)
                        if (bitmap != null) {
                            processedImagePath.set(processedFile.absolutePath)
                            if (originalImagePath.get() == originalPath) {
                                runOnUiThread {
                                    setResultBitmap(bitmap)
                                    showResult()
                                }
                            } else {
                                bitmap.recycle()
                                processedFile.delete()
                            }
                        } else {
                            processedFile.delete()
                            runOnUiThread {
                                Toast.makeText(this, "Ошибка создания Bitmap", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        runOnUiThread {
                            Toast.makeText(this, "Ошибка сохранения", Toast.LENGTH_SHORT).show()
                        }
                    }
                    processedMat.release()
                } else {
                    runOnUiThread {
                        Toast.makeText(this, "Ошибка обработки", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "processImageFromPath error", e)
                runOnUiThread { Toast.makeText(this, "Ошибка", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun transformCornersToView(
        corners: Array<Point>?,
        viewWidth: Int,
        viewHeight: Int,
        imageWidth: Int,
        imageHeight: Int
    ): Array<Point>? {
        if (corners == null || corners.size != 4) return null
        if (viewWidth <= 0 || viewHeight <= 0) return null

        val scaleX = viewWidth.toFloat() / imageWidth
        val scaleY = viewHeight.toFloat() / imageHeight
        val scale = max(scaleX, scaleY)
        val offsetX = (viewWidth - imageWidth * scale) / 2f
        val offsetY = (viewHeight - imageHeight * scale) / 2f

        return Array(4) { i ->
            Point(corners[i].x * scale + offsetX, corners[i].y * scale + offsetY)
        }
    }

    private fun shutdownExecutors() {
        analysisExecutor.shutdown()
        processingExecutor.shutdown()
        saveExecutor.shutdown()

        try {
            if (!analysisExecutor.awaitTermination(1, TimeUnit.SECONDS)) analysisExecutor.shutdownNow()
            if (!processingExecutor.awaitTermination(2, TimeUnit.SECONDS)) processingExecutor.shutdownNow()
            if (!saveExecutor.awaitTermination(2, TimeUnit.SECONDS)) saveExecutor.shutdownNow()
        } catch (e: InterruptedException) {
            analysisExecutor.shutdownNow()
            processingExecutor.shutdownNow()
            saveExecutor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    private fun releaseResources() {
        currentResultBitmap.getAndSet(null)?.recycle()
        originalImagePath.set(null)
        processedImagePath.set(null)
        DocumentDetector.release()
    }

    // ===================== HDR =====================

    private suspend fun captureHDRFrames(): List<File> = withContext(Dispatchers.IO) {
        val exposures = listOf(-2, 0, 2)
        val files = mutableListOf<File>()
        val cameraControl = camera?.cameraControl ?: return@withContext emptyList()

        val exposureRange = camera?.cameraInfo?.exposureState?.exposureCompensationRange
        val minEv = exposureRange?.lower ?: -2
        val maxEv = exposureRange?.upper ?: 2
        // Фильтруем только допустимые значения
        val safeExposures = exposures.filter { it in minEv..maxEv }

        if (safeExposures.size < 3) {
            Log.w(TAG, "Недостаточно доступных значений EV для HDR")
            return@withContext emptyList()
        }

        try {
            for (ev in safeExposures) {
                cameraControl.setExposureCompensationIndex(ev)
                delay(250.milliseconds) // даём камере стабилизироваться

                val file = File(cacheDir, "hdr_${System.currentTimeMillis()}_$ev.jpg")
                val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()
                val latch = CountDownLatch(1)
                var success = false

                imageCapture?.takePicture(
                    outputOptions,
                    ContextCompat.getMainExecutor(this@CameraActivity),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            success = true
                            latch.countDown()
                        }

                        override fun onError(exception: ImageCaptureException) {
                            Log.e(TAG, "HDR capture error for EV $ev", exception)
                            latch.countDown()
                        }
                    }
                )

                if (latch.await(5, TimeUnit.SECONDS)) {
                    if (success) {
                        files.add(file)
                    } else {
                        file.delete()
                        break
                    }
                } else {
                    Log.e(TAG, "Timeout waiting for HDR frame EV $ev")
                    file.delete()
                    break
                }
            }
        } finally {
            // Сбрасываем экспозицию
            try {
                cameraControl.setExposureCompensationIndex(0)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reset exposure compensation", e)
            }
        }

        files
    }

    // Улучшенное выравнивание с полным освобождением ресурсов
    private fun alignImages(reference: Mat, target: Mat): Mat {
        if (reference.empty() || target.empty()) return target.clone()

        var refGray: Mat? = null
        var tgtGray: Mat? = null
        var keypointsRef: MatOfKeyPoint? = null
        var keypointsTarget: MatOfKeyPoint? = null
        var descriptorsRef: Mat? = null
        var descriptorsTarget: Mat? = null
        var matches: MatOfDMatch? = null
        var srcPoints: MatOfPoint2f? = null
        var dstPoints: MatOfPoint2f? = null
        var homography: Mat? = null
        var aligned: Mat? = null
        var orb: ORB? = null
        var matcher: DescriptorMatcher? = null

        return try {
            orb = ORB.create(500)
            matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)

            refGray = Mat()
            tgtGray = Mat()
            Imgproc.cvtColor(reference, refGray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.cvtColor(target, tgtGray, Imgproc.COLOR_BGR2GRAY)

            keypointsRef = MatOfKeyPoint()
            keypointsTarget = MatOfKeyPoint()
            descriptorsRef = Mat()
            descriptorsTarget = Mat()

            orb.detectAndCompute(refGray, Mat(), keypointsRef, descriptorsRef)
            orb.detectAndCompute(tgtGray, Mat(), keypointsTarget, descriptorsTarget)

            if (keypointsRef.total() < 4 || keypointsTarget.total() < 4) {
                return target.clone()
            }

            matches = MatOfDMatch()
            matcher.match(descriptorsRef, descriptorsTarget, matches)

            val list = matches.toList().sortedBy { it.distance }.take(30)
            if (list.size < 4) return target.clone()

            val refKpArray = keypointsRef.toArray()
            val tgtKpArray = keypointsTarget.toArray()

            val srcPts = list.map { tgtKpArray[it.trainIdx].pt }.toTypedArray()
            val dstPts = list.map { refKpArray[it.queryIdx].pt }.toTypedArray()

            srcPoints = MatOfPoint2f(*srcPts)
            dstPoints = MatOfPoint2f(*dstPts)

            homography = Geometry.findHomography(srcPoints, dstPoints, Geometry.RANSAC, 5.0)
            if (homography.empty()) return target.clone()

            aligned = Mat()
            Imgproc.warpPerspective(target, aligned, homography, reference.size())
            aligned
        } catch (e: Exception) {
            Log.e(TAG, "alignImages error", e)
            target.clone()
        } finally {
            refGray?.release()
            tgtGray?.release()
            keypointsRef?.release()
            keypointsTarget?.release()
            descriptorsRef?.release()
            descriptorsTarget?.release()
            matches?.release()
            srcPoints?.release()
            dstPoints?.release()
            homography?.release()
            // ORB и DescriptorMatcher не имеют release(), вызываем delete()
            orb?.clear()
            matcher?.clear()
        }
    }

    // Слияние HDR с конвертацией в 8-битный формат
    private fun mergeHDR(images: List<Mat>): Mat {
        require(images.size >= 3) { "Нужно минимум 3 кадра" }
        val merger = Photo.createMergeMertens()
        val resultFloat = Mat()
        try {
            merger.process(images, resultFloat)
            // Конвертируем float (0..1) в 8-битный BGR (0..255)
            val result8u = Mat()
            resultFloat.convertTo(result8u, CvType.CV_8UC3, 255.0)
            return result8u
        } finally {
            merger.clear()
            resultFloat.release()
        }
    }

    private fun captureHDRAndProcess() {
        progressBar.visibility = View.VISIBLE
        hdrButton.isEnabled = false
        captureButton.isEnabled = false

        processingExecutor.execute {
            val frames = mutableListOf<File>()
            val mats = mutableListOf<Mat>()
            val aligned = mutableListOf<Mat>()
            var merged8u: Mat? = null
            var processed: Mat? = null

            try {
                // Съёмка кадров (с повторными попытками)
                analysisPaused.set(true)
                frames.addAll(runBlocking { captureHDRFrames() })
                if (frames.size < 3) {
                    runOnUiThread {
                        progressBar.visibility = View.GONE
                        hdrButton.isEnabled = true
                        captureButton.isEnabled = true
                        Toast.makeText(
                            this,
                            "Ошибка HDR: не удалось снять кадры",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@execute
                }

                // Загружаем Mat
                for (file in frames) {
                    val mat = Imgcodecs.imread(file.absolutePath)
                    if (!mat.empty()) mats.add(mat)
                }
                if (mats.size < 3) {
                    runOnUiThread {
                        progressBar.visibility = View.GONE
                        hdrButton.isEnabled = true
                        captureButton.isEnabled = true
                        Toast.makeText(this, "Ошибка HDR: недостаточно кадров", Toast.LENGTH_SHORT).show()
                    }
                    return@execute
                }

                // Выравнивание (используем средний кадр как референс)
                val reference = mats[1]
                for (i in mats.indices) {
                    if (i == 1) {
                        aligned.add(reference.clone())
                    } else {
                        val alignedMat = alignImages(reference, mats[i])
                        aligned.add(alignedMat)
                    }
                }

                // Слияние
                val mergedFloat = mergeHDR(aligned)
                merged8u = mergedFloat // уже 8-битный

                // Детекция документа и обработка
                val corners = DocumentDetector.findDocumentCorners(merged8u)
                processed = if (corners != null && corners.size == 4) {
                    processImageWithCorners(merged8u, corners)
                } else {
                    DocumentDetector.enhanceScan(merged8u, currentFilter)
                }

                if (processed == null) {
                    runOnUiThread {
                        progressBar.visibility = View.GONE
                        hdrButton.isEnabled = true
                        captureButton.isEnabled = true
                        Toast.makeText(this, "Ошибка обработки HDR", Toast.LENGTH_SHORT).show()
                    }
                    return@execute
                }

                // Сохраняем результат
                val processedFile = File(cacheDir, "processed_hdr_${System.currentTimeMillis()}.jpg")
                if (Imgcodecs.imwrite(processedFile.absolutePath, processed)) {
                    processedImagePath.set(processedFile.absolutePath)
                    val bitmap = FileManager.matToBitmap(processed)
                    runOnUiThread {
                        progressBar.visibility = View.GONE
                        hdrButton.isEnabled = true
                        captureButton.isEnabled = true
                        if (bitmap != null) {
                            setResultBitmap(bitmap)
                            showResult()
                        } else {
                            Toast.makeText(this, "Ошибка создания Bitmap", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    runOnUiThread {
                        progressBar.visibility = View.GONE
                        hdrButton.isEnabled = true
                        captureButton.isEnabled = true
                        Toast.makeText(this, "Ошибка обработки HDR", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "HDR error", e)
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    hdrButton.isEnabled = true
                    captureButton.isEnabled = true
                    Toast.makeText(this, "Ошибка HDR: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                analysisPaused.set(false)

                mats.forEach { it.release() }
                aligned.forEach { it.release() }
                merged8u?.release()
                processed?.release()
                frames.forEach { it.delete() }
            }
        }
    }
}
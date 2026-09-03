package com.dragonsima.scandoc

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.material.button.MaterialButton
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.features.DescriptorMatcher
import org.opencv.features.ORB
import org.opencv.geometry.Geometry
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import java.io.File
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds
import org.opencv.core.Mat
class CameraActivity : AppCompatActivity() {

    // ==================== UI ====================
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
    private lateinit var aiButton: MaterialButton

    // ==================== Состояние ====================
    @Volatile
    private var hdrEnabled = false

    @Volatile
    private var currentFilter: String = "color"
    private val filterCache = mutableMapOf<String, Mat>()

    private lateinit var cameraProvider: ProcessCameraProvider
    private var baseMat: Mat? = null
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

    // ==================== Launchers ====================
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

    private val mlKitScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val pages = scanResult?.pages
            if (!pages.isNullOrEmpty()) {
                val uri = pages[0].imageUri
                handleMlKitResult(uri)
            }
        }
    }

    // ==================== Жизненный цикл ====================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)
        setupFilterButtons()
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
        baseMat?.release()
        filterCache.values.forEach { it.release() }
        filterCache.clear()
        DocumentDetector.release()
    }

    // ==================== Инициализация UI ====================
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
        aiButton = findViewById(R.id.aiButton)

        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        findViewById<View>(R.id.filterPanel).visibility = View.GONE
        findViewById<View>(R.id.actionsLayout).visibility = View.GONE
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

        aiButton.setOnClickListener {
            if (isMlKitAvailable()) {
                startMlKitScanner()
            } else {
                Toast.makeText(this, "ML Kit недоступен. Используется OpenCV.", Toast.LENGTH_SHORT).show()
            }
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
            hdrButton.text = "HDR: ВКЛ"
            hdrButton.setBackgroundColor(android.graphics.Color.parseColor("#4F46E5"))
        } else {
            hdrButton.text = "HDR: ВЫКЛ"
            hdrButton.setBackgroundColor(android.graphics.Color.parseColor("#9E9E9E"))
        }
    }

    // ==================== Утилиты ====================
    private fun isMlKitAvailable(): Boolean {
        return GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    // ==================== Камера ====================
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

        val fullMat = Mat()
        try {
            Utils.bitmapToMat(rotatedBitmap, fullMat)
            if (fullMat.empty()) return

            // Классическая детекция OpenCV
            val corners = DocumentDetector.findDocumentCorners(fullMat)

            Log.d(TAG, "Corners detected: ${corners?.joinToString { "(${it.x.toInt()}, ${it.y.toInt()})" }}")

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
            fullMat.release()
            rotatedBitmap.recycle()
            imageProxy.close()
        }
    }

    // ==================== Съёмка ====================
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
                            processCapturedImage(
                                photoFile.absolutePath,
                                detected,
                                imgWidth,
                                imgHeight
                            )
                            // Вся логика показа теперь внутри processCapturedImage → onDocumentCaptured
                        } catch (e: Exception) {
                            Log.e(TAG, "Processing error", e)
                            runOnUiThread {
                                progressBar.visibility = View.GONE
                                captureButton.isEnabled = true
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

    // ==================== Обработка изображений ====================
    // Возвращает сырое выпрямленное и обрезанное изображение (без фильтров)
    private fun getRawDocumentMat(image: Mat, corners: Array<Point>? = null): Mat? {
        val finalCorners = corners ?: DocumentDetector.findDocumentCorners(image)
        if (finalCorners == null || finalCorners.size != 4) return null
        val warped = DocumentDetector.warpDocument(image, finalCorners) ?: return null
        val cropped = DocumentDetector.autoCropMargins(warped)
        warped.release()
        return cropped
    }

    // Новая версия processCapturedImage – сохраняет сырое изображение и передаёт в onDocumentCaptured
    private fun processCapturedImage(
        imagePath: String,
        detectedCorners: Array<Point>?,
        imgWidth: Int,
        imgHeight: Int
    ) {
        if (!File(imagePath).exists()) {
            runOnUiThread {
                progressBar.visibility = View.GONE
                captureButton.isEnabled = true
                Toast.makeText(this, "Файл не найден", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val image = Imgcodecs.imread(imagePath)
        if (image.empty()) {
            image.release()
            runOnUiThread {
                progressBar.visibility = View.GONE
                captureButton.isEnabled = true
                Toast.makeText(this, "Ошибка чтения", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val rawMat = if (detectedCorners != null && detectedCorners.size == 4 && imgWidth > 0 && imgHeight > 0) {
            val scaleX = image.cols().toDouble() / imgWidth
            val scaleY = image.rows().toDouble() / imgHeight
            val scaledCorners = Array(4) { i ->
                Point(detectedCorners[i].x * scaleX, detectedCorners[i].y * scaleY)
            }
            getRawDocumentMat(image, scaledCorners)
        } else {
            getRawDocumentMat(image, null)
        }
        image.release()

        if (rawMat == null) {
            runOnUiThread {
                progressBar.visibility = View.GONE
                captureButton.isEnabled = true
                Toast.makeText(this, "Не удалось найти документ", Toast.LENGTH_SHORT).show()
            }
            return
        }

        // Сохраняем сырое изображение для возможной обрезки (cropActivity)
        val rawFile = File(cacheDir, "raw_${System.currentTimeMillis()}.jpg")
        if (!Imgcodecs.imwrite(rawFile.absolutePath, rawMat)) {
            rawMat.release()
            runOnUiThread {
                progressBar.visibility = View.GONE
                captureButton.isEnabled = true
                Toast.makeText(this, "Ошибка сохранения", Toast.LENGTH_SHORT).show()
            }
            return
        }
        originalImagePath.set(rawFile.absolutePath)

        // Передаём в onDocumentCaptured для применения фильтра и отображения
        runOnUiThread {
            onDocumentCaptured(rawMat) // внутри клонирует в baseMat
            progressBar.visibility = View.GONE
            captureButton.isEnabled = true
        }
    }

    // ==================== UI: результат ====================
    private fun setResultBitmap(newBitmap: Bitmap?) {
        val old = currentResultBitmap.getAndSet(null)
        if (old != null && !old.isRecycled) {
            old.recycle()
        }
        currentResultBitmap.set(newBitmap)
    }

    private fun retakePicture() {
        deleteFileIfExists(originalImagePath.get())
        deleteFileIfExists(processedImagePath.get()) // если используется
        originalImagePath.set(null)
        processedImagePath.set(null)
        setResultBitmap(null)

        // Очищаем кеш фильтров
        filterCache.values.forEach { it.release() }
        filterCache.clear()
        baseMat?.release()
        baseMat = null

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
        aiButton.visibility = View.VISIBLE
        overlay.setCorners(null)
    }

    private fun saveDocument() {
        val mat = baseMat ?: run {
            Toast.makeText(this, "Нет изображения", Toast.LENGTH_SHORT).show()
            return
        }
        val finalMat = filterCache[currentFilter] ?: mat
        if (finalMat.empty()) {
            Toast.makeText(this, "Изображение пустое", Toast.LENGTH_SHORT).show()
            return
        }

        progressBar.visibility = View.VISIBLE
        saveResultButton.isEnabled = false

        saveExecutor.execute {
            val matToSave = finalMat.clone()
            try {
                val pdfFile = FileManager.saveToPdf(this, matToSave)
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
            } catch (e: Exception) {
                Log.e(TAG, "Save error", e)
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    saveResultButton.isEnabled = true
                    Toast.makeText(this, "Ошибка сохранения: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                matToSave.release()
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
                    runOnUiThread { Toast.makeText(this, "Файл не найден", Toast.LENGTH_SHORT).show() }
                    return@execute
                }
                val image = Imgcodecs.imread(originalPath)
                if (image.empty()) {
                    image.release()
                    runOnUiThread { Toast.makeText(this, "Ошибка чтения", Toast.LENGTH_SHORT).show() }
                    return@execute
                }

                val rawMat = getRawDocumentMat(image, corners)
                image.release()

                if (rawMat == null) {
                    runOnUiThread { Toast.makeText(this, "Не удалось обработать", Toast.LENGTH_SHORT).show() }
                    return@execute
                }

                // Сохраняем сырое
                val rawFile = File(cacheDir, "raw_${System.currentTimeMillis()}.jpg")
                if (Imgcodecs.imwrite(rawFile.absolutePath, rawMat)) {
                    originalImagePath.set(rawFile.absolutePath)
                    runOnUiThread {
                        onDocumentCaptured(rawMat)
                    }
                } else {
                    rawMat.release()
                    runOnUiThread { Toast.makeText(this, "Ошибка сохранения", Toast.LENGTH_SHORT).show() }
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

    // ==================== HDR ====================
    private suspend fun captureHDRFrames(): List<File> = withContext(Dispatchers.IO) {
        val exposures = listOf(-2, 0, 2)
        val files = mutableListOf<File>()
        val cameraControl = camera?.cameraControl ?: return@withContext emptyList()

        val exposureRange = camera?.cameraInfo?.exposureState?.exposureCompensationRange
        val minEv = exposureRange?.lower ?: -2
        val maxEv = exposureRange?.upper ?: 2
        val safeExposures = exposures.filter { it in minEv..maxEv }

        if (safeExposures.size < 3) {
            Log.w(TAG, "Недостаточно доступных значений EV для HDR")
            return@withContext emptyList()
        }

        try {
            for (ev in safeExposures) {
                cameraControl.setExposureCompensationIndex(ev)
                delay(250.milliseconds)

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
                    if (success) files.add(file) else {
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
            try {
                cameraControl.setExposureCompensationIndex(0)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reset exposure compensation", e)
            }
        }

        files
    }

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

            if (keypointsRef.total() < 4 || keypointsTarget.total() < 4) return target.clone()

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
            refGray?.release(); tgtGray?.release()
            keypointsRef?.release(); keypointsTarget?.release()
            descriptorsRef?.release(); descriptorsTarget?.release()
            matches?.release(); srcPoints?.release(); dstPoints?.release(); homography?.release()
            orb?.clear(); matcher?.clear()
        }
    }

    private fun mergeHDR(images: List<Mat>): Mat {
        require(images.size >= 3)
        val merger = Photo.createMergeMertens()
        val resultFloat = Mat()
        try {
            merger.process(images, resultFloat)
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
            var rawMat: Mat? = null   // ← вместо processed

            try {
                analysisPaused.set(true)
                frames.addAll(runBlocking { captureHDRFrames() })
                if (frames.size < 3) {
                    runOnUiThread {
                        progressBar.visibility = View.GONE
                        hdrButton.isEnabled = true
                        captureButton.isEnabled = true
                        Toast.makeText(this, "Ошибка HDR: не удалось снять кадры", Toast.LENGTH_SHORT).show()
                    }
                    return@execute
                }

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

                val reference = mats[1]
                for (i in mats.indices) {
                    if (i == 1) aligned.add(reference.clone())
                    else aligned.add(alignImages(reference, mats[i]))
                }

                val mergedFloat = mergeHDR(aligned)
                merged8u = mergedFloat

                // ---- ИЗМЕНЕНИЕ НАЧИНАЕТСЯ ЗДЕСЬ ----
                // Находим углы на HDR-изображении
                val corners = DocumentDetector.findDocumentCorners(merged8u)
                // Получаем сырое выпрямленное и обрезанное изображение (без фильтров)
                rawMat = if (corners != null && corners.size == 4) {
                    getRawDocumentMat(merged8u, corners)   // ← новый метод, который мы добавили
                } else {
                    getRawDocumentMat(merged8u, null)
                }

                if (rawMat == null) {
                    runOnUiThread {
                        progressBar.visibility = View.GONE
                        hdrButton.isEnabled = true
                        captureButton.isEnabled = true
                        Toast.makeText(this, "Ошибка обработки HDR", Toast.LENGTH_SHORT).show()
                    }
                    return@execute
                }

                // Сохраняем сырое изображение в файл
                val rawFile = File(cacheDir, "raw_hdr_${System.currentTimeMillis()}.jpg")
                if (Imgcodecs.imwrite(rawFile.absolutePath, rawMat)) {
                    originalImagePath.set(rawFile.absolutePath)
                    // Передаём в onDocumentCaptured для применения фильтра
                    runOnUiThread {
                        onDocumentCaptured(rawMat)   // ← вместо showResult()
                        progressBar.visibility = View.GONE
                        hdrButton.isEnabled = true
                        captureButton.isEnabled = true
                    }
                } else {
                    rawMat.release()
                    runOnUiThread {
                        progressBar.visibility = View.GONE
                        hdrButton.isEnabled = true
                        captureButton.isEnabled = true
                        Toast.makeText(this, "Ошибка сохранения", Toast.LENGTH_SHORT).show()
                    }
                }
                // ---- ИЗМЕНЕНИЕ ЗАКАНЧИВАЕТСЯ ----
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
                frames.forEach { it.delete() }
            }
        }
    }

    // ==================== ML Kit ====================
    private fun startMlKitScanner() {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(false)
            .setPageLimit(1)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()

        val scanner = GmsDocumentScanning.getClient(options)
        scanner.getStartScanIntent(this)
            .addOnSuccessListener { intentSender ->
                mlKitScannerLauncher.launch(
                    IntentSenderRequest.Builder(intentSender).build()
                )
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "ML Kit scanner error", e)
                Toast.makeText(this, "ML Kit недоступен, используется OpenCV", Toast.LENGTH_SHORT).show()
                takePicture()
            }
    }

    private fun handleMlKitResult(uri: Uri) {
        val bitmap = try {
            MediaStore.Images.Media.getBitmap(contentResolver, uri)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка чтения Uri", e)
            null
        }

        if (bitmap == null) {
            Toast.makeText(this, "Не удалось получить изображение", Toast.LENGTH_SHORT).show()
            return
        }

        progressBar.visibility = View.VISIBLE
        processingExecutor.execute {
            val mat = Mat()
            Utils.bitmapToMat(bitmap, mat)
            // Получаем сырое выпрямленное изображение (если ML Kit уже выпрямил – можно пропустить)
            val rawMat = getRawDocumentMat(mat, null) ?: mat // или просто mat
            // Сохраняем в файл (если нужно) и вызываем onDocumentCaptured
            val rawFile = File(cacheDir, "mlkit_raw_${System.currentTimeMillis()}.jpg")
            if (Imgcodecs.imwrite(rawFile.absolutePath, rawMat)) {
                originalImagePath.set(rawFile.absolutePath)
                runOnUiThread {
                    onDocumentCaptured(rawMat)
                    progressBar.visibility = View.GONE
                }
            } else {
                rawMat.release()
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this, "Ошибка сохранения", Toast.LENGTH_SHORT).show()
                }
            }
            bitmap.recycle()
        }
    }

    // ==================== Утилиты завершения ====================
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

    private fun onDocumentCaptured(warped: Mat) {
        baseMat = warped.clone()
        warped.release()
        currentFilter = "color"
        // Освобождаем старый кеш
        filterCache.values.forEach { it.release() }
        filterCache.clear()
        applyFilter(currentFilter)
        showResultUI(true)
    }

    private fun applyFilter(filter: String) {
        val mat = baseMat ?: return  // локальная копия, безопасно используем

        val resultMat = when (filter) {
            "original" -> mat.clone()
            else -> {
                filterCache[filter]?.clone() ?: run {
                    val enhanced = when (filter) {
                        "bw" -> DocumentDetector.enhanceScan(mat, "bw")
                        "color" -> DocumentDetector.enhanceScan(mat, "color")
                        "sharp" -> DocumentDetector.enhanceScan(mat, "sharp")
                        else -> mat.clone()
                    }
                    filterCache[filter] = enhanced
                    enhanced.clone()
                }
            }
        }

        val bitmap = matToBitmap(resultMat)
        runOnUiThread {
            findViewById<ImageView>(R.id.resultImageView).setImageBitmap(bitmap)
        }
        updateFilterButtonStates(filter)
        currentFilter = filter
    }

    private fun matToBitmap(mat: Mat): Bitmap {
        val bmp = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        org.opencv.android.Utils.matToBitmap(mat, bmp)
        return bmp
    }

    private fun setupFilterButtons() {
        findViewById<View>(R.id.filterOriginal).setOnClickListener { applyFilter("original") }
        findViewById<View>(R.id.filterColor).setOnClickListener { applyFilter("color") }
        findViewById<View>(R.id.filterBW).setOnClickListener { applyFilter("bw") }
        findViewById<View>(R.id.filterSharp).setOnClickListener { applyFilter("sharp") }
    }

    private fun updateFilterButtonStates(activeFilter: String) {
        val buttonMap = mapOf(
            R.id.filterOriginal to "original",
            R.id.filterColor to "color",
            R.id.filterBW to "bw",
            R.id.filterSharp to "sharp"
        )

        buttonMap.forEach { (id, tag) ->
            val button = findViewById<com.google.android.material.button.MaterialButton>(id)
            val isActive = (tag == activeFilter)
            val colorRes = if (isActive) R.color.primary_color else R.color.grey_600
            button.backgroundTintList = ContextCompat.getColorStateList(this, colorRes)
        }
    }

    private fun showResultUI(show: Boolean) {
        val visibility = if (show) View.VISIBLE else View.GONE
        findViewById<View>(R.id.filterPanel).visibility = visibility
        findViewById<View>(R.id.actionsLayout).visibility = visibility
        findViewById<View>(R.id.captureButton).visibility = if (show) View.GONE else View.VISIBLE
        findViewById<View>(R.id.resultImageView).visibility = visibility
        findViewById<View>(R.id.previewView).visibility = if (show) View.GONE else View.VISIBLE
        findViewById<View>(R.id.overlayView).visibility = if (show) View.GONE else View.VISIBLE
    }

    private fun releaseResources() {
        currentResultBitmap.getAndSet(null)?.recycle()
        originalImagePath.set(null)
        processedImagePath.set(null)
        DocumentDetector.release()
    }

    private fun deleteFileIfExists(path: String?) {
        path?.let { File(it).delete() }
    }

    companion object {
        private const val TAG = "CameraActivity"
    }
}
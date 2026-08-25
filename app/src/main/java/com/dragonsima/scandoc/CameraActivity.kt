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

class CameraActivity : AppCompatActivity() {

    // UI элементы
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

    // Камера
    private lateinit var cameraProvider: ProcessCameraProvider
    private var imageCapture: ImageCapture? = null

    // Исполнители (разделены по назначению)
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val processingExecutor = Executors.newSingleThreadExecutor()
    private val saveExecutor = Executors.newSingleThreadExecutor()

    // Потокобезопасные данные
    private val isProcessing = AtomicBoolean(false)
    private val lastDetectedCorners = AtomicReference<Array<Point>?>(null)
    private val lastImageWidth = AtomicLong(0)
    private val lastImageHeight = AtomicLong(0)

    // Пути к файлам (безопасно для многопоточности)
    private val originalImagePath = AtomicReference<String?>(null)
    private val processedImagePath = AtomicReference<String?>(null)
    private val currentResultBitmap = AtomicReference<Bitmap?>(null)

    // Троттлинг анализа кадров
    private val lastFrameProcessedTime = AtomicLong(0L)
    private val minFrameIntervalMs = 300L

    // Разрешение камеры
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) startCamera()
        else {
            Toast.makeText(this, "Нет разрешения на камеру", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // Ручная обрезка
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
        FileManager.cleanCache(this) // ← добавить
    }

    // ----- Инициализация UI -----

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

        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER

        actionsLayout.visibility = View.GONE
        retakeButton.visibility = View.GONE
        cropButton.visibility = View.GONE
        saveResultButton.visibility = View.GONE
        progressBar.visibility = View.GONE
    }

    private fun setupButtons() {
        backButton.setOnClickListener { finish() }
        captureButton.setOnClickListener { takePicture() }
        saveResultButton.setOnClickListener { saveDocument() }
        retakeButton.setOnClickListener { retakePicture() }
        cropButton.setOnClickListener { cropDocument() }
    }

    // ----- Разрешения и запуск камеры -----

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
                    .setTargetResolution(android.util.Size(640, 480))
                    .build()

                imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
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
                cameraProvider.bindToLifecycle(
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

    // ----- Съёмка -----

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
                            val processedBitmap = processCapturedImage(photoFile.absolutePath, detected, imgWidth, imgHeight)

                            // Проверяем, что путь к оригиналу не изменился (гонка)
                            val currentOriginal = originalImagePath.get()
                            runOnUiThread {
                                progressBar.visibility = View.GONE
                                captureButton.isEnabled = true
                                if (processedBitmap != null && currentOriginal == photoFile.absolutePath) {
                                    currentResultBitmap.set(processedBitmap)
                                    showResult()
                                } else if (processedBitmap == null) {
                                    Toast.makeText(this@CameraActivity, "Не удалось обработать фото", Toast.LENGTH_SHORT).show()
                                } else {
                                    // Результат устарел
                                    processedBitmap.recycle()
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Processing error", e)
                            runOnUiThread {
                                progressBar.visibility = View.GONE
                                captureButton.isEnabled = true
                                Toast.makeText(this@CameraActivity, "Ошибка обработки", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    runOnUiThread {
                        progressBar.visibility = View.GONE
                        captureButton.isEnabled = true
                        Toast.makeText(this@CameraActivity, "Ошибка съёмки: ${exception.message}", Toast.LENGTH_LONG).show()
                    }
                    Log.e(TAG, "Capture error", exception)
                }
            }
        )
    }

    // ----- Обработка кадра анализа (превью) -----

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

    // ----- Обработка захваченного изображения -----

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
            val processedMat = if (detectedCorners != null && detectedCorners.size == 4 && imgWidth > 0 && imgHeight > 0) {
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
                    DocumentDetector.enhanceScan(image, "bw")
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

    private fun processImageWithCorners(image: Mat, corners: Array<Point>): Mat? {
        var warped: Mat? = null
        var noShadows: Mat? = null
        var cropped: Mat? = null
        var enhanced: Mat? = null

        try {
            warped = DocumentDetector.warpDocument(image, corners) ?: return null
            noShadows = DocumentDetector.removeShadows(warped)
            cropped = DocumentDetector.autoCropMargins(noShadows) ?: return null
            enhanced = DocumentDetector.enhanceScan(cropped, "bw")
            return enhanced
        } finally {
            warped?.release()
            noShadows?.release()
            cropped?.release()
        }
    }

    // ----- Отображение результата -----

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
    }

    private fun setResultBitmap(newBitmap: Bitmap?) {
        currentResultBitmap.getAndSet(null)?.recycle()
        currentResultBitmap.set(newBitmap)
    }

    private fun deleteFileIfExists(path: String?) {
        path?.let { File(it).delete() }
    }

    private fun retakePicture() {
        // Не удаляем файлы, просто обнуляем ссылки (файлы будут удалены позже или системой)
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
        overlay.setCorners(null)
    }

    // ----- Сохранение PDF -----

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
                // Удаляем обработанный файл после успешного сохранения
                deleteFileIfExists(path)
                processedImagePath.set(null)
            } catch (e: Exception) {
                Log.e(TAG, "Save error", e)
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    saveResultButton.isEnabled = true
                    Toast.makeText(this, "Ошибка сохранения: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                image.release()
            }
        }
    }

    // ----- Ручная обрезка -----

    private fun cropDocument() {
        val originalPath = originalImagePath.get()
        if (originalPath == null || !File(originalPath).exists()) {
            Toast.makeText(this, "Нет изображения для обрезки", Toast.LENGTH_SHORT).show()
            return
        }

        // Создаём временную копию
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
            putExtra("tempFilePath", tempFile.absolutePath) // будет удалён после возврата
        }
        cropResultLauncher.launch(cropIntent)
    }

    private fun processImageFromPath(originalPath: String, corners: Array<Point>) {
        processingExecutor.execute {
            try {
                if (!File(originalPath).exists()) {
                    Log.w(TAG, "Original file not found")
                    runOnUiThread { Toast.makeText(this, "Файл не найден", Toast.LENGTH_SHORT).show() }
                    return@execute
                }

                val image = Imgcodecs.imread(originalPath)
                if (image.empty()) {
                    image.release()
                    runOnUiThread { Toast.makeText(this, "Ошибка чтения", Toast.LENGTH_SHORT).show() }
                    return@execute
                }

                val processedMat = processImageWithCorners(image, corners)
                image.release()

                if (processedMat != null) {
                    val processedFile = File(cacheDir, "processed_${System.currentTimeMillis()}.jpg")
                    if (Imgcodecs.imwrite(processedFile.absolutePath, processedMat)) {
                        processedImagePath.set(processedFile.absolutePath)
                        val bitmap = FileManager.matToBitmap(processedMat)
                        processedMat.release()

                        // Проверяем, что путь к оригиналу не изменился
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
                        processedMat.release()
                        runOnUiThread { Toast.makeText(this, "Ошибка сохранения", Toast.LENGTH_SHORT).show() }
                    }
                } else {
                    runOnUiThread { Toast.makeText(this, "Ошибка обработки", Toast.LENGTH_SHORT).show() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "processImageFromPath error", e)
                runOnUiThread { Toast.makeText(this, "Ошибка", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    // ----- Трансформация углов для отображения -----

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

    // ----- Завершение работы -----

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
}
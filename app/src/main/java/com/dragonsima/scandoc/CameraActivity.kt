package com.dragonsima.scandoc

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
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
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.imgcodecs.Imgcodecs
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class CameraActivity : AppCompatActivity() {

    // Объявления переменных
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

    private val executor = Executors.newSingleThreadExecutor()
    private var isProcessing = false
    private var imageCapture: ImageCapture? = null
    private var processedPhotoFile: File? = null
    private var frameCounter = 0

    // Сохраняем последние найденные углы
    private var lastDetectedCorners: Array<Point>? = null
    private var lastImageWidth = 0
    private var lastImageHeight = 0
    private var currentResultImage: Mat? = null
    private var originalCapturedImage: Mat? = null

    companion object {
        private const val TAG = "CameraActivity"
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "Нет разрешения на камеру", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        // Инициализация всех View
        initViews()

        // Настройка кнопок
        setupButtons()

        // Запуск камеры
        if (hasCameraPermission()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(android.Manifest.permission.CAMERA)
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

        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER

        // Скрываем кнопки действий изначально
        actionsLayout.visibility = View.GONE
        retakeButton.visibility = View.GONE
        cropButton.visibility = View.GONE
        saveResultButton.visibility = View.GONE
        progressBar.visibility = View.GONE
    }

    private fun setupButtons() {
        // Кнопка назад
        backButton.setOnClickListener {
            finish()
        }

        // Кнопка съёмки
        captureButton.setOnClickListener {
            takePicture()
        }

        // Кнопка сохранения
        saveResultButton.setOnClickListener {
            saveDocument()
        }

        // Кнопка переснять
        retakeButton.setOnClickListener {
            retakePicture()
        }

        // Кнопка обрезать
        cropButton.setOnClickListener {
            cropDocument()
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
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()

                imageAnalysis.setAnalyzer(executor) { imageProxy ->
                    if (!isProcessing) {
                        isProcessing = true
                        try {
                            processFrame(imageProxy)
                        } finally {
                            isProcessing = false
                        }
                    } else {
                        imageProxy.close()
                    }
                }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalysis, imageCapture
                )

                Log.d(TAG, "Camera started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка запуска камеры: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePicture() {
        val capture = imageCapture ?: run {
            Log.e(TAG, "imageCapture is null")
            return
        }

        // Показываем прогресс
        progressBar.visibility = View.VISIBLE
        captureButton.isEnabled = false

        val photoFile = File(cacheDir, "captured_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    // Обработка в фоновом потоке
                    Thread {
                        val processed = processCapturedImage(photoFile, lastDetectedCorners)

                        runOnUiThread {
                            progressBar.visibility = View.GONE
                            captureButton.isEnabled = true

                            if (processed != null) {
                                currentResultImage = processed
                                val resultBitmap = FileManager.matToBitmap(processed)
                                showResult(resultBitmap)
                            } else {
                                Toast.makeText(
                                    this@CameraActivity,
                                    "Не удалось обработать фото",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }.start()
                }

                override fun onError(exception: ImageCaptureException) {
                    runOnUiThread {
                        progressBar.visibility = View.GONE
                        captureButton.isEnabled = true
                    }
                    Log.e(TAG, "Съёмка не удалась: ${exception.message}", exception)
                    Toast.makeText(
                        this@CameraActivity,
                        "Ошибка съёмки: ${exception.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    private fun showResult(bitmap: Bitmap) {
        previewView.visibility = View.GONE
        overlay.visibility = View.GONE
        captureButton.visibility = View.GONE

        resultImageView.visibility = View.VISIBLE
        resultImageView.setImageBitmap(bitmap)

        actionsLayout.visibility = View.VISIBLE
        retakeButton.visibility = View.VISIBLE
        cropButton.visibility = View.VISIBLE
        saveResultButton.visibility = View.VISIBLE
    }

    private fun retakePicture() {
        // Очищаем результат
        currentResultImage?.release()
        currentResultImage = null
        originalCapturedImage?.release()
        originalCapturedImage = null
        processedPhotoFile = null
        lastDetectedCorners = null

        // Показываем камеру
        previewView.visibility = View.VISIBLE
        overlay.visibility = View.VISIBLE
        captureButton.visibility = View.VISIBLE

        // Скрываем результат и кнопки
        resultImageView.visibility = View.GONE
        actionsLayout.visibility = View.GONE
        retakeButton.visibility = View.GONE
        cropButton.visibility = View.GONE
        saveResultButton.visibility = View.GONE

        startCamera()
    }

    private fun saveDocument() {
        val image = currentResultImage ?: run {
            Toast.makeText(this, "Нет изображения для сохранения", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val pdfFile = FileManager.saveToPdf(this, image)

            val resultIntent = Intent().apply {
                putExtra("savedPdfPath", pdfFile.absolutePath)
                putExtra("savedPdfName", pdfFile.name)
            }
            setResult(RESULT_OK, resultIntent)

            Toast.makeText(this, "✅ Документ сохранён: ${pdfFile.name}", Toast.LENGTH_LONG).show()

            finish()
        } catch (e: Exception) {
            Log.e(TAG, "Save error: ${e.message}", e)
            Toast.makeText(this, "Ошибка сохранения: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun cropDocument() {
        val originalImage = originalCapturedImage ?: run {
            Toast.makeText(this, "Нет изображения для обрезки", Toast.LENGTH_SHORT).show()
            return
        }

        val tempFile = File(cacheDir, "crop_temp_${System.currentTimeMillis()}.jpg")
        Imgcodecs.imwrite(tempFile.absolutePath, originalImage)

        val cropIntent = Intent(this, CropActivity::class.java).apply {
            putExtra("imagePath", tempFile.absolutePath)

            if (lastDetectedCorners != null && lastDetectedCorners!!.size == 4) {
                val cornersArray = FloatArray(8)
                for (i in 0..3) {
                    cornersArray[i * 2] = lastDetectedCorners!![i].x.toFloat()
                    cornersArray[i * 2 + 1] = lastDetectedCorners!![i].y.toFloat()
                }
                putExtra("corners", cornersArray)
            }
        }

        cropResultLauncher.launch(cropIntent)
    }

    private val cropResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val corners = result.data?.getFloatArrayExtra("corners")
            if (corners != null && corners.size == 8 && originalCapturedImage != null) {
                val points = Array(4) { i ->
                    Point(corners[i * 2].toDouble(), corners[i * 2 + 1].toDouble())
                }

                val warped = DocumentDetector.warpDocument(originalCapturedImage!!, points)
                val noShadows = DocumentDetector.removeShadows(warped)
                val cropped = DocumentDetector.autoCropMargins(noShadows)
                val enhanced = DocumentDetector.enhanceScan(cropped, "bw")

                currentResultImage?.release()
                currentResultImage = enhanced

                val resultBitmap = FileManager.matToBitmap(enhanced)
                runOnUiThread {
                    showResult(resultBitmap)
                }

                warped.release()
                noShadows.release()
                cropped.release()
            }
        }
    }

    private fun processFrame(imageProxy: ImageProxy) {
        frameCounter++

        val bitmap = imageProxyToBitmap(imageProxy)
        if (bitmap == null) {
            imageProxy.close()
            return
        }

        val mat = Mat()
        try {
            org.opencv.android.Utils.bitmapToMat(bitmap, mat)
            if (mat.empty()) return

            val corners = DocumentDetector.findDocumentCorners(mat)

            if (corners.size == 4 && previewView.width > 0 && previewView.height > 0) {
                lastDetectedCorners = corners
                lastImageWidth = bitmap.width
                lastImageHeight = bitmap.height

                val screenCorners = transformCornersToView(
                    corners,
                    previewView.width,
                    previewView.height,
                    bitmap.width,
                    bitmap.height
                )

                runOnUiThread {
                    overlay.setCorners(screenCorners)
                }
            } else {
                runOnUiThread {
                    overlay.setCorners(null)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "processFrame error: ${e.message}", e)
        } finally {
            mat.release()
            bitmap.recycle()
            imageProxy.close()
        }
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        try {
            val width = imageProxy.width
            val height = imageProxy.height

            val yBuffer = imageProxy.planes[0].buffer
            val ySize = yBuffer.remaining()
            val yBytes = ByteArray(ySize)
            yBuffer.get(yBytes)

            val uBuffer = imageProxy.planes[1].buffer
            val vBuffer = imageProxy.planes[2].buffer
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + vSize + uSize)
            System.arraycopy(yBytes, 0, nv21, 0, ySize)

            val vBytes = ByteArray(vSize)
            val uBytes = ByteArray(uSize)
            vBuffer.get(vBytes)
            uBuffer.get(uBytes)

            var index = ySize
            val maxSize = minOf(uSize, vSize)
            for (i in 0 until maxSize) {
                nv21[index++] = vBytes[i]
                nv21[index++] = uBytes[i]
            }

            val yuv = YuvImage(
                nv21,
                android.graphics.ImageFormat.NV21,
                width,
                height,
                null
            )

            val out = ByteArrayOutputStream()
            yuv.compressToJpeg(Rect(0, 0, width, height), 70, out)
            val jpegBytes = out.toByteArray()

            val bitmap = android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                ?: return null

            val rotation = imageProxy.imageInfo.rotationDegrees
            if (rotation != 0) {
                val matrix = Matrix()
                matrix.postRotate(rotation.toFloat())
                val rotated = Bitmap.createBitmap(
                    bitmap, 0, 0,
                    bitmap.width, bitmap.height,
                    matrix, true
                )
                bitmap.recycle()
                return rotated
            }
            return bitmap

        } catch (e: Exception) {
            Log.e(TAG, "imageProxyToBitmap error: ${e.message}", e)
            return null
        }
    }

    private fun processCapturedImage(photoFile: File, detectedCorners: Array<Point>?): Mat? {
        try {
            val image = Imgcodecs.imread(photoFile.absolutePath)
            if (image.empty()) {
                Log.e(TAG, "Failed to load image: ${photoFile.absolutePath}")
                return null
            }

            originalCapturedImage?.release()
            originalCapturedImage = image.clone()

            val result: Mat

            if (detectedCorners != null && detectedCorners.size == 4) {
                val scaleX = image.cols().toDouble() / lastImageWidth
                val scaleY = image.rows().toDouble() / lastImageHeight

                val scaledCorners = Array(4) { i ->
                    Point(
                        detectedCorners[i].x * scaleX,
                        detectedCorners[i].y * scaleY
                    )
                }

                val warped = DocumentDetector.warpDocument(image, scaledCorners)
                val noShadows = DocumentDetector.removeShadows(warped)
                val cropped = DocumentDetector.autoCropMargins(noShadows)
                result = DocumentDetector.enhanceScan(cropped, "bw")

                warped.release()
                noShadows.release()
                cropped.release()
            } else {
                val corners = DocumentDetector.findDocumentCorners(image)

                if (corners.size == 4) {
                    val warped = DocumentDetector.warpDocument(image, corners)
                    val noShadows = DocumentDetector.removeShadows(warped)
                    val cropped = DocumentDetector.autoCropMargins(noShadows)
                    result = DocumentDetector.enhanceScan(cropped, "bw")

                    warped.release()
                    noShadows.release()
                    cropped.release()
                } else {
                    result = DocumentDetector.enhanceScan(image, "bw")
                }
            }

            image.release()
            return result
        } catch (e: Exception) {
            Log.e(TAG, "processCapturedImage error: ${e.message}", e)
            return null
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

        val offsetX = (viewWidth - imageWidth * scale) / 2
        val offsetY = (viewHeight - imageHeight * scale) / 2

        return Array(4) { i ->
            Point(
                corners[i].x * scale + offsetX,
                corners[i].y * scale + offsetY
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
        currentResultImage?.release()
        originalCapturedImage?.release()
    }
}
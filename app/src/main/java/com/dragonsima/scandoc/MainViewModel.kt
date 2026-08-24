package com.dragonsima.scandoc

import android.content.Context
import android.util.Log
import android.graphics.Bitmap
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.io.ByteArrayOutputStream

class MainViewModel : ViewModel() {

    // Состояния UI
    private val _statusText = MutableLiveData("Готов к сканированию")
    val statusText: LiveData<String> = _statusText

    private val _showActions = MutableLiveData(false)
    val showActions: LiveData<Boolean> = _showActions

    private val _showCapture = MutableLiveData(true)
    val showCapture: LiveData<Boolean> = _showCapture

    private val _previewBitmap = MutableLiveData<Bitmap?>()
    val previewBitmap: LiveData<Bitmap?> = _previewBitmap

    // Внутренние переменные
    var currentResultImage: Mat? = null
        private  set
    private var lastOriginalImage: Mat? = null
    private var currentFilter = "bw"
    var isBatchMode = false

    val batchPages = mutableListOf<ByteArray>()
    /**
     * Обрабатывает захваченное фото
     */
    fun processPhoto(photoFile: File): Mat? {
        _statusText.postValue("Обрабатываю...")

        val originalImage = Imgcodecs.imread(photoFile.absolutePath)
        if (originalImage.empty()) {
            _statusText.postValue("Ошибка загрузки")
            return null
        }

        lastOriginalImage = originalImage.clone()

        // Проверка резкости
        if (!DocumentDetector.isSharpEnough(originalImage)) {
            _statusText.postValue("⚠️ Изображение размыто. Переснимите.")
            return null
        }

        // Поиск углов
        val corners = DocumentDetector.findDocumentCorners(originalImage)

        val result: Mat
        if (corners.size == 4) {
            val warped = DocumentDetector.warpDocument(originalImage, corners)
            val noShadows = DocumentDetector.removeShadows((warped))
            val cropped = DocumentDetector.autoCropMargins(noShadows)
            result = DocumentDetector.enhanceScan(cropped, currentFilter)
            warped.release()
            noShadows.release()
            cropped.release()
        } else {
            result = DocumentDetector.enhanceScan(originalImage, currentFilter)
        }

        currentResultImage = result.clone()

        // Показываем превью
        _previewBitmap.postValue(FileManager.matToBitmap(result))
        _showCapture.postValue(false)
        _showActions.postValue(true)
        _statusText.postValue("Выберите действие")

        originalImage.release()
        return result

    }

    /**
     * Быстрое сохранение
     */
    fun quickSave(context: Context): File? {
        val image = currentResultImage ?: return null
        _statusText.value = "Сохраняю документ..."

        val pdfFile = FileManager.saveToPdf(context, image)
        resetUI()
        _statusText.value = "✅ Готово!"

        Log.d("MainVM", "Сохранено: ${pdfFile.absolutePath}")
        return pdfFile
    }

    /**
     * Установка фильтра
     */
    fun setFilter(filter: String) {
        currentFilter = filter
    }

    /**
     * Сброс UI
     */
    fun resetUI() {
        currentResultImage?.release()
        currentResultImage = null
        _showActions.value = false
        _showCapture.value = true
        _previewBitmap.value = null
        _statusText.value = "Готов к сканированию"
    }

    override fun onCleared() {
        super.onCleared()
        currentResultImage?.release()
        lastOriginalImage?.release()
    }

    fun toggleBatchMode(){
        isBatchMode= !isBatchMode
        batchPages.clear()
    }
    fun addToBatch(image: Mat): Boolean {
        if (!isBatchMode) return false
        val buffer = ByteArrayOutputStream()
        val bitmap = FileManager.matToBitmap(image)
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, buffer)
        batchPages.add(buffer.toByteArray())
        return true
    }

    fun saveBatch(context: Context): File? {
        if (batchPages.isEmpty()) return null
        return FileManager.saveBatchToPdf(context, batchPages)
    }

    fun getBatchCount() = batchPages.size
}
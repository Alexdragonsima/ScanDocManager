package com.dragonsima.scandoc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.util.Log
import com.google.mlkit.vision.text.Text
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * Управляет сохранением сканов в PDF/JPG, миниатюрами и индексами.
 * Все методы потокобезопасны, освобождают ресурсы и обрабатывают ошибки.
 */
object FileManager {

    private const val TAG = "FileManager"

    // Папки внутри filesDir
    private const val DOCUMENTS_FOLDER = "Documents"
    private const val INDICES_FOLDER = "Indices"
    private const val THUMBNAILS_FOLDER = "Thumbnails"

    // Размер миниатюры
    private const val THUMB_WIDTH = 200
    private const val THUMB_HEIGHT = 260

    // Качество JPEG
    private const val JPEG_QUALITY = 92
    private const val THUMB_JPEG_QUALITY = 85

    // Размер A4 в точках (PDF)
    private const val A4_WIDTH = 595
    private const val A4_HEIGHT = 842

    // ==================== ИНИЦИАЛИЗАЦИЯ ====================

    fun init(context: Context) {
        File(context.filesDir, DOCUMENTS_FOLDER).mkdirs()
        File(context.filesDir, THUMBNAILS_FOLDER).mkdirs()
        File(context.filesDir, INDICES_FOLDER).mkdirs()
    }

    // ==================== СОХРАНЕНИЕ PDF ====================

    /**
     * Сохраняет одно изображение в PDF формате A4 (без текстового слоя).
     */
    fun saveToPdf(context: Context, image: Mat): File = saveSinglePagePdf(context, image, null)

    /**
     * Сохраняет изображение в PDF с невидимым текстовым слоем.
     * Благодаря нему PDF-ридеры могут искать, выделять и копировать текст.
     */
    fun saveToPdfWithText(context: Context, image: Mat, visionText: Text?): File =
        saveSinglePagePdf(context, image, visionText)

    /**
     * Единая логика сохранения одной страницы в PDF.
     * @param visionText null — без текстового слоя; Text — с невидимым слоем.
     */
    private fun saveSinglePagePdf(context: Context, image: Mat, visionText: Text?): File {
        require(!image.empty()) { "Mat пустой" }

        val timestamp = newTimestamp()
        val pdfFile = File(context.filesDir, "$DOCUMENTS_FOLDER/$timestamp.pdf")

        val quality = getPdfQuality(context)
        val resizedMat = prepareMatForPdf(image, quality)
        val bitmap = matToBitmap(resizedMat)
        resizedMat.release()

        var thumbSource: Bitmap? = null
        var pdfDocument: PdfDocument? = null

        try {
            thumbSource = matToBitmap(image)

            pdfDocument = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(A4_WIDTH, A4_HEIGHT, 1).create()
            val page = pdfDocument.startPage(pageInfo)
            val canvas = page.canvas

            val (scaledBitmap, x, y, scale) = drawPageIntoCanvas(canvas, bitmap)
            try {
                if (visionText != null) {
                    drawInvisibleText(canvas, visionText, scale, x, y)
                }
                pdfDocument.finishPage(page)

                FileOutputStream(pdfFile).use { pdfDocument.writeTo(it) }
            } finally {
                scaledBitmap.recycle()
            }

            saveThumbnail(context, thumbSource, timestamp)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при сохранении PDF", e)
            pdfFile.delete()
            throw e
        } finally {
            pdfDocument?.close()
            bitmap.recycle()
            thumbSource?.let { if (!it.isRecycled) it.recycle() }
        }

        if (BuildConfig.DEBUG) Log.d(TAG, "PDF сохранён: ${pdfFile.name}")
        return pdfFile
    }

    /**
     * Сохраняет список страниц в многостраничный PDF A4 с текстовым слоем.
     * Если страница выгружена на диск — подгружает Mat из файла на время обработки.
     */
    fun saveBatchToPdfWithText(context: Context, pages: List<ScannedPage>): File {
        require(pages.isNotEmpty()) { "Список страниц пуст" }

        val timestamp = newTimestamp()
        val pdfFile = File(context.filesDir, "$DOCUMENTS_FOLDER/batch_$timestamp.pdf")

        val quality = getPdfQuality(context)
        val pdfDocument = PdfDocument()

        try {
            pages.forEachIndexed { index, page ->
                val localMat: Mat
                val needsRelease: Boolean

                when {
                    page.mat != null -> {
                        localMat = page.mat
                        needsRelease = false
                    }
                    page.filePath != null -> {
                        localMat = Imgcodecs.imread(page.filePath)
                        if (localMat.empty()) {
                            Log.w(TAG, "Страница ${index + 1} не загрузилась, пропуск")
                            return@forEachIndexed
                        }
                        needsRelease = true
                    }
                    else -> {
                        Log.w(TAG, "Страница ${index + 1} без данных, пропуск")
                        return@forEachIndexed
                    }
                }

                try {
                    renderPage(pdfDocument, localMat, page.visionText, index + 1, quality)
                } finally {
                    if (needsRelease) localMat.release()
                }
            }

            FileOutputStream(pdfFile).use { pdfDocument.writeTo(it) }
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Многостраничный PDF сохранён: ${pdfFile.name}, страниц: ${pages.size}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при сохранении многостраничного PDF", e)
            pdfFile.delete()
            throw e
        } finally {
            pdfDocument.close()
        }

        // Миниатюра по первой странице
        pages.firstOrNull()?.thumbnail?.let { thumb ->
            if (!thumb.isRecycled) saveThumbnail(context, thumb, "batch_$timestamp")
        }

        return pdfFile
    }

    /**
     * Рендерит одну страницу в PDF: изображение + невидимый текстовый слой.
     */
    private fun renderPage(
        pdfDocument: PdfDocument,
        image: Mat,
        visionText: Text?,
        pageNumber: Int,
        quality: String
    ) {
        val pageInfo = PdfDocument.PageInfo.Builder(A4_WIDTH, A4_HEIGHT, pageNumber).create()
        val page = pdfDocument.startPage(pageInfo)
        val canvas = page.canvas

        val resizedMat = prepareMatForPdf(image, quality)
        val bitmap = matToBitmap(resizedMat)
        resizedMat.release()

        try {
            val (scaledBitmap, x, y, scale) = drawPageIntoCanvas(canvas, bitmap)
            try {
                if (visionText != null) {
                    drawInvisibleText(canvas, visionText, scale, x, y)
                }
                pdfDocument.finishPage(page)
            } finally {
                scaledBitmap.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Масштабирует bitmap по пропорциям A4, центрирует и рисует в canvas.
     * Возвращает: масштабированный bitmap, его x/y, использованный scale.
     * Вызывающий обязан recycle()'нуть scaledBitmap.
     */
    private data class PageDrawResult(
        val scaledBitmap: Bitmap,
        val x: Float,
        val y: Float,
        val scale: Float
    )

    private fun drawPageIntoCanvas(canvas: Canvas, source: Bitmap): PageDrawResult {
        val scale = min(A4_WIDTH.toFloat() / source.width, A4_HEIGHT.toFloat() / source.height)
        val scaledWidth = (source.width * scale).toInt().coerceAtLeast(1)
        val scaledHeight = (source.height * scale).toInt().coerceAtLeast(1)

        val scaledBitmap = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)
        val x = (A4_WIDTH - scaledWidth) / 2f
        val y = (A4_HEIGHT - scaledHeight) / 2f

        canvas.drawBitmap(scaledBitmap, x, y, null)
        return PageDrawResult(scaledBitmap, x, y, scale)
    }

    /**
     * Рисует невидимый текстовый слой.
     * alpha=1 — текст физически присутствует в PDF, но визуально невидим.
     * Поиск и выделение в ридерах при этом работают.
     */
    private fun drawInvisibleText(
        canvas: Canvas,
        visionText: Text,
        scale: Float,
        offsetX: Float,
        offsetY: Float
    ) {
        val paint = Paint().apply {
            color = Color.BLACK
            alpha = 1
            isAntiAlias = true
            isSubpixelText = true
        }

        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                val text = line.text
                if (text.isBlank()) continue
                if (box.width() <= 0 || box.height() <= 0) continue

                val pageX = offsetX + box.left * scale
                val pageY = offsetY + box.top * scale
                val pageW = box.width() * scale
                val pageH = box.height() * scale

                paint.textSize = pageH * 0.9f
                val measured = paint.measureText(text)
                if (measured > 0f && pageW > 0f) {
                    paint.textSize *= (pageW / measured)
                }

                val baseline = pageY + pageH * 0.85f
                canvas.drawText(text, pageX, baseline, paint)
            }
        }
    }

    // ==================== СОХРАНЕНИЕ JPG ====================

    /**
     * Сохраняет Mat в JPEG. Возвращает файл.
     */
    fun saveToJpg(context: Context, image: Mat): File {
        require(!image.empty()) { "Mat пустой" }

        val timestamp = newTimestamp()
        val jpgFile = File(context.filesDir, "$DOCUMENTS_FOLDER/$timestamp.jpg")

        if (!imwriteJpeg(jpgFile, image)) {
            jpgFile.delete()
            throw IllegalStateException("Не удалось сохранить JPEG: ${jpgFile.name}")
        }

        // Миниатюра
        val bitmap = matToBitmap(image)
        try {
            saveThumbnail(context, bitmap, timestamp)
        } finally {
            bitmap.recycle()
        }

        if (BuildConfig.DEBUG) Log.d(TAG, "JPEG сохранён: ${jpgFile.name}")
        return jpgFile
    }

    /**
     * Сохраняет список страниц в папку Documents/jpg_<timestamp>/ как отдельные JPEG.
     * Возвращает папку.
     */
    fun saveBatchToJpg(context: Context, pages: List<ScannedPage>): File {
        require(pages.isNotEmpty()) { "Список страниц пуст" }

        val timestamp = newTimestamp()
        val folder = File(context.filesDir, "$DOCUMENTS_FOLDER/jpg_$timestamp")
        if (!folder.exists() && !folder.mkdirs()) {
            throw IllegalStateException("Не удалось создать папку для JPG")
        }

        pages.forEachIndexed { index, page ->
            val localMat: Mat
            val needsRelease: Boolean

            when {
                page.mat != null -> {
                    localMat = page.mat
                    needsRelease = false
                }
                page.filePath != null -> {
                    localMat = Imgcodecs.imread(page.filePath)
                    if (localMat.empty()) {
                        Log.w(TAG, "Страница ${index + 1} не загрузилась")
                        return@forEachIndexed
                    }
                    needsRelease = true
                }
                else -> return@forEachIndexed
            }

            try {
                val file = File(folder, "page_${(index + 1).toString().padStart(2, '0')}.jpg")
                if (!imwriteJpeg(file, localMat)) {
                    Log.e(TAG, "Не удалось записать ${file.name}")
                }
            } finally {
                if (needsRelease) localMat.release()
            }
        }

        // Миниатюра по первой странице
        pages.firstOrNull()?.thumbnail?.let { thumb ->
            if (!thumb.isRecycled) saveThumbnail(context, thumb, "jpg_$timestamp")
        }

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Пакет JPG сохранён: ${folder.name}, файлов: ${pages.size}")
        }
        return folder
    }

    /**
     * Общий помощник записи JPEG с параметром качества.
     * Освобождает MatOfInt сам.
     */
    private fun imwriteJpeg(file: File, image: Mat): Boolean {
        val params = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, JPEG_QUALITY)
        return try {
            Imgcodecs.imwrite(file.absolutePath, image, params)
        } finally {
            params.release()
        }
    }

    // ==================== КОНВЕРТАЦИЯ ====================

    /**
     * Конвертирует Mat (BGR или grayscale) в Bitmap (ARGB).
     * Освобождает промежуточный Mat.
     */
    fun matToBitmap(mat: Mat): Bitmap {
        require(!mat.empty()) { "Mat пустой" }

        val rgbaMat = Mat()
        when (mat.channels()) {
            1 -> Imgproc.cvtColor(mat, rgbaMat, Imgproc.COLOR_GRAY2RGBA)
            3 -> Imgproc.cvtColor(mat, rgbaMat, Imgproc.COLOR_BGR2RGBA)
            4 -> mat.copyTo(rgbaMat)
            else -> throw IllegalArgumentException("Unsupported channels: ${mat.channels()}")
        }

        try {
            val bitmap = Bitmap.createBitmap(rgbaMat.cols(), rgbaMat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rgbaMat, bitmap)
            return bitmap
        } finally {
            rgbaMat.release()
        }
    }

    /**
     * Читает настройку качества PDF.
     * Возвращает "high" | "medium" | "low".
     */
    private fun getPdfQuality(context: Context): String {
        val prefs = context.getSharedPreferences(SettingsActivity.PREFS_SETTINGS, Context.MODE_PRIVATE)
        return prefs.getString(SettingsActivity.KEY_PDF_QUALITY, "high") ?: "high"
    }

    /**
     * Уменьшает Mat в зависимости от качества PDF.
     * Возвращает НОВЫЙ Mat — вызывающий обязан release()'нуть.
     */
    private fun prepareMatForPdf(source: Mat, quality: String): Mat {
        val maxSide = when (quality) {
            "low" -> 1200
            "medium" -> 2000
            else -> 3000
        }

        val longest = maxOf(source.cols(), source.rows())
        if (longest <= maxSide) return source.clone()

        val scale = maxSide.toDouble() / longest
        val newW = (source.cols() * scale).toInt().coerceAtLeast(1)
        val newH = (source.rows() * scale).toInt().coerceAtLeast(1)

        val resized = Mat()
        Imgproc.resize(source, resized, Size(newW.toDouble(), newH.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        return resized
    }

    // ==================== МИНИАТЮРЫ ====================

    /**
     * Сохраняет миниатюру.
     * ВАЖНО: если bitmap уже нужного размера, используется он сам — и НЕ recycle'ится,
     * чтобы не ломать вызывающий код (например, PageRepository).
     */
    private fun saveThumbnail(context: Context, bitmap: Bitmap, name: String) {
        val thumbFile = File(context.filesDir, "$THUMBNAILS_FOLDER/${name}_thumb.jpg")

        val alreadyCorrectSize = (bitmap.width == THUMB_WIDTH && bitmap.height == THUMB_HEIGHT)
        val scaled = if (alreadyCorrectSize) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, THUMB_WIDTH, THUMB_HEIGHT, true)
        }
        val isSameRef = scaled === bitmap

        try {
            FileOutputStream(thumbFile).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, THUMB_JPEG_QUALITY, out)
            }
            if (BuildConfig.DEBUG) Log.d(TAG, "Миниатюра сохранена: ${thumbFile.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка сохранения миниатюры", e)
        } finally {
            if (!isSameRef && !scaled.isRecycled) scaled.recycle()
        }
    }

    // ==================== КЭШ ====================

    /**
     * Удаляет временные файлы приложения из cacheDir.
     * Безопасно вызывать из любого потока.
     */
    fun cleanCache(context: Context) {
        var deleted = 0
        val cacheDir = context.cacheDir
        if (cacheDir.exists()) {
            cacheDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.matches(TEMP_FILE_PATTERN)) {
                    if (file.delete()) deleted++
                }
            }
        }
        if (deleted > 0 && BuildConfig.DEBUG) Log.d(TAG, "Кэш очищен: $deleted файлов")
    }

    private val TEMP_FILE_PATTERN = Regex(
        "^(temp_|captured_|processed_|crop_temp_|raw_|raw_hdr_|hdr_|mlkit_raw_|page_).*"
    )

    // ==================== ИНДЕКСЫ ДЛЯ ПОИСКА ====================

    /**
     * Сохраняет текст в индекс для файла.
     */
    fun saveIndexForFile(context: Context, fileName: String, text: String) {
        if (text.isBlank()) return
        val indexFile = File(context.filesDir, "$INDICES_FOLDER/${baseName(fileName)}.txt")
        try {
            indexFile.writeText(text, Charsets.UTF_8)
            if (BuildConfig.DEBUG) Log.d(TAG, "Индекс сохранён: ${indexFile.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка записи индекса: ${e.message}", e)
        }
    }

    fun getIndexForFile(context: Context, fileName: String): String? {
        val indexFile = File(context.filesDir, "$INDICES_FOLDER/${baseName(fileName)}.txt")
        if (!indexFile.exists()) return null
        return try {
            indexFile.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка чтения индекса: ${e.message}", e)
            null
        }
    }

    fun deleteIndexForFile(context: Context, fileName: String) {
        val indexFile = File(context.filesDir, "$INDICES_FOLDER/${baseName(fileName)}.txt")
        if (indexFile.exists()) indexFile.delete()
    }

    fun renameIndexForFile(context: Context, oldFileName: String, newFileName: String) {
        val oldFile = File(context.filesDir, "$INDICES_FOLDER/${baseName(oldFileName)}.txt")
        if (!oldFile.exists()) return
        val newFile = File(context.filesDir, "$INDICES_FOLDER/${baseName(newFileName)}.txt")
        oldFile.renameTo(newFile)
    }

    /**
     * Ищет запрос во всех индексах. Возвращает map: имя файла -> число совпадений.
     */
    fun searchInIndices(context: Context, query: String): Map<String, Int> {
        if (query.isBlank()) return emptyMap()

        val indicesDir = File(context.filesDir, INDICES_FOLDER)
        val docsDir = File(context.filesDir, DOCUMENTS_FOLDER)
        if (!indicesDir.exists() || !docsDir.exists()) return emptyMap()

        val lowerQuery = query.lowercase(Locale.getDefault())
        val result = mutableMapOf<String, Int>()
        val allowedExtensions = listOf("pdf", "txt", "docx")

        indicesDir.listFiles { f -> f.extension == "txt" }?.forEach { indexFile ->
            try {
                val base = indexFile.nameWithoutExtension

                // Ищем реальный файл в Documents
                val realFile = allowedExtensions
                    .asSequence()
                    .map { File(docsDir, "$base.$it") }
                    .firstOrNull { it.exists() }
                    ?: return@forEach

                val text = indexFile.readText(Charsets.UTF_8).lowercase(Locale.getDefault())
                var count = 0
                var idx = 0
                while (true) {
                    idx = text.indexOf(lowerQuery, idx)
                    if (idx < 0) break
                    count++
                    idx += lowerQuery.length
                }
                if (count > 0) result[realFile.name] = count
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка чтения индекса ${indexFile.name}", e)
            }
        }
        return result
    }

    // ==================== SAF: КОПИРОВАНИЕ В ВЫБРАННУЮ ПАПКУ ====================

    /**
     * Копирует файл в SAF-папку, выбранную пользователем в настройках.
     * Возвращает true при успехе.
     */
    fun copyToSaveFolderIfSet(context: Context, sourceFile: File): Boolean {
        val treeUri = getSaveFolderUri(context) ?: return false

        return try {
            val parentDoc = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
                ?: return false

            // Перезапись — удаляем существующий
            parentDoc.findFile(sourceFile.name)?.delete()

            val newDoc = parentDoc.createFile(mimeOf(sourceFile), sourceFile.name)
                ?: return false

            val outputStream = context.contentResolver.openOutputStream(newDoc.uri)
            if (outputStream == null) {
                Log.e(TAG, "openOutputStream вернул null для ${sourceFile.name}")
                newDoc.delete()
                return false
            }

            outputStream.use { out ->
                sourceFile.inputStream().use { input ->
                    input.copyTo(out)
                }
            }

            if (BuildConfig.DEBUG) Log.d(TAG, "Файл скопирован в SAF: ${sourceFile.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка копирования в SAF: ${e.message}", e)
            false
        }
    }

    /**
     * Удаляет копию файла из SAF-папки.
     */
    fun deleteFromSaveFolderIfSet(context: Context, fileName: String) {
        val treeUri = getSaveFolderUri(context) ?: return

        try {
            val parentDoc = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri) ?: return
            val doc = parentDoc.findFile(fileName) ?: return
            val deleted = doc.delete()
            if (BuildConfig.DEBUG) Log.d(TAG, "SAF-удаление $fileName: $deleted")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка SAF-удаления: ${e.message}", e)
        }
    }

    /**
     * Переименовывает копию файла в SAF-папке.
     */
    fun renameInSaveFolderIfSet(context: Context, oldName: String, newName: String) {
        val treeUri = getSaveFolderUri(context) ?: return

        try {
            val parentDoc = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri) ?: return
            val doc = parentDoc.findFile(oldName) ?: return
            val renamed = doc.renameTo(newName)
            if (BuildConfig.DEBUG) Log.d(TAG, "SAF-переименование $oldName → $newName: $renamed")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка SAF-переименования: ${e.message}", e)
        }
    }

    private fun getSaveFolderUri(context: Context): android.net.Uri? {
        val prefs = context.getSharedPreferences(SettingsActivity.PREFS_SETTINGS, Context.MODE_PRIVATE)
        val uriString = prefs.getString(SettingsActivity.KEY_SAVE_FOLDER_URI, null) ?: return null
        return try {
            android.net.Uri.parse(uriString)
        } catch (_: Exception) {
            null
        }
    }

    private fun mimeOf(file: File): String = when (file.extension.lowercase()) {
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        else -> "*/*"
    }

    // ==================== УТИЛИТЫ ====================

    private fun baseName(fileName: String): String = fileName.substringBeforeLast(".")

    private fun newTimestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
}
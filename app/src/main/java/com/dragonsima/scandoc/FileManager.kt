package com.dragonsima.scandoc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.min
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.mlkit.vision.text.Text
import org.opencv.core.MatOfInt

/**
 * Управляет сохранением сканов в PDF и временными файлами.
 * Все методы потокобезопасны, освобождают ресурсы и обрабатывают ошибки.
 */
object FileManager {

    private const val DOCUMENTS_FOLDER = "Documents"
    private const val THUMBNAILS_FOLDER = "Thumbnails"
    private const val TAG = "FileManager"

    /**
     * Инициализирует необходимые папки в filesDir.
     */
    fun init(context: Context) {
        File(context.filesDir, DOCUMENTS_FOLDER).mkdirs()
        File(context.filesDir, THUMBNAILS_FOLDER).mkdirs()
        Log.d(TAG, "Папки созданы")
    }

    /**
     * Сохраняет изображение (Mat) в PDF формате A4.
     * Возвращает файл PDF.
     */
    fun saveToPdf(context: Context, image: Mat): File {
        require(!image.empty()) { "Mat пустой" }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val pdfFile = File(context.filesDir, "$DOCUMENTS_FOLDER/$timestamp.pdf")

        val bitmap = matToBitmap(image)   // может выбросить исключение
        try {
            val pdfDocument = PdfDocument()
            val pageWidth = 595   // A4 ширина в точках
            val pageHeight = 842  // A4 высота в точках
            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
            val page = pdfDocument.startPage(pageInfo)
            val canvas = page.canvas

            // Масштабируем с сохранением пропорций
            val scale = min(pageWidth.toFloat() / bitmap.width, pageHeight.toFloat() / bitmap.height)
            val scaledBitmap = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt(),
                (bitmap.height * scale).toInt(),
                true
            )
            val x = (pageWidth - scaledBitmap.width) / 2f
            val y = (pageHeight - scaledBitmap.height) / 2f

            canvas.drawBitmap(scaledBitmap, x, y, null)
            pdfDocument.finishPage(page)

            FileOutputStream(pdfFile).use { pdfDocument.writeTo(it) }
            pdfDocument.close()

            // Миниатюру сохраняем до освобождения bitmap (используем исходный bitmap)
            saveThumbnail(context, bitmap, timestamp)

            scaledBitmap.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при сохранении PDF", e)
            pdfFile.delete()  // удаляем битый файл
            throw e
        } finally {
            bitmap.recycle()
        }

        Log.d(TAG, "PDF сохранён: ${pdfFile.absolutePath}")
        return pdfFile
    }

    /**
     * Сохраняет изображение в PDF формате A4 с невидимым текстовым слоем.
     * Текст из ML Kit позиционируется точно по координатам boundingBox,
     * что позволяет PDF-ридерам искать, выделять и копировать его.
     */
    fun saveToPdfWithText(context: Context, image: Mat, visionText: Text?): File {
        require(!image.empty()) { "Mat пустой" }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val pdfFile = File(context.filesDir, "$DOCUMENTS_FOLDER/$timestamp.pdf")

        val bitmap = matToBitmap(image)
        try {
            val pdfDocument = PdfDocument()
            val pageWidth = 595
            val pageHeight = 842
            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
            val page = pdfDocument.startPage(pageInfo)
            val canvas = page.canvas

            // Та же трансформация, что и в saveToPdf — общая для картинки и текста
            val scale = min(pageWidth.toFloat() / bitmap.width, pageHeight.toFloat() / bitmap.height)
            val scaledWidth = (bitmap.width * scale).toInt()
            val scaledHeight = (bitmap.height * scale).toInt()
            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
            val x = (pageWidth - scaledWidth) / 2f
            val y = (pageHeight - scaledHeight) / 2f

            canvas.drawBitmap(scaledBitmap, x, y, null)

            // Невидимый текстовый слой поверх картинки
            if (visionText != null) {
                drawInvisibleText(
                    canvas = canvas,
                    visionText = visionText,
                    scale = scale,
                    offsetX = x,
                    offsetY = y
                )
            }

            pdfDocument.finishPage(page)

            FileOutputStream(pdfFile).use { pdfDocument.writeTo(it) }
            pdfDocument.close()

            // Миниатюра — как в обычном saveToPdf
            saveThumbnail(context, bitmap, timestamp)
            scaledBitmap.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при сохранении PDF с текстом", e)
            pdfFile.delete()
            throw e
        } finally {
            bitmap.recycle()
        }

        Log.d(TAG, "PDF с текстовым слоем сохранён: ${pdfFile.absolutePath}")
        return pdfFile
    }

    /**
     * Рисует невидимый текстовый слой.
     * alpha=1 — текст физически присутствует в PDF, но визуально невидим.
     * Поиск и выделение в ридерах при этом работают.
     *
     * Координаты boundingBox приходят в пикселях bitmap, поэтому
     * применяем ту же трансформацию (scale + offset), что и к изображению.
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
            alpha = 1                 // почти прозрачно — не видно глазу, но парсится
            isAntiAlias = true
            isSubpixelText = true     // точное позиционирование
        }

        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                val text = line.text
                if (text.isBlank()) continue
                if (box.width() <= 0 || box.height() <= 0) continue

                // Координаты в системе PDF-страницы
                val pageX = offsetX + box.left * scale
                val pageY = offsetY + box.top * scale
                val pageW = box.width() * scale
                val pageH = box.height() * scale

                // Подбираем textSize так, чтобы строка вписалась в ширину бокса
                paint.textSize = pageH * 0.9f
                val measured = paint.measureText(text)
                if (measured > 0f && pageW > 0f) {
                    paint.textSize *= (pageW / measured)
                }

                // Базовая линия: низ бокса минус небольшой отступ
                val baseline = pageY + pageH * 0.85f
                canvas.drawText(text, pageX, baseline, paint)
            }
        }
    }

    /**
     * Сохраняет список страниц в многостраничный PDF A4 с невидимым текстовым слоем.
     * Если страница выгружена на диск — подгружает Mat из файла на время обработки.
     */
    fun saveBatchToPdfWithText(context: Context, pages: List<ScannedPage>): File {
        require(pages.isNotEmpty()) { "Список страниц пуст" }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val pdfFile = File(context.filesDir, "$DOCUMENTS_FOLDER/batch_$timestamp.pdf")

        val pdfDocument = PdfDocument()
        try {
            pages.forEachIndexed { index, page ->
                // Получаем Mat: из памяти или с диска
                val localMat: Mat
                val needsRelease: Boolean
                if (page.mat != null) {
                    localMat = page.mat
                    needsRelease = false
                } else if (page.filePath != null) {
                    localMat = Imgcodecs.imread(page.filePath)
                    if (localMat.empty()) {
                        Log.w(TAG, "Страница ${index + 1} не загрузилась, пропуск")
                        return@forEachIndexed
                    }
                    needsRelease = true
                } else {
                    Log.w(TAG, "Страница ${index + 1} без данных, пропуск")
                    return@forEachIndexed
                }

                try {
                    renderPage(pdfDocument, localMat, page.visionText, index + 1)
                } finally {
                    if (needsRelease) localMat.release()
                }
            }

            FileOutputStream(pdfFile).use { pdfDocument.writeTo(it) }
            Log.d(TAG, "Многостраничный PDF сохранён: ${pdfFile.absolutePath}, страниц: ${pages.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при сохранении многостраничного PDF", e)
            pdfFile.delete()
            throw e
        } finally {
            pdfDocument.close()
        }

        // Миниатюра — по первой странице
        val firstPage = pages.firstOrNull()
        val firstThumb = firstPage?.thumbnail
        if (firstThumb != null && !firstThumb.isRecycled) {
            saveThumbnail(context, firstThumb, "batch_$timestamp")
        }

        return pdfFile
    }

    /**
     * Рендерит одну страницу в PDF: изображение + невидимый текстовый слой.
     */
    private fun renderPage(
        pdfDocument: PdfDocument,
        image: Mat,
        visionText: com.google.mlkit.vision.text.Text?,
        pageNumber: Int
    ) {
        val pageWidth = 595
        val pageHeight = 842
        val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
        val page = pdfDocument.startPage(pageInfo)
        val canvas = page.canvas

        val bitmap = matToBitmap(image)
        try {
            val scale = min(pageWidth.toFloat() / bitmap.width, pageHeight.toFloat() / bitmap.height)
            val scaledWidth = (bitmap.width * scale).toInt()
            val scaledHeight = (bitmap.height * scale).toInt()
            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
            val x = (pageWidth - scaledWidth) / 2f
            val y = (pageHeight - scaledHeight) / 2f

            canvas.drawBitmap(scaledBitmap, x, y, null)
            scaledBitmap.recycle()

            if (visionText != null) {
                drawInvisibleText(canvas, visionText, scale, x, y)
            }

            pdfDocument.finishPage(page)
        } finally {
            bitmap.recycle()
        }
    }
    /**
     * Конвертирует Mat в Bitmap.
     * Освобождает промежуточный Mat, если он был создан.
     */
    fun matToBitmap(mat: Mat): Bitmap {
        require(!mat.empty()) { "Mat пустой" }

        val needsConversion = mat.channels() == 1
        val targetMat = if (needsConversion) {
            Mat().also { tmp ->
                Imgproc.cvtColor(mat, tmp, Imgproc.COLOR_GRAY2BGR)
            }
        } else mat

        try {
            val bitmap = Bitmap.createBitmap(targetMat.cols(), targetMat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(targetMat, bitmap)
            return bitmap
        } finally {
            if (needsConversion) targetMat.release()
        }
    }

    /**
     * Конвертирует Bitmap в Mat (возвращает новый Mat).
     */
    fun bitmapToMat(bitmap: Bitmap): Mat {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        return mat
    }

    /**
     * Сохраняет Mat во временный JPEG-файл в cacheDir.
     */
    fun saveTempJpeg(context: Context, image: Mat): File {
        val file = File(context.cacheDir, "temp_${UUID.randomUUID()}.jpg")
        if (!Imgcodecs.imwrite(file.absolutePath, image)) {
            Log.e(TAG, "Не удалось сохранить временный JPEG")
            throw IllegalStateException("Ошибка записи временного файла")
        }
        return file
    }

    /**
     * Удаляет все временные файлы, созданные приложением.
     * Безопасно вызывать из любого потока.
     */
    fun cleanCache(context: Context) {
        var deleted = 0
        val cacheDir = context.cacheDir
        if (cacheDir.exists()) {
            cacheDir.listFiles()?.forEach { file ->
                if (file.isFile && (
                            file.name.startsWith("temp_") ||
                                    file.name.startsWith("captured_") ||
                                    file.name.startsWith("processed_") ||
                                    file.name.startsWith("crop_temp_")
                            )) {
                    if (file.delete()) deleted++
                }
            }
        }
        if (deleted > 0) Log.d(TAG, "Кэш очищен: $deleted файлов")
    }

    /**
     * Сохраняет миниатюру (200x260) для предпросмотра.
     * Входной Bitmap не освобождается внутри — за это отвечает вызывающий код.
     */
    private fun saveThumbnail(context: Context, bitmap: Bitmap, name: String) {
        val thumbFile = File(context.filesDir, "$THUMBNAILS_FOLDER/${name}_thumb.jpg")
        val scaled = Bitmap.createScaledBitmap(bitmap, 200, 260, true)
        try {
            FileOutputStream(thumbFile).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            Log.d(TAG, "Миниатюра сохранена: ${thumbFile.absolutePath}")
        } finally {
            scaled.recycle()
        }
    }
    /**
     * Сохраняет Mat в JPEG. Возвращает файл.
     */
    fun saveToJpg(context: Context, image: Mat): File {
        require(!image.empty()) { "Mat пустой" }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val jpgFile = File(context.filesDir, "$DOCUMENTS_FOLDER/$timestamp.jpg")

        val params = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 92)
        val success = try {
            Imgcodecs.imwrite(jpgFile.absolutePath, image, params)
        } finally {
            params.release()
        }

        // Миниатюра — как у PDF
        val bitmap = matToBitmap(image)
        try {
            saveThumbnail(context, bitmap, timestamp)
        } finally {
            bitmap.recycle()
        }

        Log.d(TAG, "JPEG сохранён: ${jpgFile.absolutePath}")
        return jpgFile
    }

    /**
     * Сохраняет список страниц в папку Documents/jpg_<timestamp>/ как отдельные JPEG.
     * Возвращает папку.
     */
    fun saveBatchToJpg(context: Context, pages: List<ScannedPage>): File {
        require(pages.isNotEmpty()) { "Список страниц пуст" }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val folder = File(context.filesDir, "$DOCUMENTS_FOLDER/jpg_$timestamp")
        if (!folder.exists() && !folder.mkdirs()) {
            throw IllegalStateException("Не удалось создать папку для JPG")
        }

        pages.forEachIndexed { index, page ->
            // Получаем Mat — из памяти или с диска
            val localMat: Mat
            val needsRelease: Boolean
            if (page.mat != null) {
                localMat = page.mat
                needsRelease = false
            } else if (page.filePath != null) {
                localMat = Imgcodecs.imread(page.filePath)
                if (localMat.empty()) {
                    Log.w(TAG, "Страница ${index + 1} не загрузилась")
                    return@forEachIndexed
                }
                needsRelease = true
            } else return@forEachIndexed

            try {
                val file = File(folder, "page_${(index + 1).toString().padStart(2, '0')}.jpg")
                val params = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 92)
                try {
                    Imgcodecs.imwrite(file.absolutePath, localMat, params)
                } finally {
                    params.release()
                }
            } finally {
                if (needsRelease) localMat.release()
            }
        }

        // Миниатюра — по первой странице
        val firstThumb = pages.firstOrNull()?.thumbnail
        if (firstThumb != null && !firstThumb.isRecycled) {
            saveThumbnail(context, firstThumb, "jpg_$timestamp")
        }

        Log.d(TAG, "Пакет JPG сохранён: ${folder.absolutePath}, файлов: ${pages.size}")
        return folder
    }
}
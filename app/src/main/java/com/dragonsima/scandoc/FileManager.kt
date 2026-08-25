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
     * Сохраняет несколько изображений (в виде JPEG-байтов) в многостраничный PDF.
     * @param pages список ByteArray, каждый элемент — JPEG-изображение страницы.
     */
    fun saveBatchToPdf(context: Context, pages: List<ByteArray>): File {
        require(pages.isNotEmpty()) { "Список страниц пуст" }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val pdfFile = File(context.filesDir, "$DOCUMENTS_FOLDER/batch_$timestamp.pdf")

        val pdfDocument = PdfDocument()
        try {
            pages.forEachIndexed { index, pageBytes ->
                val bitmap = BitmapFactory.decodeByteArray(pageBytes, 0, pageBytes.size)
                    ?: throw IllegalArgumentException("Невозможно декодировать страницу ${index + 1}")

                try {
                    val pageWidth = 595
                    val pageHeight = 842
                    val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
                    val page = pdfDocument.startPage(pageInfo)
                    val canvas = page.canvas

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
                    scaledBitmap.recycle()
                } finally {
                    bitmap.recycle()
                }
            }

            FileOutputStream(pdfFile).use { pdfDocument.writeTo(it) }
            Log.d(TAG, "Многостраничный PDF сохранён: ${pdfFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при сохранении многостраничного PDF", e)
            pdfFile.delete()
            throw e
        } finally {
            pdfDocument.close()
        }

        // Сохраняем миниатюру первой страницы
        val firstPageBitmap = BitmapFactory.decodeByteArray(pages[0], 0, pages[0].size)
        if (firstPageBitmap != null) {
            try {
                saveThumbnail(context, firstPageBitmap, "batch_$timestamp")
            } finally {
                firstPageBitmap.recycle()
            }
        } else {
            Log.w(TAG, "Не удалось создать миниатюру для первой страницы")
        }

        return pdfFile
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
}
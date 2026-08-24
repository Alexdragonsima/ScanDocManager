package com.dragonsima.scandoc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.util.Log
import androidx.core.graphics.scale
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

object FileManager {

    private const val DOCUMENTS_FOLDER = "Documents"
    private const val THUMBNAILS_FOLDER = "Thumbnails"

    fun init(context: Context) {
        File(context.filesDir, DOCUMENTS_FOLDER).mkdirs()
        File(context.filesDir, THUMBNAILS_FOLDER).mkdirs()
        Log.d("FileManager", "Папки созданы")
    }

    /**
     * Сохраняет скан в PDF
     */
    fun saveToPdf(context: Context, image: Mat): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "$timestamp.pdf"
        val pdfFile = File(context.filesDir, "$DOCUMENTS_FOLDER/$fileName")

        // Конвертируем Mat → Bitmap
        val bitmap = matToBitmap(image)

        // Создаём PDF
        val pdfDocument = PdfDocument()
        val pageWidth = 595  // A4 ширина в точках
        val pageHeight = 842 // A4 высота в точках

        val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
        val page = pdfDocument.startPage(pageInfo)

        // Рисуем изображение на странице
        val canvas = page.canvas
        val scale = Math.min(
            pageWidth.toFloat() / bitmap.width,
            pageHeight.toFloat() / bitmap.height
        )
        val displayWidth = bitmap.width * scale
        val displayHeight = bitmap.height * scale
        val x = (pageWidth - displayWidth) / 2
        val y = (pageHeight - displayHeight) / 2

        canvas.drawBitmap(
            bitmap.scale( displayWidth.toInt(), displayHeight.toInt(), true),
            x, y, null
        )

        pdfDocument.finishPage(page)

        // Сохраняем
        FileOutputStream(pdfFile).use { pdfDocument.writeTo(it) }
        pdfDocument.close()

        // Сохраняем миниатюру
        saveThumbnail(context, bitmap, timestamp)

        Log.d("FileManager", "PDF сохранён: ${pdfFile.absolutePath}")
        return pdfFile
    }

    /**
     * Сохраняет миниатюру
     */
    private fun saveThumbnail(context: Context, bitmap: Bitmap, name: String) {
        val thumbFile = File(context.filesDir, "$THUMBNAILS_FOLDER/${name}_thumb.jpg")
        val scaled = Bitmap.createScaledBitmap(bitmap, 200, 260, true)
        FileOutputStream(thumbFile).use {
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, it)
        }
        Log.d("FileManager", "Миниатюра сохранена: ${thumbFile.absolutePath}")
    }

    /**
     * Конвертирует Mat → Bitmap
     */
    fun matToBitmap(mat: Mat): Bitmap {
        val bitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, bitmap)
        return bitmap
    }

    /**
     * Конвертирует Bitmap → Mat
     */
    fun bitmapToMat(bitmap: Bitmap): Mat {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        return mat
    }

    /**
     * Сохраняет Mat как JPEG во временный файл
     */
    fun saveTempJpeg(context: Context, image: Mat): File {
        val file = File(context.cacheDir, "temp_${UUID.randomUUID()}.jpg")
        Imgcodecs.imwrite(file.absolutePath, image)
        return file
    }

    /**
     * Очищает временные файлы
     */
    fun cleanCache(context: Context) {
        var deleted = 0
        context.cacheDir.listFiles()?.filter { it.name.startsWith("temp_") }?.forEach {
            it.delete()
            deleted++
        }
        if (deleted > 0) Log.d("FileManager", "Кэш очищен: $deleted файлов")
    }

    fun saveBatchToPdf(context: Context, pages: List<ByteArray>): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val pdfFile = File(context.filesDir, "$DOCUMENTS_FOLDER/batch_$timestamp.pdf")

        val pdfDocument = PdfDocument()
        for (pageBytes in pages) {
            val bitmap = BitmapFactory.decodeByteArray(pageBytes, 0, pageBytes.size)
            val pageWidth = 595
            val pageHeight = 842
            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pages.indexOf(pageBytes) + 1).create()
            val page = pdfDocument.startPage(pageInfo)
            val canvas = page.canvas
            val scale = minOf(pageWidth.toFloat() / bitmap.width, pageHeight.toFloat() / bitmap.height)
            canvas.drawBitmap(
                bitmap.scale((bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true),
                (pageWidth - bitmap.width * scale) / 2,
                (pageHeight - bitmap.height * scale) / 2,
                null
            )
            pdfDocument.finishPage(page)
        }

        FileOutputStream(pdfFile).use { pdfDocument.writeTo(it) }
        pdfDocument.close()

        // Сохраняем миниатюру первой страницы
        if (pages.isNotEmpty()) {
            val firstPageBitmap = BitmapFactory.decodeByteArray(pages[0], 0, pages[0].size)
            saveThumbnail(context, firstPageBitmap, "batch_$timestamp")
        }

        return pdfFile
    }
}
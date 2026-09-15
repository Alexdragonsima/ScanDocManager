package com.dragonsima.scandoc

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.text.Text
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.File

/**
 * Репозиторий страниц текущей сессии сканирования.
 * Гибридное хранилище: до MAX_IN_MEMORY страниц держим в оперативке,
 * остальные сбрасываем на диск как JPEG в cacheDir.
 */
object PageRepository {

    private const val TAG = "PageRepository"
    private const val MAX_IN_MEMORY = 5

    private val pages = mutableListOf<ScannedPage>()

    fun getCount(): Int = pages.size
    fun isEmpty(): Boolean = pages.isEmpty()

    /**
     * Добавляет страницу. Если в памяти больше MAX_IN_MEMORY — самую старую
     * страницу сбрасываем на диск, освобождая Mat.
     */
    fun addPage(context: Context, mat: Mat, visionText: Text?, thumbnail: Bitmap) {
        if (pages.size >= MAX_IN_MEMORY) {
            flushOldestToDisk(context)
        }
        val pageMat = mat.clone()
        pages.add(
            ScannedPage(
                mat = pageMat,
                visionText = visionText,
                thumbnail = thumbnail,
                filePath = null
            )
        )
        Log.d(TAG, "Добавлена страница, всего: ${pages.size}")
    }

    /**
     * Возвращает копию списка. Mat у страниц в памяти НЕ клонируется —
     * не освобождай его вручную.
     */
    fun getAll(): List<ScannedPage> = pages.toList()

    /**
     * Возвращает страницу по индексу. Если была выгружена на диск —
     * подгружает Mat из файла.
     */
    fun getPage(index: Int): ScannedPage? {
        val page = pages.getOrNull(index) ?: return null
        if (page.mat != null || page.filePath == null) return page

        val loaded = Imgcodecs.imread(page.filePath)
        if (loaded.empty()) {
            Log.e(TAG, "Не удалось загрузить ${page.filePath}")
            return page
        }
        val restored = page.copy(mat = loaded, filePath = null)
        pages[index] = restored
        return restored
    }

    /**
     * Удаляет последнюю страницу (для «Переснять» в мультирежиме).
     */
    fun removeLast() {
        val last = pages.removeLastOrNull() ?: return
        last.mat?.release()
        if (!last.thumbnail.isRecycled) last.thumbnail.recycle()
        last.filePath?.let { File(it).delete() }
        Log.d(TAG, "Удалена последняя страница, осталось: ${pages.size}")
    }

    /**
     * Полная очистка. Вызывать при завершении сессии.
     */
    fun clear() {
        pages.forEach { page ->
            page.mat?.release()
            if (!page.thumbnail.isRecycled) page.thumbnail.recycle()
            page.filePath?.let { File(it).delete() }
        }
        pages.clear()
        Log.d(TAG, "Репозиторий очищен")
    }

    /**
     * Удаляет страницу по индексу.
     */
    fun removeAt(index: Int) {
        val page = pages.removeAt(index)
        page.mat?.release()
        if (!page.thumbnail.isRecycled) page.thumbnail.recycle()
        page.filePath?.let { File(it).delete() }
        Log.d(TAG, "Удалена страница $index, осталось: ${pages.size}")
    }
    // ---------- приватное ----------

    private fun flushOldestToDisk(context: Context) {
        val index = pages.indexOfFirst { it.mat != null }
        if (index < 0) return

        val page = pages[index]
        val mat = page.mat ?: return

        val file = File(context.cacheDir, "page_${System.currentTimeMillis()}_$index.jpg")
        val written = Imgcodecs.imwrite(file.absolutePath, mat)
        if (!written) {
            Log.e(TAG, "Ошибка записи страницы на диск")
            return
        }

        mat.release()
        pages[index] = page.copy(mat = null, filePath = file.absolutePath)
        Log.d(TAG, "Страница $index выгружена на диск: ${file.name}")
    }
}
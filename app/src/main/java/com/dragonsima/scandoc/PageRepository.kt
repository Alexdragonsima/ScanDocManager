package com.dragonsima.scandoc

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.text.Text
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Репозиторий страниц текущей сессии сканирования.
 *
 * Гибридное хранилище: до [MAX_IN_MEMORY] страниц держим в оперативке,
 * остальные сбрасываем на диск как JPEG в cacheDir.
 *
 * Все методы потокобезопасны.
 */
object PageRepository {

    private const val TAG = "PageRepository"
    private const val MAX_IN_MEMORY = 5

    private val pages = mutableListOf<ScannedPage>()
    private val lock = Any()
    private val fileCounter = AtomicLong(0L)

    // ==================== ПУБЛИЧНОЕ API ====================

    fun getCount(): Int = synchronized(lock) { pages.size }

    /**
     * Добавляет страницу. Если в памяти больше [MAX_IN_MEMORY] — самую старую
     * выгружаем на диск, освобождая Mat.
     */
    fun addPage(context: Context, mat: Mat, visionText: Text?, thumbnail: Bitmap) {
        synchronized(lock) {
            if (pages.size >= MAX_IN_MEMORY) {
                flushOldestToDiskLocked(context)
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
            if (BuildConfig.DEBUG) Log.d(TAG, "Добавлена страница, всего: ${pages.size}")
        }
    }

    /**
     * Возвращает копию списка. Mat у страниц в памяти НЕ клонируется —
     * не освобождай его вручную.
     */
    fun getAll(): List<ScannedPage> = synchronized(lock) { pages.toList() }

    /**
     * Удаляет страницу по индексу.
     */
    fun removeAt(index: Int) {
        synchronized(lock) {
            if (index !in pages.indices) return
            val page = pages.removeAt(index)
            releasePage(page)
            if (BuildConfig.DEBUG) Log.d(TAG, "Удалена страница $index, осталось: ${pages.size}")
        }
    }

    /**
     * Полная очистка. Вызывать при завершении сессии.
     */
    fun clear() {
        synchronized(lock) {
            pages.forEach { releasePage(it) }
            pages.clear()
            if (BuildConfig.DEBUG) Log.d(TAG, "Репозиторий очищен")
        }
    }

    // ==================== ПРИВАТНОЕ ====================

    /**
     * Освобождает ресурсы одной страницы: Mat, thumbnail, файл.
     * Не трогает коллекцию — вызывается под lock.
     */
    private fun releasePage(page: ScannedPage) {
        page.mat?.release()
        if (!page.thumbnail.isRecycled) page.thumbnail.recycle()
        page.filePath?.let { runCatching { File(it).delete() } }
    }

    /**
     * Выгружает самую старую страницу с Mat в памяти на диск.
     * Вызывается под lock.
     */
    private fun flushOldestToDiskLocked(context: Context) {
        val index = pages.indexOfFirst { it.mat != null }
        if (index < 0) return

        val page = pages[index]
        val mat = page.mat ?: return

        val uniqueId = fileCounter.incrementAndGet()
        val file = File(
            context.cacheDir,
            "page_${System.currentTimeMillis()}_${uniqueId}_$index.jpg"
        )

        val written = try {
            Imgcodecs.imwrite(file.absolutePath, mat)
        } catch (e: Exception) {
            Log.e(TAG, "Исключение при записи страницы", e)
            false
        }

        if (!written) {
            // Не смогли записать — освобождаем Mat и помечаем как потерянную
            Log.e(TAG, "Ошибка записи страницы $index на диск — освобождаем Mat")
            mat.release()
            pages[index] = page.copy(mat = null, filePath = null)
            return
        }

        // Успешно записали — освобождаем Mat в памяти
        mat.release()
        pages[index] = page.copy(mat = null, filePath = file.absolutePath)
        if (BuildConfig.DEBUG) Log.d(TAG, "Страница $index выгружена: ${file.name}")
    }
}
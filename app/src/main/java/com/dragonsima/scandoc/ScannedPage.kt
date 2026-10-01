package com.dragonsima.scandoc

import android.graphics.Bitmap
import com.google.mlkit.vision.text.Text
import org.opencv.core.Mat

/**
 * Одна отсканированная страница.
 *
 * Всегда содержит хотя бы одно из:
 *  - [mat] — изображение в памяти,
 *  - [filePath] — путь к JPEG-файлу на диске (для гибридного хранилища).
 *
 * [thumbnail] всегда в памяти — используется в UI.
 * [visionText] — результат OCR (если был выполнен), используется для индексации PDF.
 */
@Suppress("ArrayInDataClass")
data class ScannedPage(
    val mat: Mat?,
    val visionText: Text?,
    val thumbnail: Bitmap,
    val filePath: String? = null
) {
    init {
        require(mat != null || filePath != null) {
            "ScannedPage должен иметь либо mat, либо filePath"
        }
    }
}
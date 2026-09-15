package com.dragonsima.scandoc

import android.graphics.Bitmap
import com.google.mlkit.vision.text.Text
import org.opencv.core.Mat

/**
 * Одна отсканированная страница.
 * Хранит либо Mat в памяти (mat != null), либо путь к файлу на диске (filePath != null).
 * Thumbnail всегда в памяти — для UI.
 */
data class ScannedPage(
    val mat: Mat?,
    val visionText: Text?,
    val thumbnail: Bitmap,
    val filePath: String? = null
)
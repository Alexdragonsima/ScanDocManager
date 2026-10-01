package com.dragonsima.scandoc

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.io.File
import java.util.Locale

/**
 * Единая точка действий с файлами: открыть, поделиться, показать диалог после сохранения.
 * Используется из CameraActivity, DocumentsActivity, PdfProtectionActivity.
 */
object DocumentActions {

    // ==================== ОТКРЫТИЕ ====================

    /** Открыть любой файл подходящим приложением (по mime-типу). */
    fun openFile(context: Context, file: File): Boolean =
        openInternal(context, file, mimeOf(file))

    /** Открыть JPG. */
    fun openImage(context: Context, file: File): Boolean =
        openInternal(context, file, "image/jpeg")

    private fun openInternal(context: Context, file: File, mime: String): Boolean {
        if (!file.exists()) {
            Toast.makeText(context, context.getString(R.string.camera_file_not_found), Toast.LENGTH_SHORT).show()
            return false
        }
        return try {
            val uri = buildFileUri(context, file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            val msgRes = when {
                mime.startsWith("image/") -> R.string.actions_no_viewer_image
                mime == "application/pdf" -> R.string.actions_no_viewer_pdf
                else -> R.string.actions_no_viewer_general
            }
            Toast.makeText(context, context.getString(msgRes), Toast.LENGTH_SHORT).show()
            false
        }
    }

    // ==================== SHARE: ОДИН ФАЙЛ ====================

    /** Поделиться одним файлом. */
    fun shareFile(context: Context, file: File): Boolean =
        shareInternal(context, file, mimeOf(file))

    /** Поделиться JPG. */
    fun shareImage(context: Context, file: File): Boolean =
        shareInternal(context, file, "image/jpeg")

    private fun shareInternal(context: Context, file: File, mime: String): Boolean {
        if (!file.exists()) return false
        return try {
            val uri = buildFileUri(context, file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.common_share)))
            true
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.actions_share_error), Toast.LENGTH_SHORT).show()
            false
        }
    }

    // ==================== SHARE: НЕСКОЛЬКО ФАЙЛОВ ====================

    /** Поделиться несколькими JPG сразу. */
    fun shareImages(context: Context, files: List<File>): Boolean =
        shareMultipleInternal(context, files, "image/jpeg")

    /** Поделиться несколькими PDF сразу. */
    fun shareMultiplePdfs(context: Context, files: List<File>): Boolean =
        shareMultipleInternal(context, files, "application/pdf")

    private fun shareMultipleInternal(context: Context, files: List<File>, mime: String): Boolean {
        val existing = files.filter { it.exists() }
        if (existing.isEmpty()) return false

        return try {
            val uris = ArrayList<Uri>(existing.size).apply {
                existing.forEach { add(buildFileUri(context, it)) }
            }
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mime
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.common_share)))
            true
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.actions_share_error), Toast.LENGTH_SHORT).show()
            false
        }
    }

    // ==================== BOTTOM SHEET ПОСЛЕ СОХРАНЕНИЯ ====================

    /**
     * Bottom sheet: открыть / поделиться / защитить / готово.
     */
    fun showSavedDialog(
        context: Context,
        file: File,
        pagesCount: Int = 1,
        onDone: () -> Unit
    ) {
        val message = if (pagesCount > 1) {
            context.getString(R.string.actions_saved_multi, file.name, pagesCount)
        } else {
            file.name
        }

        val sheetView = LayoutInflater.from(context).inflate(R.layout.sheet_saved_actions, null)
        val sheetDialog = BottomSheetDialog(context)
        sheetDialog.setContentView(sheetView)

        sheetView.findViewById<TextView>(R.id.sheetTitle).text =
            context.getString(R.string.actions_saved_title)
        sheetView.findViewById<TextView>(R.id.sheetMessage).text = message

        sheetView.findViewById<View>(R.id.actionOpen).setOnClickListener {
            sheetDialog.dismiss()
            openFile(context, file)
            onDone()
        }
        sheetView.findViewById<View>(R.id.actionShare).setOnClickListener {
            sheetDialog.dismiss()
            shareFile(context, file)
            onDone()
        }
        sheetView.findViewById<View>(R.id.actionProtect).setOnClickListener {
            sheetDialog.dismiss()
            context.startActivity(PdfProtectionActivity.createIntent(context, file))
            onDone()
        }
        sheetView.findViewById<View>(R.id.actionDone).setOnClickListener {
            sheetDialog.dismiss()
            onDone()
        }

        sheetDialog.setCancelable(false)
        sheetDialog.show()
    }

    // ==================== УТИЛИТЫ ====================

    private fun buildFileUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    private fun mimeOf(file: File): String =
        when (file.extension.lowercase(Locale.ROOT)) {
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            else -> "*/*"
        }
}
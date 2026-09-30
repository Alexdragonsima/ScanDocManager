package com.dragonsima.scandoc

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * Общие действия с PDF-файлами: открыть, поделиться, показать диалог после сохранения.
 * Используется из CameraActivity и DocumentsActivity.
 */
object DocumentActions {

    fun openPdf(context: Context, file: File): Boolean {
        if (!file.exists()) {
            Toast.makeText(context, context.getString(R.string.camera_file_not_found), Toast.LENGTH_SHORT).show()
            return false
        }
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.actions_no_viewer_pdf), Toast.LENGTH_SHORT).show()
            false
        }
    }

    fun sharePdf(context: Context, file: File): Boolean {
        if (!file.exists()) {
            Toast.makeText(context, context.getString(R.string.camera_file_not_found), Toast.LENGTH_SHORT).show()
            return false
        }
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.actions_share_chooser_pdf)))
            true
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.actions_share_error), Toast.LENGTH_SHORT).show()
            false
        }
    }

    /**
     * Bottom sheet после сохранения PDF: открыть / поделиться / защитить / готово.
     */
    fun showSavedDialog(
        context: Context,
        file: File,
        pagesCount: Int = 1,
        onDone: () -> Unit
    ) {
        val title = context.getString(R.string.actions_saved_title)
        val message = if (pagesCount > 1) {
            context.getString(R.string.actions_saved_multi, file.name, pagesCount)
        } else {
            file.name
        }

        val sheetView = android.view.LayoutInflater.from(context)
            .inflate(R.layout.sheet_saved_actions, null)

        val sheetDialog = com.google.android.material.bottomsheet.BottomSheetDialog(context)
        sheetDialog.setContentView(sheetView)

        sheetView.findViewById<android.widget.TextView>(R.id.sheetTitle).text = title
        sheetView.findViewById<android.widget.TextView>(R.id.sheetMessage).text = message

        // Открыть
        sheetView.findViewById<android.view.View>(R.id.actionOpen).setOnClickListener {
            sheetDialog.dismiss()
            openFile(context, file)
            onDone()
        }

        // Поделиться
        sheetView.findViewById<android.view.View>(R.id.actionShare).setOnClickListener {
            sheetDialog.dismiss()
            shareFile(context, file)
            onDone()
        }

        // Защитить паролем
        sheetView.findViewById<android.view.View>(R.id.actionProtect).setOnClickListener {
            sheetDialog.dismiss()
            val intent = PdfProtectionActivity.createIntent(context, file)
            context.startActivity(intent)
            onDone()
        }

        // Готово
        sheetView.findViewById<android.view.View>(R.id.actionDone).setOnClickListener {
            sheetDialog.dismiss()
            onDone()
        }

        sheetDialog.setCancelable(false)
        sheetDialog.show()
    }

    fun openImage(context: Context, file: File): Boolean {
        if (!file.exists()) {
            Toast.makeText(context, context.getString(R.string.camera_file_not_found), Toast.LENGTH_SHORT).show()
            return false
        }
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/jpeg")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.actions_no_viewer_image), Toast.LENGTH_SHORT).show()
            false
        }
    }

    fun shareImage(context: Context, file: File): Boolean {
        if (!file.exists()) return false
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.actions_share_chooser_image)))
            true
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.actions_share_error), Toast.LENGTH_SHORT).show()
            false
        }
    }

    /**
     * Поделиться несколькими JPG сразу.
     */
    fun shareImages(context: Context, files: List<File>): Boolean {
        val existing = files.filter { it.exists() }
        if (existing.isEmpty()) return false
        return try {
            val uris = existing.map {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    it
                )
            }.toTypedArray()

            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/jpeg"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris.toList()))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.actions_share_chooser_image)))
            true
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.actions_share_error), Toast.LENGTH_SHORT).show()
            false
        }
    }

    fun shareMultiplePdfs(context: Context, files: List<File>): Boolean {
        val existing = files.filter { it.exists() }
        if (existing.isEmpty()) return false
        return try {
            val uris = existing.map {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    it
                )
            }.toTypedArray()

            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "application/pdf"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris.toList()))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.docs_share_multiple_title)))
            true
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.actions_share_error), Toast.LENGTH_SHORT).show()
            false
        }
    }

    /**
     * MIME-тип по расширению файла.
     */
    private fun mimeOf(file: File): String {
        return when (file.extension.lowercase()) {
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            else -> "*/*"
        }
    }

    /**
     * Открывает файл любым подходящим приложением.
     */
    fun openFile(context: Context, file: File): Boolean {
        if (!file.exists()) {
            Toast.makeText(context, context.getString(R.string.camera_file_not_found), Toast.LENGTH_SHORT).show()
            return false
        }
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeOf(file))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Toast.makeText(context, "Нет приложения для этого типа", Toast.LENGTH_SHORT).show()
            false
        }
    }

    /**
     * Делится одним файлом.
     */
    fun shareFile(context: Context, file: File): Boolean {
        if (!file.exists()) return false
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeOf(file)
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
}
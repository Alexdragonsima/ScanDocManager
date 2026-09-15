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
            Toast.makeText(context, "Файл не найден", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(context, "Нет программы для просмотра PDF", Toast.LENGTH_SHORT).show()
            false
        }
    }

    fun sharePdf(context: Context, file: File): Boolean {
        if (!file.exists()) {
            Toast.makeText(context, "Файл не найден", Toast.LENGTH_SHORT).show()
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
            context.startActivity(Intent.createChooser(intent, "Поделиться"))
            true
        } catch (e: Exception) {
            Toast.makeText(context, "Ошибка при попытке поделиться", Toast.LENGTH_SHORT).show()
            false
        }
    }

    /**
     * Показывает диалог после успешного сохранения PDF:
     * Открыть / Поделиться / Готово.
     * onDone вызывается в любом случае — чтобы закрыть текущую активити.
     */
    fun showSavedDialog(
        context: Context,
        file: File,
        pagesCount: Int = 1,
        onDone: () -> Unit
    ) {
        val message = if (pagesCount > 1) {
            "${file.name}\nСтраниц: $pagesCount"
        } else {
            file.name
        }

        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle("✅ PDF сохранён")
            .setMessage(message)
            .setPositiveButton("Открыть") { _, _ ->
                openPdf(context, file)
                onDone()
            }
            .setNeutralButton("Поделиться") { _, _ ->
                sharePdf(context, file)
                onDone()
            }
            .setNegativeButton("Готово") { _, _ ->
                onDone()
            }
            .setCancelable(false)
            .show()
    }

    fun openImage(context: Context, file: File): Boolean {
        if (!file.exists()) {
            Toast.makeText(context, "Файл не найден", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(context, "Нет программы для просмотра", Toast.LENGTH_SHORT).show()
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
            context.startActivity(Intent.createChooser(intent, "Поделиться"))
            true
        } catch (e: Exception) {
            Toast.makeText(context, "Ошибка", Toast.LENGTH_SHORT).show()
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
            context.startActivity(Intent.createChooser(intent, "Поделиться"))
            true
        } catch (e: Exception) {
            Toast.makeText(context, "Ошибка", Toast.LENGTH_SHORT).show()
            false
        }
    }
}
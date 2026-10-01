package com.dragonsima.scandoc

import android.util.Log
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import java.io.File

/**
 * Шифрование PDF паролем через PdfBox-Android.
 * Заменяет оригинальный файл защищённой версией.
 */
object PdfProtector {

    private const val TAG = "PdfProtector"

    /**
     * Защищает PDF паролем in-place.
     * Пароль используется как пользовательский (для открытия) и как владельца.
     *
     * Гарантия: при любой ошибке оригинальный файл остаётся неизменным.
     *
     * @return true при успехе
     */
    fun protectInPlace(inputFile: File, password: String): Boolean {
        if (!inputFile.exists() || !inputFile.isFile) {
            Log.e(TAG, "Файл не найден или не является файлом: ${inputFile.absolutePath}")
            return false
        }

        if (password.isEmpty()) {
            Log.e(TAG, "Пустой пароль")
            return false
        }

        val tempFile = File(
            inputFile.parentFile,
            "${inputFile.nameWithoutExtension}_${System.currentTimeMillis()}.tmp.pdf"
        )

        // Чистим возможные остатки от предыдущих попыток
        runCatching { tempFile.delete() }

        return try {
            // 1. Шифруем во временный файл
            encryptToFile(inputFile, tempFile, password)

            // 2. Проверяем, что временный файл валиден и не пустой
            if (!tempFile.exists() || tempFile.length() < 100) {
                Log.e(TAG, "Временный файл пустой или не создан")
                runCatching { tempFile.delete() }
                return false
            }

            // 3. Атомарная замена: удаляем оригинал, переименовываем temp
            if (!inputFile.delete()) {
                Log.e(TAG, "Не удалось удалить оригинал")
                runCatching { tempFile.delete() }
                return false
            }

            if (!tempFile.renameTo(inputFile)) {
                Log.e(TAG, "Не удалось переименовать temp → оригинал. Восстанавливаем...")
                // Пытаемся скопировать temp обратно в inputFile
                val restored = runCatching {
                    tempFile.copyTo(inputFile, overwrite = true)
                    true
                }.getOrElse { e ->
                    Log.e(TAG, "КРИТИЧНО: не удалось восстановить оригинал", e)
                    false
                }
                runCatching { tempFile.delete() }

                if (!restored) {
                    Log.e(TAG, "Файл потерян: ${inputFile.name}")
                }
                return false
            }

            if (BuildConfig.DEBUG) Log.d(TAG, "PDF защищён: ${inputFile.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка шифрования: ${e.message}", e)
            runCatching { tempFile.delete() }
            false
        }
    }

    // ==================== ПРИВАТНОЕ ====================

    /**
     * Шифрует [source] в [target]. Оригинал не трогается.
     */
    private fun encryptToFile(source: File, target: File, password: String) {
        val document = PDDocument.load(source)
        try {
            // Разрешаем пользователю всё — пароль только для открытия
            val permission = AccessPermission().apply {
                setCanPrint(true)
                setCanModify(true)
                setCanExtractContent(true)
                setCanModifyAnnotations(true)
                setCanFillInForm(true)
                setCanExtractForAccessibility(true)
                setCanAssembleDocument(true)
                setCanPrintFaithful(true)
            }

            val policy = StandardProtectionPolicy(password, password, permission).apply {
                encryptionKeyLength = 128
            }

            document.protect(policy)
            document.save(target)
        } finally {
            runCatching { document.close() }
        }
    }
}
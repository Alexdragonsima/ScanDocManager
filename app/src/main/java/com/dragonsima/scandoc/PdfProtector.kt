package com.dragonsima.scandoc

import android.util.Log
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import java.io.File

/**
 * Шифрование PDF паролем через PdfBox-Android.
 */
object PdfProtector {

    private const val TAG = "PdfProtector"

    /**
     * Защищает PDF паролем, заменяя оригинальный файл.
     * Пароль используется и как пользовательский (для открытия), и как владельца
     * (для снятия ограничений).
     *
     * @return true при успехе
     */
    fun protectInPlace(inputFile: File, password: String): Boolean {
        if (!inputFile.exists()) {
            Log.e(TAG, "Файл не найден: ${inputFile.absolutePath}")
            return false
        }

        val tempFile = File(inputFile.parentFile, "${inputFile.nameWithoutExtension}_protected.tmp.pdf")

        return try {
            val document = PDDocument.load(inputFile)
            try {
                // Разрешаем всё — пароль только для открытия
                val permission = AccessPermission()
                permission.setCanPrint(true)
                permission.setCanModify(true)
                permission.setCanExtractContent(true)
                permission.setCanModifyAnnotations(true)
                permission.setCanFillInForm(true)
                permission.setCanExtractForAccessibility(true)
                permission.setCanAssembleDocument(true)
                permission.setCanPrintFaithful(true)

                val policy = StandardProtectionPolicy(password, password, permission).apply {
                    encryptionKeyLength = 128
                }

                document.protect(policy)
                document.save(tempFile)
            } finally {
                document.close()
            }

            // Заменяем оригинал
            if (!inputFile.delete()) {
                Log.e(TAG, "Не удалось удалить оригинал")
                tempFile.delete()
                return false
            }

            if (!tempFile.renameTo(inputFile)) {
                Log.e(TAG, "Не удалось переименовать temp в оригинал")
                // Пытаемся восстановить: копируем обратно
                tempFile.copyTo(inputFile, overwrite = true)
                tempFile.delete()
                return false
            }

            Log.d(TAG, "PDF защищён: ${inputFile.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка шифрования: ${e.message}", e)
            tempFile.delete()
            false
        }
    }
}
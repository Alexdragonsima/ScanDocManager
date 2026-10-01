package com.dragonsima.scandoc

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

/**
 * Application-класс.
 * Инициализирует PDFBox и применяет сохранённую тему ДО создания первой Activity.
 */
class ScanDocApp : Application() {

    companion object {
        private const val TAG = "ScanDocApp"

        /**
         * Флаг разблокировки PIN в текущей сессии приложения.
         * Сбрасывается при смерти процесса (свайп из недавних / kill).
         * НЕ сохраняется в SharedPreferences — это намеренно.
         */
        @Volatile
        var isUnlocked: Boolean = false
    }

    override fun onCreate() {
        super.onCreate()

        initPdfBox()
        applySavedTheme()
    }

    /**
     * Инициализация PDFBox для шифрования PDF.
     * Если не удастся — приложение всё равно запустится,
     * только функция «защита паролем» будет недоступна.
     */
    private fun initPdfBox() {
        try {
            PDFBoxResourceLoader.init(applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка инициализации PDFBox", e)
        }
    }

    /**
     * Применяет сохранённую тему к AppCompatDelegate.
     * Вызывается из Application.onCreate — до создания Activity.
     */
    private fun applySavedTheme() {
        val prefs = getSharedPreferences(SettingsActivity.PREFS_SETTINGS, MODE_PRIVATE)
        val theme = prefs.getString(SettingsActivity.KEY_THEME, SettingsActivity.THEME_SYSTEM)
            ?: SettingsActivity.THEME_SYSTEM

        val nightMode = when (theme) {
            SettingsActivity.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            SettingsActivity.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }
}
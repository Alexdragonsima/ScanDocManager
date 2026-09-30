package com.dragonsima.scandoc

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class ScanDocApp : Application() {

    companion object {
        @Volatile
        var isUnlocked: Boolean = false
    }

    override fun onCreate() {
        super.onCreate()

        // Инициализация PDFBox (обязательно до первого использования)
        PDFBoxResourceLoader.init(applicationContext)

        applySavedTheme()
    }

    private fun applySavedTheme() {
        val prefs = getSharedPreferences(SettingsActivity.PREFS_SETTINGS, MODE_PRIVATE)
        val theme = prefs.getString(SettingsActivity.KEY_THEME, "system") ?: "system"
        val nightMode = when (theme) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }
}
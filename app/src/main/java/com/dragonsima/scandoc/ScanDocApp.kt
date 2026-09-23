package com.dragonsima.scandoc

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

/**
 * Application-класс приложения.
 * Применяет сохранённую тему до создания первой активити.
 */
class ScanDocApp : Application() {

    override fun onCreate() {
        super.onCreate()
        applySavedTheme()
    }

    /**
     * Читает тему из SharedPreferences и применяет её глобально.
     * Вызывается один раз при старте приложения.
     */
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
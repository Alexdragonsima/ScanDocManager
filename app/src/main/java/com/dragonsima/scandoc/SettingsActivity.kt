package com.dragonsima.scandoc

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SettingsActivity : AppCompatActivity() {

    companion object {
        const val PREFS_SETTINGS = "settings_prefs"

        // Тема
        const val KEY_THEME = "theme_mode"                 // "light" | "dark" | "system"
        const val KEY_DYNAMIC_COLORS = "dynamic_colors"    // Boolean

        // Сканирование
        const val KEY_DEFAULT_FORMAT = "default_format"    // "pdf" | "jpg"
        const val KEY_PDF_QUALITY = "pdf_quality"          // "high" | "medium" | "low"
        const val KEY_AUTO_ROTATE = "auto_rotate"          // Boolean
    }

    private lateinit var prefs: android.content.SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE)

        // Back
        findViewById<ImageView>(R.id.backButton).setOnClickListener { finish() }

        // === ВНЕШНИЙ ВИД ===
        findViewById<android.view.View>(R.id.themeRow).setOnClickListener {
            showThemeDialog()
        }

        findViewById<MaterialSwitch>(R.id.dynamicColorsSwitch).apply {
            isChecked = prefs.getBoolean(KEY_DYNAMIC_COLORS, true)
            setOnCheckedChangeListener { _, value ->
                prefs.edit { putBoolean(KEY_DYNAMIC_COLORS, value) }
                Toast.makeText(this@SettingsActivity, "Перезапустите приложение для применения", Toast.LENGTH_SHORT).show()
            }
        }

        // === СКАНИРОВАНИЕ ===
        findViewById<android.view.View>(R.id.defaultFormatRow).setOnClickListener {
            showFormatDialog()
        }

        findViewById<android.view.View>(R.id.pdfQualityRow).setOnClickListener {
            showQualityDialog()
        }

        findViewById<MaterialSwitch>(R.id.autoRotateSwitch).apply {
            isChecked = prefs.getBoolean(KEY_AUTO_ROTATE, false)
            setOnCheckedChangeListener { _, value ->
                prefs.edit { putBoolean(KEY_AUTO_ROTATE, value) }
            }
        }

        // === ДАННЫЕ ===
        findViewById<android.view.View>(R.id.clearCacheRow).setOnClickListener {
            confirmClearCache()
        }

        // === ПРИЛОЖЕНИЕ ===
        findViewById<android.view.View>(R.id.aboutRow).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        findViewById<android.view.View>(R.id.onboardingRow).setOnClickListener {
            resetOnboarding()
        }

        // Заполняем значения
        refreshValues()
    }

    override fun onResume() {
        super.onResume()
        refreshValues()
    }

    // ==================== ВНЕШНИЙ ВИД ====================

    private fun showThemeDialog() {
        val modes = arrayOf(
            getString(R.string.settings_theme_light),
            getString(R.string.settings_theme_dark),
            getString(R.string.settings_theme_system)
        )
        val current = prefs.getString(KEY_THEME, "system") ?: "system"
        val checked = when (current) {
            "light" -> 0
            "dark" -> 1
            else -> 2
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_theme_title))
            .setSingleChoiceItems(modes, checked) { dialog, which ->
                val mode = when (which) {
                    0 -> "light"
                    1 -> "dark"
                    else -> "system"
                }
                prefs.edit { putString(KEY_THEME, mode) }
                applyTheme(mode)
                refreshValues()
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun applyTheme(mode: String) {
        val nightMode = when (mode) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    // ==================== СКАНИРОВАНИЕ ====================

    private fun showFormatDialog() {
        val formats = arrayOf("PDF", "JPG")
        val current = prefs.getString(KEY_DEFAULT_FORMAT, "pdf") ?: "pdf"
        val checked = if (current == "jpg") 1 else 0

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_format_title))
            .setSingleChoiceItems(formats, checked) { dialog, which ->
                prefs.edit { putString(KEY_DEFAULT_FORMAT, if (which == 1) "jpg" else "pdf") }
                refreshValues()
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun showQualityDialog() {
        val qualities = arrayOf(
            getString(R.string.settings_pdf_quality_high),
            getString(R.string.settings_pdf_quality_medium),
            getString(R.string.settings_pdf_quality_low)
        )
        val current = prefs.getString(KEY_PDF_QUALITY, "high") ?: "high"
        val checked = when (current) {
            "medium" -> 1
            "low" -> 2
            else -> 0
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_pdf_quality_title))
            .setSingleChoiceItems(qualities, checked) { dialog, which ->
                val value = when (which) {
                    1 -> "medium"
                    2 -> "low"
                    else -> "high"
                }
                prefs.edit { putString(KEY_PDF_QUALITY, value) }
                refreshValues()
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    // ==================== ДАННЫЕ ====================

    private fun confirmClearCache() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_clear_cache_title))
            .setMessage(getString(R.string.settings_clear_cache_message))
            .setPositiveButton(getString(R.string.common_ok)) { _, _ ->
                FileManager.cleanCache(this)
                // Дополнительно — чистим временные raw/crop файлы
                cacheDir.listFiles()?.forEach { f ->
                    if (f.name.startsWith("raw_") || f.name.startsWith("hdr_") ||
                        f.name.startsWith("page_") || f.name.startsWith("mlkit_")) {
                        f.delete()
                    }
                }
                Toast.makeText(this, getString(R.string.settings_clear_cache_done), Toast.LENGTH_SHORT).show()
                refreshStats()
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun refreshStats() {
        lifecycleScope.launch {
            val stats = withContext(Dispatchers.IO) {
                val docsDir = File(filesDir, "Documents")
                val pdfs = docsDir.listFiles { f -> f.extension == "pdf" }?.size ?: 0
                val totalBytes = docsDir.listFiles()?.sumOf { it.length() } ?: 0L
                Pair(pdfs, totalBytes)
            }
            val (count, bytes) = stats
            val sizeStr = formatSize(bytes)
            findViewById<TextView>(R.id.statsValueText).text =
                getString(R.string.settings_stats_format, count, sizeStr)
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes Б"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f КБ", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f МБ", mb)
        val gb = mb / 1024.0
        return String.format("%.2f ГБ", gb)
    }

    // ==================== ПРИЛОЖЕНИЕ ====================

    private fun resetOnboarding() {
        val appPrefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        appPrefs.edit { putBoolean("onboarding_completed", false) }
        startActivity(Intent(this, OnboardingActivity::class.java))
        finish()
    }

    // ==================== ОБНОВЛЕНИЕ UI ====================

    private fun refreshValues() {
        // Тема
        val theme = prefs.getString(KEY_THEME, "system") ?: "system"
        findViewById<TextView>(R.id.themeValueText).text = when (theme) {
            "light" -> getString(R.string.settings_theme_light)
            "dark" -> getString(R.string.settings_theme_dark)
            else -> getString(R.string.settings_theme_system)
        }

        // Формат
        val format = prefs.getString(KEY_DEFAULT_FORMAT, "pdf") ?: "pdf"
        findViewById<TextView>(R.id.defaultFormatValueText).text = format.uppercase()

        // Качество
        val quality = prefs.getString(KEY_PDF_QUALITY, "high") ?: "high"
        findViewById<TextView>(R.id.pdfQualityValueText).text = when (quality) {
            "medium" -> getString(R.string.settings_pdf_quality_medium)
            "low" -> getString(R.string.settings_pdf_quality_low)
            else -> getString(R.string.settings_pdf_quality_high)
        }

        refreshStats()
    }
}
package com.dragonsima.scandoc

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    companion object {
        const val PREFS_SETTINGS = "settings_prefs"

        // Тема
        const val KEY_THEME = "theme_mode"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        const val THEME_SYSTEM = "system"

        // Прочее
        const val KEY_DYNAMIC_COLORS = "dynamic_colors"
        const val KEY_DEFAULT_FORMAT = "default_format"
        const val KEY_PDF_QUALITY = "pdf_quality"
        const val KEY_AUTO_ROTATE = "auto_rotate"
        const val KEY_SAVE_FOLDER_URI = "save_folder_uri"

        const val FORMAT_PDF = "pdf"
        const val FORMAT_JPG = "jpg"

        const val QUALITY_HIGH = "high"
        const val QUALITY_MEDIUM = "medium"
        const val QUALITY_LOW = "low"

        // App preferences (общие с MainActivity/OnboardingActivity)
        const val PREFS_APP = "app_prefs"
        const val KEY_ONBOARDING_DONE = "onboarding_completed"

        private const val TAG = "SettingsActivity"
    }

    private lateinit var prefs: android.content.SharedPreferences

    // ==================== LAUNCHERS ====================

    /**
     * Единый launcher для PIN: возвращает OK → значит пользователь успешно
     * прошёл PIN (создал / сменил / ввёл старый).
     */
    private val pinLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            ScanDocApp.isUnlocked = true
        }
        refreshValues()
    }

    /**
     * Отдельный launcher для подтверждения перед удалением PIN:
     * при OK удаляем PIN.
     */
    private val pinVerifyLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            PinActivity.clearPin(this)
            ScanDocApp.isUnlocked = true
            Toast.makeText(this, getString(R.string.pin_disabled_success), Toast.LENGTH_SHORT).show()
            refreshValues()
        }
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data ?: return@registerForActivityResult
        if (result.resultCode != RESULT_OK) return@registerForActivityResult

        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            prefs.edit { putString(KEY_SAVE_FOLDER_URI, uri.toString()) }
            Toast.makeText(
                this,
                getString(R.string.settings_save_folder_copied, uri.lastPathSegment ?: ""),
                Toast.LENGTH_SHORT
            ).show()
            refreshValues()
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось сохранить URI папки", e)
            Toast.makeText(this, getString(R.string.settings_save_folder_error), Toast.LENGTH_SHORT).show()
        }
    }

    // ==================== ЖИЗНЕННЫЙ ЦИКЛ ====================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE)

        setupListeners()
        refreshValues()
    }

    override fun onResume() {
        super.onResume()
        refreshValues()
    }

    // ==================== ПОДКЛЮЧЕНИЕ UI ====================

    private fun setupListeners() {
        findViewById<ImageView>(R.id.backButton).setOnClickListener { finish() }

        // === ВНЕШНИЙ ВИД ===
        findViewById<View>(R.id.themeRow).setOnClickListener { showThemeDialog() }

        findViewById<MaterialSwitch>(R.id.dynamicColorsSwitch).apply {
            isChecked = prefs.getBoolean(KEY_DYNAMIC_COLORS, true)
            setOnCheckedChangeListener { _, value ->
                prefs.edit { putBoolean(KEY_DYNAMIC_COLORS, value) }
                Toast.makeText(
                    this@SettingsActivity,
                    getString(R.string.settings_restart_needed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        // === СКАНИРОВАНИЕ ===
        findViewById<View>(R.id.defaultFormatRow).setOnClickListener { showFormatDialog() }
        findViewById<View>(R.id.pdfQualityRow).setOnClickListener { showQualityDialog() }

        findViewById<MaterialSwitch>(R.id.autoRotateSwitch).apply {
            isChecked = prefs.getBoolean(KEY_AUTO_ROTATE, false)
            setOnCheckedChangeListener { _, value ->
                prefs.edit { putBoolean(KEY_AUTO_ROTATE, value) }
            }
        }

        // === ПАПКА СОХРАНЕНИЯ ===
        findViewById<View>(R.id.saveFolderRow).setOnClickListener { showSaveFolderDialog() }

        // === ДАННЫЕ ===
        findViewById<View>(R.id.clearCacheRow).setOnClickListener { confirmClearCache() }

        // === БЕЗОПАСНОСТЬ ===
        findViewById<View>(R.id.pinRow).setOnClickListener { showPinDialog() }

        // === ПРИЛОЖЕНИЕ ===
        findViewById<View>(R.id.aboutRow).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        findViewById<View>(R.id.onboardingRow).setOnClickListener { resetOnboarding() }
    }

    // ==================== ВНЕШНИЙ ВИД ====================

    private fun showThemeDialog() {
        val modes = arrayOf(
            getString(R.string.settings_theme_light),
            getString(R.string.settings_theme_dark),
            getString(R.string.settings_theme_system)
        )
        val current = prefs.getString(KEY_THEME, THEME_SYSTEM) ?: THEME_SYSTEM
        val checked = when (current) {
            THEME_LIGHT -> 0
            THEME_DARK -> 1
            else -> 2
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_theme_title))
            .setSingleChoiceItems(modes, checked) { dialog, which ->
                val mode = when (which) {
                    0 -> THEME_LIGHT
                    1 -> THEME_DARK
                    else -> THEME_SYSTEM
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
            THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    // ==================== СКАНИРОВАНИЕ ====================

    private fun showFormatDialog() {
        val formats = arrayOf("PDF", "JPG")
        val current = prefs.getString(KEY_DEFAULT_FORMAT, FORMAT_PDF) ?: FORMAT_PDF
        val checked = if (current == FORMAT_JPG) 1 else 0

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_format_title))
            .setSingleChoiceItems(formats, checked) { dialog, which ->
                val value = if (which == 1) FORMAT_JPG else FORMAT_PDF
                prefs.edit { putString(KEY_DEFAULT_FORMAT, value) }
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
        val current = prefs.getString(KEY_PDF_QUALITY, QUALITY_HIGH) ?: QUALITY_HIGH
        val checked = when (current) {
            QUALITY_MEDIUM -> 1
            QUALITY_LOW -> 2
            else -> 0
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_pdf_quality_title))
            .setSingleChoiceItems(qualities, checked) { dialog, which ->
                val value = when (which) {
                    1 -> QUALITY_MEDIUM
                    2 -> QUALITY_LOW
                    else -> QUALITY_HIGH
                }
                prefs.edit { putString(KEY_PDF_QUALITY, value) }
                refreshValues()
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    // ==================== ПАПКА СОХРАНЕНИЯ ====================

    private fun showSaveFolderDialog() {
        val currentUri = prefs.getString(KEY_SAVE_FOLDER_URI, null)

        if (currentUri == null) {
            launchFolderPicker()
        } else {
            val options = arrayOf(
                getString(R.string.settings_save_folder_pick),
                getString(R.string.settings_save_folder_unlink)
            )
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.settings_save_folder_title))
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> launchFolderPicker()
                        1 -> unlinkFolder()
                    }
                }
                .setNegativeButton(getString(R.string.common_cancel), null)
                .show()
        }
    }

    private fun launchFolderPicker() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_save_folder_title))
            .setMessage(getString(R.string.settings_save_folder_description))
            .setPositiveButton(getString(R.string.settings_save_folder_pick)) { _, _ ->
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    )
                }
                folderPickerLauncher.launch(intent)
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun unlinkFolder() {
        val uriStr = prefs.getString(KEY_SAVE_FOLDER_URI, null) ?: return
        runCatching {
            val uri = Uri.parse(uriStr)
            contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        prefs.edit { remove(KEY_SAVE_FOLDER_URI) }
        Toast.makeText(this, getString(R.string.settings_save_folder_unlinked), Toast.LENGTH_SHORT).show()
        refreshValues()
    }

    // ==================== ДАННЫЕ ====================

    private fun confirmClearCache() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_clear_cache_title))
            .setMessage(getString(R.string.settings_clear_cache_message))
            .setPositiveButton(getString(R.string.common_ok)) { _, _ ->
                FileManager.cleanCache(this)
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
                val count = docsDir.listFiles()?.count { it.isFile } ?: 0
                val totalBytes = docsDir.listFiles()?.sumOf { it.length() } ?: 0L
                count to totalBytes
            }
            val (count, bytes) = stats
            findViewById<TextView>(R.id.statsValueText).text =
                getString(R.string.settings_stats_format, count, formatSize(bytes))
        }
    }

    private fun formatSize(bytes: Long): String {
        val locale = Locale.ROOT
        if (bytes < 1024) return getString(R.string.size_bytes, bytes)
        val kb = bytes / 1024.0
        if (kb < 1024) return getString(R.string.size_kb, String.format(locale, "%.1f", kb))
        val mb = kb / 1024.0
        if (mb < 1024) return getString(R.string.size_mb, String.format(locale, "%.1f", mb))
        val gb = mb / 1024.0
        return getString(R.string.size_gb, String.format(locale, "%.2f", gb))
    }

    // ==================== БЕЗОПАСНОСТЬ ====================

    private fun showPinDialog() {
        val hasPin = PinActivity.isPinSet(this)

        val options = if (hasPin) {
            arrayOf(
                getString(R.string.settings_pin_action_change),
                getString(R.string.settings_pin_action_disable)
            )
        } else {
            arrayOf(getString(R.string.settings_pin_action_enable))
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_pin_dialog_title))
            .setItems(options) { _, which ->
                if (hasPin) {
                    when (which) {
                        0 -> pinLauncher.launch(
                            PinActivity.createIntent(this, PinActivity.MODE_CHANGE_OLD)
                        )
                        1 -> pinVerifyLauncher.launch(
                            PinActivity.createIntent(this, PinActivity.MODE_VERIFY)
                        )
                    }
                } else {
                    pinLauncher.launch(PinActivity.createIntent(this, PinActivity.MODE_CREATE))
                }
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    // ==================== ПРИЛОЖЕНИЕ ====================

    private fun resetOnboarding() {
        val appPrefs = getSharedPreferences(PREFS_APP, MODE_PRIVATE)
        appPrefs.edit { putBoolean(KEY_ONBOARDING_DONE, false) }
        startActivity(Intent(this, OnboardingActivity::class.java))
        finish()
    }

    // ==================== ОБНОВЛЕНИЕ UI ====================

    private fun refreshValues() {
        refreshThemeValue()
        refreshFormatValue()
        refreshQualityValue()
        refreshPinValue()
        refreshSaveFolderValue()
        refreshStats()
    }

    private fun refreshThemeValue() {
        val theme = prefs.getString(KEY_THEME, THEME_SYSTEM) ?: THEME_SYSTEM
        findViewById<TextView>(R.id.themeValueText).text = when (theme) {
            THEME_LIGHT -> getString(R.string.settings_theme_light)
            THEME_DARK -> getString(R.string.settings_theme_dark)
            else -> getString(R.string.settings_theme_system)
        }
    }

    private fun refreshFormatValue() {
        val format = prefs.getString(KEY_DEFAULT_FORMAT, FORMAT_PDF) ?: FORMAT_PDF
        findViewById<TextView>(R.id.defaultFormatValueText).text =
            format.uppercase(Locale.ROOT)
    }

    private fun refreshQualityValue() {
        val quality = prefs.getString(KEY_PDF_QUALITY, QUALITY_HIGH) ?: QUALITY_HIGH
        findViewById<TextView>(R.id.pdfQualityValueText).text = when (quality) {
            QUALITY_MEDIUM -> getString(R.string.settings_pdf_quality_medium)
            QUALITY_LOW -> getString(R.string.settings_pdf_quality_low)
            else -> getString(R.string.settings_pdf_quality_high)
        }
    }

    private fun refreshPinValue() {
        findViewById<TextView>(R.id.pinValueText).text = if (PinActivity.isPinSet(this)) {
            getString(R.string.settings_pin_enabled)
        } else {
            getString(R.string.settings_pin_disabled)
        }
    }

    private fun refreshSaveFolderValue() {
        val folderUri = prefs.getString(KEY_SAVE_FOLDER_URI, null)
        findViewById<TextView>(R.id.saveFolderValueText).text = if (folderUri == null) {
            getString(R.string.settings_save_folder_default)
        } else {
            val decoded = Uri.decode(folderUri)
            decoded.substringAfterLast(":").ifEmpty { decoded.substringAfterLast("/") }
        }
    }
}
package com.dragonsima.scandoc

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Экран просмотра распознанного текста с экспортом в TXT / DOCX.
 * Текст принимается через intent extra [EXTRA_TEXT].
 */
class OcrResultActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TEXT = "recognizedText"

        fun createIntent(context: Context, text: String): Intent =
            Intent(context, OcrResultActivity::class.java).apply {
                putExtra(EXTRA_TEXT, text)
            }

        private const val TAG = "OcrResultActivity"
        private val WORD_SPLIT_REGEX = Regex("\\s+")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ocr_result)

        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()

        val textView = findViewById<TextView>(R.id.recognizedTextView)
        val statsText = findViewById<TextView>(R.id.statsText)

        textView.text = text
        statsText.text = buildStatsText(text)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<View>(R.id.copyAllButton).setOnClickListener { copyAllToClipboard(text) }
        findViewById<View>(R.id.shareTextButton).setOnClickListener { shareText(text) }
        findViewById<View>(R.id.saveButton).setOnClickListener { showSaveDialog(text) }
    }

    // ==================== СТАТИСТИКА ====================

    private fun buildStatsText(text: String): String {
        val chars = text.length
        val charsNoSpaces = text.count { !it.isWhitespace() }
        val words = if (text.isBlank()) 0 else text.split(WORD_SPLIT_REGEX).count { it.isNotBlank() }
        val lines = if (text.isEmpty()) 0 else text.lines().size

        return getString(R.string.ocr_stats, chars, charsNoSpaces, words, lines)
    }

    // ==================== КОПИРОВАНИЕ ====================

    private fun copyAllToClipboard(text: String) {
        if (text.isBlank()) {
            Toast.makeText(this, getString(R.string.ocr_empty), Toast.LENGTH_SHORT).show()
            return
        }

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.ocr_title), text))
        Toast.makeText(this, getString(R.string.ocr_copied), Toast.LENGTH_SHORT).show()
    }

    // ==================== SHARE ТЕКСТА ====================

    private fun shareText(text: String) {
        if (text.isBlank()) {
            Toast.makeText(this, getString(R.string.ocr_empty), Toast.LENGTH_SHORT).show()
            return
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.ocr_share_subject))
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.ocr_share_title)))
    }

    // ==================== СОХРАНЕНИЕ В ФАЙЛ ====================

    private fun showSaveDialog(text: String) {
        if (text.isBlank()) {
            Toast.makeText(this, getString(R.string.ocr_empty), Toast.LENGTH_SHORT).show()
            return
        }

        val options = arrayOf(
            getString(R.string.ocr_save_txt),
            getString(R.string.ocr_save_docx)
        )

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ocr_save_title))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> saveTextFile(text, "txt")
                    1 -> saveTextFile(text, "docx")
                }
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun saveTextFile(text: String, format: String) {
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                try {
                    val f = when (format) {
                        "docx" -> TextExporter.saveAsDocx(this@OcrResultActivity, text)
                        else -> TextExporter.saveAsTxt(this@OcrResultActivity, text)
                    }
                    FileManager.saveIndexForFile(this@OcrResultActivity, f.name, text)
                    FileManager.copyToSaveFolderIfSet(this@OcrResultActivity, f)
                    f
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка сохранения текста", e)
                    null
                }
            }

            if (file == null) {
                Toast.makeText(this@OcrResultActivity, getString(R.string.ocr_save_error), Toast.LENGTH_LONG).show()
                return@launch
            }

            showSavedDialog(file, format)
        }
    }

    private fun showSavedDialog(file: File, format: String) {
        val message = if (format == "docx") {
            getString(R.string.ocr_saved_docx, file.name)
        } else {
            getString(R.string.ocr_saved_txt, file.name)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ocr_saved_title))
            .setMessage(message)
            .setPositiveButton(getString(R.string.ocr_open_file)) { _, _ ->
                DocumentActions.openFile(this, file)
            }
            .setNeutralButton(getString(R.string.ocr_share_file)) { _, _ ->
                DocumentActions.shareFile(this, file)
            }
            .setNegativeButton(getString(R.string.common_done), null)
            .show()
    }
}
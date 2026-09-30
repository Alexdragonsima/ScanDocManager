package com.dragonsima.scandoc

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.util.Log
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Полноэкранный просмотр распознанного текста.
 * Принимает строку через intent extra "recognizedText".
 */
class OcrResultActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TEXT = "recognizedText"

        fun createIntent(context: Context, text: String): Intent {
            return Intent(context, OcrResultActivity::class.java).apply {
                putExtra(EXTRA_TEXT, text)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ocr_result)

        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()

        val textView = findViewById<TextView>(R.id.recognizedTextView)
        val statsText = findViewById<TextView>(R.id.statsText)

        textView.text = text

        // Считаем статистику
        val chars = text.length
        val charsNoSpaces = text.count { !it.isWhitespace() }
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }.size
        val lines = text.lines().size

        statsText.text = getString(R.string.ocr_stats, chars, charsNoSpaces, words, lines)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }

        findViewById<View>(R.id.copyAllButton).setOnClickListener {
            if (text.isBlank()) {
                Toast.makeText(this, getString(R.string.ocr_empty), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.ocr_title), text))
            Toast.makeText(this, getString(R.string.ocr_copied), Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.shareTextButton).setOnClickListener {
            if (text.isBlank()) {
                Toast.makeText(this, getString(R.string.ocr_empty), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.ocr_share_subject))
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.ocr_share_title)))
        }

        findViewById<android.view.View>(R.id.saveButton).setOnClickListener {
            showSaveDialog(text)
        }
    }

    /**
     * Диалог выбора формата сохранения.
     */
    private fun showSaveDialog(text: String) {
        if (text.isBlank()) {
            Toast.makeText(this, getString(R.string.ocr_empty), Toast.LENGTH_SHORT).show()
            return
        }

        val options = arrayOf(
            getString(R.string.ocr_save_txt),
            getString(R.string.ocr_save_docx)
        )

        androidx.appcompat.app.AlertDialog.Builder(this)
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

    /**
     * Сохраняет текст в выбранном формате и показывает диалог с действиями.
     */
    private fun saveTextFile(text: String, format: String) {
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                try {
                    val f = if (format == "docx") {
                        TextExporter.saveAsDocx(this@OcrResultActivity, text)
                    } else {
                        TextExporter.saveAsTxt(this@OcrResultActivity, text)
                    }
                    FileManager.saveIndexForFile(this@OcrResultActivity, f.name, text)
                    FileManager.copyToSaveFolderIfSet(this@OcrResultActivity, f)
                    f   // ← возвращаем File, последнее выражение
                } catch (e: Exception) {
                    Log.e("OcrResultActivity", "save error", e)
                    null
                }
            }

            if (file == null) {
                Toast.makeText(
                    this@OcrResultActivity,
                    getString(R.string.ocr_save_error, "—"),
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            val message = if (format == "docx") {
                getString(R.string.ocr_saved_docx, file.name)
            } else {
                getString(R.string.ocr_saved_txt, file.name)
            }

            androidx.appcompat.app.AlertDialog.Builder(this@OcrResultActivity)
                .setTitle("✅")
                .setMessage(message)
                .setPositiveButton(getString(R.string.ocr_open_file)) { _, _ ->
                    openTextFile(file, format)
                }
                .setNeutralButton(getString(R.string.ocr_share_file)) { _, _ ->
                    shareTextFile(file, format)
                }
                .setNegativeButton(getString(R.string.common_done), null)
                .show()
        }
    }

    private fun openTextFile(file: File, format: String) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "${packageName}.fileprovider", file
            )
            val mime = if (format == "docx") {
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            } else {
                "text/plain"
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Нет подходящего приложения", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareTextFile(file: File, format: String) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "${packageName}.fileprovider", file
            )
            val mime = if (format == "docx") {
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            } else {
                "text/plain"
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.ocr_share)))
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка", Toast.LENGTH_SHORT).show()
        }
    }
}
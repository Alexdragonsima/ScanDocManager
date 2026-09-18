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
    }
}
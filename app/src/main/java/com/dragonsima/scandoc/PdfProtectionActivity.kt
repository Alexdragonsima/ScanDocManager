package com.dragonsima.scandoc

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PdfProtectionActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE_PATH = "filePath"

        fun createIntent(context: Context, file: File): Intent {
            return Intent(context, PdfProtectionActivity::class.java).apply {
                putExtra(EXTRA_FILE_PATH, file.absolutePath)
            }
        }
    }

    private lateinit var file: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf_protection)

        val path = intent.getStringExtra(EXTRA_FILE_PATH) ?: run {
            Toast.makeText(this, getString(R.string.camera_file_not_found), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        file = File(path)
        if (!file.exists() || !file.extension.equals("pdf", ignoreCase = true)) {
            Toast.makeText(this, getString(R.string.camera_file_not_found), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }
        findViewById<TextView>(R.id.fileNameText).text = file.nameWithoutExtension

        val passwordField = findViewById<TextInputEditText>(R.id.passwordField)
        val passwordLayout = findViewById<TextInputLayout>(R.id.passwordFieldLayout)
        val protectButton = findViewById<MaterialButton>(R.id.protectButton)

        protectButton.setOnClickListener {
            val password = passwordField.text?.toString().orEmpty()
            if (password.length < 4) {
                passwordLayout.error = getString(R.string.pdf_protect_password_error)
                return@setOnClickListener
            }
            passwordLayout.error = null
            startProtection(password, protectButton)
        }
    }

    private fun startProtection(password: String, button: MaterialButton) {
        button.isEnabled = false
        button.text = getString(R.string.pdf_protect_processing)

        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                PdfProtector.protectInPlace(file, password)
            }

            button.isEnabled = true
            button.text = getString(R.string.pdf_protect_button)

            if (success) {
                // Копируем защищённую версию в SAF-папку
                withContext(Dispatchers.IO) {
                    FileManager.copyToSaveFolderIfSet(this@PdfProtectionActivity, file)
                }
                androidx.appcompat.app.AlertDialog.Builder(this@PdfProtectionActivity)
                    .setTitle(getString(R.string.pdf_protect_success))
                    .setMessage(file.name)
                    .setPositiveButton(getString(R.string.ocr_open_file)) { _, _ ->
                        DocumentActions.openFile(this@PdfProtectionActivity, file)
                        finish()
                    }
                    .setNeutralButton(getString(R.string.common_share)) { _, _ ->
                        DocumentActions.shareFile(this@PdfProtectionActivity, file)
                        finish()
                    }
                    .setNegativeButton(getString(R.string.common_done)) { _, _ -> finish() }
                    .setCancelable(false)
                    .show()
            } else {
                Toast.makeText(
                    this@PdfProtectionActivity,
                    getString(R.string.pdf_protect_error, "—"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
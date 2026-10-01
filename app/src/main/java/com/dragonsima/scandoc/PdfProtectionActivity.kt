package com.dragonsima.scandoc

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
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

        fun createIntent(context: Context, file: File): Intent =
            Intent(context, PdfProtectionActivity::class.java).apply {
                putExtra(EXTRA_FILE_PATH, file.absolutePath)
            }

        private const val TAG = "PdfProtectionActivity"
        private const val MIN_PASSWORD_LENGTH = 4
        private const val STATE_PROCESSING = "state_processing"   // ← добавить
    }

    private lateinit var file: File
    private lateinit var protectButton: MaterialButton
    private lateinit var passwordField: TextInputEditText
    private lateinit var passwordLayout: TextInputLayout

    private var isProcessing = false

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

        // Защита от повторного запуска шифрования при повороте экрана
        isProcessing = savedInstanceState?.getBoolean(STATE_PROCESSING, false) ?: false

        initViews()
        setupButtons()
        setupBackHandler()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_PROCESSING, isProcessing)
    }

    // ==================== ИНИЦИАЛИЗАЦИЯ ====================

    private fun initViews() {
        findViewById<ImageButton>(R.id.backButton).setOnClickListener { handleBack() }
        findViewById<TextView>(R.id.fileNameText).text = file.nameWithoutExtension

        passwordField = findViewById(R.id.passwordField)
        passwordLayout = findViewById(R.id.passwordFieldLayout)
        protectButton = findViewById(R.id.protectButton)
    }

    private fun setupButtons() {
        protectButton.setOnClickListener {
            if (isProcessing) return@setOnClickListener

            val password = passwordField.text?.toString().orEmpty()
            if (password.length < MIN_PASSWORD_LENGTH) {
                passwordLayout.error = getString(R.string.pdf_protect_password_error)
                return@setOnClickListener
            }
            passwordLayout.error = null
            startProtection(password)
        }
    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { handleBack() }
        })
    }

    private fun handleBack() {
        if (isProcessing) {
            Toast.makeText(this, getString(R.string.pdf_protect_wait), Toast.LENGTH_SHORT).show()
            return
        }
        finish()
    }

    // ==================== ЗАЩИТА ====================

    private fun startProtection(password: String) {
        // Повторная проверка файла (мог быть удалён извне)
        if (!file.exists()) {
            Toast.makeText(this, getString(R.string.camera_file_not_found), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        isProcessing = true
        protectButton.isEnabled = false
        protectButton.text = getString(R.string.pdf_protect_processing)
        passwordField.isEnabled = false

        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                PdfProtector.protectInPlace(file, password)
            }

            isProcessing = false
            protectButton.isEnabled = true
            protectButton.text = getString(R.string.pdf_protect_button)
            passwordField.isEnabled = true

            if (isFinishing || isDestroyed) return@launch

            if (success) {
                // Копируем защищённую версию в SAF-папку
                withContext(Dispatchers.IO) {
                    FileManager.copyToSaveFolderIfSet(this@PdfProtectionActivity, file)
                }
                showSuccessDialog()
            } else {
                Log.e(TAG, "Ошибка шифрования файла: ${file.name}")
                Toast.makeText(
                    this@PdfProtectionActivity,
                    getString(R.string.pdf_protect_error),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showSuccessDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.pdf_protect_success))
            .setMessage(file.name)
            .setPositiveButton(getString(R.string.ocr_open_file)) { _, _ ->
                DocumentActions.openFile(this, file)
                finish()
            }
            .setNeutralButton(getString(R.string.common_share)) { _, _ ->
                DocumentActions.shareFile(this, file)
                finish()
            }
            .setNegativeButton(getString(R.string.common_done)) { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }
}
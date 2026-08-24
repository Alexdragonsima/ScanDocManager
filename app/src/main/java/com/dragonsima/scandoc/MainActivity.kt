package com.dragonsima.scandoc

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var viewModel: MainViewModel
    private lateinit var previewImage: android.widget.ImageView
    private lateinit var statusText: android.widget.TextView
    private lateinit var captureButton: MaterialButton
    private lateinit var placeholderLayout: android.widget.LinearLayout
    private lateinit var drawerLayout: androidx.drawerlayout.widget.DrawerLayout
    private lateinit var navigationView: com.google.android.material.navigation.NavigationView

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val savedPdfPath = result.data?.getStringExtra("savedPdfPath")
            val savedPdfName = result.data?.getStringExtra("savedPdfName")

            if (savedPdfPath != null) {
                Toast.makeText(this, "✅ Документ сохранён: $savedPdfName", Toast.LENGTH_LONG).show()
                // Обновляем превью последнего документа
                loadLastDocumentPreview()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        if (!prefs.getBoolean("onboarding_completed", false)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Инициализация OpenCV
        if (!OpenCVLoader.initLocal()) {
            Toast.makeText(this, "OpenCV не загружен", Toast.LENGTH_SHORT).show()
            Log.e("Main", "OpenCV failed to load")
        } else {
            Log.d("Main", "OpenCV loaded OK")
        }

        // Инициализация
        FileManager.init(this)
        FileManager.cleanCache(this)
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        // Привязка UI
        initViews()
        setupListeners()

        // Загружаем превью последнего документа
        loadLastDocumentPreview()
    }

    private fun initViews() {
        previewImage = findViewById(R.id.previewImage)
        statusText = findViewById(R.id.statusText)
        captureButton = findViewById(R.id.captureButton)
        placeholderLayout = findViewById(R.id.placeholderLayout)
        drawerLayout = findViewById(R.id.drawerLayout)
        navigationView = findViewById(R.id.navigationView)
    }

    private fun setupListeners() {
        // Захват фото
        captureButton.setOnClickListener {
            cameraLauncher.launch(Intent(this, CameraActivity::class.java))
        }

        val menuButton = findViewById<android.widget.TextView>(R.id.menuButton)
        menuButton.setOnClickListener {
            drawerLayout.open()
        }

        navigationView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.menu_documents -> {
                    startActivity(Intent(this, DocumentsActivity::class.java))
                    drawerLayout.close()
                    true
                }
                R.id.menu_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    drawerLayout.close()
                    true
                }
                else -> false
            }
        }
    }

    private fun loadLastDocumentPreview() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val documentsDir = File(filesDir, "Documents")
                if (documentsDir.exists()) {
                    val pdfFiles = documentsDir.listFiles { file ->
                        file.extension == "pdf"
                    }?.sortedByDescending { it.lastModified() }

                    if (pdfFiles != null && pdfFiles.isNotEmpty()) {
                        val lastPdf = pdfFiles.first()
                        val thumbnailFile = File(
                            documentsDir.parentFile,
                            "Thumbnails/${lastPdf.nameWithoutExtension}_thumb.jpg"
                        )

                        if (thumbnailFile.exists()) {
                            runOnUiThread {
                                val bitmap = android.graphics.BitmapFactory.decodeFile(thumbnailFile.absolutePath)
                                if (bitmap != null) {
                                    previewImage.setImageBitmap(bitmap)
                                    statusText.text = "Последний документ: ${lastPdf.nameWithoutExtension}"
                                    placeholderLayout.visibility = android.view.View.GONE
                                    previewImage.visibility = android.view.View.VISIBLE
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadLastDocumentPreview()
    }
}
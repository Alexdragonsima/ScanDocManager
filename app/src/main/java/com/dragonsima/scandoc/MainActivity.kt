package com.dragonsima.scandoc

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.dragonsima.scandoc.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val previewBitmap = AtomicReference<Bitmap?>(null)
    private var opencvLoaded = false

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val savedPdfPath = result.data?.getStringExtra("savedPdfPath")
            val savedPdfName = result.data?.getStringExtra("savedPdfName")

            if (savedPdfPath != null) {
                Toast.makeText(
                    this,
                    getString(R.string.main_saved_toast, savedPdfName ?: getString(R.string.main_saved_file)),
                    Toast.LENGTH_LONG
                ).show()
                loadLastDocumentPreview()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        if (!prefs.getBoolean("onboarding_completed", false)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Инициализация OpenCV
        opencvLoaded = OpenCVLoader.initLocal()
        if (!opencvLoaded) {
            Log.e(TAG, "OpenCV failed to load")
            Toast.makeText(this, getString(R.string.main_opencv_error), Toast.LENGTH_LONG).show()
            binding.captureButton.isEnabled = false
        }else {
            Log.d(TAG, "OpenCV loaded successfully")
            binding.captureButton.isEnabled = true
        }

        // Инициализация файловой системы
        FileManager.init(this)
        FileManager.cleanCache(this)

        setupListeners()
        loadLastDocumentPreview()
    }

    override fun onResume() {
        super.onResume()
        // Если OpenCV загружен, показываем превью, иначе не показываем
        if (opencvLoaded) {
            loadLastDocumentPreview()
        }
    }

    private fun setupListeners() {
        binding.captureButton.setOnClickListener {
            if (opencvLoaded) {
                val intent = Intent(this, CameraActivity::class.java).apply {
                    putExtra("multiPageMode", false)
                }
                cameraLauncher.launch(intent)
            } else {
                Toast.makeText(this, getString(R.string.main_opencv_unavailable), Toast.LENGTH_SHORT).show()
            }
        }

        binding.multiScanButton.setOnClickListener {
            if (opencvLoaded) {
                // Очищаем репозиторий перед новой сессией — на всякий случай
                PageRepository.clear()
                val intent = Intent(this, CameraActivity::class.java).apply {
                    putExtra("multiPageMode", true)
                }
                cameraLauncher.launch(intent)
            } else {
                Toast.makeText(this, getString(R.string.main_opencv_unavailable), Toast.LENGTH_SHORT).show()
            }
        }

        binding.menuButton.setOnClickListener {
            binding.drawerLayout.open()
        }

        binding.navigationView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.menu_documents -> {
                    startActivity(Intent(this, DocumentsActivity::class.java))
                    binding.drawerLayout.close()
                    true
                }
                R.id.menu_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    binding.drawerLayout.close()
                    true
                }
                else -> false
            }
        }
    }

    private fun loadLastDocumentPreview() {
        lifecycleScope.launch {
            val bitmap = loadThumbnailFromDisk()
            if (bitmap != null) {
                setPreviewBitmap(bitmap)
                binding.previewImage.setImageBitmap(bitmap)
                binding.statusText.text = getString(R.string.main_status_last_document)
                showPreview(true)
            } else {
                showPreview(false)
                binding.statusText.text = getString(R.string.main_status_ready)
            }
        }
    }

    private suspend fun loadThumbnailFromDisk(): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val documentsDir = File(filesDir, "Documents")
            val thumbnailsDir = File(filesDir, "Thumbnails")
            if (!documentsDir.exists() || !thumbnailsDir.exists()) {
                return@withContext null
            }

            val lastPdf = documentsDir.listFiles { file ->
                file.extension == "pdf"
            }?.maxByOrNull { it.lastModified() } ?: return@withContext null

            val thumbnailFile = File(thumbnailsDir, "${lastPdf.nameWithoutExtension}_thumb.jpg")
            if (thumbnailFile.exists()) {
                BitmapFactory.decodeFile(thumbnailFile.absolutePath)
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Error loading thumbnail", e)
            null
        }
    }

    private fun setPreviewBitmap(bitmap: Bitmap?) {
        previewBitmap.getAndSet(null)?.recycle()
        previewBitmap.set(bitmap)
    }

    private fun showPreview(show: Boolean) {
        binding.previewImage.visibility = if (show) View.VISIBLE else View.GONE
        binding.placeholderLayout.visibility = if (show) View.GONE else View.VISIBLE
    }

    override fun onDestroy() {
        super.onDestroy()
        previewBitmap.getAndSet(null)?.recycle()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
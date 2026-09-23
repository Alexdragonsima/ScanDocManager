package com.dragonsima.scandoc

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.dragonsima.scandoc.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var opencvLoaded = false
    private val dateFormatter = SimpleDateFormat("d MMM, HH:mm", Locale.forLanguageTag("ru"))

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val savedPdfPath = result.data?.getStringExtra("savedPdfPath")
            val savedPdfName = result.data?.getStringExtra("savedPdfName")
            if (savedPdfPath != null) {
                val displayName = savedPdfName ?: getString(R.string.main_saved_file)
                Toast.makeText(
                    this,
                    getString(R.string.main_saved_toast, displayName),
                    Toast.LENGTH_LONG
                ).show()
                loadDashboard()
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

        // OpenCV
        opencvLoaded = OpenCVLoader.initLocal()
        if (!opencvLoaded) {
            Log.e(TAG, "OpenCV failed to load")
            Toast.makeText(this, getString(R.string.main_opencv_error), Toast.LENGTH_LONG).show()
        }

        // Файловая система
        FileManager.init(this)
        FileManager.cleanCache(this)

        setupListeners()
        loadDashboard()
    }

    override fun onResume() {
        super.onResume()
        if (opencvLoaded) loadDashboard()
    }

    private fun setupListeners() {
        binding.menuButton.setOnClickListener {
            binding.drawerLayout.open()
        }

        binding.scanCard.setOnClickListener {
            if (opencvLoaded) {
                val intent = Intent(this, CameraActivity::class.java).apply {
                    putExtra("multiPageMode", false)
                }
                cameraLauncher.launch(intent)
            } else {
                Toast.makeText(this, getString(R.string.main_opencv_unavailable), Toast.LENGTH_SHORT).show()
            }
        }

        binding.multiScanCard.setOnClickListener {
            if (opencvLoaded) {
                PageRepository.clear()
                val intent = Intent(this, CameraActivity::class.java).apply {
                    putExtra("multiPageMode", true)
                }
                cameraLauncher.launch(intent)
            } else {
                Toast.makeText(this, getString(R.string.main_opencv_unavailable), Toast.LENGTH_SHORT).show()
            }
        }

        binding.allDocumentsButton.setOnClickListener {
            startActivity(Intent(this, DocumentsActivity::class.java))
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

    /**
     * Загружает документы, обновляет статистику и список недавних.
     */
    private fun loadDashboard() {
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) {
                val documentsDir = File(filesDir, "Documents")
                if (documentsDir.exists()) {
                    documentsDir.listFiles { f -> f.extension == "pdf" }
                        ?.sortedByDescending { it.lastModified() }
                        ?.toList() ?: emptyList()
                } else emptyList()
            }

            binding.documentCountText.text = files.size.toString()

            binding.greetingText.text = getGreeting()

            if (files.isEmpty()) {
                binding.recentSection.visibility = View.GONE
                binding.placeholderLayout.visibility = View.VISIBLE
            } else {
                binding.recentSection.visibility = View.VISIBLE
                binding.placeholderLayout.visibility = View.GONE

                // Показываем 3 последних
                val recent = files.take(3)
                renderRecentDocuments(recent)
            }
        }
    }

    /**
     * Рисует список последних документов (до 3 штук).
     */
    private fun renderRecentDocuments(files: List<File>) {
        binding.recentContainer.removeAllViews()

        val inflater = LayoutInflater.from(this)
        files.forEach { file ->
            val row = inflater.inflate(R.layout.item_recent_document, binding.recentContainer, false)

            val thumb = row.findViewById<ImageView>(R.id.recentThumb)
            val title = row.findViewById<TextView>(R.id.recentTitle)
            val date = row.findViewById<TextView>(R.id.recentDate)

            title.text = file.nameWithoutExtension
            date.text = dateFormatter.format(Date(file.lastModified()))

            val thumbFile = File(filesDir, "Thumbnails/${file.nameWithoutExtension}_thumb.jpg")
            Glide.with(this)
                .load(thumbFile)
                .placeholder(R.drawable.placeholder_pdf)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .into(thumb)

            row.setOnClickListener {
                DocumentActions.openPdf(this, file)
            }

            binding.recentContainer.addView(row)
        }
    }

    private fun getGreeting(): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> "Доброе утро! Готовы сканировать?"
            in 12..17 -> "Добрый день! Готовы сканировать?"
            in 18..22 -> "Добрый вечер! Готовы сканировать?"
            else -> "Доброй ночи! Работаем?"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
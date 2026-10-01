package com.dragonsima.scandoc

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
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
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var opencvLoaded = false

    private val dateFormatter = SimpleDateFormat("d MMM, HH:mm", Locale.forLanguageTag("ru"))
    private val fileNameFormatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    private val allowedExtensions = setOf("pdf", "txt", "docx")

    // ==================== LAUNCHERS ====================

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val savedPdfName = result.data?.getStringExtra("savedPdfName")
            val displayName = savedPdfName ?: getString(R.string.main_saved_file)
            Toast.makeText(
                this,
                getString(R.string.main_saved_toast, displayName),
                Toast.LENGTH_LONG
            ).show()
            loadDashboard()
        }
    }

    private val pinLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            recreate()
        } else {
            finish()
        }
    }

    // ==================== ЖИЗНЕННЫЙ ЦИКЛ ====================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. PIN-код
        if (PinActivity.isPinSet(this) && !ScanDocApp.isUnlocked) {
            pinLauncher.launch(PinActivity.createIntent(this, PinActivity.MODE_VERIFY))
            return
        }

        // 2. Онбординг
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        if (!prefs.getBoolean("onboarding_completed", false)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 3. OpenCV
        opencvLoaded = OpenCVLoader.initLocal()
        if (!opencvLoaded) {
            Log.e(TAG, "OpenCV failed to load")
            Toast.makeText(this, getString(R.string.main_opencv_error), Toast.LENGTH_LONG).show()
        }

        // 4. Файловая система
        FileManager.init(this)
        FileManager.cleanCache(this)

        setupListeners()
        // loadDashboard вызовется из onResume (всегда идёт после onCreate)
    }

    override fun onResume() {
        super.onResume()
        if (opencvLoaded) loadDashboard()
    }

    // ==================== НАВИГАЦИЯ ====================

    private fun setupListeners() {
        binding.menuButton.setOnClickListener { binding.drawerLayout.open() }

        binding.scanCard.setOnClickListener { launchCamera(multiPage = false) }

        binding.multiScanCard.setOnClickListener {
            PageRepository.clear()
            launchCamera(multiPage = true)
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

    private fun launchCamera(multiPage: Boolean) {
        if (!opencvLoaded) {
            Toast.makeText(this, getString(R.string.main_opencv_unavailable), Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, CameraActivity::class.java).apply {
            putExtra("multiPageMode", multiPage)
        }
        cameraLauncher.launch(intent)
    }

    // ==================== DASHBOARD ====================

    private fun loadDashboard() {
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) {
                val documentsDir = File(filesDir, "Documents")
                if (!documentsDir.exists()) return@withContext emptyList<File>()

                documentsDir.listFiles { f ->
                    f.isFile && f.extension.lowercase(Locale.ROOT) in allowedExtensions
                }?.sortedByDescending { file ->
                    // Сортируем по реальному timestamp (с учётом бэкапов)
                    getFileTimestamp(file)
                }?.toList() ?: emptyList()
            }

            binding.documentCountText.text = files.size.toString()
            binding.greetingText.text = getGreeting()

            if (files.isEmpty()) {
                binding.recentSection.visibility = View.GONE
                binding.placeholderLayout.visibility = View.VISIBLE
            } else {
                binding.recentSection.visibility = View.VISIBLE
                binding.placeholderLayout.visibility = View.GONE
                renderRecentDocuments(files.take(3))
            }
        }
    }

    private fun renderRecentDocuments(files: List<File>) {
        binding.recentContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)

        files.forEach { file ->
            val row = inflater.inflate(R.layout.item_recent_document, binding.recentContainer, false)

            row.findViewById<TextView>(R.id.recentTitle).text = file.nameWithoutExtension

            val timestamp = getFileTimestamp(file)
            row.findViewById<TextView>(R.id.recentDate).text = if (timestamp > 0L) {
                dateFormatter.format(Date(timestamp))
            } else {
                "—"
            }

            val thumbFile = File(filesDir, "Thumbnails/${file.nameWithoutExtension}_thumb.jpg")
            Glide.with(this)
                .load(thumbFile)
                .placeholder(R.drawable.placeholder_pdf)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .into(row.findViewById<ImageView>(R.id.recentThumb))

            row.setOnClickListener {
                DocumentActions.openFile(this, file)
            }

            binding.recentContainer.addView(row)
        }
    }

    /**
     * Возвращает корректный timestamp файла.
     * Если lastModified() < 2000 года (файл восстановлен из бэкапа) — парсит дату из имени.
     */
    private fun getFileTimestamp(file: File): Long {
        val lastMod = file.lastModified()
        val year2000 = 946684800000L

        if (lastMod > year2000) return lastMod

        val match = Regex("(\\d{8}_\\d{6})").find(file.nameWithoutExtension) ?: return lastMod
        return try {
            fileNameFormatter.parse(match.value)?.time ?: lastMod
        } catch (e: Exception) {
            lastMod
        }
    }

    // ==================== ПРИВЕТСТВИЕ ====================

    private fun getGreeting(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> getString(R.string.main_greeting_morning)
            in 12..17 -> getString(R.string.main_greeting_day)
            in 18..22 -> getString(R.string.main_greeting_evening)
            else -> getString(R.string.main_greeting_night)
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
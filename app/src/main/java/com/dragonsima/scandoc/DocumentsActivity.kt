package com.dragonsima.scandoc

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.dragonsima.scandoc.databinding.ActivityDocumentsBinding
import com.dragonsima.scandoc.databinding.ItemDocumentBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

class DocumentsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDocumentsBinding
    private lateinit var adapter: DocumentAdapter

    // Список отфильтрованных документов (то, что видит адаптер)
    private val documents = mutableListOf<File>()
    // Полный список (без фильтра)
    private val allDocuments = mutableListOf<File>()

    private lateinit var searchInput: EditText
    private var sortByName = false
    private var searchJob: Job? = null

    private var inSelectionMode = false
    private lateinit var normalTopBar: View
    private lateinit var selectionTopBar: View
    private lateinit var selectionCountText: TextView

    private var enlargedDialog: android.app.Dialog? = null

    private val allowedExtensions = setOf("pdf", "txt", "docx", "jpg", "jpeg")

    // ==================== ЖИЗНЕННЫЙ ЦИКЛ ====================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDocumentsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        normalTopBar = findViewById(R.id.normalTopBar)
        selectionTopBar = findViewById(R.id.selectionTopBar)
        selectionCountText = findViewById(R.id.selectionCountText)
        searchInput = findViewById(R.id.searchInput)

        sortByName = savedInstanceState?.getBoolean("sortByName", false) ?: false

        setupRecyclerView()
        setupListeners()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (inSelectionMode) exitSelectionMode() else finish()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        loadDocuments()
    }

    override fun onDestroy() {
        super.onDestroy()
        enlargedDialog?.dismiss()
        enlargedDialog = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("sortByName", sortByName)
    }

    // ==================== ИНИЦИАЛИЗАЦИЯ ====================

    private fun setupRecyclerView() {
        adapter = DocumentAdapter(
            documents = documents,
            onClick = { file ->
                if (inSelectionMode) updateSelectionUi()
                else showDocumentMenu(file)
            },
            onLongClick = { enterSelectionMode() },
            onCheckChanged = { _, _ -> updateSelectionUi() }
        )

        binding.documentsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.documentsRecyclerView.adapter = adapter

        val swipeCallback = object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ) = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return

                // Возвращаем элемент на место
                adapter.notifyItemChanged(position)

                // В режиме выделения свайп игнорируется
                if (inSelectionMode) return

                val file = documents.getOrNull(position) ?: return
                AlertDialog.Builder(this@DocumentsActivity)
                    .setTitle(getString(R.string.docs_delete_title))
                    .setMessage(getString(R.string.docs_delete_message))
                    .setPositiveButton(getString(R.string.docs_delete_confirm)) { _, _ ->
                        deleteFile(file)
                    }
                    .setNegativeButton(getString(R.string.common_cancel), null)
                    .show()
            }
        }
        ItemTouchHelper(swipeCallback).attachToRecyclerView(binding.documentsRecyclerView)
    }

    private fun setupListeners() {
        findViewById<View>(R.id.backButton).setOnClickListener {
            if (inSelectionMode) exitSelectionMode() else finish()
        }

        binding.sortButton.setOnClickListener { showSortDialog() }
        binding.reindexButton.setOnClickListener { reindexAllDocuments() }

        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                applyFilter(s?.toString().orEmpty())
            }
        })

        findViewById<View>(R.id.cancelSelectionButton).setOnClickListener { exitSelectionMode() }
        findViewById<View>(R.id.selectAllButton).setOnClickListener {
            adapter.selectAll()
            updateSelectionUi()
        }
        findViewById<View>(R.id.shareSelectedButton).setOnClickListener { shareSelected() }
        findViewById<View>(R.id.deleteSelectedButton).setOnClickListener { confirmDeleteSelected() }
    }

    // ==================== ЗАГРУЗКА ДОКУМЕНТОВ ====================

    private fun loadDocuments() {
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) {
                val documentsDir = File(filesDir, "Documents")
                if (documentsDir.exists()) {
                    documentsDir.listFiles { file ->
                        file.isFile && file.extension.lowercase() in allowedExtensions
                    }?.toList() ?: emptyList()
                } else emptyList()
            }

            allDocuments.clear()
            allDocuments.addAll(files)

            applyFilter(searchInput.text?.toString().orEmpty())
        }
    }

    /**
     * Фильтрует список по запросу. Использует индексы текстов для поиска.
     * Дебаунс 300 мс, отмена предыдущих задач.
     */
    private fun applyFilter(query: String) {
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            val trimmed = query.trim()
            delay(300)

            if (isFinishing || isDestroyed) return@launch

            val result: List<File>
            val counts: Map<String, Int>

            if (trimmed.isBlank()) {
                result = allDocuments.toList()
                counts = emptyMap()
            } else {
                val matches = withContext(Dispatchers.IO) {
                    FileManager.searchInIndices(this@DocumentsActivity, trimmed)
                }
                counts = matches
                result = allDocuments
                    .filter { it.name in matches.keys }
                    .sortedByDescending { matches[it.name] ?: 0 }
            }

            if (isFinishing || isDestroyed) return@launch

            documents.clear()
            documents.addAll(result)
            adapter.setMatchCounts(counts)
            sortDocuments()
            updateEmptyState()
        }
    }

    private fun sortDocuments() {
        if (sortByName) {
            documents.sortBy { it.nameWithoutExtension.lowercase(Locale.getDefault()) }
        } else {
            documents.sortByDescending { it.lastModified() }
        }
        adapter.notifyDataSetChanged()
    }

    private fun showSortDialog() {
        val options = arrayOf(
            getString(R.string.docs_sort_by_date),
            getString(R.string.docs_sort_by_name)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.docs_sort_title))
            .setItems(options) { _, which ->
                sortByName = which == 1
                sortDocuments()
            }
            .show()
    }

    private fun updateEmptyState() {
        val isEmpty = documents.isEmpty()
        val hasQuery = searchInput.text?.toString()?.isNotBlank() == true

        binding.emptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.documentsRecyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE

        binding.emptyState.text = if (isEmpty && hasQuery) {
            getString(R.string.docs_empty_search)
        } else {
            getString(R.string.docs_empty)
        }
    }

    // ==================== РЕЖИМ ВЫДЕЛЕНИЯ ====================

    private fun enterSelectionMode() {
        inSelectionMode = true
        adapter.setSelectionMode(true)
        normalTopBar.visibility = View.GONE
        selectionTopBar.visibility = View.VISIBLE
        updateSelectionUi()
    }

    private fun exitSelectionMode() {
        inSelectionMode = false
        adapter.setSelectionMode(false)
        normalTopBar.visibility = View.VISIBLE
        selectionTopBar.visibility = View.GONE
    }

    private fun updateSelectionUi() {
        val count = adapter.getSelectedFiles().size
        selectionCountText.text = getString(R.string.docs_selected_count, count)
        findViewById<View>(R.id.shareSelectedButton).isEnabled = count > 0
        findViewById<View>(R.id.deleteSelectedButton).isEnabled = count > 0
    }

    private fun shareSelected() {
        val files = adapter.getSelectedFiles()
        if (files.isEmpty()) return
        DocumentActions.shareMultiplePdfs(this, files)
        exitSelectionMode()
    }

    private fun confirmDeleteSelected() {
        val files = adapter.getSelectedFiles()
        if (files.isEmpty()) return

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.docs_delete_selected_title))
            .setMessage(getString(R.string.docs_delete_selected_message, files.size))
            .setPositiveButton(getString(R.string.common_delete)) { _, _ -> deleteMultipleFiles(files) }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    // ==================== УДАЛЕНИЕ ====================

    /**
     * Общая логика удаления одного файла: SAF-копия, локальный файл, thumb, индекс.
     * Возвращает true при успехе.
     */
    private suspend fun removeDocument(file: File): Boolean = withContext(Dispatchers.IO) {
        FileManager.deleteFromSaveFolderIfSet(this@DocumentsActivity, file.name)

        val fileDeleted = file.delete()
        val thumbFile = File(
            file.parentFile?.parentFile,
            "Thumbnails/${file.nameWithoutExtension}_thumb.jpg"
        )
        val thumbDeleted = if (thumbFile.exists()) thumbFile.delete() else true

        FileManager.deleteIndexForFile(this@DocumentsActivity, file.name)

        fileDeleted && thumbDeleted
    }

    private fun deleteFile(file: File) {
        lifecycleScope.launch {
            val success = removeDocument(file)
            if (success) {
                Toast.makeText(this@DocumentsActivity, getString(R.string.docs_deleted), Toast.LENGTH_SHORT).show()
                loadDocuments()
            } else {
                Toast.makeText(this@DocumentsActivity, getString(R.string.docs_delete_error), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun deleteMultipleFiles(files: List<File>) {
        lifecycleScope.launch {
            var deletedCount = 0
            for (file in files) {
                if (removeDocument(file)) deletedCount++
            }
            Toast.makeText(
                this@DocumentsActivity,
                getString(R.string.docs_deleted_count, deletedCount),
                Toast.LENGTH_SHORT
            ).show()
            exitSelectionMode()
            loadDocuments()
        }
    }

    // ==================== ПЕРЕИНДЕКСАЦИЯ ====================

    private fun reindexAllDocuments() {
        val pdfsWithoutIndex = allDocuments.filter { file ->
            file.extension.equals("pdf", ignoreCase = true) &&
                    FileManager.getIndexForFile(this, file.name) == null
        }

        if (pdfsWithoutIndex.isEmpty()) {
            Toast.makeText(this, getString(R.string.docs_reindex_all_done), Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.docs_reindex_title))
            .setMessage(getString(R.string.docs_reindex_message, pdfsWithoutIndex.size))
            .setPositiveButton(getString(R.string.docs_reindex_start)) { _, _ ->
                runReindexing(pdfsWithoutIndex)
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun runReindexing(files: List<File>) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_progress, null)
        val messageView = dialogView.findViewById<TextView>(R.id.progressMessage)

        val dialog =AlertDialog.Builder(this)
            .setTitle(getString(R.string.docs_reindex_progress_title))
            .setView(dialogView)
            .setCancelable(false)
            .create()

        messageView.text = getString(R.string.docs_reindex_progress, 0, files.size)
        dialog.show()

        lifecycleScope.launch {
            var success = 0
            for ((idx, file) in files.withIndex()) {
                if (withContext(Dispatchers.IO) { indexSinglePdf(file) }) success++
                messageView.text = getString(R.string.docs_reindex_progress, idx + 1, files.size)
            }
            dialog.dismiss()

            Toast.makeText(
                this@DocumentsActivity,
                getString(R.string.docs_reindex_result, success, files.size),
                Toast.LENGTH_LONG
            ).show()
            loadDocuments()
        }
    }

    private suspend fun indexSinglePdf(file: File): Boolean {
        return try {
            val text = extractTextFromPdf(file) ?: return false
            if (text.isBlank()) return false
            FileManager.saveIndexForFile(this, file.name, text)
            true
        } catch (e: Exception) {
            Log.e(TAG, "indexSinglePdf error", e)
            false
        }
    }

    /**
     * Открывает PDF, рендерит первую страницу в Bitmap, прогоняет OCR.
     * Все ресурсы освобождаются в finally.
     */
    private suspend fun extractTextFromPdf(file: File): String? = withContext(Dispatchers.IO) {
        var pfd: android.os.ParcelFileDescriptor? = null
        var renderer: android.graphics.pdf.PdfRenderer? = null
        var page: android.graphics.pdf.PdfRenderer.Page? = null
        var bitmap: Bitmap? = null
        var recognizer: TextRecognizer? = null

        try {
            pfd = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = android.graphics.pdf.PdfRenderer(pfd)

            if (renderer.pageCount == 0) return@withContext null

            page = renderer.openPage(0)
            bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)

            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

            suspendCancellableCoroutine<String?> { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        if (cont.isActive) cont.resume(visionText.text)
                    }
                    .addOnFailureListener {
                        if (cont.isActive) cont.resume(null)
                    }
            }
        } catch (e: Exception) {
            Log.e(TAG, "extractTextFromPdf error", e)
            null
        } finally {
            runCatching { recognizer?.close() }
            runCatching { bitmap?.recycle() }
            runCatching { page?.close() }
            runCatching { renderer?.close() }
            runCatching { pfd?.close() }
        }
    }

    // ==================== МЕНЮ ДОКУМЕНТА ====================

    private fun showDocumentMenu(file: File) {
        val isPdf = file.extension.equals("pdf", ignoreCase = true)
        val options = if (isPdf) {
            arrayOf(
                getString(R.string.docs_menu_open),
                getString(R.string.docs_menu_share),
                getString(R.string.pdf_protect_menu),
                getString(R.string.docs_menu_thumbnail),
                getString(R.string.docs_menu_rename),
                getString(R.string.docs_menu_delete)
            )
        } else {
            arrayOf(
                getString(R.string.docs_menu_open),
                getString(R.string.docs_menu_share),
                getString(R.string.docs_menu_thumbnail),
                getString(R.string.docs_menu_rename),
                getString(R.string.docs_menu_delete)
            )
        }

        AlertDialog.Builder(this)
            .setTitle(file.nameWithoutExtension)
            .setItems(options) { _, which ->
                if (isPdf) {
                    when (which) {
                        0 -> DocumentActions.openFile(this, file)
                        1 -> DocumentActions.shareFile(this, file)
                        2 -> startActivity(PdfProtectionActivity.createIntent(this, file))
                        3 -> showEnlargedThumbnail(file)
                        4 -> renameFile(file)
                        5 -> deleteFile(file)
                    }
                } else {
                    when (which) {
                        0 -> DocumentActions.openFile(this, file)
                        1 -> DocumentActions.shareFile(this, file)
                        2 -> showEnlargedThumbnail(file)
                        3 -> renameFile(file)
                        4 -> deleteFile(file)
                    }
                }
            }
            .show()
    }

    private fun showEnlargedThumbnail(file: File) {
        enlargedDialog?.dismiss()
        enlargedDialog = null

        val imageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnClickListener { enlargedDialog?.dismiss() }
        }

        enlargedDialog = android.app.Dialog(this).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setContentView(imageView)
            window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.WHITE))
            window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.85).toInt()
            )
            setOnDismissListener { enlargedDialog = null }
            show()
        }

        val dialog = enlargedDialog ?: return

        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                when (file.extension.lowercase(Locale.ROOT)) {
                    "jpg", "jpeg" -> loadJpegPreview(file)
                    "pdf" -> renderFirstPagePreview(file)
                    else -> null
                }
            }

            if (dialog.isShowing && bitmap != null && !bitmap.isRecycled) {
                imageView.setImageBitmap(bitmap)
            } else {
                // Fallback — маленькая thumbnail
                val thumbnailFile = File(
                    file.parentFile?.parentFile,
                    "Thumbnails/${file.nameWithoutExtension}_thumb.jpg"
                )
                if (thumbnailFile.exists()) {
                    Glide.with(this@DocumentsActivity).load(thumbnailFile).into(imageView)
                } else {
                    Toast.makeText(
                        this@DocumentsActivity,
                        getString(R.string.docs_thumbnail_missing),
                        Toast.LENGTH_SHORT
                    ).show()
                    dialog.dismiss()
                }
            }
        }
    }

    /**
     * Загружает JPG с уменьшением до ~1200px (inSampleSize).
     * Не грузит оригинал целиком — экономит память.
     */
    private fun loadJpegPreview(file: File): Bitmap? {
        return try {
            val bounds = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)

            val maxSide = 1200
            var sample = 1
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            while (longest / sample > maxSide) sample *= 2

            android.graphics.BitmapFactory.decodeFile(
                file.absolutePath,
                android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = sample
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "loadJpegPreview error", e)
            null
        }
    }

    /**
     * Рендерит первую страницу PDF в Bitmap.
     * Размер ограничен 1400px по длинной стороне.
     */
    private fun renderFirstPagePreview(file: File): Bitmap? {
        var pfd: android.os.ParcelFileDescriptor? = null
        var renderer: android.graphics.pdf.PdfRenderer? = null
        var page: android.graphics.pdf.PdfRenderer.Page? = null

        return try {
            pfd = android.os.ParcelFileDescriptor.open(
                file,
                android.os.ParcelFileDescriptor.MODE_READ_ONLY
            )
            renderer = android.graphics.pdf.PdfRenderer(pfd)
            if (renderer.pageCount == 0) return null

            page = renderer.openPage(0)

            val maxSide = 1400
            val longest = maxOf(page.width, page.height)
            val scale = if (longest > maxSide) maxSide.toFloat() / longest else 1f

            val w = (page.width * scale).toInt().coerceAtLeast(1)
            val h = (page.height * scale).toInt().coerceAtLeast(1)

            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "renderFirstPagePreview error", e)
            null
        } finally {
            runCatching { page?.close() }
            runCatching { renderer?.close() }
            runCatching { pfd?.close() }
        }
    }

    // ==================== ПЕРЕИМЕНОВАНИЕ ====================

    private fun renameFile(file: File) {
        val input = EditText(this)
        input.setText(file.nameWithoutExtension)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.docs_rename_title))
            .setView(input)
            .setPositiveButton(getString(R.string.docs_rename_save)) { _, _ ->
                val newName = input.text.toString().trim()
                if (!validateNewName(newName, file)) return@setPositiveButton

                val newFile = File(file.parentFile, "$newName.${file.extension}")
                performRename(file, newFile)
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun validateNewName(newName: String, file: File): Boolean {
        if (newName.isEmpty()) {
            Toast.makeText(this, getString(R.string.docs_rename_empty), Toast.LENGTH_SHORT).show()
            return false
        }

        val forbidden = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
        if (newName.any { it in forbidden }) {
            Toast.makeText(this, getString(R.string.docs_rename_forbidden), Toast.LENGTH_SHORT).show()
            return false
        }

        if (newName == file.nameWithoutExtension) return false

        val newFile = File(file.parentFile, "$newName.${file.extension}")
        if (newFile.exists()) {
            Toast.makeText(this, getString(R.string.docs_rename_exists), Toast.LENGTH_SHORT).show()
            return false
        }

        return true
    }

    private fun performRename(file: File, newFile: File) {
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                if (!file.renameTo(newFile)) return@withContext false

                val oldThumb = File(
                    file.parentFile?.parentFile,
                    "Thumbnails/${file.nameWithoutExtension}_thumb.jpg"
                )
                val newThumb = File(
                    file.parentFile?.parentFile,
                    "Thumbnails/${newFile.nameWithoutExtension}_thumb.jpg"
                )
                if (oldThumb.exists()) oldThumb.renameTo(newThumb)

                FileManager.renameIndexForFile(this@DocumentsActivity, file.name, newFile.name)
                FileManager.renameInSaveFolderIfSet(this@DocumentsActivity, file.name, newFile.name)

                true
            }

            if (success) {
                Toast.makeText(this@DocumentsActivity, getString(R.string.docs_renamed), Toast.LENGTH_SHORT).show()
                loadDocuments()
            } else {
                Toast.makeText(this@DocumentsActivity, getString(R.string.docs_rename_error), Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private const val TAG = "DocumentsActivity"
    }
}

// ==================== АДАПТЕР ====================

/**
 * Адаптер списка документов с поддержкой мультивыбора.
 */
class DocumentAdapter(
    private val documents: List<File>,
    private val onClick: (File) -> Unit,
    private val onLongClick: (File) -> Unit,
    private val onCheckChanged: (File, Boolean) -> Unit
) : RecyclerView.Adapter<DocumentAdapter.DocumentViewHolder>() {

    private val dateFormatter = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.forLanguageTag("ru"))
    private val fileNameFormatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    private var matchCounts: Map<String, Int> = emptyMap()
    private val selectedFiles = mutableSetOf<String>()

    private var selectionModeInternal = false
    val selectionMode: Boolean get() = selectionModeInternal

    // ==================== API ====================

    fun setMatchCounts(counts: Map<String, Int>) {
        matchCounts = counts
        notifyDataSetChanged()
    }

    fun setSelectionMode(enabled: Boolean) {
        selectionModeInternal = enabled
        if (!enabled) selectedFiles.clear()
        notifyDataSetChanged()
    }

    fun getSelectedFiles(): List<File> = documents.filter { it.name in selectedFiles }

    fun selectAll() {
        selectedFiles.clear()
        documents.forEach { selectedFiles.add(it.name) }
        notifyDataSetChanged()
    }

    // ==================== VIEWHOLDER ====================

    class DocumentViewHolder(val binding: ItemDocumentBinding) : RecyclerView.ViewHolder(binding.root) {
        val thumbnail: ImageView = binding.thumbnailImage
        val title: TextView = binding.documentTitle
        val date: TextView = binding.documentDate
        val menuButton: ImageView = binding.menuButton
        val matchCount: TextView = binding.matchCount
        val checkbox: com.google.android.material.checkbox.MaterialCheckBox = binding.selectionCheckbox
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DocumentViewHolder {
        val binding = ItemDocumentBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return DocumentViewHolder(binding)
    }

    override fun getItemCount() = documents.size

    override fun onViewRecycled(holder: DocumentViewHolder) {
        Glide.with(holder.itemView.context).clear(holder.thumbnail)
        super.onViewRecycled(holder)
    }

    // ==================== BIND ====================

    override fun onBindViewHolder(holder: DocumentViewHolder, position: Int) {
        val file = documents[position]

        holder.title.text = file.nameWithoutExtension
        val timestamp = getFileTimestamp(file)
        holder.date.text = if (timestamp > 0L) dateFormatter.format(Date(timestamp)) else "—"

        // Счётчик совпадений (скрывается в режиме выделения)
        val count = matchCounts[file.name] ?: 0
        if (count > 0 && !selectionMode) {
            holder.matchCount.text = holder.itemView.context.getString(R.string.docs_match_count, count)
            holder.matchCount.visibility = View.VISIBLE
        } else {
            holder.matchCount.visibility = View.GONE
        }

        bindThumbnail(holder, file)
        bindSelection(holder, file)
        bindActions(holder, file)
    }

    private fun bindThumbnail(holder: DocumentViewHolder, file: File) {
        when (file.extension.lowercase()) {
            "pdf" -> {
                holder.thumbnail.setPadding(0, 0, 0, 0)
                holder.thumbnail.clearColorFilter()
                holder.thumbnail.scaleType = ImageView.ScaleType.CENTER_CROP

                val thumbnailFile = File(
                    file.parentFile?.parentFile,
                    "Thumbnails/${file.nameWithoutExtension}_thumb.jpg"
                )
                Glide.with(holder.itemView.context)
                    .load(thumbnailFile)
                    .placeholder(R.drawable.placeholder_pdf)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .into(holder.thumbnail)
            }
            "txt", "docx" -> {
                Glide.with(holder.itemView.context).clear(holder.thumbnail)
                val iconRes = if (file.extension.lowercase() == "txt") {
                    R.drawable.ic_text_file
                } else {
                    R.drawable.ic_word_file
                }
                holder.thumbnail.setImageResource(iconRes)
                holder.thumbnail.setColorFilter(
                    ContextCompat.getColor(holder.itemView.context, R.color.primary_color)
                )
                holder.thumbnail.setPadding(28, 36, 28, 36)
                holder.thumbnail.scaleType = ImageView.ScaleType.FIT_CENTER
            }
            "jpg", "jpeg" -> {
                holder.thumbnail.setPadding(0, 0, 0, 0)
                holder.thumbnail.clearColorFilter()
                holder.thumbnail.scaleType = ImageView.ScaleType.CENTER_CROP

                val thumbnailFile = File(
                    file.parentFile?.parentFile,
                    "Thumbnails/${file.nameWithoutExtension}_thumb.jpg"
                )
                Glide.with(holder.itemView.context)
                    .load(thumbnailFile)
                    .placeholder(R.drawable.placeholder_pdf)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .into(holder.thumbnail)
            }
        }
    }

    private fun bindSelection(holder: DocumentViewHolder, file: File) {
        if (selectionMode) {
            holder.checkbox.visibility = View.VISIBLE
            holder.checkbox.isChecked = file.name in selectedFiles
        } else {
            holder.checkbox.visibility = View.GONE
        }
    }

    private fun bindActions(holder: DocumentViewHolder, file: File) {
        holder.itemView.setOnClickListener {
            if (selectionMode) {
                toggleSelection(file)
                holder.checkbox.isChecked = file.name in selectedFiles
                onCheckChanged(file, file.name in selectedFiles)
            } else {
                onClick(file)
            }
        }

        holder.itemView.setOnLongClickListener {
            if (!selectionMode) {
                onLongClick(file)
                true
            } else false
        }

        holder.checkbox.setOnClickListener {
            // Чекбокс уже переключён системой — синхронизируем Set
            if (holder.checkbox.isChecked) selectedFiles.add(file.name)
            else selectedFiles.remove(file.name)
            onCheckChanged(file, holder.checkbox.isChecked)
        }

        holder.menuButton.visibility = if (selectionMode) View.GONE else View.VISIBLE
        holder.menuButton.setOnClickListener { onClick(file) }
    }

    private fun toggleSelection(file: File) {
        if (file.name in selectedFiles) selectedFiles.remove(file.name)
        else selectedFiles.add(file.name)
    }

    /**
     * Возвращает корректный timestamp файла.
     * Если lastModified() меньше 2000 года (файл восстановлен из бэкапа) — парсит дату из имени.
     */
    private fun getFileTimestamp(file: File): Long {
        val lastMod = file.lastModified()
        val year2000 = 946684800000L  // 1 января 2000, 00:00 UTC

        if (lastMod > year2000) return lastMod

        val match = Regex("(\\d{8}_\\d{6})").find(file.nameWithoutExtension) ?: return lastMod
        return try {
            fileNameFormatter.parse(match.value)?.time ?: lastMod
        } catch (e: Exception) {
            lastMod
        }
    }
}
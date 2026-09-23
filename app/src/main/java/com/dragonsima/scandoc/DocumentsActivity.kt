package com.dragonsima.scandoc

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.activity.OnBackPressedCallback
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.dragonsima.scandoc.databinding.ActivityDocumentsBinding
import com.dragonsima.scandoc.databinding.ItemDocumentBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume

class DocumentsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDocumentsBinding
    private lateinit var adapter: DocumentAdapter

    private val documents = mutableListOf<File>()
    private lateinit var searchInput: EditText
    private val allDocuments = mutableListOf<File>()      // полный список
    private var sortByName = false
    private var searchJob: kotlinx.coroutines.Job? = null

    private var inSelectionMode = false
    private lateinit var normalTopBar: View
    private lateinit var selectionTopBar: View
    private lateinit var selectionCountText: TextView

    // Диалог увеличенной миниатюры
    private var enlargedDialog: android.app.Dialog? = null

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
        // Не вызываем loadDocuments() здесь, так как onResume всегда вызовется после onCreate
        // и загрузит список один раз.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (inSelectionMode) {
                    exitSelectionMode()
                } else {
                    finish()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        // Обновляем список при возврате (например, после удаления извне)
        loadDocuments()
    }

    private fun setupRecyclerView() {
        adapter = DocumentAdapter(
            documents = documents,
            onClick = { file ->
                if (inSelectionMode) {
                    updateSelectionUi()
                } else {
                    showDocumentMenu(file)
                }
            },
            onLongClick = { file -> enterSelectionMode(file) },
            onCheckChanged = { _, _ -> updateSelectionUi() }
        )
        binding.documentsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.documentsRecyclerView.adapter = adapter

        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
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

                // Возвращаем элемент на место до подтверждения
                adapter.notifyItemChanged(position)

                val file = documents[position]
                AlertDialog.Builder(this@DocumentsActivity)
                    .setTitle("Удалить?")
                    .setMessage("Документ будет удалён безвозвратно")
                    .setPositiveButton("Удалить") { _, _ ->
                        deleteFile(file)
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
        })
        itemTouchHelper.attachToRecyclerView(binding.documentsRecyclerView)
    }

    private fun setupListeners() {
        findViewById<View>(R.id.backButton).setOnClickListener {
            if (inSelectionMode) exitSelectionMode() else finish()
        }

        binding.sortButton.setOnClickListener {
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

        binding.reindexButton.setOnClickListener {
            reindexAllDocuments()
        }

        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                applyFilter(s?.toString().orEmpty())
            }
        })

        findViewById<View>(R.id.cancelSelectionButton).setOnClickListener {
            exitSelectionMode()
        }

        findViewById<View>(R.id.selectAllButton).setOnClickListener {
            adapter.selectAll()
            updateSelectionUi()
        }

        findViewById<View>(R.id.shareSelectedButton).setOnClickListener {
            shareSelected()
        }

        findViewById<View>(R.id.deleteSelectedButton).setOnClickListener {
            confirmDeleteSelected()
        }
    }

    private fun loadDocuments() {
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) {
                val documentsDir = File(filesDir, "Documents")
                if (documentsDir.exists()) {
                    documentsDir.listFiles { file -> file.extension == "pdf" }?.toList() ?: emptyList()
                } else emptyList()
            }

            allDocuments.clear()
            allDocuments.addAll(files)

            // Применяем текущий фильтр (если есть)
            applyFilter(searchInput.text?.toString().orEmpty())
        }
    }

    // ==================== РЕЖИМ ВЫДЕЛЕНИЯ ====================

    private fun enterSelectionMode(firstFile: File) {
        inSelectionMode = true
        adapter.setSelectionMode(true)
        adapter.clearSelection()
        // Не выделяем сразу — пользователь сам тапнет, но подсказываем через долгий тап
        adapter.notifyDataSetChanged()
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
            .setPositiveButton(getString(R.string.common_delete)) { _, _ ->
                deleteMultipleFiles(files)
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun deleteMultipleFiles(files: List<File>) {
        lifecycleScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                var count = 0
                files.forEach { file ->
                    val pdfDeleted = file.delete()
                    val thumbFile = File(
                        file.parentFile?.parentFile,
                        "Thumbnails/${file.nameWithoutExtension}_thumb.jpg"
                    )
                    if (thumbFile.exists()) thumbFile.delete()
                    FileManager.deleteIndexForPdf(this@DocumentsActivity, file.name)
                    if (pdfDeleted) count++
                }
                count
            }
            Toast.makeText(
                this@DocumentsActivity,
                getString(R.string.docs_deleted_count, deleted),
                Toast.LENGTH_SHORT
            ).show()
            exitSelectionMode()
            loadDocuments()
        }
    }

    /**
     * Фильтрует список по запросу. Использует индексы текстов для поиска.
     */
    private fun applyFilter(query: String) {
        searchJob?.cancel()

        searchJob = lifecycleScope.launch {
            val trimmed = query.trim()

            delay(300)

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
                result = allDocuments.filter { it.name in matches.keys }
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

    private fun reindexAllDocuments() {
        val pdfsWithoutIndex = allDocuments.filter { file ->
            FileManager.getIndexForPdf(this, file.name) == null
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
        val progressDialog = android.app.ProgressDialog(this).apply {
            setTitle(getString(R.string.docs_reindex_progress_title))
            setMessage(getString(R.string.docs_reindex_progress, 0, files.size))
            setCancelable(false)
            show()
        }

        lifecycleScope.launch {
            var done = 0
            var success = 0

            for (file in files) {
                val ok = withContext(Dispatchers.IO) {
                    indexSinglePdf(file)
                }
                if (ok) success++
                done++
                withContext(Dispatchers.Main) {
                    progressDialog.setMessage(getString(R.string.docs_reindex_progress, done, files.size))
                }
            }

            progressDialog.dismiss()
            Toast.makeText(
                this@DocumentsActivity,
                getString(R.string.docs_reindex_result, success, files.size),
                Toast.LENGTH_LONG
            ).show()

            loadDocuments()
        }
    }

    /**
     * Обрабатывает один PDF: рендерит первую страницу, распознаёт текст, сохраняет индекс.
     * Возвращает true при успехе.
     */
    private suspend fun indexSinglePdf(file: File): Boolean {
        return try {
            val text = extractTextFromPdf(file) ?: return false
            if (text.isBlank()) return false
            FileManager.saveIndexForPdf(this, file.name, text)
            true
        } catch (e: Exception) {
            Log.e("DocumentsActivity", "indexSinglePdf error", e)
            false
        }
    }

    /**
     * Открывает PDF через PdfRenderer, рендерит первую страницу в Bitmap,
     * прогоняет через ML Kit OCR, возвращает распознанный текст.
     */
    private suspend fun extractTextFromPdf(file: File): String? {
        return withContext(Dispatchers.IO) {
            try {
                val pfd = android.os.ParcelFileDescriptor.open(
                    file,
                    android.os.ParcelFileDescriptor.MODE_READ_ONLY
                )
                val renderer = android.graphics.pdf.PdfRenderer(pfd)
                try {
                    if (renderer.pageCount == 0) return@withContext null

                    val page = renderer.openPage(0)
                    try {
                        val bitmap = Bitmap.createBitmap(
                            page.width * 2,
                            page.height * 2,
                            Bitmap.Config.ARGB_8888
                        )
                        // Белый фон — иначе прозрачность даст чёрный в OCR
                        val canvas = android.graphics.Canvas(bitmap)
                        canvas.drawColor(Color.WHITE)
                        page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                        // OCR
                        val image = InputImage.fromBitmap(bitmap, 0)
                        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

                        val result = kotlinx.coroutines.suspendCancellableCoroutine<String?> { cont ->
                            recognizer.process(image)
                                .addOnSuccessListener { visionText ->
                                    cont.resume(visionText.text)
                                }
                                .addOnFailureListener {
                                    cont.resume(null)
                                }
                        }

                        recognizer.close()
                        bitmap.recycle()
                        page.close()
                        result
                    } catch (e: Exception) {
                        Log.e("DocumentsActivity", "render page error", e)
                        null
                    }
                } finally {
                    renderer.close()
                    pfd.close()
                }
            } catch (e: Exception) {
                Log.e("DocumentsActivity", "extractTextFromPdf error", e)
                null
            }
        }
    }

    private fun showDocumentMenu(file: File) {
        val options = arrayOf(
            getString(R.string.docs_menu_open),
            getString(R.string.docs_menu_share),
            getString(R.string.docs_menu_thumbnail),
            getString(R.string.docs_menu_rename),
            getString(R.string.docs_menu_delete)
        )

        AlertDialog.Builder(this)
            .setTitle(file.nameWithoutExtension)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openPdf(file)
                    1 -> sharePdf(file)
                    2 -> showEnlargedThumbnail(file)
                    3 -> renameFile(file)
                    4 -> deleteFile(file)
                }
            }
            .show()
    }

    private fun showEnlargedThumbnail(file: File) {
        // Закрываем старый диалог, если открыт
        enlargedDialog?.dismiss()
        enlargedDialog = null

        val thumbnailFile = File(
            file.parentFile?.parentFile,
            "Thumbnails/${file.nameWithoutExtension}_thumb.jpg"
        )

        if (!thumbnailFile.exists()) {
            Toast.makeText(this, getString(R.string.docs_thumbnail_missing), Toast.LENGTH_SHORT).show()
            return
        }

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
                (resources.displayMetrics.heightPixels * 0.7).toInt()
            )
            setOnDismissListener { enlargedDialog = null }
            show()

            // Загрузка изображения через Glide с уменьшением размера
            Glide.with(this@DocumentsActivity)
                .load(thumbnailFile)
                .override(800, 800)  // ограничиваем размер для экономии памяти
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .into(imageView)
        }
    }

    private fun openPdf(file: File) {
        DocumentActions.openPdf(this, file)
    }

    private fun sharePdf(file: File) {
        DocumentActions.sharePdf(this, file)
    }

    private fun renameFile(file: File) {
        val input = EditText(this)
        input.setText(file.nameWithoutExtension)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.docs_rename_title))
            .setView(input)
            .setPositiveButton(getString(R.string.docs_rename_save)) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) {
                    Toast.makeText(this, getString(R.string.docs_rename_empty), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val forbiddenChars = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
                if (newName.any { it in forbiddenChars }) {
                    Toast.makeText(this, getString(R.string.docs_rename_forbidden), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (newName == file.nameWithoutExtension) {
                    return@setPositiveButton
                }

                val newFile = File(file.parentFile, "$newName.pdf")
                if (newFile.exists()) {
                    Toast.makeText(this, getString(R.string.docs_rename_exists), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                lifecycleScope.launch {
                    val success = withContext(Dispatchers.IO) {
                        if (file.renameTo(newFile)) {
                            val oldThumb = File(
                                file.parentFile?.parentFile,
                                "Thumbnails/${file.nameWithoutExtension}_thumb.jpg"
                            )
                            val newThumb = File(
                                file.parentFile?.parentFile,
                                "Thumbnails/${newFile.nameWithoutExtension}_thumb.jpg"
                            )
                            if (oldThumb.exists()) {
                                oldThumb.renameTo(newThumb)
                            }
                            FileManager.renameIndexForPdf(
                                this@DocumentsActivity,
                                file.name,
                                newFile.name
                            )
                            true
                        } else false
                    }
                    if (success) {
                        Toast.makeText(this@DocumentsActivity, getString(R.string.docs_renamed), Toast.LENGTH_SHORT).show()
                        loadDocuments()
                    } else {
                        Toast.makeText(this@DocumentsActivity, getString(R.string.docs_rename_error), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun deleteFile(file: File) {
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                val pdfDeleted = file.delete()
                val thumbFile = File(
                    file.parentFile?.parentFile,
                    "Thumbnails/${file.nameWithoutExtension}_thumb.jpg"
                )
                val thumbDeleted = if (thumbFile.exists()) thumbFile.delete() else true

                FileManager.deleteIndexForPdf(this@DocumentsActivity, file.name)

                pdfDeleted && thumbDeleted
            }
            if (success) {
                Toast.makeText(this@DocumentsActivity, getString(R.string.docs_deleted), Toast.LENGTH_SHORT).show()
                loadDocuments()
            } else {
                Toast.makeText(this@DocumentsActivity, getString(R.string.docs_delete_error), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("sortByName", sortByName)
    }

    override fun onDestroy() {
        super.onDestroy()
        enlargedDialog?.dismiss()
        enlargedDialog = null
    }
}

/**
 * Адаптер для списка документов с использованием Glide для миниатюр.
 * Glide автоматически управляет памятью и кэшированием.
 */
class DocumentAdapter(
    private val documents: List<File>,
    private val onClick: (File) -> Unit,
    private val onLongClick: (File) -> Unit,
    private val onCheckChanged: (File, Boolean) -> Unit
) : RecyclerView.Adapter<DocumentAdapter.DocumentViewHolder>() {

    private val dateFormatter = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.forLanguageTag("ru"))
    private var matchCounts: Map<String, Int> = emptyMap()
    private val selectedFiles = mutableSetOf<String>()  // имена файлов
    private var selectionModeInternal = false
    val selectionMode: Boolean get() = selectionModeInternal

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

    fun clearSelection() {
        selectedFiles.clear()
        notifyDataSetChanged()
    }

    fun selectAll() {
        selectedFiles.clear()
        documents.forEach { selectedFiles.add(it.name) }
        notifyDataSetChanged()
    }

    class DocumentViewHolder(val binding: ItemDocumentBinding) :
        RecyclerView.ViewHolder(binding.root) {
        val thumbnail: ImageView = binding.thumbnailImage
        val title: TextView = binding.documentTitle
        val date: TextView = binding.documentDate
        val menuButton: ImageView = binding.menuButton
        val matchCount: TextView = binding.matchCount
        val checkbox: com.google.android.material.checkbox.MaterialCheckBox = binding.selectionCheckbox
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DocumentViewHolder {
        val binding = ItemDocumentBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return DocumentViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DocumentViewHolder, position: Int) {
        val file = documents[position]
        holder.title.text = file.nameWithoutExtension
        holder.date.text = dateFormatter.format(Date(file.lastModified()))

        val count = matchCounts[file.name] ?: 0
        if (count > 0 && !selectionMode) {
            holder.matchCount.text = holder.itemView.context.getString(R.string.docs_match_count, count)
            holder.matchCount.visibility = View.VISIBLE
        } else {
            holder.matchCount.visibility = View.GONE
        }

        val thumbnailFile = File(
            file.parentFile?.parentFile,
            "Thumbnails/${file.nameWithoutExtension}_thumb.jpg"
        )
        Glide.with(holder.itemView.context)
            .load(thumbnailFile)
            .placeholder(R.drawable.placeholder_pdf)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .into(holder.thumbnail)

        // Чекбокс
        if (selectionMode) {
            holder.checkbox.visibility = View.VISIBLE
            holder.checkbox.isChecked = file.name in selectedFiles
        } else {
            holder.checkbox.visibility = View.GONE
        }

        holder.itemView.setOnClickListener {
            if (selectionMode) {
                val newState = file.name !in selectedFiles
                if (newState) selectedFiles.add(file.name) else selectedFiles.remove(file.name)
                holder.checkbox.isChecked = newState
                onCheckChanged(file, newState)
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
            if (holder.checkbox.isChecked) {
                selectedFiles.add(file.name)
            } else {
                selectedFiles.remove(file.name)
            }
            onCheckChanged(file, holder.checkbox.isChecked)
        }

        holder.menuButton.visibility = if (selectionMode) View.GONE else View.VISIBLE
        holder.menuButton.setOnClickListener { onClick(file) }
    }

    override fun getItemCount() = documents.size

    override fun onViewRecycled(holder: DocumentViewHolder) {
        Glide.with(holder.itemView.context).clear(holder.thumbnail)
        super.onViewRecycled(holder)
    }
}
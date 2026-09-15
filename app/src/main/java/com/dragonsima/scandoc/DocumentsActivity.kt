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
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.dragonsima.scandoc.databinding.ActivityDocumentsBinding
import com.dragonsima.scandoc.databinding.ItemDocumentBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DocumentsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDocumentsBinding
    private lateinit var adapter: DocumentAdapter

    private val documents = mutableListOf<File>()
    private var sortByName = false

    // Диалог увеличенной миниатюры
    private var enlargedDialog: android.app.Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDocumentsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sortByName = savedInstanceState?.getBoolean("sortByName", false) ?: false

        setupRecyclerView()
        setupListeners()
        // Не вызываем loadDocuments() здесь, так как onResume всегда вызовется после onCreate
        // и загрузит список один раз.
    }

    override fun onResume() {
        super.onResume()
        // Обновляем список при возврате (например, после удаления извне)
        loadDocuments()
    }

    private fun setupRecyclerView() {
        adapter = DocumentAdapter(
            documents = documents,
            onClick = { file -> showDocumentMenu(file) },
            onLongClick = { file -> showEnlargedThumbnail(file) }
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
        binding.backButton.setOnClickListener { finish() }

        binding.sortButton.setOnClickListener {
            val options = arrayOf("📅 По дате", "🔤 По имени")
            AlertDialog.Builder(this)
                .setTitle("Сортировка")
                .setItems(options) { _, which ->
                    sortByName = which == 1
                    sortDocuments()
                }
                .show()
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

            documents.clear()
            documents.addAll(files)
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
        binding.emptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.documentsRecyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun showDocumentMenu(file: File) {
        val options = arrayOf(
            "📂 Открыть PDF",
            "📤 Поделиться",
            "✏️ Переименовать",
            "🗑 Удалить"
        )

        AlertDialog.Builder(this)
            .setTitle(file.nameWithoutExtension)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openPdf(file)
                    1 -> sharePdf(file)
                    2 -> renameFile(file)
                    3 -> deleteFile(file)
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
            Toast.makeText(this, "Миниатюра не найдена", Toast.LENGTH_SHORT).show()
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
            .setTitle("Переименовать")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) {
                    Toast.makeText(this, "Имя не может быть пустым", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                // Проверка недопустимых символов
                val forbiddenChars = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
                if (newName.any { it in forbiddenChars }) {
                    Toast.makeText(this, "Недопустимые символы в имени", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (newName == file.nameWithoutExtension) {
                    return@setPositiveButton
                }

                val newFile = File(file.parentFile, "$newName.pdf")
                if (newFile.exists()) {
                    Toast.makeText(this, "Файл с таким именем уже существует", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                // Выполняем переименование в фоне
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
                            true
                        } else {
                            false
                        }
                    }
                    if (success) {
                        Toast.makeText(this@DocumentsActivity, "✅ Переименовано", Toast.LENGTH_SHORT).show()
                        loadDocuments()
                    } else {
                        Toast.makeText(this@DocumentsActivity, "Ошибка переименования", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Отмена", null)
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
                pdfDeleted && thumbDeleted
            }
            if (success) {
                Toast.makeText(this@DocumentsActivity, "🗑 Удалено", Toast.LENGTH_SHORT).show()
                loadDocuments()
            } else {
                Toast.makeText(this@DocumentsActivity, "Ошибка удаления", Toast.LENGTH_SHORT).show()
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
    private val onLongClick: (File) -> Unit
) : RecyclerView.Adapter<DocumentAdapter.DocumentViewHolder>() {

    private val dateFormatter = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())

    class DocumentViewHolder(val binding: ItemDocumentBinding) :
        RecyclerView.ViewHolder(binding.root) {
        val thumbnail: ImageView = binding.thumbnailImage
        val title: TextView = binding.documentTitle
        val date: TextView = binding.documentDate
        val menuButton: ImageView = binding.menuButton
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

        val thumbnailFile = File(
            file.parentFile?.parentFile,
            "Thumbnails/${file.nameWithoutExtension}_thumb.jpg"
        )

        // Glide автоматически:
        // - управляет памятью (кэширует, освобождает)
        // - загружает с уменьшением
        // - не вызывает утечек
        Glide.with(holder.itemView.context)
            .load(thumbnailFile)
            .placeholder(R.drawable.placeholder_pdf)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .into(holder.thumbnail)

        holder.thumbnail.setOnLongClickListener {
            onLongClick(file)
            true
        }

        holder.itemView.setOnClickListener { onClick(file) }
        holder.menuButton.setOnClickListener { onClick(file) }
    }

    override fun getItemCount() = documents.size

    override fun onViewRecycled(holder: DocumentViewHolder) {
        // Очищаем Glide для этого ViewHolder
        Glide.with(holder.itemView.context).clear(holder.thumbnail)
        super.onViewRecycled(holder)
    }

    // Убрано: onDetachedFromRecyclerView с clearMemory(), чтобы не очищать весь кэш Glide
}
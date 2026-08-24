package com.dragonsima.scandoc

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.util.Log
import android.graphics.drawable.ColorDrawable
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class DocumentsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: DocumentAdapter
    private val documents = mutableListOf<File>()
    private var sortByName = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_documents)

        recyclerView = findViewById(R.id.documentsRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        loadDocuments()

        adapter = DocumentAdapter(documents,
            onClick = { file -> showDocumentMenu(file) },
            onLongClick = { file -> showEnlargedThumbnail(file) }
        )
        recyclerView.adapter = adapter

        sortDocuments()

        findViewById<View>(R.id.backButton).setOnClickListener {
            finish()
        }

        findViewById<TextView>(R.id.sortButton).setOnClickListener {
            val options = arrayOf("📅 По дате", "🔤 По имени")
            AlertDialog.Builder(this)
                .setTitle("Сортировка")
                .setItems(options) { _, which ->
                    sortByName = which == 1
                    sortDocuments()
                }
                .show()
        }

        // Свайп-удаление
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
                val file = documents[position]

                AlertDialog.Builder(this@DocumentsActivity)
                    .setTitle("Удалить?")
                    .setMessage("Документ будет удалён безвозвратно")
                    .setPositiveButton("Удалить") { _, _ ->
                        file.delete()
                        documents.removeAt(position)
                        adapter.notifyItemRemoved(position)
                        Toast.makeText(this@DocumentsActivity, "🗑 Удалено", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Отмена") { _, _ ->
                        adapter.notifyItemChanged(position)
                    }
                    .show()
            }

            override fun onChildDraw(
                c: android.graphics.Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                // Красный фон
                val itemView = viewHolder.itemView
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.parseColor("#EF4444")
                }

                if (dX > 0) {
                    // Свайп вправо
                    c.drawRect(
                        itemView.left.toFloat(),
                        itemView.top.toFloat(),
                        dX,
                        itemView.bottom.toFloat(),
                        paint
                    )
                    // Иконка
                    val icon = android.graphics.BitmapFactory.decodeResource(
                        resources,
                        android.R.drawable.ic_menu_delete
                    )
                    val iconX = (itemView.left + 40).toFloat()
                    val iconY = (itemView.top + itemView.height / 2 - icon.height / 2).toFloat()
                    c.drawBitmap(icon, iconX, iconY, null)
                } else if (dX < 0) {
                    // Свайп влево
                    c.drawRect(
                        itemView.right.toFloat() + dX,
                        itemView.top.toFloat(),
                        itemView.right.toFloat(),
                        itemView.bottom.toFloat(),
                        paint
                    )
                    val icon = android.graphics.BitmapFactory.decodeResource(
                        resources,
                        android.R.drawable.ic_menu_delete
                    )
                    val iconX = (itemView.right - icon.width - 40).toFloat()
                    val iconY = (itemView.top + itemView.height / 2 - icon.height / 2).toFloat()
                    c.drawBitmap(icon, iconX, iconY, null)
                }

                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }
        })

        itemTouchHelper.attachToRecyclerView(recyclerView)
    }

    override fun onResume() {
        super.onResume()
        loadDocuments()
        adapter.notifyDataSetChanged()
    }

    private fun loadDocuments() {
        documents.clear()
        val documentsDir = File(filesDir, "Documents")
        if (documentsDir.exists()) {
            val files = documentsDir.listFiles { file -> file.extension == "pdf" }
            if (files != null) {
                documents.addAll(files.toList())
            }
        }
    }

    private fun sortDocuments() {
        if (sortByName) {
            documents.sortBy { it.nameWithoutExtension.lowercase() }
        } else {
            documents.sortByDescending { it.lastModified() }
        }
        adapter.notifyDataSetChanged()
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
        val thumbnailFile = File(
            file.parentFile?.parentFile,
            "Thumbnails/${file.nameWithoutExtension}_thumb.jpg"
        )

        if (!thumbnailFile.exists()) return

        val imageView = ImageView(this)
        imageView.setImageURI(android.net.Uri.fromFile(thumbnailFile))
        imageView.scaleType = ImageView.ScaleType.FIT_XY
        imageView.setPadding(0, 0, 0, 0)

        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(imageView)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.WHITE))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.7).toInt(),
            (resources.displayMetrics.heightPixels * 0.7).toInt()
        )
        dialog.show()

        imageView.setOnClickListener { dialog.dismiss() }
    }

    private fun openPdf(file: File) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Нет программы для просмотра PDF", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sharePdf(file: File) {
        if (!file.exists()) {
            Toast.makeText(this, "Файл не найден", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Поделиться"))
    }

    private fun renameFile(file: File) {
        val input = EditText(this)
        input.setText(file.nameWithoutExtension)

        AlertDialog.Builder(this)
            .setTitle("Переименовать")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    val newFile = File(file.parentFile, "$newName.pdf")
                    if (file.renameTo(newFile)) {
                        Toast.makeText(this, "✅ Переименовано", Toast.LENGTH_SHORT).show()
                        loadDocuments()
                        adapter.notifyDataSetChanged()
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun deleteFile(file: File) {
        AlertDialog.Builder(this)
            .setTitle("Удалить?")
            .setMessage("Документ будет удалён безвозвратно")
            .setPositiveButton("Удалить") { _, _ ->
                file.delete()
                Toast.makeText(this, "🗑 Удалено", Toast.LENGTH_SHORT).show()
                loadDocuments()
                adapter.notifyDataSetChanged()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

}

class DocumentAdapter(
    private val documents: List<File>,
    private val onClick: (File) -> Unit,
    private val onLongClick: (File) -> Unit
) : RecyclerView.Adapter<DocumentAdapter.DocumentViewHolder>() {

    class DocumentViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView = view.findViewById(R.id.thumbnailImage)
        val title: TextView = view.findViewById(R.id.documentTitle)
        val date: TextView = view.findViewById(R.id.documentDate)
        val menuButton: TextView = view.findViewById(R.id.menuButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DocumentViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_document, parent, false)
        return DocumentViewHolder(view)
    }

    override fun onBindViewHolder(holder: DocumentViewHolder, position: Int) {
        val file = documents[position]
        holder.title.text = file.nameWithoutExtension
        holder.date.text = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
            .format(Date(file.lastModified()))

        val thumbnailFile = File(
            file.parentFile?.parentFile,
            "Thumbnails/${file.nameWithoutExtension}_thumb.jpg"
        )

        Log.d("DocAdapter", "PDF: ${file.name}")
        Log.d("DocAdapter", "Thumbnail: ${thumbnailFile.absolutePath}")
        Log.d("DocAdapter", "Exists: ${thumbnailFile.exists()}")

        if (thumbnailFile.exists()) {
            holder.thumbnail.setImageURI(android.net.Uri.fromFile(thumbnailFile))
        }

        holder.thumbnail.setOnLongClickListener {
            onLongClick(file)
            true
        }

        holder.itemView.setOnClickListener { onClick(file) }
        holder.menuButton.setOnClickListener { onClick(file) }
    }

    override fun getItemCount() = documents.size
}
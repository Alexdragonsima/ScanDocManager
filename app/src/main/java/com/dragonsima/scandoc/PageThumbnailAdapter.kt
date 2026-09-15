package com.dragonsima.scandoc

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Адаптер для горизонтальной полоски миниатюр страниц в мультирежиме.
 */
class PageThumbnailAdapter(
    private val onPageClick: (Int) -> Unit,
    private val onPageLongClick: (Int) -> Unit
) : RecyclerView.Adapter<PageThumbnailAdapter.PageViewHolder>() {

    private val pages = mutableListOf<ScannedPage>()

    fun submitPages(newPages: List<ScannedPage>) {
        pages.clear()
        pages.addAll(newPages)
        notifyDataSetChanged()
    }

    class PageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView = view.findViewById(R.id.pageThumbnail)
        val number: TextView = view.findViewById(R.id.pageNumber)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_page_thumbnail, parent, false)
        return PageViewHolder(view)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        val page = pages[position]
        holder.thumbnail.setImageBitmap(page.thumbnail)
        holder.number.text = "${position + 1}"

        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onPageClick(pos)
        }
        holder.itemView.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                onPageLongClick(pos)
                true
            } else false
        }
    }

    override fun getItemCount() = pages.size
}
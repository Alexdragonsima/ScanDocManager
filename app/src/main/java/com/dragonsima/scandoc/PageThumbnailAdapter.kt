package com.dragonsima.scandoc

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

/**
 * Адаптер для горизонтальной полоски миниатюр страниц в мультирежиме.
 * Использует DiffUtil для точечных обновлений списка.
 */
class PageThumbnailAdapter(
    private val onPageClick: (Int) -> Unit,
    private val onPageLongClick: (Int) -> Unit
) : RecyclerView.Adapter<PageThumbnailAdapter.PageViewHolder>() {

    private val pages = mutableListOf<ScannedPage>()

    /**
     * Обновляет список страниц. Если изменились только thumbnails — не перерисовывает весь список.
     */
    fun submitPages(newPages: List<ScannedPage>) {
        val diff = DiffUtil.calculateDiff(PageDiffCallback(pages.toList(), newPages))
        pages.clear()
        pages.addAll(newPages)
        diff.dispatchUpdatesTo(this)
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
        holder.number.text = (position + 1).toString()

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

    /**
     * Diff callback: сравнивает страницы по thumbnail (идентичность Bitmap).
     * Если thumbnail один и тот же — элемент не перерисовывается.
     */
    private class PageDiffCallback(
        private val old: List<ScannedPage>,
        private val new: List<ScannedPage>
    ) : DiffUtil.Callback() {

        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size

        override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
            old[oldPos].thumbnail === new[newPos].thumbnail

        override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
            val o = old[oldPos]
            val n = new[newPos]
            return o.thumbnail === n.thumbnail && o.visionText === n.visionText
        }
    }
}
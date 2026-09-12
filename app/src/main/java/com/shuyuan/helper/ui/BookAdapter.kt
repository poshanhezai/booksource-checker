package com.shuyuan.helper.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.shuyuan.helper.data.BookRecord
import com.shuyuan.helper.databinding.ItemBookBinding

class BookAdapter(
    private val onClick: (BookRecord) -> Unit
) : RecyclerView.Adapter<BookAdapter.VH>() {

    private val items = mutableListOf<BookRecord>()

    fun submit(list: List<BookRecord>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemBookBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(private val binding: ItemBookBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(book: BookRecord) {
            binding.tvBookName.text = book.name
            binding.tvBookAuthor.text = buildString {
                append(book.author.ifBlank { "佚名" })
                append(" · ")
                append(book.sourceName)
            }
            val total = book.chapters.size
            binding.tvBookProgress.text = if (total > 0) {
                "已读 ${(book.lastChapterIndex + 1).coerceAtMost(total)}/$total 章"
            } else {
                "尚未获取目录"
            }
            binding.root.setOnClickListener { onClick(book) }
        }
    }
}

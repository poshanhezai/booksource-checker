package com.shuyuan.helper.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.shuyuan.helper.data.SearchBook
import com.shuyuan.helper.databinding.ItemSearchResultBinding

class SearchResultAdapter(
    private val onClick: (SearchBook) -> Unit
) : RecyclerView.Adapter<SearchResultAdapter.VH>() {

    private val items = mutableListOf<SearchBook>()

    fun submit(list: List<SearchBook>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemSearchResultBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(private val binding: ItemSearchResultBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(book: SearchBook) {
            binding.tvResultName.text = book.name
            binding.tvResultMeta.text = buildString {
                append(book.author.ifBlank { "佚名" })
                append(" · ")
                append(book.sourceName)
            }
            binding.tvResultIntro.text = book.intro
            binding.tvResultIntro.visibility =
                if (book.intro.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
            binding.root.setOnClickListener { onClick(book) }
        }
    }
}

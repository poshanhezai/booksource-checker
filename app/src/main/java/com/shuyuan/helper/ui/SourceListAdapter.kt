package com.shuyuan.helper.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.shuyuan.helper.R
import com.shuyuan.helper.data.SourceItem
import com.shuyuan.helper.data.SourceState
import com.shuyuan.helper.databinding.ItemSourceBinding

enum class SourceFilter(val match: (SourceState) -> Boolean) {
    ALL({ true }),
    OK({ it == SourceState.OK }),
    UNCERTAIN({ it == SourceState.UNCERTAIN }),
    DEAD({ it == SourceState.DEAD || it == SourceState.PENDING || it == SourceState.CHECKING })
}

class SourceListAdapter(
    private val onClick: (SourceItem) -> Unit
) : RecyclerView.Adapter<SourceListAdapter.VH>() {

    private val all = mutableListOf<SourceItem>()
    var filter: SourceFilter = SourceFilter.ALL
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    fun submit(list: List<SourceItem>) {
        all.clear()
        all.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemSourceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun getItemCount(): Int {
        return all.count { filter.match(it.state) }
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = all.filter { filter.match(it.state) }[position]
        holder.bind(item)
    }

    inner class VH(private val binding: ItemSourceBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SourceItem) {
            binding.tvName.text = item.name
            binding.tvUrl.text = item.url
            binding.tvStatus.text = item.detail
            val ctx = binding.root.context
            val colorRes = when (item.state) {
                SourceState.OK -> R.color.ok_green
                SourceState.UNCERTAIN -> R.color.warn_orange
                SourceState.DEAD -> R.color.dead_red
                else -> R.color.neutral_gray
            }
            val color = ContextCompat.getColor(ctx, colorRes)
            binding.tvDot.backgroundTintList = ColorStateList.valueOf(color)

            if (item.checked) {
                binding.tvBadge.visibility = android.view.View.VISIBLE
                binding.tvBadge.text = item.state.label
                binding.tvBadge.setTextColor(color)
            } else {
                binding.tvBadge.visibility = android.view.View.GONE
            }
            binding.root.setOnClickListener { onClick(item) }
        }
    }
}

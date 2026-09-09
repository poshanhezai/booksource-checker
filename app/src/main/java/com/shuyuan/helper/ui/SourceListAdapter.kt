package com.shuyuan.helper.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.shuyuan.helper.R
import com.shuyuan.helper.data.SourceGroup
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
    private val onClick: (SourceItem) -> Unit,
    private val onLongClick: ((SourceItem) -> Unit)? = null,
    private val onSelectionChanged: (() -> Unit)? = null
) : RecyclerView.Adapter<SourceListAdapter.VH>() {

    private val all = mutableListOf<SourceItem>()
    private val selectedKeys = LinkedHashSet<String>()
    var selectionMode: Boolean = false
        private set
    var filter: SourceFilter = SourceFilter.ALL
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    /** null 表示显示全部分组 */
    var groupFilter: SourceGroup? = null
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    val selectedCount: Int
        get() = selectedKeys.size

    private fun keyOf(item: SourceItem): String = "${item.host}\u0000${item.name}"

    private fun visibleItems(): List<SourceItem> =
        all.filter { filter.match(it.state) && (groupFilter == null || it.group == groupFilter) }

    fun isSelected(item: SourceItem): Boolean = selectedKeys.contains(keyOf(item))

    fun enterSelectionMode() {
        if (selectionMode) return
        selectionMode = true
        notifyDataSetChanged()
        onSelectionChanged?.invoke()
    }

    fun exitSelectionMode() {
        if (!selectionMode && selectedKeys.isEmpty()) return
        selectionMode = false
        selectedKeys.clear()
        notifyDataSetChanged()
        onSelectionChanged?.invoke()
    }

    fun toggleSelected(item: SourceItem) {
        if (!selectionMode) enterSelectionMode()
        val key = keyOf(item)
        if (!selectedKeys.remove(key)) selectedKeys.add(key)
        notifyDataSetChanged()
        onSelectionChanged?.invoke()
    }

    fun selectAllVisible() {
        visibleItems().forEach { selectedKeys.add(keyOf(it)) }
        notifyDataSetChanged()
        onSelectionChanged?.invoke()
    }

    fun selectedVisibleCount(): Int = visibleItems().count { isSelected(it) }

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
        return visibleItems().size
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = visibleItems()[position]
        holder.bind(item)
    }

    inner class VH(private val binding: ItemSourceBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SourceItem) {
            binding.cbSelect.isVisible = selectionMode
            binding.cbSelect.isChecked = isSelected(item)
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
            if (selectionMode) {
                binding.root.setOnClickListener { toggleSelected(item) }
                binding.root.setOnLongClickListener(null)
            } else {
                binding.root.setOnClickListener { onClick(item) }
                binding.root.setOnLongClickListener {
                    onLongClick?.invoke(item)
                    true
                }
            }
        }
    }
}

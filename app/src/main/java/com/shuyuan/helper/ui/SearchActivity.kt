package com.shuyuan.helper.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shuyuan.helper.R
import com.shuyuan.helper.data.AppLog
import com.shuyuan.helper.data.SourceItem
import com.shuyuan.helper.data.SourceStore
import com.shuyuan.helper.databinding.ActivitySearchBinding
import com.shuyuan.helper.net.ReaderEngine
import com.shuyuan.helper.net.ReaderHttpException
import com.shuyuan.helper.net.RuleUnsupportedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private lateinit var adapter: SearchResultAdapter
    private var sources: List<SourceItem> = emptyList()
    private var selectedSource: SourceItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = SearchResultAdapter { book ->
            startActivity(
                Intent(this, BookDetailActivity::class.java)
                    .putExtra(BookDetailActivity.EXTRA_NAME, book.name)
                    .putExtra(BookDetailActivity.EXTRA_AUTHOR, book.author)
                    .putExtra(BookDetailActivity.EXTRA_BOOK_URL, book.bookUrl)
                    .putExtra(BookDetailActivity.EXTRA_COVER, book.coverUrl)
                    .putExtra(BookDetailActivity.EXTRA_INTRO, book.intro)
                    .putExtra(BookDetailActivity.EXTRA_SOURCE_URL, book.sourceUrl)
            )
        }
        binding.rvSearchResults.layoutManager = LinearLayoutManager(this)
        binding.rvSearchResults.adapter = adapter
        binding.btnSearchBack.setOnClickListener { finish() }
        binding.btnPickSource.setOnClickListener { showSourcePicker() }
        binding.btnDoSearch.setOnClickListener { doSearch() }

        lifecycleScope.launch {
            sources = withContext(Dispatchers.IO) { SourceStore.load(this@SearchActivity) }
            selectedSource = sources.firstOrNull()
            updateSourceText()
            if (sources.isEmpty()) {
                binding.tvSearchStatus.text = getString(R.string.search_no_source)
            }
        }
    }

    private fun updateSourceText() {
        val s = selectedSource
        binding.tvSearchSource.text = if (s == null) {
            getString(R.string.search_no_source)
        } else {
            "${getString(R.string.search_source_prefix)}${s.name}（${s.group.label}）"
        }
    }

    private fun doSearch() {
        val source = selectedSource
        if (source == null) {
            toast(getString(R.string.search_no_source))
            return
        }
        val keyword = binding.etSearchKeyword.text?.toString().orEmpty().trim()
        if (keyword.isBlank()) {
            toast("请输入书名或作者")
            return
        }
        binding.searchProgress.visibility = View.VISIBLE
        binding.tvSearchStatus.text = "正在用「${source.name}」搜索…"
        lifecycleScope.launch {
            try {
                val results = withContext(Dispatchers.IO) { ReaderEngine.search(source, keyword) }
                adapter.submit(results)
                binding.tvSearchStatus.text = if (results.isEmpty()) {
                    getString(R.string.search_no_result)
                } else {
                    "找到 ${results.size} 条结果"
                }
                AppLog.append(AppLog.Tag.APP, "搜索：${source.name} 关键词=$keyword 结果=${results.size}")
            } catch (e: RuleUnsupportedException) {
                binding.tvSearchStatus.text = "该书源暂不支持：${e.message}"
                AppLog.warn(AppLog.Tag.APP, "搜索规则不支持：${source.name} ${e.message}")
            } catch (e: ReaderHttpException) {
                binding.tvSearchStatus.text = "搜索失败：${e.message}"
                AppLog.warn(AppLog.Tag.APP, "搜索网络失败：${source.name} ${e.message}")
            } catch (e: Exception) {
                binding.tvSearchStatus.text = "搜索异常：${e.message}"
                AppLog.error(AppLog.Tag.APP, "搜索异常：${source.name}", e)
            } finally {
                binding.searchProgress.visibility = View.GONE
            }
        }
    }

    private fun showSourcePicker() {
        if (sources.isEmpty()) {
            toast(getString(R.string.search_no_source))
            return
        }
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val input = EditText(this).apply { hint = "筛选书源名称" }
        val listView = ListView(this)
        container.addView(input)
        container.addView(listView)
        val sourceNames = sources.map { "${it.name}（${it.group.label}）" }
        val listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, sourceNames.toMutableList())
        listView.adapter = listAdapter
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.search_pick_source)
            .setView(container)
            .setNegativeButton("取消", null)
            .create()
        listView.setOnItemClickListener { _, _, position, _ ->
            val name = listAdapter.getItem(position).orEmpty()
            selectedSource = sources.firstOrNull { "${it.name}（${it.group.label}）" == name }
            updateSourceText()
            dialog.dismiss()
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString().orEmpty()
                listAdapter.clear()
                listAdapter.addAll(sourceNames.filter { q.isBlank() || it.contains(q, true) })
                listAdapter.notifyDataSetChanged()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        dialog.show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}

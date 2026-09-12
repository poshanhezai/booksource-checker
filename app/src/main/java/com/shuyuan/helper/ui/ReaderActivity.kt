package com.shuyuan.helper.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shuyuan.helper.R
import com.shuyuan.helper.data.AppLog
import com.shuyuan.helper.data.BookRecord
import com.shuyuan.helper.data.BookStore
import com.shuyuan.helper.data.SourceItem
import com.shuyuan.helper.data.SourceStore
import com.shuyuan.helper.databinding.ActivityReaderBinding
import com.shuyuan.helper.net.ReaderEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReaderBinding
    private var record: BookRecord? = null
    private var source: SourceItem? = null
    private var chapterIndex = 0
    private var fontSize = 18f
    private var nightMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val bookUrl = intent.getStringExtra(EXTRA_BOOK_URL).orEmpty()
        val sourceUrl = intent.getStringExtra(EXTRA_SOURCE_URL).orEmpty()
        val prefs = getSharedPreferences("reader", MODE_PRIVATE)
        fontSize = prefs.getFloat("font_size", 18f)
        nightMode = prefs.getBoolean("night_mode", false)
        applyFont()
        applyTheme()

        binding.btnReaderBack.setOnClickListener { finish() }
        binding.btnPrevChapter.setOnClickListener { loadChapter(chapterIndex - 1) }
        binding.btnNextChapter.setOnClickListener { loadChapter(chapterIndex + 1) }
        binding.btnFontSmaller.setOnClickListener {
            fontSize = (fontSize - 1f).coerceAtLeast(12f)
            prefs.edit().putFloat("font_size", fontSize).apply()
            applyFont()
        }
        binding.btnFontLarger.setOnClickListener {
            fontSize = (fontSize + 1f).coerceAtMost(30f)
            prefs.edit().putFloat("font_size", fontSize).apply()
            applyFont()
        }
        binding.btnReaderTheme.setOnClickListener {
            nightMode = !nightMode
            prefs.edit().putBoolean("night_mode", nightMode).apply()
            applyTheme()
        }
        binding.btnCatalog.setOnClickListener { showCatalog() }

        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                BookStore.find(this@ReaderActivity, bookUrl, sourceUrl) to
                    SourceStore.load(this@ReaderActivity).firstOrNull { it.url == sourceUrl }
            }
            record = loaded.first
            source = loaded.second
            val book = record
            if (book == null || source == null) {
                toast("书籍不存在或书源已被删除")
                finish()
                return@launch
            }
            if (book.chapters.isEmpty()) {
                binding.tvReaderContent.text = "这本书还没有目录，请回详情页重试。"
                return@launch
            }
            chapterIndex = book.lastChapterIndex.coerceIn(0, book.chapters.size - 1)
            loadChapter(chapterIndex)
        }
    }

    private fun loadChapter(index: Int) {
        val book = record ?: return
        val src = source ?: return
        if (index !in book.chapters.indices) {
            toast(if (index < 0) "已经是第一章" else "已经是最后一章")
            return
        }
        chapterIndex = index
        val chapter = book.chapters[index]
        binding.tvChapterTitle.text = chapter.name
        binding.tvReaderContent.text = "正在加载章节…"
        binding.btnPrevChapter.isEnabled = index > 0
        binding.btnNextChapter.isEnabled = index < book.chapters.size - 1
        lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    ReaderEngine.fetchContent(src, chapter.url, src.url)
                }
                binding.tvReaderContent.text = text
                binding.readerScroll.post { binding.readerScroll.scrollTo(0, 0) }
                BookStore.updateProgress(this@ReaderActivity, book.bookUrl, book.sourceUrl, index)
                AppLog.append(AppLog.Tag.APP, "阅读：${book.name} 第${index + 1}章 ${chapter.name}")
            } catch (e: Exception) {
                binding.tvReaderContent.text = "章节加载失败：${e.message}"
                AppLog.error(AppLog.Tag.APP, "章节加载失败：${book.name} ${chapter.name}", e)
            }
        }
    }

    private fun showCatalog() {
        val book = record ?: return
        if (book.chapters.isEmpty()) return
        val listView = ListView(this)
        val names = book.chapters.map { "${it.index}. ${it.name}" }
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.reader_catalog)
            .setView(listView)
            .setNegativeButton("关闭", null)
            .create()
        listView.setOnItemClickListener { _, _, position, _ ->
            dialog.dismiss()
            loadChapter(position)
        }
        dialog.show()
    }

    private fun applyFont() {
        binding.tvReaderContent.textSize = fontSize
    }

    private fun applyTheme() {
        val bg = if (nightMode) Color.parseColor("#14161A") else ContextCompat.getColor(this, R.color.bg_window)
        val fg = if (nightMode) Color.parseColor("#C9CDD3") else ContextCompat.getColor(this, R.color.text_primary)
        binding.root.setBackgroundColor(bg)
        binding.readerScroll.setBackgroundColor(bg)
        binding.tvReaderContent.setTextColor(fg)
        binding.tvChapterTitle.setTextColor(fg)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_BOOK_URL = "reader_book_url"
        const val EXTRA_SOURCE_URL = "reader_source_url"
    }
}

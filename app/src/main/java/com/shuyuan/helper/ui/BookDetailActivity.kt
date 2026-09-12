package com.shuyuan.helper.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.shuyuan.helper.R
import com.shuyuan.helper.data.AppLog
import com.shuyuan.helper.data.BookRecord
import com.shuyuan.helper.data.BookStore
import com.shuyuan.helper.data.SearchBook
import com.shuyuan.helper.data.SourceStore
import com.shuyuan.helper.databinding.ActivityBookDetailBinding
import com.shuyuan.helper.net.ReaderEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BookDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBookDetailBinding
    private var record: BookRecord? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBookDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnDetailBack.setOnClickListener { finish() }

        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val author = intent.getStringExtra(EXTRA_AUTHOR).orEmpty()
        val bookUrl = intent.getStringExtra(EXTRA_BOOK_URL).orEmpty()
        val cover = intent.getStringExtra(EXTRA_COVER).orEmpty()
        val intro = intent.getStringExtra(EXTRA_INTRO).orEmpty()
        val sourceUrl = intent.getStringExtra(EXTRA_SOURCE_URL).orEmpty()
        binding.tvDetailName.text = name
        binding.tvDetailMeta.text = author.ifBlank { "佚名" }
        binding.tvDetailIntro.text = intro
        binding.tvDetailStatus.text = getString(R.string.book_loading)
        binding.btnAddShelf.isEnabled = false
        binding.btnStartRead.isEnabled = false

        lifecycleScope.launch {
            val source = withContext(Dispatchers.IO) {
                SourceStore.load(this@BookDetailActivity).firstOrNull { it.url == sourceUrl }
            }
            if (source == null) {
                binding.tvDetailStatus.text = "找不到对应书源，请重新搜索"
                return@launch
            }
            try {
                val searchBook = SearchBook(name, author, bookUrl, cover, intro, source.name, source.url)
                val detail = withContext(Dispatchers.IO) { ReaderEngine.detail(source, searchBook) }
                record = detail
                binding.tvDetailName.text = detail.name
                binding.tvDetailMeta.text = buildString {
                    append(detail.author.ifBlank { "佚名" })
                    append(" · ")
                    append(detail.sourceName)
                    if (detail.chapters.isNotEmpty()) append(" · ${detail.chapters.size} 章")
                }
                binding.tvDetailIntro.text = detail.intro.ifBlank { intro }
                binding.tvDetailStatus.text = "书籍信息已就绪"
                binding.btnAddShelf.isEnabled = true
                binding.btnStartRead.isEnabled = true
                AppLog.append(AppLog.Tag.APP, "书籍详情：${detail.name} 章节=${detail.chapters.size}")
            } catch (e: Exception) {
                binding.tvDetailStatus.text = "获取详情失败：${e.message}"
                AppLog.error(AppLog.Tag.APP, "获取书籍详情失败：$name", e)
            }
        }

        binding.btnAddShelf.setOnClickListener {
            val detail = record ?: return@setOnClickListener
            BookStore.upsert(this, detail)
            toast("已加入书架")
        }
        binding.btnStartRead.setOnClickListener {
            val detail = record ?: return@setOnClickListener
            BookStore.upsert(this, detail)
            startActivity(
                Intent(this, ReaderActivity::class.java)
                    .putExtra(ReaderActivity.EXTRA_BOOK_URL, detail.bookUrl)
                    .putExtra(ReaderActivity.EXTRA_SOURCE_URL, detail.sourceUrl)
            )
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_NAME = "book_name"
        const val EXTRA_AUTHOR = "book_author"
        const val EXTRA_BOOK_URL = "book_url"
        const val EXTRA_COVER = "book_cover"
        const val EXTRA_INTRO = "book_intro"
        const val EXTRA_SOURCE_URL = "source_url"
    }
}

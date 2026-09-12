package com.shuyuan.helper.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.shuyuan.helper.data.AppLog
import com.shuyuan.helper.data.BookStore
import com.shuyuan.helper.databinding.ActivityBookshelfBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BookshelfActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBookshelfBinding
    private lateinit var adapter: BookAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBookshelfBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = BookAdapter { book ->
            startActivity(
                Intent(this, ReaderActivity::class.java)
                    .putExtra(ReaderActivity.EXTRA_BOOK_URL, book.bookUrl)
                    .putExtra(ReaderActivity.EXTRA_SOURCE_URL, book.sourceUrl)
            )
        }
        binding.rvBooks.layoutManager = LinearLayoutManager(this)
        binding.rvBooks.adapter = adapter
        binding.btnGoSearch.setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }
        binding.btnGoSources.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val books = withContext(Dispatchers.IO) { BookStore.load(this@BookshelfActivity) }
            adapter.submit(books)
            binding.tvShelfEmpty.visibility = if (books.isEmpty()) View.VISIBLE else View.GONE
            binding.rvBooks.visibility = if (books.isEmpty()) View.GONE else View.VISIBLE
            if (books.isEmpty()) {
                val sources = withContext(Dispatchers.IO) {
                    com.shuyuan.helper.data.SourceStore.load(this@BookshelfActivity)
                }
                AppLog.append(AppLog.Tag.APP, "书架加载：${books.size} 本，书源 ${sources.size} 个")
            }
        }
    }
}

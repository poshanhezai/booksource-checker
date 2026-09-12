package com.shuyuan.helper.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/** 书源持久化：沿用检测页的 import_sources.json，重启后书架/搜索仍可用 */
object SourceStore {

    fun file(context: Context): File = File(context.filesDir, "import_sources.json")

    fun load(context: Context): List<SourceItem> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return runCatching {
            SourceImporter.parse(f.readText()).items
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, items: List<SourceItem>) {
        runCatching {
            val f = file(context)
            f.parentFile?.mkdirs()
            f.writeText(SourceImporter.toRawText(items))
        }
    }
}

/** 书架与阅读进度持久化 */
object BookStore {

    private val gson = Gson()
    private val type = object : TypeToken<List<BookRecord>>() {}.type

    private fun file(context: Context): File = File(context.filesDir, "bookshelf.json")

    fun load(context: Context): MutableList<BookRecord> {
        val f = file(context)
        if (!f.exists()) return mutableListOf()
        return runCatching {
            val list: List<BookRecord> = gson.fromJson(f.readText(), type) ?: emptyList()
            list.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    fun save(context: Context, books: List<BookRecord>) {
        runCatching { file(context).writeText(gson.toJson(books)) }
    }

    fun upsert(context: Context, book: BookRecord) {
        val list = load(context)
        val idx = list.indexOfFirst { it.bookUrl == book.bookUrl && it.sourceUrl == book.sourceUrl }
        if (idx >= 0) list[idx] = book else list.add(0, book)
        save(context, list)
    }

    fun find(context: Context, bookUrl: String, sourceUrl: String): BookRecord? =
        load(context).firstOrNull { it.bookUrl == bookUrl && it.sourceUrl == sourceUrl }

    fun updateProgress(context: Context, bookUrl: String, sourceUrl: String, chapterIndex: Int) {
        val list = load(context)
        val idx = list.indexOfFirst { it.bookUrl == bookUrl && it.sourceUrl == sourceUrl }
        if (idx < 0) return
        list[idx] = list[idx].copy(lastChapterIndex = chapterIndex)
        save(context, list)
    }
}

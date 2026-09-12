package com.shuyuan.helper.data

data class SearchBook(
    val name: String,
    val author: String,
    val bookUrl: String,
    val coverUrl: String,
    val intro: String,
    val sourceName: String,
    val sourceUrl: String
)

data class ChapterInfo(
    val index: Int,
    val name: String,
    val url: String
)

data class BookRecord(
    val name: String,
    val author: String,
    val bookUrl: String,
    val coverUrl: String,
    val intro: String,
    val sourceName: String,
    val sourceUrl: String,
    val chapters: List<ChapterInfo> = emptyList(),
    val lastChapterIndex: Int = 0
)

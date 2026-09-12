package com.shuyuan.helper.net

import com.google.gson.JsonObject
import com.shuyuan.helper.data.BookRecord
import com.shuyuan.helper.data.ChapterInfo
import com.shuyuan.helper.data.SearchBook
import com.shuyuan.helper.data.SourceItem
import java.net.URI

/**
 * 阅读核心：把 Legado 书源规则跑起来。
 * 搜索 → 详情 → 目录 → 正文。
 */
object ReaderEngine {

    suspend fun search(source: SourceItem, keyword: String): List<SearchBook> {
        val ruleSearch = source.json.obj("ruleSearch") ?: return emptyList()
        val listRule = ruleSearch.str("bookList")
        if (listRule.isBlank()) throw RuleUnsupportedException("书源缺少搜索列表规则 bookList")
        val built = SearchRequestBuilder.build(source.json, keyword)
        val resp = ReaderHttp.execute(built)
        val nodes = RuleEngine.listNodes(resp.root, listRule)
        val results = ArrayList<SearchBook>()
        for (node in nodes) {
            val name = RuleEngine.text(node, ruleSearch.str("name"))
            if (name.isBlank()) continue
            val bookUrlRaw = RuleEngine.text(node, ruleSearch.str("bookUrl"))
            if (bookUrlRaw.isBlank()) continue
            val bookUrl = absolute(resp.finalUrl, bookUrlRaw)
            val author = RuleEngine.text(node, ruleSearch.str("author"))
            val coverRaw = RuleEngine.text(node, ruleSearch.str("coverUrl"))
            val intro = RuleEngine.text(node, ruleSearch.str("intro"))
            results.add(
                SearchBook(
                    name = name,
                    author = author,
                    bookUrl = bookUrl,
                    coverUrl = if (coverRaw.isBlank()) "" else absolute(resp.finalUrl, coverRaw),
                    intro = intro,
                    sourceName = source.name,
                    sourceUrl = source.url
                )
            )
            if (results.size >= 60) break
        }
        return results
    }

    suspend fun detail(source: SourceItem, searchBook: SearchBook): BookRecord {
        val resp = ReaderHttp.get(searchBook.bookUrl, source.url)
        val info = source.json.obj("ruleBookInfo")
        var name = searchBook.name
        var author = searchBook.author
        var cover = searchBook.coverUrl
        var intro = searchBook.intro
        var tocUrl = searchBook.bookUrl
        if (info != null) {
            info.str("name").takeIf { it.isNotBlank() }?.let {
                RuleEngine.extract(resp.root, it).takeIf { v -> v.isNotBlank() }?.let { v -> name = v }
            }
            info.str("author").takeIf { it.isNotBlank() }?.let {
                RuleEngine.extract(resp.root, it).takeIf { v -> v.isNotBlank() }?.let { v -> author = v }
            }
            info.str("coverUrl").takeIf { it.isNotBlank() }?.let {
                RuleEngine.extract(resp.root, it).takeIf { v -> v.isNotBlank() }?.let { v ->
                    cover = absolute(resp.finalUrl, v)
                }
            }
            info.str("intro").takeIf { it.isNotBlank() }?.let {
                RuleEngine.extract(resp.root, it).takeIf { v -> v.isNotBlank() }?.let { v -> intro = v }
            }
            info.str("tocUrl").takeIf { it.isNotBlank() }?.let {
                RuleEngine.extract(resp.root, it).takeIf { v -> v.isNotBlank() }?.let { v ->
                    tocUrl = absolute(resp.finalUrl, v)
                }
            }
        }
        val chapters = fetchToc(source, tocUrl, resp.finalUrl)
        return BookRecord(
            name = name,
            author = author,
            bookUrl = searchBook.bookUrl,
            coverUrl = cover,
            intro = intro,
            sourceName = source.name,
            sourceUrl = source.url,
            chapters = chapters
        )
    }

    suspend fun fetchToc(source: SourceItem, tocUrl: String, referer: String? = null): List<ChapterInfo> {
        val resp = ReaderHttp.get(tocUrl, referer ?: source.url)
        val toc = source.json.obj("ruleToc") ?: throw RuleUnsupportedException("书源缺少目录规则 ruleToc")
        val listRule = toc.str("chapterList")
        if (listRule.isBlank()) throw RuleUnsupportedException("书源缺少章节列表规则 chapterList")
        val nodes = RuleEngine.listNodes(resp.root, listRule)
        val chapters = ArrayList<ChapterInfo>(nodes.size)
        var index = 1
        for (node in nodes) {
            val name = RuleEngine.text(node, toc.str("chapterName"))
            val urlRaw = RuleEngine.text(node, toc.str("chapterUrl"))
            if (name.isBlank() || urlRaw.isBlank()) continue
            chapters.add(
                ChapterInfo(
                    index = index,
                    name = name,
                    url = absolute(resp.finalUrl, urlRaw)
                )
            )
            index++
        }
        if (chapters.isEmpty()) throw RuleUnsupportedException("没有解析到章节，可能是 JS 规则或页面结构较特殊")
        return chapters
    }

    suspend fun fetchContent(source: SourceItem, chapterUrl: String, referer: String? = null): String {
        val resp = ReaderHttp.get(chapterUrl, referer ?: source.url)
        val content = source.json.obj("ruleContent") ?: throw RuleUnsupportedException("书源缺少正文规则 ruleContent")
        val rule = content.str("content")
        if (rule.isBlank()) throw RuleUnsupportedException("书源正文规则为空")
        val text = RuleEngine.extract(resp.root, rule)
        if (text.isBlank()) throw RuleUnsupportedException("没有解析到正文，可能是 JS/WebView 规则")
        return text
            .replace('\u00A0', ' ')
            .replace(Regex("""[ \t]+\n"""), "\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }

    fun pickSource(sources: List<SourceItem>, query: String): SourceItem? {
        if (sources.isEmpty()) return null
        val q = query.trim()
        if (q.isBlank()) return null
        return sources.firstOrNull { it.name == q }
            ?: sources.firstOrNull { it.name.contains(q, true) }
            ?: sources.firstOrNull { it.host.contains(q, true) }
    }

    private fun absolute(base: String, url: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        return runCatching { URI(base).resolve(url).toString() }.getOrDefault(url)
    }

    private fun JsonObject.obj(key: String): JsonObject? {
        val v = get(key) ?: return null
        return if (v.isJsonObject) v.asJsonObject else null
    }

    private fun JsonObject.str(key: String): String {
        val v = get(key) ?: return ""
        return if (v.isJsonPrimitive) v.asString else ""
    }
}

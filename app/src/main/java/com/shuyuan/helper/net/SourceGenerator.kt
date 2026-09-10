package com.shuyuan.helper.net

import com.google.gson.JsonObject
import com.shuyuan.helper.data.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit

/**
 * 试验版「网址 → 书源初稿」自动生成器。
 *
 * 思路：
 * 1. 抓取目标页面；
 * 2. 从搜索框 / 搜索链接推断搜索地址（GET/POST）；
 * 3. 用测试关键词发一次真实搜索；
 * 4. 在结果页里找“重复出现的条目结构”，推断 bookList / name / bookUrl；
 * 5. 组装成标准 Legado 书源 JSON，供导入检测、人工微调。
 */
data class GeneratedSource(
    val ok: Boolean,
    val message: String,
    val json: String? = null,
    val summary: String? = null,
    val searchUrl: String = "",
    val listRule: String = "",
    val selfTestCount: Int = -1
)

data class SiteInspection(
    val ok: Boolean,
    val verdict: String,
    val summary: String,
    val items: List<String>
)

object SourceGenerator {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val keywordNames = listOf(
        "q", "key", "keyword", "keys", "searchkey", "searchKey", "wd", "word",
        "kw", "query", "searchword", "bookname", "bookName", "name", "search",
        "keyboard", "txtkey", "searchvalue", "searchtext", "content", "skey", "k"
    ).toHashSet()

    suspend fun generate(rawInput: String, keywordInput: String): GeneratedSource =
        withContext(Dispatchers.IO) {
            val input = normalizeUrl(rawInput)
            if (input == null) {
                return@withContext GeneratedSource(
                    false,
                    "网址格式不对，请输入 http:// 或 https:// 开头的完整地址"
                )
            }
            val rootOrigin = originOf(input)
            if (rootOrigin == null) {
                return@withContext GeneratedSource(false, "无法识别网址主机")
            }
            val keyword = keywordInput.trim().ifBlank { "我" }

            val homePage = fetch(input)
            if (homePage == null || homePage.status !in 200..399) {
                return@withContext GeneratedSource(
                    false,
                    if (homePage == null) "页面抓取失败，请确认网址可访问" else "页面返回 HTTP ${homePage.status}"
                )
            }

            val homeDoc = homePage.doc
            val siteName = guessSiteName(homeDoc, rootOrigin)
            val search = discoverSearch(input, homeDoc)

            // 尝试做一次真实搜索并推断列表规则
            var inferDoc: Document? = null
            var searchNote = ""
            var selfTestCount = -1
            var listRule = ""

            if (search != null) {
                val resultPage = fetchSearch(search, keyword, rootOrigin)
                if (resultPage != null && resultPage.status in 200..399) {
                    inferDoc = resultPage.doc
                    searchNote = if (looksLikeNoResult(resultPage.doc)) "搜索返回空结果页" else ""
                } else {
                    searchNote = if (resultPage == null) "搜索请求失败" else "搜索返回 HTTP ${resultPage.status}"
                }
            }

            val rule = if (inferDoc != null && !looksLikeNoResult(inferDoc)) {
                inferRules(inferDoc, rootOrigin)
            } else null

            if (rule != null && rule.bookList.isNotBlank()) {
                listRule = rule.bookList
                selfTestCount = rule.selfTestCount
            }

            val searchRule = search?.legadoRule.orEmpty()
            val commentParts = mutableListOf("由「网址生成书源（试验版）」自动生成，建议在阅读 App 中验证后使用。")
            if (search != null) {
                commentParts.add("已推断搜索地址。")
            } else {
                commentParts.add("未找到页面搜索入口，searchUrl 为空，需人工补充。")
            }
            if (rule != null && rule.bookList.isNotBlank()) {
                commentParts.add("自动推断列表规则，自测命中 ${rule.selfTestCount} 条。")
            } else {
                commentParts.add("未能可靠推断列表规则，ruleSearch 留空，可先复制 JSON 人工补规则。")
            }
            if (searchNote.isNotBlank()) commentParts.add(searchNote)

            val json = buildSourceJson(
                siteName = siteName,
                siteUrl = rootOrigin,
                searchUrl = searchRule,
                rule = rule,
                comment = commentParts.joinToString("")
            )
            val summary = buildString {
                append("站点：").append(siteName).append('\n')
                append("地址：").append(rootOrigin).append('\n')
                append("搜索规则：").append(if (searchRule.isNotBlank()) "已生成" else "未找到（空）").append('\n')
                val listText = rule?.bookList
                append("列表规则：").append(if (listText.isNullOrBlank()) "未推断" else listText).append('\n')
                if (selfTestCount >= 0) {
                    append("自测命中：").append(selfTestCount).append(" 条").append('\n')
                }
                append("说明：这是初稿，导入前建议先核对规则。")
            }
            GeneratedSource(
                ok = true,
                message = "生成完成",
                json = json.toString(),
                summary = summary,
                searchUrl = searchRule,
                listRule = listRule,
                selfTestCount = selfTestCount
            )
        }

    /**
     * 适性预检：先判断网站是否具备「自动生成书源」的条件，
     * 而不是直接盲目生成。结论分三档：
     * 可直接自动生成 / 可做但需人工补规则 / 暂不适合自动生成。
     */
    suspend fun inspect(rawInput: String, keywordInput: String): SiteInspection =
        withContext(Dispatchers.IO) {
            val input = normalizeUrl(rawInput)
            if (input == null) {
                return@withContext SiteInspection(
                    ok = false,
                    verdict = "网址无效",
                    summary = "请输入 http:// 或 https:// 开头的完整网址。",
                    items = emptyList()
                )
            }
            val origin = originOf(input)
            if (origin == null) {
                return@withContext SiteInspection(
                    ok = false,
                    verdict = "网址无效",
                    summary = "无法识别网址主机。",
                    items = emptyList()
                )
            }
            val page = fetch(input)
            if (page == null) {
                return@withContext SiteInspection(
                    ok = false,
                    verdict = "暂不适合自动生成",
                    summary = "连不上目标网站：超时、被网络阻断或站点已关闭。",
                    items = listOf("建议先用浏览器打开确认网站是否仍能访问。")
                )
            }
            val checks = ArrayList<String>()
            checks.add("主页状态：HTTP ${page.status}")
            val html = page.doc.html().lowercase()
            val antiBot = containsAntiBot(html)

            if (page.status == 403) {
                return@withContext SiteInspection(
                    ok = false,
                    verdict = "暂不适合自动生成",
                    summary = if (antiBot) {
                        "网站启用了反爬/人机验证（Cloudflare 或同类防护），普通请求被 403 拒绝。"
                    } else {
                        "网站拒绝访问（HTTP 403）：可能封禁了非浏览器请求、需要登录或按地区限制。"
                    },
                    items = checks + "即使强行生成，检测时也会同样被拦截。"
                )
            }
            if (page.status !in 200..399) {
                return@withContext SiteInspection(
                    ok = false,
                    verdict = "暂不适合自动生成",
                    summary = "主页返回 HTTP ${page.status}，网站当前不可正常访问。",
                    items = checks
                )
            }
            checks.add("页面标题：${guessSiteName(page.doc, origin)}")
            if (antiBot && page.doc.body().text().length < 200) {
                return@withContext SiteInspection(
                    ok = false,
                    verdict = "暂不适合自动生成",
                    summary = "返回的是人机验证/JS 挑战页（Cloudflare 等），不是真实内容页。",
                    items = checks + "自动请求无法通过验证，书源在阅读器中也会失效。"
                )
            }

            val search = discoverSearch(input, page.doc)
            if (search == null) {
                return@withContext SiteInspection(
                    ok = true,
                    verdict = "可做但需人工补规则",
                    summary = "网站本身可访问，但没有找到可自动识别的搜索入口。",
                    items = checks + "可以手动提供搜索地址，或只用于书架/目录站。"
                )
            }
            checks.add("搜索入口：已找到（${if (search.method == "POST") "POST" else "GET"}）")

            val keyword = keywordInput.trim().ifBlank { "我" }
            val resultPage = fetchSearch(search, keyword, origin)
            if (resultPage == null || resultPage.status !in 200..399) {
                return@withContext SiteInspection(
                    ok = false,
                    verdict = "可做但需人工补规则",
                    summary = if (resultPage == null) {
                        "主页可访问，但测试搜索请求失败。"
                    } else {
                        "主页可访问，但测试搜索返回 HTTP ${resultPage.status}，搜索接口被拦截。"
                    },
                    items = checks
                )
            }
            if (looksLikeNoResult(resultPage.doc)) {
                return@withContext SiteInspection(
                    ok = true,
                    verdict = "可做但需人工补规则",
                    summary = "搜索入口能用，但当前关键词没有返回结果，无法自动推断列表规则。",
                    items = checks + "可换个常见书名重试。"
                )
            }

            val rule = inferRules(resultPage.doc, origin)
            if (rule != null && rule.bookList.isNotBlank()) {
                checks.add("列表结构：自动识别成功，自测命中 ${rule.selfTestCount} 条")
                return@withContext SiteInspection(
                    ok = true,
                    verdict = "可直接自动生成",
                    summary = "网站结构适合做书源，可以尝试直接生成初稿。",
                    items = checks + "注意：目录与正文规则仍需后续验证。"
                )
            }
            return@withContext SiteInspection(
                ok = true,
                verdict = "可做但需人工补规则",
                summary = "网站能访问、搜索也能返回内容，但页面结构较特殊，自动推断没有命中。",
                items = checks + "生成初稿后可能需要人工修改 ruleSearch。"
            )
        }

    private fun containsAntiBot(html: String): Boolean {
        val markers = listOf(
            "cf-chl", "challenge-platform", "cf-browser-verification", "cf-turnstile",
            "just a moment", "enable javascript and cookies", "verify you are human",
            "attention required", "安全检查", "人机验证", "请开启javascript", "captcha"
        )
        return markers.any { html.contains(it) }
    }

    private fun normalizeUrl(input: String): String? {
        var s = input.trim()
        if (s.isBlank()) return null
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
        return runCatching {
            val uri = URI(s)
            if (uri.host.isNullOrBlank()) null else s
        }.getOrDefault(null)
    }

    private fun originOf(url: String): String? {
        return runCatching {
            val uri = URI(url)
            val host = uri.host ?: return null
            val scheme = uri.scheme.lowercase()
            val port = if (uri.port > 0 && !((scheme == "http" && uri.port == 80) || (scheme == "https" && uri.port == 443))) ":${uri.port}" else ""
            "$scheme://$host$port"
        }.getOrNull()
    }

    private suspend fun fetch(url: String): FetchedPage? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", ProbeEngine.UA)
                .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val status = resp.code
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                val finalUrl = resp.request.url.toString()
                val doc = parse(bytes, finalUrl, resp.header("Content-Type"))
                FetchedPage(status, finalUrl, doc, bytes)
            }
        } catch (e: Exception) {
            AppLog.error(AppLog.Tag.GENERATE, "抓取页面失败：$url", e)
            null
        }
    }

    private suspend fun fetchSearch(search: FoundSearch, keyword: String, refererOrigin: String): FetchedPage? {
        return try {
            val encoded = URLEncoder.encode(keyword, "UTF-8")
            val reqBuilder = Request.Builder()
                .header("User-Agent", ProbeEngine.UA)
                .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("Referer", refererOrigin)
            if (search.method.equals("POST", true)) {
                val body = (search.bodyTemplate ?: "").replace("{{key}}", encoded)
                reqBuilder.post(body.toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull()))
                reqBuilder.url(search.requestUrl)
            } else {
                val url = search.requestUrl.replace("{{key}}", encoded)
                reqBuilder.url(url)
                reqBuilder.get()
            }
            client.newCall(reqBuilder.build()).execute().use { resp ->
                val status = resp.code
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                val finalUrl = resp.request.url.toString()
                FetchedPage(status, finalUrl, parse(bytes, finalUrl, resp.header("Content-Type")), bytes)
            }
        } catch (e: Exception) {
            AppLog.error(
                AppLog.Tag.GENERATE,
                "生成书源搜索请求失败：${search.requestUrl}",
                e
            )
            null
        }
    }

    private fun parse(bytes: ByteArray, baseUrl: String, contentTypeHeader: String?): Document {
        val guesses = ArrayList<String>()
        contentTypeHeader?.let {
            val cs = Regex("""charset\s*=\s*["']?([\w-]+)""", RegexOption.IGNORE_CASE)
                .find(it)?.groupValues?.get(1)
            if (!cs.isNullOrBlank()) guesses.add(cs.lowercase())
        }
        val headAscii = String(bytes, 0, minOf(2048, bytes.size), Charsets.ISO_8859_1)
        Regex("""charset\s*=\s*["']?([\w-]+)""", RegexOption.IGNORE_CASE)
            .find(headAscii)?.groupValues?.get(1)?.lowercase()?.let { if (it !in guesses) guesses.add(it) }
        guesses.add("utf-8")
        guesses.add("gbk")
        for (name in guesses) {
            val html = decodeStrict(bytes, name)
            if (html != null) {
                return Jsoup.parse(html, baseUrl)
            }
        }
        return Jsoup.parse(String(bytes, Charsets.UTF_8), baseUrl)
    }

    private fun decodeStrict(bytes: ByteArray, name: String): String? {
        return try {
            val charset = when (name) {
                "gb2312", "gb-2312" -> Charset.forName("GBK")
                else -> Charset.forName(name)
            }
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun guessSiteName(doc: Document, origin: String): String {
        val title = doc.title().trim()
        if (title.isNotBlank()) {
            val parts = title.split(Regex("""[-_–—|丨，,]""")).map { it.trim() }.filter { it.isNotBlank() && it.length in 2..30 }
            val pick = parts.firstOrNull { !it.equals("首页", true) && !it.equals("home", true) }
            if (pick != null) return pick
            if (title.length <= 30) return title
        }
        return runCatching { URI(origin).host.orEmpty().removePrefix("www.").removePrefix("m.") }.getOrDefault(origin)
    }

    private data class FoundSearch(
        val legadoRule: String,
        val method: String,
        val requestUrl: String,
        val bodyTemplate: String? = null
    )

    private fun discoverSearch(inputUrl: String, doc: Document): FoundSearch? {
        // 1) 用户输入的本身可能是带关键词的搜索地址
        fromUserSearchUrl(inputUrl)?.let { return it }

        // 2) 页面里的搜索表单
        for (form in doc.select("form")) {
            val action = if (form.hasAttr("action")) form.absUrl("action") else doc.location()
            if (action.isBlank()) continue
            val inputs = form.select("input[type], input:not([type])")
            val keyInput = inputs.firstOrNull { input ->
                val type = input.attr("type").lowercase()
                val name = input.attr("name").trim()
                (type.isEmpty() || type == "text" || type == "search") &&
                    name.isNotEmpty() && (keywordNames.contains(name.lowercase()) || name.lowercase().contains("key"))
            } ?: inputs.firstOrNull { input ->
                val type = input.attr("type").lowercase()
                val name = input.attr("name").trim()
                type != "hidden" && type != "submit" && type != "button" && type != "image" && name.isNotEmpty()
            } ?: continue
            val keyName = keyInput.attr("name").trim()
            if (keyName.isBlank()) continue

            val hidden = LinkedHashMap<String, String>()
            for (input in inputs) {
                if (input.attr("type").lowercase() != "hidden") continue
                val n = input.attr("name").trim()
                val v = input.attr("value").trim()
                if (n.isEmpty() || v.isEmpty()) continue
                if (n.equals(keyName, true)) continue
                if (n.lowercase().contains("csrf") || n.lowercase().contains("token") || n.lowercase().contains("__")) continue
                if (v.contains("'") || v.contains("{{")) continue
                hidden[n] = v
            }

            val method = form.attr("method").lowercase().ifBlank { "get" }
            if (method == "post") {
                val bodyParts = ArrayList<String>()
                hidden.forEach { (k, v) -> bodyParts.add("$k=${encodeSafeStatic(v)}") }
                bodyParts.add("$keyName={{key}}")
                val body = bodyParts.joinToString("&")
                val actionUrl = replaceOrAppendQuery(action, keyName, forceRemove = true)
                val rule = "$actionUrl,{ 'method': 'POST', 'body': '$body' }"
                return FoundSearch(rule, "POST", actionUrl, body)
            }

            var base = replaceOrAppendQuery(action, keyName)
            hidden.forEach { (k, v) -> base = appendQuery(base, "$k=${encodeSafeStatic(v)}") }
            return FoundSearch(base, "GET", base)
        }

        // 3) 搜索入口链接
        val link = doc.select("a[href]").firstOrNull {
            val text = it.text().lowercase()
            (text.contains("搜索") || text.contains("search")) &&
                !it.absUrl("href").isNullOrBlank() &&
                it.absUrl("href").contains("javascript").not()
        }
        if (link != null) {
            val href = link.absUrl("href")
            val known = detectKnownQueryKey(href)
            if (known != null) {
                val rule = replaceOrAppendQuery(href, known)
                return FoundSearch(rule, "GET", rule)
            }
            val path = runCatching { URI(href).path ?: "" }.getOrDefault("")
            if (path.isNotBlank() && path != "/") {
                val rule = appendQuery(href, "key={{key}}")
                return FoundSearch(rule, "GET", rule)
            }
        }
        return null
    }

    private fun fromUserSearchUrl(input: String): FoundSearch? {
        val uri = runCatching { URI(input) }.getOrNull() ?: return null
        val query = uri.rawQuery ?: return null
        val pairs = query.split("&")
        val idx = pairs.indexOfFirst {
            val name = it.substringBefore('=').lowercase()
            keywordNames.contains(name)
        }
        if (idx < 0) return null
        val updated = pairs.mapIndexed { i, pair ->
            if (i == idx) {
                val name = pair.substringBefore('=')
                if (name.isBlank()) pair else "$name={{key}}"
            } else pair
        }.joinToString("&")
        val base = input.substringBefore('?')
        val rebuilt = "$base?$updated"
        return FoundSearch(rebuilt, "GET", rebuilt)
    }

    private fun detectKnownQueryKey(url: String): String? {
        return runCatching {
            val uri = URI(url)
            val q = uri.rawQuery ?: return null
            q.split("&").firstNotNullOfOrNull {
                val name = it.substringBefore('=').lowercase()
                if (keywordNames.contains(name)) name else null
            }
        }.getOrNull()
    }

    private fun replaceOrAppendQuery(url: String, keyName: String, forceRemove: Boolean = false): String {
        val base = url.substringBefore('?')
        val rawQuery = if (url.contains('?')) url.substringAfter('?') else ""
        val parts = rawQuery.split("&")
            .filter { it.isNotBlank() }
            .filterNot { it.substringBefore('=').equals(keyName, true) }
            .toMutableList()
        if (!forceRemove) {
            parts.add("$keyName={{key}}")
        }
        return if (parts.isEmpty()) base else "$base?${parts.joinToString("&")}"
    }

    private fun appendQuery(url: String, pair: String): String {
        return if (url.contains('?')) {
            if (url.endsWith("?") || url.endsWith("&")) "$url$pair" else "$url&$pair"
        } else {
            "$url?$pair"
        }
    }

    private fun encodeSafeStatic(v: String): String {
        return if (Regex("""^[A-Za-z0-9._%\-]+$""").matches(v)) v else URLEncoder.encode(v, "UTF-8")
    }

    private fun looksLikeNoResult(doc: Document): Boolean {
        val text = doc.text().lowercase()
        return text.contains("没有找到") || text.contains("未搜索到") || text.contains("没有检索到") ||
            text.contains("搜索结果为0") || text.contains("无搜索结果") || text.contains("暂无搜索结果") ||
            text.contains("搜索结果为空") || text.contains("找不到相关") || text.contains("查无此书")
    }

    private data class InferredRule(
        val bookList: String,
        val name: String,
        val bookUrl: String,
        val selfTestCount: Int
    )

    private data class Candidate(val anchor: Element, val text: String, val href: String, val path: String, val score: Int)

    private fun inferRules(doc: Document, origin: String): InferredRule? {
        val baseHost = runCatching { URI(origin).host.orEmpty().lowercase() }.getOrDefault("")
        if (baseHost.isBlank()) return null
        val candidates = collectCandidates(doc, baseHost)
        if (candidates.size < 2) return null

        val boxes = candidates.mapNotNull { c -> itemBox(c, candidates) }
        if (boxes.size < 2) return null

        val boxList = boxes.distinctBy { it.item }
        val bestToken = bestSharedClass(boxList) ?: run {
            parentClassFallback(doc, boxList)
        } ?: return null

        val listRule = bestToken
        val reps = boxList.take(8)
        val rep = reps.firstOrNull { it.item.select("a[href]").isNotEmpty() } ?: return null
        val chain = chainFromItem(rep.item, rep.anchor)
        if (chain.isNullOrBlank()) return null
        val nameRule = "$chain@text"
        val urlRule = "$chain@href"

        val hits = selfTest(doc, listRule, nameRule, urlRule)
        if (hits < 1) return null
        return InferredRule(listRule, nameRule, urlRule, hits)
    }

    private fun collectCandidates(doc: Document, baseHost: String): List<Candidate> {
        val freq = HashMap<String, Int>()
        doc.select("a[href]").forEach { a ->
            val t = a.text().trim()
            if (t.isNotEmpty()) freq[t] = (freq[t] ?: 0) + 1
        }
        val result = ArrayList<Candidate>()
        for (a in doc.select("a[href]")) {
            val text = a.text().trim().replace(Regex("\\s+"), " ")
            if (text.isBlank() || text.length > 36) continue
            if (text in badText || freq[text] ?: 0 >= 12) continue
            val href = a.absUrl("href")
            if (href.isBlank() || href.startsWith("javascript:") || href.contains("#")) continue
            if (!sameSite(href, baseHost)) continue
            val uri = runCatching { URI(href) }.getOrNull() ?: continue
            val path = uri.path ?: ""
            val lowerPath = path.lowercase()
            if (lowerPath.isBlank() || lowerPath == "/") continue
            if (badPathKeywords.any { lowerPath.contains(it) }) continue
            if (text.all { it.isDigit() }) continue

            var score = 0
            if (Regex("""\.(html?|shtml|aspx?|jsp|php)$""").containsMatchIn(lowerPath)) score += 4
            if (Regex("""(?:^|/)\d{4,}""").containsMatchIn(lowerPath)) score += 3
            if (lowerPath.contains("book") || lowerPath.contains("novel") || lowerPath.contains("info") ||
                lowerPath.contains("detail") || lowerPath.contains("xiaoshuo") || lowerPath.contains("txt")
            ) {
                score += 3
            }
            if (lowerPath.count { it == '/' } >= 2) score += 1
            if (a.parents().any { it.tagName() in headingTags }) score += 2
            if (text.length in 2..22) score += 2
            if (freq[text] ?: 0 >= 6) score -= 5
            if (text.lowercase().contains("chapter") || text.lowercase().contains("最新章节")) score -= 2
            if (score >= 4) {
                result.add(Candidate(a, text, href, lowerPath, score))
            }
        }
        result.sortByDescending { it.score }
        return result.take(80)
    }

    private fun sameSite(href: String, baseHost: String): Boolean {
        val host = runCatching { URI(href).host?.lowercase() }.getOrNull() ?: return false
        val norm = { h: String -> h.removePrefix("www.").removePrefix("m.").removePrefix("wap.") }
        return host == baseHost || norm(host) == norm(baseHost)
    }

    private data class ItemBox(val item: Element, val anchor: Element)

    private fun itemBox(c: Candidate, candidates: List<Candidate>): ItemBox? {
        var cur: Element? = c.anchor.parent()
        var chosen: Element? = null
        var steps = 0
        while (cur != null && cur.tagName() !in setOf("body", "html") && steps < 8) {
            val count = candidates.count { cand ->
                cand.anchor.parents().any { p -> p === cur }
            }
            if (count > 1) break
            chosen = cur
            cur = cur.parent()
            steps++
        }
        return chosen?.let { ItemBox(it, c.anchor) }
    }

    private fun bestSharedClass(boxes: List<ItemBox>): String? {
        val counts = HashMap<String, Int>()
        for (box in boxes) {
            for (cls in box.item.classNames()) {
                if (cls.isBlank() || cls.length < 2) continue
                if (Regex("""^[A-Za-z0-9_-]+$""").matches(cls).not()) continue
                counts[cls] = (counts[cls] ?: 0) + 1
            }
        }
        val best = counts.entries
            .filter { it.value >= 2 && it.value * 2 >= boxes.size }
            .maxByOrNull { it.value }
            ?: return null
        return "class.${best.key}"
    }

    private fun parentClassFallback(doc: Document, boxes: List<ItemBox>): String? {
        val firstParent = boxes.first().item.parent()
        if (boxes.all { it.item.parent() === firstParent }) {
            val itemTag = boxes.first().item.tagName()
            val parentToken = firstParent?.classNames()?.firstOrNull { cls ->
                cls.length >= 2 && Regex("""^[A-Za-z0-9_-]+$""").matches(cls)
            }
            val listSelector = if (parentToken != null) {
                "class.$parentToken@tag.$itemTag"
            } else if (firstParent != null && firstParent.tagName() in listParentTags) {
                "tag.${firstParent.tagName()}@tag.$itemTag"
            } else {
                null
            }
            return listSelector?.let { sel ->
                if (selfTest(doc, sel, "tag.a@text", "tag.a@href") >= 1) sel else null
            }
        }
        return null
    }

    private fun chainFromItem(item: Element, anchor: Element): String? {
        val path = ArrayList<Element>()
        var cur: Element? = anchor
        while (cur != null && cur !== item) {
            path.add(cur)
            cur = cur.parent()
        }
        if (cur !== item || path.isEmpty()) return null
        path.reverse()
        val parts = path.map { el ->
            val id = el.id()
            if (id.isNotBlank() && Regex("""^[A-Za-z0-9_-]+$""").matches(id)) {
                "id.$id"
            } else {
                val safeClass = el.classNames().firstOrNull {
                    it.isNotBlank() && Regex("""^[A-Za-z0-9_-]+$""").matches(it)
                }
                if (safeClass != null) "class.$safeClass" else "tag.${el.tagName()}"
            }
        }
        return parts.joinToString("@")
    }

    private fun selfTest(doc: Document, listRule: String, nameRule: String, urlRule: String): Int {
        val items = selectByRule(listOf(doc), listRule)
        if (items.isEmpty()) return 0
        var hit = 0
        for (item in items.take(80)) {
            val nameChain = nameRule.substringBeforeLast("@")
            val urlChain = urlRule.substringBeforeLast("@")
            val names = selectByRule(listOf(item), nameChain)
            val urls = selectByRule(listOf(item), urlChain)
            val name = names.firstOrNull()?.text()?.trim().orEmpty()
            val url = urls.firstOrNull()?.absUrl("href").orEmpty()
            if (name.isNotEmpty() && url.isNotEmpty()) hit++
        }
        return hit
    }

    private fun selectByRule(root: List<Element>, rule: String): List<Element> {
        var nodes = root
        for (part in rule.split("@")) {
            if (part.isBlank()) continue
            val m = Regex("""^(class|id|tag)\.([^@]+?)(?:\.(\d+))?$""").find(part)
            if (m == null) return emptyList()
            val kind = m.groupValues[1]
            val value = m.groupValues[2]
            val index = m.groupValues[3].toIntOrNull()
            val next = nodes.flatMap { n ->
                when (kind) {
                    "class" -> n.select(".$value")
                    "id" -> n.select("#$value")
                    else -> n.select(value)
                }
            }
            nodes = if (index != null && index >= 0) listOfNotNull(next.getOrNull(index)) else next
            if (nodes.isEmpty()) break
        }
        return nodes
    }

    private fun buildSourceJson(
        siteName: String,
        siteUrl: String,
        searchUrl: String,
        rule: InferredRule?,
        comment: String
    ): JsonObject {
        val obj = JsonObject()
        obj.addProperty("bookSourceName", siteName)
        obj.addProperty("bookSourceUrl", siteUrl)
        obj.addProperty("bookSourceType", 0)
        obj.addProperty("bookSourceGroup", "AI初稿-待验证")
        obj.addProperty("bookSourceComment", comment)
        obj.addProperty("enabled", true)
        obj.addProperty("customOrder", 0)
        if (searchUrl.isNotBlank()) {
            obj.addProperty("searchUrl", searchUrl)
        }
        if (rule != null) {
            val search = JsonObject()
            search.addProperty("bookList", rule.bookList)
            search.addProperty("name", rule.name)
            search.addProperty("bookUrl", rule.bookUrl)
            obj.add("ruleSearch", search)
        }
        return obj
    }

    private val badText = setOf(
        "首页", "书库", "书架", "登录", "注册", "排行", "分类", "搜索", "全部", "返回",
        "下一页", "上一页", "阅读", "开始阅读", "目录", "简介", "最新章节", "加入书架",
        "作者", "更新时间", "最新更新", "网站首页", "手机版", "电脑版", "关于我们",
        "意见反馈", "设置", "客户端", "下载", "封面", "投推荐票", "加入书签"
    )

    private val badPathKeywords = listOf(
        "/search", "/so/", "/find", "/query", "/login", "/register", "/reg.php",
        "/user", "/bookcase", "/bookshelf", "/history", "/tag/", "/author/",
        "/sort", "/rank", "/top", "/category", "/fenlei", "/quanbu", "/update",
        "/page", "/help", "/about", "/feedback", "/rss", "/feed", "/api/", "/app"
    )

    private val headingTags = setOf("h1", "h2", "h3", "h4", "h5")
    private val listParentTags = setOf("ul", "ol", "tbody", "table")

    private data class FetchedPage(val status: Int, val url: String, val doc: Document, val bytes: ByteArray)
}

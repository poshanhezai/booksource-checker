package com.shuyuan.helper.net

import com.google.gson.JsonObject
import java.net.URLEncoder
import java.nio.charset.Charset

data class BuiltRequest(
    val url: String,
    val method: String = "GET",
    val body: String? = null,
    val contentType: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val charset: String? = null,
    val supported: Boolean = true,
    val unsupportedReason: String = ""
)

/**
 * 把书源的 searchUrl 规则转成一次可以真实发出的 HTTP 请求。
 * 这里覆盖最常见的 Legado 写法：
 *   URL 里 {{key}} / {{key2}} 替换；
 *   URL,{method/body/charset/header} 附加参数；
 *   单引号 header/options；
 *   POST form 与 JSON body。
 * 含 @js / <js> / webView / 复杂内嵌规则的书源标记为“需人工确认”，不会误判为失效。
 */
object SearchRequestBuilder {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    fun build(source: JsonObject, keywordInput: String): BuiltRequest {
        val sourceUrl = source.string("bookSourceUrl")
        val rule = source.string("searchUrl")
        if (rule.isBlank()) return BuiltRequest("", supported = false, unsupportedReason = "searchUrl 为空")
        val checkKeyword = source.ruleSearchCheckKeyWord()
        val keyword = checkKeyword.ifBlank { keywordInput }
        if (keyword.isBlank()) return BuiltRequest("", supported = false, unsupportedReason = "没有可用的搜索关键词")

        val (ruleUrl, optionText) = UrlOptionParser.splitRule(rule)
        val unsupportedByText = unsupportedMarker(ruleUrl)
        if (unsupportedByText != null) {
            return BuiltRequest(ruleUrl, supported = false, unsupportedReason = unsupportedByText)
        }

        val option = optionText?.let { UrlOptionParser.parse(it) } ?: emptyMap()
        if (option["webView"].toBool() || option["useWebView"].toBool()) {
            return BuiltRequest(ruleUrl, supported = false, unsupportedReason = "需要 WebView 执行")
        }

        val replaced = replaceVariables(ruleUrl, keyword)
        if (replaced.second != null) {
            return BuiltRequest(ruleUrl, supported = false, unsupportedReason = replaced.second!!)
        }
        var url = absoluteUrl(sourceUrl, replaced.first)

        val method = (option["method"] ?: "GET").uppercase()
        val charset = option["charset"]?.ifBlank { null }
        val headerMap = LinkedHashMap<String, String>()
        UrlOptionParser.parse(source.string("header")).forEach { (k, v) -> headerMap[k] = v }
        option["header"]?.let { UrlOptionParser.parse(it).forEach { (k, v) -> headerMap[k] = v } }
        if (headerMap.none { it.key.equals("User-Agent", true) }) {
            headerMap["User-Agent"] = UA
        }
        if (headerMap.none { it.key.equals("Accept", true) }) {
            headerMap["Accept"] = "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8"
        }

        var bodyRaw = option["body"]?.takeIf { it.isNotBlank() }
        val bodyReplaced = bodyRaw?.let { replaceVariables(it, keyword) }
        if (bodyReplaced?.second != null) {
            return BuiltRequest(url, method, supported = false, unsupportedReason = bodyReplaced.second!!)
        }
        bodyRaw = bodyReplaced?.first

        var contentType: String? = headerMap.entries
            .firstOrNull { it.key.equals("Content-Type", true) }?.value
        var body: String? = null
        if (method == "POST") {
            if (!bodyRaw.isNullOrBlank()) {
                val bodyTrim = bodyRaw.trim()
                val looksJson = bodyTrim.startsWith("{") || bodyTrim.startsWith("[")
                val formType = contentType?.contains("form", true) == true
                if (!looksJson || formType) {
                    body = encodeForm(bodyRaw, charset)
                    if (contentType.isNullOrBlank()) {
                        contentType = "application/x-www-form-urlencoded"
                    }
                } else {
                    body = bodyRaw
                    if (contentType.isNullOrBlank()) {
                        contentType = "application/json; charset=utf-8"
                    }
                }
            } else {
                body = ""
                contentType = contentType ?: "application/x-www-form-urlencoded"
            }
        } else {
            url = encodeQuery(url, charset)
        }
        return BuiltRequest(
            url = url,
            method = method,
            body = body,
            contentType = contentType,
            headers = headerMap,
            charset = charset
        )
    }

    private fun replaceVariables(text: String, keyword: String): Pair<String, String?> {
        var out = text
        out = out.replace(Regex("""\{\{\s*(key2|key|searchKey|kw)\s*\|urlencode\s*\}\}""")) {
            URLEncoder.encode(keyword, "UTF-8")
        }
        out = out.replace("{{key2}}", keyword)
        out = out.replace("{{searchKey}}", keyword)
        out = out.replace("{{key}}", keyword)
        out = out.replace("{{page}}", "1")
        out = out.replace("{{page-1}}", "0")
        out = out.replace(Regex("""\{\{\s*page-(\d+)\s*\}\}""")) { m ->
            (1 - (m.groupValues[1].toIntOrNull() ?: 1)).coerceAtLeast(0).toString()
        }
        out = out.replace(Regex("""\{\{\s*page\+(\d+)\s*\}\}""")) { m ->
            (1 + (m.groupValues[1].toIntOrNull() ?: 0)).toString()
        }
        if (out.contains("{{") || out.contains("}}")) {
            return out to "搜索规则含未支持的变量/JS 表达式"
        }
        return out to null
    }

    private fun unsupportedMarker(rule: String): String? {
        return when {
            rule.contains("@js:") -> "搜索规则含 @js: JS 代码"
            rule.contains("<js>") || rule.contains("</js>") -> "搜索规则含 <js> JS 代码"
            rule.contains("java.webView") || rule.contains("webView(") -> "需要 WebView 执行"
            rule.contains("java.") -> "搜索规则调用了 java 接口，暂不执行"
            else -> null
        }
    }

    private fun encodeForm(body: String, charset: String?): String {
        val enc = charset ?: "UTF-8"
        return body.split('&').joinToString("&") { pair ->
            val idx = pair.indexOf('=')
            if (idx < 0) encode(pair, enc)
            else encode(pair.substring(0, idx), enc) + "=" + encode(pair.substring(idx + 1), enc)
        }
    }

    private fun encodeQuery(url: String, charset: String?): String {
        val enc = charset ?: "UTF-8"
        val hashIdx = url.indexOf('#')
        val fragment = if (hashIdx >= 0) url.substring(hashIdx) else ""
        val noFrag = if (hashIdx >= 0) url.substring(0, hashIdx) else url
        val qIdx = noFrag.indexOf('?')
        if (qIdx < 0) return noFrag + fragment
        val path = noFrag.substring(0, qIdx)
        val query = noFrag.substring(qIdx + 1)
        if (query.isBlank()) return noFrag + fragment
        val encoded = query.split('&').joinToString("&") { pair ->
            val idx = pair.indexOf('=')
            if (idx < 0) {
                if (pair.contains('%')) pair else encode(pair, enc)
            } else {
                val k = pair.substring(0, idx)
                val v = pair.substring(idx + 1)
                val ek = if (k.contains('%')) k else encode(k, enc)
                val ev = if (v.contains('%')) v else encode(v, enc)
                "$ek=$ev"
            }
        }
        return path + "?" + encoded + fragment
    }

    private fun encode(s: String, charsetName: String): String {
        val charset = runCatching { Charset.forName(charsetName) }.getOrDefault(Charsets.UTF_8)
        return try {
            URLEncoder.encode(s, charset.name()).replace("+", "%20")
        } catch (e: Exception) {
            s
        }
    }

    private fun absoluteUrl(base: String, url: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        return try {
            java.net.URI(base).resolve(url).toString()
        } catch (e: Exception) {
            url
        }
    }

    private fun JsonObject.string(key: String): String {
        val v = get(key) ?: return ""
        return if (v.isJsonPrimitive) v.asString else ""
    }

    private fun JsonObject.ruleSearchCheckKeyWord(): String {
        val rule = get("ruleSearch")
        if (rule == null || !rule.isJsonObject) return ""
        val v = rule.asJsonObject.get("checkKeyWord")
        return if (v != null && v.isJsonPrimitive) v.asString else ""
    }

    private fun String?.toBool(): Boolean = this == "true" || this == "1" || this == "yes"
}

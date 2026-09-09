package com.shuyuan.helper.data

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI

data class ImportResult(
    val items: List<SourceItem>,
    val skipped: Int,
    val message: String = ""
)

object SourceImporter {
    private val gson = Gson()

    fun parse(rawInput: String): ImportResult {
        val raw = rawInput.trim().removePrefix("\uFEFF")
        if (raw.isEmpty()) return ImportResult(emptyList(), 0, "内容为空")
        val root: JsonArray = when {
            raw.startsWith("[") -> {
                val parsed = JsonParser.parseString(raw)
                if (parsed.isJsonArray) parsed.asJsonArray
                else return ImportResult(emptyList(), 0, "不是书源 JSON 数组")
            }

            raw.startsWith("{") -> {
                val parsed = JsonParser.parseString(raw)
                if (parsed.isJsonObject) {
                    val arr = JsonArray()
                    arr.add(parsed)
                    arr
                } else return ImportResult(emptyList(), 0, "不是书源 JSON 对象")
            }

            else -> {
                // 兼容每条独立一行、外面没有 [] 的文本
                val arr = JsonArray()
                for (line in raw.lineSequence()) {
                    val t = line.trim()
                    if (t.isEmpty()) continue
                    if (t.startsWith("{") && t.endsWith("}")) {
                        runCatching { JsonParser.parseString(t) }.getOrNull()?.let { arr.add(it) }
                    }
                }
                if (arr.size() == 0) return ImportResult(emptyList(), 0, "无法识别书源内容")
                arr
            }
        }

        val items = ArrayList<SourceItem>(root.size())
        var skipped = 0
        for (i in 0 until root.size()) {
            val el = root[i]
            if (!el.isJsonObject) {
                skipped++
                continue
            }
            val obj = el.asJsonObject
            val name = stringOf(obj, "bookSourceName", "name").ifBlank { "未命名书源" }
            val url = stringOf(obj, "bookSourceUrl", "url").trim()
            if (url.isEmpty()) {
                skipped++
                continue
            }
            val host = extractHost(url)
            if (host == null) {
                skipped++
                continue
            }
            val type = runCatching { obj.get("bookSourceType").asInt }.getOrDefault(0)
            items.add(SourceItem(json = obj, name = name, url = url, host = host, type = type))
        }
        val message = if (items.isEmpty()) "没有解析出可检测的书源" else ""
        return ImportResult(items, skipped, message)
    }

    private fun stringOf(obj: JsonObject, vararg keys: String): String {
        for (key in keys) {
            val v = obj.get(key)
            if (v != null && v.isJsonPrimitive) return v.asString
        }
        return ""
    }

    private fun extractHost(rawUrl: String): String? {
        if (rawUrl.any { it.code in 0..31 || it == '，' || it == '♦' || it == ' ' || it == '\t' }) return null
        val url = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) rawUrl else "http://$rawUrl"
        return runCatching {
            val host = URI(url).host ?: return@runCatching null
            if (host.isBlank() || !host.contains('.')) null else host.lowercase()
        }.getOrNull()
    }

    /** 将导入结果保存为原始 JSON 文本，供前台服务重新读取 */
    fun toRawText(items: List<SourceItem>): String {
        val arr = JsonArray()
        items.forEach { arr.add(it.json) }
        return gson.toJson(arr)
    }
}

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
            val group = detectGroup(obj, name, url, type)
            items.add(SourceItem(json = obj, name = name, url = url, host = host, type = type, group = group))
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

    /**
     * 自动判断书源分组。规则优先级：成人 > 音频 > 漫画 > 小说/其他。
     * 优先看 bookSourceGroup / 名称里的关键词，再按 Legado 类型兜底。
     */
    private fun detectGroup(obj: JsonObject, name: String, url: String, type: Int): SourceGroup {
        val hay = buildString {
            append(stringOf(obj, "bookSourceGroup", "group")).append('\n')
            append(name).append('\n')
            append(url).append('\n')
            append(stringOf(obj, "bookSourceComment"))
        }.lowercase()

        if (audioKeys.any { hay.contains(it) } || type == 1) return SourceGroup.AUDIO
        if (adultKeys.any { hay.contains(it) }) return SourceGroup.ADULT
        if (comicKeys.any { hay.contains(it) }) return SourceGroup.COMIC
        return when (type) {
            1 -> SourceGroup.AUDIO
            2 -> SourceGroup.COMIC
            3, 4 -> SourceGroup.OTHER
            else -> SourceGroup.NOVEL
        }
    }

    private val audioKeys = listOf(
        "听书", "听小说", "有声", "音频", "广播剧", "audiobook"
    )

    private val adultKeys = listOf(
        "成人", "色情", "小黄文", "黄文", "肉文", "h漫", "里番", "18x",
        "18禁", "r18", "r-18", "porn", "adult", "成人小说", "成人漫画", "黄书"
    )

    private val comicKeys = listOf(
        "漫画", "动漫", "manga", "manhua", "comic", "comics", "看漫画",
        "漫画屋", "dm5", "绅士", "本子"
    )

    /** 将导入结果保存为原始 JSON 文本，供前台服务重新读取 */
    fun toRawText(items: List<SourceItem>): String {
        val arr = JsonArray()
        items.forEach { arr.add(it.json) }
        return gson.toJson(arr)
    }
}

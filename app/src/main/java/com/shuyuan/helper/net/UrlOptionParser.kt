package com.shuyuan.helper.net

/**
 * 兼容 Legado 常见的 url,{option} 附加参数写法。Legado 允许单引号、未加引号的键名，
 * 这里做一个宽松的小型解析器，不依赖严格 JSON。
 */
object UrlOptionParser {

    /**
     * 把 "URL,{...options...}" 拆成 URL 与 options 原文。
     */
    fun splitRule(rule: String): Pair<String, String?> {
        var depth = 0
        var quote: Char? = null
        var escaped = false
        for (i in rule.indices) {
            val c = rule[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (quote != null) {
                if (c == '\\') escaped = true
                else if (c == quote) quote = null
                continue
            }
            when (c) {
                '\'', '"' -> quote = c
                '{', '[', '(' -> depth++
                '}', ']', ')' -> if (depth > 0) depth--
                ',' -> if (depth == 0) {
                    var j = i + 1
                    while (j < rule.length && (rule[j] == ' ' || rule[j] == '\t' || rule[j] == '\r' || rule[j] == '\n')) j++
                    if (j < rule.length && rule[j] == '{') {
                        return rule.substring(0, i).trim() to rule.substring(j)
                    }
                }
            }
        }
        return rule.trim() to null
    }

    /**
     * 解析 options 文本或 header 文本为 key/value 字符串表。
     */
    fun parse(text: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        var s = text.trim()
        if (s.startsWith("{") && s.endsWith("}")) s = s.substring(1, s.length - 1)
        if (s.isEmpty()) return map

        var i = 0
        while (i < s.length) {
            while (i < s.length && (s[i].isWhitespace() || s[i] == ',')) i++
            if (i >= s.length) break
            val keyStart = i
            var quote: Char? = null
            while (i < s.length) {
                val c = s[i]
                if (quote != null) {
                    if (c == '\\') i++
                    else if (c == quote) quote = null
                } else if (c == '\'' || c == '"') {
                    quote = c
                } else if (c == ':' || c == '=' || c == ',') {
                    break
                }
                i++
            }
            var key = s.substring(keyStart, i).trim().trim('"', '\'')
            while (i < s.length && (s[i].isWhitespace() || s[i] == ':' || s[i] == '=')) i++
            val value = readValue(s, i)
            if (key.isNotEmpty()) {
                // 去掉多余前导空白后 key 可能含引号
                key = key.trim('"', '\'')
                map[key] = value.first
            }
            i = value.second
        }
        return map
    }

    private fun readValue(s: String, start: Int): Pair<String, Int> {
        var i = start
        while (i < s.length && s[i].isWhitespace()) i++
        if (i >= s.length) return "" to i
        val c0 = s[i]
        if (c0 == '\'' || c0 == '"') {
            val quote = c0
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        else -> sb.append(s[i + 1])
                    }
                    i += 2
                    continue
                }
                if (c == quote) {
                    i++
                    return sb.toString() to skipTail(s, i)
                }
                sb.append(c)
                i++
            }
            return sb.toString() to i
        }
        if (c0 == '{' || c0 == '[' || c0 == '(') {
            val close = when (c0) {
                '{' -> '}'
                '[' -> ']'
                else -> ')'
            }
            var depth = 0
            var q: Char? = null
            while (i < s.length) {
                val c = s[i]
                if (q != null) {
                    if (c == '\\') i++
                    else if (c == q) q = null
                } else {
                    when (c) {
                        '\'', '"' -> q = c
                        c0 -> depth++
                        close -> depth--
                    }
                }
                i++
                if (depth == 0) break
            }
            return s.substring(start, i) to skipTail(s, i)
        }
        // 数字 / true / false / null / 裸字符串
        val sb = StringBuilder()
        var q: Char? = null
        while (i < s.length) {
            val c = s[i]
            if (q != null) {
                if (c == '\\') {
                    i++
                    if (i < s.length) sb.append(s[i])
                } else if (c == q) q = null
                else sb.append(c)
                i++
                continue
            }
            if (c == ',' || c == '}') break
            if (c == '\'' || c == '"') {
                q = c
            } else if (c.isWhitespace() && s.substring(start, i).isNotBlank()) {
                // 裸文本一般到空白结束
                val tail = i
                return sb.toString() to tail
            } else {
                sb.append(c)
            }
            i++
        }
        return sb.toString().trim() to i
    }

    private fun skipTail(s: String, from: Int): Int {
        var i = from
        while (i < s.length && (s[i] == ',' || s[i].isWhitespace())) i++
        return i
    }
}

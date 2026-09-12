package com.shuyuan.helper.net

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class RuleUnsupportedException(message: String) : Exception(message)

sealed class RuleNode {
    data class Html(val element: Element) : RuleNode()
    data class Json(val element: JsonElement) : RuleNode()
}

/**
 * 自主实现的 Legado 规则解析器（只借鉴规则语法，不复制阅读源码）。
 * 支持：
 * - HTML：class./id./tag./text. 链式规则、CSS、XPath
 * - JSON：$. 路径、[*]、[n]
 * - 末尾 text/href/src/html/outerHtml
 * - `##正则##替换##`
 * 含 @js: / <js> 的规则会抛出 RuleUnsupportedException，由上层提示。
 */
object RuleEngine {

    fun parseDocument(text: String, baseUrl: String): Any {
        val trimmed = text.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            runCatching { JsonParser.parseString(trimmed) }.getOrNull()?.let { return it }
        }
        return Jsoup.parse(text, baseUrl)
    }

    fun listNodes(root: Any, ruleRaw: String): List<RuleNode> {
        val (core, _) = splitReplace(clean(ruleRaw))
        ensureSupported(core)
        if (core.isBlank()) throw RuleUnsupportedException("规则为空")
        return when (root) {
            is JsonElement -> jsonNodes(root, core)
            is Document -> htmlNodes(root, core)
            is Element -> htmlNodes(root, core)
            else -> emptyList()
        }
    }

    fun text(node: RuleNode, ruleRaw: String): String {
        val (core, rep) = splitReplace(clean(ruleRaw))
        ensureSupported(core)
        val raw = when (node) {
            is RuleNode.Json -> jsonText(node.element, core)
            is RuleNode.Html -> htmlText(node.element, core)
        }
        return applyReplace(raw, rep).trim()
    }

    fun html(node: RuleNode, ruleRaw: String): String {
        val (core, rep) = splitReplace(clean(ruleRaw))
        ensureSupported(core)
        val raw = when (node) {
            is RuleNode.Json -> jsonText(node.element, core)
            is RuleNode.Html -> {
                if (core.isBlank() || core == "html" || core == "@html") {
                    node.element.html()
                } else {
                    htmlNodes(node.element, core).firstOrNull()?.let {
                        (it as? RuleNode.Html)?.element?.html().orEmpty()
                    }.orEmpty()
                }
            }
        }
        return applyReplace(raw, rep)
    }

    fun firstText(nodes: List<RuleNode>, rule: String): String =
        nodes.firstOrNull()?.let { text(it, rule) }.orEmpty()

    /** 从整份 HTML/JSON 文档里直接取一个字段 */
    fun extract(root: Any, ruleRaw: String): String {
        val (core, rep) = splitReplace(clean(ruleRaw))
        ensureSupported(core)
        val raw = when (root) {
            is JsonElement -> jsonText(root, core)
            is Document -> htmlText(root, core)
            is Element -> htmlText(root, core)
            else -> ""
        }
        return applyReplace(raw, rep).trim()
    }

    private fun ensureSupported(rule: String) {
        if (rule.contains("@js:") || rule.contains("<js>") || rule.contains("</js>")) {
            throw RuleUnsupportedException("该书源规则含 JavaScript，当前版本暂不支持")
        }
    }

    private fun clean(raw: String): String {
        var s = raw.trim()
        while (s.startsWith("@@")) s = s.removePrefix("@@").trim()
        return s
    }

    private fun splitReplace(raw: String): Pair<String, List<Pair<String, String>>> {
        val parts = raw.split("##")
        if (parts.size < 2) return raw to emptyList()
        val core = parts[0]
        val reps = ArrayList<Pair<String, String>>()
        var i = 1
        while (i < parts.size) {
            val pattern = parts[i]
            val replacement = if (i + 1 < parts.size) parts[i + 1] else ""
            reps.add(pattern to replacement)
            i += 2
        }
        return core to reps
    }

    private fun applyReplace(raw: String, reps: List<Pair<String, String>>): String {
        var out = raw
        for ((pattern, replacement) in reps) {
            if (pattern.isBlank()) continue
            out = runCatching { Regex(pattern).replace(out, replacement) }.getOrDefault(out)
        }
        return out
    }

    // ---------------- HTML ----------------

    private fun htmlNodes(root: Element, core: String): List<RuleNode> {
        val rule = core.trim()
        if (rule.startsWith("@css:")) {
            return root.select(rule.removePrefix("@css:").trim()).map { RuleNode.Html(it) }
        }
        if (rule.startsWith(".")) {
            return root.select(rule).map { RuleNode.Html(it) }
        }
        if (rule.startsWith("//") || rule.startsWith("/html") || rule.startsWith("@XPath:")) {
            return xpathList(root, rule).map { RuleNode.Html(it) }
        }
        var nodes = listOf(root)
        for (stepRaw in rule.split("@")) {
            val step = stepRaw.trim()
            if (step.isBlank() || isFinalStep(step)) break
            nodes = nodes.flatMap { applyElementStep(it, step) }
            if (nodes.isEmpty()) break
        }
        return nodes.map { RuleNode.Html(it) }
    }

    private fun applyElementStep(element: Element, step: String): List<Element> {
        val m = Regex("""^(class|id|tag|text)\.(.+?)(?:\.(\d+))?$""").find(step)
        if (m == null) {
            val tag = step.removeSuffix(".0")
            return runCatching { element.select(tag) }.getOrDefault(emptyList())
        }
        val kind = m.groupValues[1]
        val value = m.groupValues[2]
        val index = m.groupValues[3].toIntOrNull()
        val found = when (kind) {
            "class" -> element.select(".$value")
            "id" -> element.select("#$value")
            "tag" -> element.select(value)
            "text" -> element.allElements.toList().filter { el ->
                (element === el || el.parents().toList().any { p -> p === element }) &&
                    (el.text().trim() == value || el.ownText().trim() == value)
            }
            else -> emptyList()
        }
        return if (index != null && index in found.indices) listOf(found[index]) else found
    }

    private fun htmlText(element: Element, core: String): String {
        val rule = core.trim()
        if (rule.isBlank() || rule == "text" || rule == "@text" || rule == "ownText") {
            return if (rule == "ownText") element.ownText() else element.text()
        }
        if (rule == "html" || rule == "@html") return element.html()
        if (rule == "outerHtml") return element.outerHtml()
        if (rule == "href" || rule == "@href") return attr(element, "href")
        if (rule == "src" || rule == "@src") return attr(element, "src")
        if (rule.startsWith("@css:")) {
            return element.select(rule.removePrefix("@css:").trim()).firstOrNull()?.text().orEmpty()
        }
        if (rule.startsWith(".")) {
            return element.select(rule).firstOrNull()?.text().orEmpty()
        }
        if (rule.startsWith("//") || rule.startsWith("/html") || rule.startsWith("@XPath:")) {
            return xpathText(element, rule)
        }
        val steps = rule.split("@").map { it.trim() }.filter { it.isNotBlank() }
        if (steps.isEmpty()) return element.text()
        val last = steps.last()
        val elementSteps = if (isFinalStep(last)) steps.dropLast(1) else steps
        var nodes = listOf(element)
        for (step in elementSteps) {
            nodes = nodes.flatMap { applyElementStep(it, step) }
            if (nodes.isEmpty()) break
        }
        val node = nodes.firstOrNull() ?: return ""
        return when (last) {
            "text", "@text" -> node.text()
            "ownText" -> node.ownText()
            "href", "@href" -> attr(node, "href")
            "src", "@src" -> attr(node, "src")
            "html", "@html" -> node.html()
            "outerHtml" -> node.outerHtml()
            else -> node.text()
        }
    }

    private fun attr(element: Element, name: String): String {
        val abs = element.absUrl(name)
        return abs.ifBlank { element.attr(name) }
    }

    private fun isFinalStep(step: String): Boolean = when (step) {
        "text", "@text", "ownText", "href", "@href", "src", "@src",
        "html", "@html", "outerHtml" -> true
        else -> false
    }

    private fun xpathList(root: Element, rule: String): List<Element> {
        val xpath = rule.removePrefix("@XPath:").trim()
        val clean = xpath
            .replace(Regex("""/text\(\)$"""), "")
            .replace(Regex("""/@[\w-]+$"""), "")
        return runCatching { root.selectXpath(clean) }.getOrDefault(emptyList())
    }

    private fun xpathText(root: Element, rule: String): String {
        val xpath = rule.removePrefix("@XPath:").trim()
        val attr = Regex("""/@([\w-]+)$""").find(xpath)?.groupValues?.get(1)
        val clean = xpath
            .replace(Regex("""/text\(\)$"""), "")
            .replace(Regex("""/@[\w-]+$"""), "")
            .replace(Regex("""^//"""), ".//")
        val nodes = runCatching { root.selectXpath(clean) }.getOrDefault(emptyList())
        val first = nodes.firstOrNull() ?: return ""
        return if (attr != null) attr(first, attr) else first.text()
    }

    // ---------------- JSON ----------------

    private fun jsonNodes(root: JsonElement, rule: String): List<RuleNode> {
        val path = rule.removePrefix("@Json:").trim()
        if (!path.startsWith("$")) {
            return emptyList()
        }
        return jsonQuery(root, path).map { RuleNode.Json(it) }
    }

    private fun jsonText(root: JsonElement, rule: String): String {
        val path = rule.removePrefix("@Json:").trim()
        if (!path.startsWith("$")) return ""
        val node = jsonQuery(root, path).firstOrNull() ?: return ""
        return if (node is JsonPrimitive) node.asString else node.toString()
    }

    private fun jsonQuery(root: JsonElement, path: String): List<JsonElement> {
        var p = path.removePrefix("$")
        if (p.startsWith(".")) p = p.removePrefix(".")
        if (p.isBlank()) return listOf(root)
        val tokens = Regex("""[^.\[]+|\[[^\]]+\]""").findAll(p).map { it.value }.toList()
        var current = listOf(root)
        for (token in tokens) {
            current = current.flatMap { applyJsonStep(it, token) }
            if (current.isEmpty()) break
        }
        return current
    }

    private fun applyJsonStep(element: JsonElement, token: String): List<JsonElement> {
        if (token == "[*]") {
            return if (element is JsonArray) element.toList() else emptyList()
        }
        if (token.startsWith("[") && token.endsWith("]")) {
            val idx = token.removePrefix("[").removeSuffix("]").toIntOrNull() ?: return emptyList()
            if (element is JsonArray && idx in 0 until element.size()) return listOf(element[idx])
            return emptyList()
        }
        return when (element) {
            is JsonObject -> {
                val child = element.get(token)
                if (child == null || child.isJsonNull) emptyList() else listOf(child)
            }
            is JsonArray -> element.flatMap { applyJsonStep(it, token) }
            else -> emptyList()
        }
    }
}

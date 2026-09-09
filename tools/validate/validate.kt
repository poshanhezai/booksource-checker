import com.shuyuan.helper.data.SourceImporter
import com.shuyuan.helper.net.SearchRequestBuilder
import java.io.File

fun main() {
    val file = File("D:/aichengguo/yudu/1567个书源.json")
    val text = file.readText()
    val parsed = SourceImporter.parse(text)
    println("total=${parsed.items.size} skipped=${parsed.skipped} message=${parsed.message}")
    var supported = 0
    var unsupported = 0
    var get = 0
    var post = 0
    var noKey = 0
    val unsupportedReasons = linkedMapOf<String, Int>()
    val samples = mutableListOf<String>()
    val seenHosts = mutableMapOf<String, Int>()
    parsed.items.forEach { item ->
        val host = item.host
        seenHosts[host] = (seenHosts[host] ?: 0) + 1
        val built = SearchRequestBuilder.build(item.json, "我")
        if (built.supported) {
            supported++
            if (built.method.equals("POST", true)) post++ else get++
            if (samples.size < 6) {
                samples.add("${item.name} :: ${built.method} :: ${built.url} :: body=${built.body} :: ct=${built.contentType}")
            }
        } else {
            unsupported++
            unsupportedReasons[built.unsupportedReason] = (unsupportedReasons[built.unsupportedReason] ?: 0) + 1
        }
    }
    println("supported=$supported unsupported=$unsupported get=$get post=$post")
    println("uniqueHosts=${seenHosts.size}")
    println("topHosts=${seenHosts.entries.sortedByDescending { it.value }.take(5)}")
    println("unsupportedReasons=$unsupportedReasons")
    println("--- samples ---")
    samples.forEach { println(it) }
}

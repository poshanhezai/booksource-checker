package com.shuyuan.helper.net

/**
 * 主动内容识别：不只看书源名称/分组文字，而是分析真实抓到的
 * 站点主页或搜索返回内容里出现的成人特征词，再决定是否归入「成人」。
 */
object AdultContentSniffer {

    private val markers = listOf(
        "成人小说", "成人漫画", "成人文学", "成人内容", "成人网站", "成人视频",
        "色情小说", "色情漫画", "色情文学", "色情网站", "情色文学", "情色小说",
        "黄色小说", "小黄文", "黄文", "肉文", "np文", "h漫", "里番", "本子",
        "无码", "有码", "porn", "r18", "18禁", "十八禁", "av资源", "av在线",
        "未成年禁止", "未满18", "成人限定", "成人专区", "成人阅读"
    )

    /** 返回命中的特征词（最多 3 个）；没命中返回空列表。 */
    fun hits(rawSample: String): List<String> {
        if (rawSample.isBlank()) return emptyList()
        val cleaned = rawSample
            .replace(Regex("<[^>]*>"), " ")
            .replace(Regex("&[a-zA-Z#0-9]+;"), " ")
            .replace(Regex("\\s+"), " ")
            .lowercase()
        return markers.filter { cleaned.contains(it) }.take(3)
    }
}

package com.shuyuan.helper.data

import com.google.gson.JsonObject

enum class SourceGroup(val label: String) {
    NOVEL("小说"),
    COMIC("漫画"),
    ADULT("成人"),
    AUDIO("音频"),
    OTHER("其他");

    companion object {
        fun ofLabel(label: String): SourceGroup? =
            entries.firstOrNull { it.label == label }
    }
}

enum class SourceState(val label: String) {
    PENDING("等待检测"),
    CHECKING("检测中"),
    OK("可用"),
    UNCERTAIN("疑似可用"),
    DEAD("失效");

    companion object {
        fun of(state: String): SourceState = entries.firstOrNull { it.name == state } ?: PENDING
    }
}

/**
 * 单条书源。json 保存导入时的原始对象，导出时原样写回，保证与阅读 App 完全兼容。
 */
data class SourceItem(
    val json: JsonObject,
    val name: String,
    val url: String,
    val host: String,
    val type: Int = 0,
    val state: SourceState = SourceState.PENDING,
    val detail: String = "尚未检测",
    val httpCode: Int = 0,
    val elapsedMs: Long = -1L,
    val searchTried: Boolean = false,
    val group: SourceGroup = SourceGroup.NOVEL
) {
    val checked: Boolean
        get() = state != SourceState.PENDING && state != SourceState.CHECKING
}

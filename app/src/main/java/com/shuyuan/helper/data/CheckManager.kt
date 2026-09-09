package com.shuyuan.helper.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 前后台共享的检测状态。检测放在前台服务中执行，界面只观察这个单例。
 */
object CheckManager {
    private val _items = MutableStateFlow<List<SourceItem>>(emptyList())
    val items: StateFlow<List<SourceItem>> = _items.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    private val _progress = MutableStateFlow(0)
    val progress: StateFlow<Int> = _progress.asStateFlow()

    private val _progressText = MutableStateFlow("")
    val progressText: StateFlow<String> = _progressText.asStateFlow()

    private val _finishedText = MutableStateFlow("")
    val finishedText: StateFlow<String> = _finishedText.asStateFlow()

    /** 最近一次导入的原始 JSON 文件路径，前台服务据此开始检测 */
    var importFile: String? = null

    fun replaceAll(items: List<SourceItem>, progressText: String) {
        _items.value = items
        _progress.value = 0
        _progressText.value = progressText
        _finishedText.value = ""
    }

    fun updateItem(index: Int, item: SourceItem) {
        val cur = _items.value
        if (index < 0 || index >= cur.size) return
        val next = cur.toMutableList()
        next[index] = item
        _items.value = next
    }

    fun updateProgress(done: Int, total: Int, runningText: String, finishText: String) {
        _progress.value = if (total <= 0) 0 else (done * 100 / total)
        _progressText.value = runningText
        _finishedText.value = finishText
    }

    fun setRunning(running: Boolean) {
        _running.value = running
        if (!running) {
            _progressText.value = ""
            _paused.value = false
        }
    }

    fun setPaused(paused: Boolean) {
        _paused.value = paused
    }

    fun clear() {
        _items.value = emptyList()
        _running.value = false
        _paused.value = false
        _progress.value = 0
        _progressText.value = ""
        _finishedText.value = ""
        importFile = null
    }
}

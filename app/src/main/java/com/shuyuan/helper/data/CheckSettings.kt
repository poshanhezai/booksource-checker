package com.shuyuan.helper.data

enum class CheckMode(val label: String) {
    QUICK("快速检测"),
    STANDARD("标准检测")
}

data class CheckSettings(
    val mode: CheckMode = CheckMode.STANDARD,
    val timeoutSec: Long = 12L,
    val concurrency: Int = 12,
    val keyword: String = "我",
    val maxBodyBytes: Int = 512 * 1024,
    val adultInspect: Boolean = true
)

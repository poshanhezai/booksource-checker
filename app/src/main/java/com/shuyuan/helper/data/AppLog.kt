package com.shuyuan.helper.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轻量本地日志：记录导入 / 检测 / 生成 / 导出等关键动作，
 * 日志保存在 App 内部，可随时在「日志」页导出到手机任意位置。
 */
object AppLog {

    private const val MAX_LINES = 2000
    private val lock = Any()

    fun file(context: Context): File =
        File(context.filesDir, "logs/helper.log")

    fun append(context: Context, tag: String, message: String) {
        synchronized(lock) {
            runCatching {
                val f = file(context)
                f.parentFile?.mkdirs()
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val line = "[$stamp] [$tag] $message"
                val old = if (f.exists()) f.readText().trimEnd() else ""
                val lines = (if (old.isBlank()) listOf(line) else old.lines() + line)
                    .takeLast(MAX_LINES)
                f.writeText(lines.joinToString("\n") + "\n")
            }
        }
    }

    fun read(context: Context): String {
        return synchronized(lock) {
            runCatching { file(context).readText() }.getOrDefault("")
        }
    }

    fun clear(context: Context) {
        synchronized(lock) {
            runCatching { file(context).writeText("") }
        }
    }

    object Tag {
        const val IMPORT = "导入"
        const val CHECK = "检测"
        const val GENERATE = "生成"
        const val EXPORT = "导出"
        const val APP = "应用"
    }
}

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

    private const val MAX_LINES = 5000
    private val lock = Any()
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        val ctx = appContext ?: return
        val version = runCatching {
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            val code = if (android.os.Build.VERSION.SDK_INT >= 28) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            "${info.versionName} ($code)"
        }.getOrDefault("unknown")
        append(
            ctx,
            Tag.APP,
            "INFO",
            "启动 ${ctx.packageName} v$version | " +
                "设备=${android.os.Build.MANUFACTURER}/${android.os.Build.BRAND}/${android.os.Build.MODEL} | " +
                "Android=${android.os.Build.VERSION.RELEASE} API=${android.os.Build.VERSION.SDK_INT} | " +
                "ABI=${android.os.Build.SUPPORTED_ABIS.joinToString(",")}",
            null
        )
    }

    fun file(context: Context): File =
        File(context.filesDir, "logs/helper.log")

    fun append(context: Context, tag: String, message: String) {
        append(context, tag, "INFO", message, null)
    }

    fun append(tag: String, message: String) {
        append(appContext ?: return, tag, "INFO", message, null)
    }

    fun append(tag: String, message: String, throwable: Throwable?) {
        append(appContext ?: return, tag, "ERROR", message, throwable)
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        append(appContext ?: return, tag, "ERROR", message, throwable)
    }

    fun warn(tag: String, message: String) {
        append(appContext ?: return, tag, "WARN", message, null)
    }

    fun append(context: Context, tag: String, level: String, message: String, throwable: Throwable?) {
        synchronized(lock) {
            runCatching {
                val f = file(context)
                f.parentFile?.mkdirs()
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val head = "[$stamp] [$level] [$tag] $message"
                val line = if (throwable == null) {
                    head
                } else {
                    val sw = java.io.StringWriter()
                    throwable.printStackTrace(java.io.PrintWriter(sw))
                    head + "\n" + sw.toString().trimEnd()
                }
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
        const val COMPAT = "兼容"
    }
}

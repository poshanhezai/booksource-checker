package com.shuyuan.helper.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.shuyuan.helper.data.SourceItem
import com.shuyuan.helper.data.SourceState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SourceExporter {

    /** 按原始 JSON 原样打包，保证可被 Legado 直接导入 */
    fun buildJson(items: List<SourceItem>, includeUncertain: Boolean, onlyDead: Boolean = false): String {
        val arr = JsonArray()
        items.forEach {
            val keep = when {
                onlyDead -> it.state == SourceState.DEAD
                includeUncertain -> it.state == SourceState.OK || it.state == SourceState.UNCERTAIN
                else -> it.state == SourceState.OK
            }
            if (keep) arr.add(it.json)
        }
        return Gson().toJson(arr)
    }

    /** 选中导出：不按状态过滤，原样打包所选书源，保持 Legado 兼容 */
    fun buildRawJson(items: List<SourceItem>): String {
        val arr = JsonArray()
        items.forEach { arr.add(it.json) }
        return Gson().toJson(arr)
    }

    fun share(context: Context, json: String, label: String): Boolean {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(dir, "${label}_$stamp.json")
        file.writeText(json)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "导出书源 JSON")
        context.startActivity(chooser)
        return true
    }
}

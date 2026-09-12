package com.shuyuan.helper.net

import com.shuyuan.helper.data.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit

data class ImportFetchResult(
    val ok: Boolean,
    val message: String,
    val text: String = ""
)

/** 网络导入：从 URL 下载书源 JSON 文本 */
object ImportFetcher {

    private const val MAX_BYTES = 32L * 1024 * 1024

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    suspend fun fetch(urlInput: String): ImportFetchResult = withContext(Dispatchers.IO) {
        var url = urlInput.trim()
        if (url.isBlank()) return@withContext ImportFetchResult(false, "网址为空")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", ProbeEngine.UA)
            .header("Accept", "application/json,text/plain,*/*")
            .get()
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                if (resp.code !in 200..299) {
                    AppLog.warn(AppLog.Tag.IMPORT, "网络导入失败：HTTP ${resp.code} $url")
                    return@withContext ImportFetchResult(false, "下载失败：HTTP ${resp.code}")
                }
                val length = resp.body?.contentLength() ?: -1L
                if (length > MAX_BYTES) {
                    AppLog.warn(AppLog.Tag.IMPORT, "网络导入失败：文件过大 size=$length url=$url")
                    return@withContext ImportFetchResult(false, "文件过大：${length / 1024 / 1024}MB，最大支持 32MB")
                }
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                if (bytes.size > MAX_BYTES) {
                    return@withContext ImportFetchResult(false, "文件过大：${bytes.size / 1024 / 1024}MB，最大支持 32MB")
                }
                val text = decode(bytes, resp.header("Content-Type"))
                val trimmed = text.trim().removePrefix("\uFEFF")
                if (!trimmed.startsWith("[") && !trimmed.startsWith("{")) {
                    AppLog.warn(AppLog.Tag.IMPORT, "网络导入内容不是书源 JSON：$url")
                    return@withContext ImportFetchResult(false, "下载成功，但内容不是书源 JSON")
                }
                AppLog.append(AppLog.Tag.IMPORT, "网络导入下载成功：$url，${bytes.size} 字节")
                ImportFetchResult(true, "下载成功", trimmed)
            }
        } catch (e: Exception) {
            AppLog.error(AppLog.Tag.IMPORT, "网络导入异常：$url", e)
            ImportFetchResult(false, "下载异常：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun decode(bytes: ByteArray, contentType: String?): String {
        val guesses = ArrayList<String>()
        contentType?.let {
            Regex("""charset\s*=\s*["']?([\w-]+)""", RegexOption.IGNORE_CASE)
                .find(it)?.groupValues?.get(1)?.lowercase()?.let { cs -> guesses.add(cs) }
        }
        val head = String(bytes, 0, minOf(2048, bytes.size), Charsets.ISO_8859_1)
        Regex("""charset\s*=\s*["']?([\w-]+)""", RegexOption.IGNORE_CASE)
            .find(head)?.groupValues?.get(1)?.lowercase()?.let { if (it !in guesses) guesses.add(it) }
        guesses.add("utf-8")
        guesses.add("gbk")
        for (name in guesses) {
            val text = decodeStrict(bytes, name)
            if (text != null) return text
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun decodeStrict(bytes: ByteArray, name: String): String? = try {
        val charset = when (name) {
            "gb2312", "gb-2312" -> Charset.forName("GBK")
            else -> Charset.forName(name)
        }
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: Exception) {
        null
    }
}

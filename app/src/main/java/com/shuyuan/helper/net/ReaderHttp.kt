package com.shuyuan.helper.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit

data class ReaderResponse(
    val code: Int,
    val finalUrl: String,
    val text: String,
    val root: Any,
    val contentType: String?
)

class ReaderHttpException(message: String) : Exception(message)

/** 阅读器统一网络层：执行书源请求并解析成 HTML/Jsoup 或 JSON/Gson 文档 */
object ReaderHttp {

    private const val MAX_BYTES = 4 * 1024 * 1024

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    suspend fun execute(built: BuiltRequest): ReaderResponse = withContext(Dispatchers.IO) {
        if (!built.supported) throw ReaderHttpException(built.unsupportedReason.ifBlank { "规则不支持" })
        val builder = Request.Builder().url(built.url)
        built.headers.forEach { (k, v) ->
            if (!k.equals("Host", true) && !k.equals("Content-Length", true)) builder.header(k, v)
        }
        if (built.headers.none { it.key.equals("User-Agent", true) }) {
            builder.header("User-Agent", ProbeEngine.UA)
        }
        if (built.method.equals("POST", true)) {
            val bytes = (built.body ?: "").toByteArray(ProbeEngine.charsetOf(built.charset))
            val type = built.contentType ?: "application/x-www-form-urlencoded"
            builder.post(bytes.toRequestBody(type.toMediaTypeOrNull()))
        } else {
            builder.get()
        }
        doRequest(builder.build(), built.charset)
    }

    suspend fun get(url: String, referer: String? = null, charset: String? = null): ReaderResponse =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", ProbeEngine.UA)
                .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
            if (!referer.isNullOrBlank()) builder.header("Referer", referer)
            doRequest(builder.get().build(), charset)
        }

    private fun doRequest(request: Request, preferredCharset: String?): ReaderResponse {
        try {
            client.newCall(request).execute().use { resp ->
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                if (bytes.size > MAX_BYTES) {
                    throw ReaderHttpException("页面过大：${bytes.size / 1024}KB")
                }
                val contentType = resp.header("Content-Type")
                val text = decode(bytes, contentType, preferredCharset)
                val finalUrl = resp.request.url.toString()
                if (resp.code !in 200..399) {
                    throw ReaderHttpException("HTTP ${resp.code}")
                }
                val root = RuleEngine.parseDocument(text, finalUrl)
                return ReaderResponse(resp.code, finalUrl, text, root, contentType)
            }
        } catch (e: ReaderHttpException) {
            throw e
        } catch (e: Exception) {
            throw ReaderHttpException(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun decode(bytes: ByteArray, contentType: String?, preferred: String?): String {
        val guesses = ArrayList<String>()
        if (!preferred.isNullOrBlank()) guesses.add(preferred)
        contentType?.let {
            Regex("""charset\s*=\s*["']?([\w-]+)""", RegexOption.IGNORE_CASE)
                .find(it)?.groupValues?.get(1)?.lowercase()?.let { cs -> if (cs !in guesses) guesses.add(cs) }
        }
        val head = String(bytes, 0, minOf(2048, bytes.size), Charsets.ISO_8859_1)
        Regex("""charset\s*=\s*["']?([\w-]+)""", RegexOption.IGNORE_CASE)
            .find(head)?.groupValues?.get(1)?.lowercase()?.let { if (it !in guesses) guesses.add(it) }
        guesses.add("utf-8")
        guesses.add("gbk")
        for (name in guesses) {
            decodeStrict(bytes, name)?.let { return it }
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

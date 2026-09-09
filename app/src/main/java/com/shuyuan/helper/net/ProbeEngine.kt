package com.shuyuan.helper.net

import com.google.gson.JsonObject
import com.shuyuan.helper.data.CheckSettings
import com.shuyuan.helper.data.SourceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

data class ProbeResult(
    val state: SourceState,
    val message: String,
    val httpCode: Int = 0,
    val elapsedMs: Long = -1L,
    val bodySize: Int = 0
)

data class SearchAttempt(
    val supported: Boolean,
    val result: ProbeResult? = null,
    val reason: String = ""
)

class ProbeEngine(private val settings: CheckSettings) {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(settings.timeoutSec, TimeUnit.SECONDS)
            .writeTimeout(settings.timeoutSec, TimeUnit.SECONDS)
            .callTimeout(settings.timeoutSec + 4, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(false)
            .build()
    }

    /** 检查书源地址（通常就是站点/接口根地址）能否访问 */
    suspend fun probeHost(urlInput: String): ProbeResult = withContext(Dispatchers.IO) {
        if (!urlInput.startsWith("http://") && !urlInput.startsWith("https://")) {
            return@withContext ProbeResult(SourceState.DEAD, "地址不是 http(s) 链接")
        }
        val started = System.currentTimeMillis()
        val req = Request.Builder()
            .url(urlInput)
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
            .get()
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                val elapsed = System.currentTimeMillis() - started
                val read = readPreview(resp)
                val text = read.first
                val size = read.second
                classifyHttp(resp.code, text, size, elapsed)
            }
        } catch (e: Exception) {
            ProbeResult(SourceState.DEAD, exceptionMessage(e), elapsedMs = System.currentTimeMillis() - started)
        }
    }

    /** 按 searchUrl 规则发出真实搜索请求 */
    suspend fun probeSearch(source: JsonObject, keyword: String): SearchAttempt =
        withContext(Dispatchers.IO) {
            val built = SearchRequestBuilder.build(source, keyword)
            if (!built.supported) {
                return@withContext SearchAttempt(supported = false, reason = built.unsupportedReason)
            }
            val started = System.currentTimeMillis()
            try {
                val reqBuilder = Request.Builder()
                    .url(built.url)
                built.headers.forEach { (k, v) ->
                    if (!k.equals("Host", true) && !k.equals("Content-Length", true)) {
                        reqBuilder.header(k, v)
                    }
                }
                if (built.method.equals("POST", true)) {
                    val ct = built.contentType ?: "application/x-www-form-urlencoded"
                    val bodyBytes = built.body?.toByteArray(charsetOf(built.charset)) ?: ByteArray(0)
                    reqBuilder.post(bodyBytes.toRequestBody(ct.toMediaType()))
                } else if (built.method.equals("HEAD", true)) {
                    reqBuilder.head()
                } else {
                    reqBuilder.get()
                }
                val resp = client.newCall(reqBuilder.build()).execute()
                resp.use {
                    val read = readPreview(it, built.charset)
                    val elapsed = System.currentTimeMillis() - started
                    val text = read.first
                    val size = read.second
                    val code = it.code
                    val state: SourceState
                    val message: String
                    when {
                        code in 200..299 -> {
                            val meaningful = text.length > 60 || size > 300
                            if (meaningful && !looksLikeErrorPage(text) && !looksLikeNoResult(text)) {
                                state = SourceState.OK
                                message = "HTTP $code，搜索接口正常返回"
                            } else {
                                state = SourceState.UNCERTAIN
                                message = "HTTP $code，但返回内容过少或疑似错误页"
                            }
                        }

                        code == 401 || code == 403 -> {
                            state = SourceState.UNCERTAIN
                            message = "HTTP $code，搜索接口需要登录或触发反爬"
                        }

                        else -> {
                            state = SourceState.DEAD
                            message = "HTTP $code，搜索接口不可用"
                        }
                    }
                    return@withContext SearchAttempt(
                        supported = true,
                        result = ProbeResult(state, message, code, elapsed, size)
                    )
                }
            } catch (e: Exception) {
                SearchAttempt(
                    supported = true,
                    result = ProbeResult(
                        SourceState.DEAD,
                        exceptionMessage(e),
                        elapsedMs = System.currentTimeMillis() - started
                    )
                )
            }
        }

    private fun classifyHttp(
        code: Int,
        bodyText: String,
        bodySize: Int,
        elapsedMs: Long
    ): ProbeResult {
        val parked = parkedOrClosed(bodyText)
        return when {
            code in 200..299 && parked != null ->
                ProbeResult(SourceState.DEAD, parked, code, elapsedMs, bodySize)

            code in 200..399 ->
                ProbeResult(SourceState.OK, "HTTP $code 站点正常可达", code, elapsedMs, bodySize)

            code == 401 || code == 403 ->
                ProbeResult(SourceState.UNCERTAIN, "HTTP $code 需要登录或被反爬拦截", code, elapsedMs, bodySize)

            code == 404 || code == 410 ->
                ProbeResult(SourceState.DEAD, "HTTP $code 页面不存在", code, elapsedMs, bodySize)

            code >= 400 ->
                ProbeResult(SourceState.DEAD, "HTTP $code 站点返回错误", code, elapsedMs, bodySize)

            else ->
                ProbeResult(SourceState.OK, "HTTP $code", code, elapsedMs, bodySize)
        }
    }

    private fun readPreview(resp: okhttp3.Response, preferredCharset: String? = null): Pair<String, Int> {
        val body = resp.body ?: return "" to 0
        val out = ByteArrayOutputStream()
        val bytes = ByteArray(8192)
        var total = 0
        try {
            val stream = body.byteStream()
            while (total < settings.maxBodyBytes) {
                val n = stream.read(bytes, 0, minOf(bytes.size, settings.maxBodyBytes - total))
                if (n < 0) break
                out.write(bytes, 0, n)
                total += n
                if (total >= settings.maxBodyBytes) break
            }
        } catch (_: IOException) {
        }
        val raw = out.toByteArray()
        return decodePreview(raw, resp, preferredCharset) to raw.size
    }

    private fun decodePreview(
        raw: ByteArray,
        resp: okhttp3.Response,
        preferredCharset: String? = null
    ): String {
        // BOM
        if (raw.size >= 3 && raw[0] == 0xEF.toByte() && raw[1] == 0xBB.toByte() && raw[2] == 0xBF.toByte()) {
            return raw.toString(Charsets.UTF_8)
        }
        val headerCharset = resp.header("Content-Type")
            ?.substringAfter("charset=", "")
            ?.trim('"', '\'', ' ', ';')
            ?.takeIf { it.isNotEmpty() }
        val charsetName = preferredCharset?.takeIf { it.isNotBlank() } ?: headerCharset ?: "UTF-8"
        val direct = runCatching { raw.toString(charsetOf(charsetName)) }.getOrDefault(raw.toString(Charsets.UTF_8))
        // UTF-8 解码失败特征明显时尝试 GBK
        if (charsetName.equals("UTF-8", true) && direct.contains('\uFFFD')) {
            return runCatching { raw.toString(Charset.forName("GBK")) }.getOrDefault(direct)
        }
        return direct
    }

    private fun parkedOrClosed(text: String): String? {
        val t = text.lowercase()
        return when {
            t.contains("域名已过期") || t.contains("域名到期") || t.contains("domain has expired") ->
                "站点域名疑似已过期"

            t.contains("site is for sale") || t.contains("buy this domain") ||
                t.contains("this domain is parked") || t.contains("domain parking") ->
                "站点疑似域名停放/出售"

            t.contains("网站已关闭") || t.contains("站点已关闭") || t.contains("网站已经关闭") ||
                t.contains("网站维护中") -> "站点已关闭或维护"

            t.contains("非法访问") && t.length < 500 -> "疑似被封禁或需验证"

            t.contains("err_too_many_redirects") || t.contains("too many redirects") ->
                "站点重定向过多"

            else -> null
        }
    }

    private fun looksLikeErrorPage(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("404 not found") || t.contains("whitelabel error page") ||
            t.contains("503 service unavailable") || t.contains("502 bad gateway") ||
            t.contains("请求过于频繁") || t.contains("access denied")
    }

    private fun looksLikeNoResult(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("没有找到") || t.contains("未搜索到") || t.contains("没有检索到") ||
            t.contains("搜索结果为0") || t.contains("无搜索结果") || t.contains("暂无搜索结果") ||
            t.contains("搜索结果为空") || t.contains("找不到相关") || t.contains("查无此书")
    }

    private fun exceptionMessage(e: Exception): String {
        return when (e) {
            is java.net.UnknownHostException -> "域名无法解析（DNS 失败）"
            is java.net.ConnectException -> "连接被拒绝"
            is java.net.SocketTimeoutException -> "连接超时"
            is javax.net.ssl.SSLException -> "SSL/TLS 证书异常"
            is java.net.SocketException -> "网络连接被重置"
            is IllegalArgumentException -> "链接格式非法：${e.message ?: ""}"
            else -> {
                val msg = e.message ?: e.javaClass.simpleName
                if (msg.contains("timeout", true)) "连接超时" else msg.take(120)
            }
        }
    }

    companion object {
        const val UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        fun charsetOf(name: String?): Charset {
            return runCatching { Charset.forName(name ?: "UTF-8") }.getOrDefault(Charsets.UTF_8)
        }
    }
}

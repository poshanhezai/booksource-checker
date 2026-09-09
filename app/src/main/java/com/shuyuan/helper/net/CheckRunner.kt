package com.shuyuan.helper.net

import com.shuyuan.helper.data.CheckManager
import com.shuyuan.helper.data.CheckMode
import com.shuyuan.helper.data.CheckSettings
import com.shuyuan.helper.data.SourceItem
import com.shuyuan.helper.data.SourceGroup
import com.shuyuan.helper.data.SourceState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

object CheckRunner {

    suspend fun run(items: List<SourceItem>, settings: CheckSettings) {
        val engine = ProbeEngine(settings)
        val semaphore = Semaphore(settings.concurrency.coerceAtLeast(1))
        val hostProbes = ConcurrentHashMap<String, CompletableDeferred<ProbeResult>>()
        val total = items.size
        var done = 0
        val finishLock = Any()

        coroutineScope {
            val jobs = items.mapIndexed { index, source ->
                async {
                    semaphore.withPermit {
                        val result = checkOne(engine, hostProbes, source, settings)
                        var group = source.group
                        var detail = result.message
                        if (settings.adultInspect && group != SourceGroup.ADULT) {
                            val hits = AdultContentSniffer.hits(result.contentSample)
                            if (hits.isNotEmpty()) {
                                group = SourceGroup.ADULT
                                detail = "${result.message}\n[内容识别] 命中成人特征：${hits.joinToString("、")}"
                            }
                        }
                        CheckManager.updateItem(
                            index,
                            source.copy(
                                state = result.state,
                                detail = detail,
                                httpCode = result.httpCode,
                                elapsedMs = result.elapsedMs,
                                searchTried = settings.mode == CheckMode.STANDARD,
                                group = group
                            )
                        )
                        synchronized(finishLock) {
                            done++
                            CheckManager.updateProgress(
                                done = done,
                                total = total,
                                runningText = "正在检测 ${source.name}  ($done/$total)",
                                finishText = ""
                            )
                        }
                    }
                }
            }
            jobs.joinAll()
        }
    }

    private suspend fun checkOne(
        engine: ProbeEngine,
        hostProbes: ConcurrentHashMap<String, CompletableDeferred<ProbeResult>>,
        source: SourceItem,
        settings: CheckSettings
    ): ProbeResult {
        val origin = originKey(source.url)
        val nonText = source.type != 0
        if (settings.mode == CheckMode.STANDARD && !nonText) {
            val attempt = engine.probeSearch(source.json, settings.keyword)
            if (attempt.supported && attempt.result != null) {
                return attempt.result
            }
            // 规则含 JS / WebView 等：退回站点可达性检查，但不直接判死
            val host = probeHostOnce(engine, hostProbes, origin, source.url)
            return if (host.state == SourceState.OK) {
                ProbeResult(
                    SourceState.UNCERTAIN,
                    "站点可达；${attempt.reason.ifBlank { "规则未深检" }}",
                    host.httpCode,
                    host.elapsedMs
                )
            } else {
                host
            }
        }
        return probeHostOnce(engine, hostProbes, origin, source.url)
    }

    private suspend fun probeHostOnce(
        engine: ProbeEngine,
        hostProbes: ConcurrentHashMap<String, CompletableDeferred<ProbeResult>>,
        origin: String,
        url: String
    ): ProbeResult {
        val existing = hostProbes[origin]
        if (existing != null) return existing.await()
        val deferred = CompletableDeferred<ProbeResult>()
        val raced = hostProbes.putIfAbsent(origin, deferred)
        if (raced != null) return raced.await()
        deferred.complete(engine.probeHost(url))
        return deferred.await()
    }

    private fun originKey(url: String): String {
        return runCatching {
            val uri = URI(url)
            "${uri.scheme.lowercase()}://${uri.host.lowercase()}:${if (uri.port > 0) uri.port else if (uri.scheme == "https") 443 else 80}"
        }.getOrDefault(url)
    }
}

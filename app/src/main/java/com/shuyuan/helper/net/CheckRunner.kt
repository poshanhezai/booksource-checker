package com.shuyuan.helper.net

import com.shuyuan.helper.data.CheckManager
import com.shuyuan.helper.data.CheckMode
import com.shuyuan.helper.data.CheckSettings
import com.shuyuan.helper.data.AppLog
import com.shuyuan.helper.data.SourceItem
import com.shuyuan.helper.data.SourceGroup
import com.shuyuan.helper.data.SourceState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

object CheckRunner {

    suspend fun run(items: List<SourceItem>, settings: CheckSettings) {
        AppLog.append(
            AppLog.Tag.CHECK,
            "检测参数：模式=${settings.mode.label} 数量=${items.size} 并发=${settings.concurrency} " +
                "超时=${settings.timeoutSec}s 关键词=${settings.keyword} 内容识别=${settings.adultInspect}"
        )
        val engine = ProbeEngine(settings)
        val semaphore = Semaphore(settings.concurrency.coerceAtLeast(1))
        val hostProbes = ConcurrentHashMap<String, CompletableDeferred<ProbeResult>>()
        val total = items.size
        var done = 0
        val finishLock = Any()

        coroutineScope {
            val jobs = items.mapIndexed { index, source ->
                async {
                    // 暂停时新任务在这里等待；正在执行的单条检测会先跑完，随后也停住
                    while (CheckManager.paused.value) {
                        delay(250)
                    }
                    semaphore.withPermit {
                        val result = checkOne(engine, hostProbes, source, settings)
                        var group = source.group
                        var detail = result.message
                        if (settings.adultInspect && group != SourceGroup.ADULT) {
                            val hits = AdultContentSniffer.hits(
                                result.contentSample,
                                settings.customAdultKeywords
                            )
                            if (hits.isNotEmpty()) {
                                group = SourceGroup.ADULT
                                detail = "${result.message}\n[主动访问识别] 命中特征：${hits.joinToString("、")}"
                                AppLog.append(
                                    AppLog.Tag.CHECK,
                                    "内容识别命中：${source.name} [${source.url}] -> ${hits.joinToString("、")}"
                                )
                            }
                        }
                        if (result.state == SourceState.DEAD) {
                            AppLog.warn(
                                AppLog.Tag.CHECK,
                                "检测失败：${source.name} [${source.url}] HTTP=${result.httpCode} " +
                                    "耗时=${result.elapsedMs}ms 原因=${result.message}"
                            )
                        } else if (result.state == SourceState.UNCERTAIN) {
                            AppLog.append(
                                AppLog.Tag.CHECK,
                                "疑似可用：${source.name} [${source.url}] ${result.message}"
                            )
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

package com.shuyuan.helper.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.shuyuan.helper.R
import com.shuyuan.helper.data.AppLog
import com.shuyuan.helper.data.CheckManager
import com.shuyuan.helper.data.CheckMode
import com.shuyuan.helper.data.CheckSettings
import com.shuyuan.helper.data.SourceImporter
import com.shuyuan.helper.data.SourceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class CheckService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private lateinit var notificationManager: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val path = intent.getStringExtra(EXTRA_PATH)
                if (path != null) {
                    val settings = intent.settings()
                    try {
                        startForeground(ID, notification("正在准备书源…"))
                    } catch (e: Exception) {
                        AppLog.error(
                            AppLog.Tag.COMPAT,
                            "前台服务启动失败：系统可能限制后台服务（设备=${android.os.Build.MANUFACTURER}/${android.os.Build.MODEL}）",
                            e
                        )
                        CheckManager.setRunning(false)
                        CheckManager.updateProgress(0, 0, "", "系统禁止后台检测服务，请改用「应用内检测」")
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    CheckManager.setRunning(true)
                    AppLog.append(
                        AppLog.Tag.CHECK,
                        "启动前台检测服务：模式=${settings.mode.label} 并发=${settings.concurrency} " +
                            "超时=${settings.timeoutSec}s 内容识别=${settings.adultInspect} " +
                            "自定义词数=${settings.customAdultKeywords.split(',', '，', '\n').count { it.isNotBlank() }}"
                    )
                    job?.cancel()
                    job = scope.launch {
                        runCheck(path, settings)
                    }
                }
            }

            ACTION_STOP -> {
                AppLog.append(AppLog.Tag.CHECK, "收到停止检测指令")
                job?.cancel()
                finish(stoppedByUser = true)
            }

            ACTION_PAUSE -> {
                AppLog.append(AppLog.Tag.CHECK, "收到暂停检测指令")
                CheckManager.setPaused(true)
                notificationManager.notify(ID, notification("检测已暂停"))
            }

            ACTION_RESUME -> {
                AppLog.append(AppLog.Tag.CHECK, "收到继续检测指令")
                CheckManager.setPaused(false)
                notificationManager.notify(ID, notification("检测继续中"))
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runCheck(path: String, settings: CheckSettings) {
        val parsed = withContext(Dispatchers.IO) {
            try {
                val text = File(path).readText()
                SourceImporter.parse(text)
            } catch (e: Exception) {
                AppLog.error(AppLog.Tag.CHECK, "读取/解析导入文件失败：$path", e)
                throw e
            }
        }
        if (parsed.items.isEmpty()) {
            AppLog.append(this, AppLog.Tag.CHECK, "检测启动失败：导入内容无法解析或没有可检测书源")
            CheckManager.updateProgress(0, 0, "", "导入内容无法解析或没有可检测书源")
            finish()
            return
        }
        CheckManager.importFile = path
        val items = parsed.items
        val total = items.size
        AppLog.append(AppLog.Tag.CHECK, "开始检测 $total 个书源")
        CheckManager.replaceAll(items, "共 $total 个书源，开始${settings.mode.label}…")

        try {
            CheckRunner.run(items, settings)
            val now = CheckManager.items.value
            val ok = now.count { it.state == SourceState.OK }
            val uncertain = now.count { it.state == SourceState.UNCERTAIN }
            val dead = now.count { it.state == SourceState.DEAD }
            AppLog.append(
                this,
                AppLog.Tag.CHECK,
                "检测完成：共 $total 个，可用 $ok 疑似 $uncertain 失效 $dead"
            )
            CheckManager.updateProgress(total, total, "", "检测完成：$total 个书源已处理")
        } catch (e: kotlinx.coroutines.CancellationException) {
            AppLog.append(this, AppLog.Tag.CHECK, "检测已停止")
            CheckManager.updateProgress(0, 0, "", "检测已停止")
        } catch (e: Exception) {
            AppLog.error(AppLog.Tag.CHECK, "检测运行异常", e)
            CheckManager.updateProgress(0, 0, "", "检测出错：${e.message}")
        } finally {
            finish()
        }
    }

    private fun finish(stoppedByUser: Boolean = false) {
        CheckManager.setRunning(false)
        if (stoppedByUser) {
            CheckManager.updateProgress(0, 0, "", "检测已停止")
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTimeout(startId: Int) {
        AppLog.warn(AppLog.Tag.COMPAT, "前台服务 onTimeout(startId=$startId)，自动停止")
        CheckManager.updateProgress(0, 0, "", "系统要求前台服务停止，检测已中断")
        finish(stoppedByUser = true)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        AppLog.warn(
            AppLog.Tag.COMPAT,
            "前台服务超时：startId=$startId fgsType=$fgsType（Android 15+ 限制）"
        )
        CheckManager.updateProgress(0, 0, "", "前台服务超时，检测已中断；下次可改用应用内检测")
        finish(stoppedByUser = true)
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "书源批量检测",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "批量检测书源时的进度通知" }
        notificationManager.createNotificationChannel(channel)
    }

    private fun notification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, CheckService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("书源体检中")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "停止", stopIntent)
            .setContentIntent(android.app.PendingIntent.getActivity(
                this,
                0,
                packageManager.getLaunchIntentForPackage(packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLog.append(AppLog.Tag.CHECK, "检测服务已销毁")
        job?.cancel()
        scope.cancel()
    }

    private fun Intent.settings(): CheckSettings {
        val mode = if (getBooleanExtra(EXTRA_STANDARD, true)) CheckMode.STANDARD else CheckMode.QUICK
        return CheckSettings(
            mode = mode,
            timeoutSec = getLongExtra(EXTRA_TIMEOUT_SEC, 12L),
            concurrency = getIntExtra(EXTRA_CONCURRENCY, 12),
            keyword = getStringExtra(EXTRA_KEYWORD) ?: "我",
            adultInspect = getBooleanExtra(EXTRA_ADULT_INSPECT, true),
            customAdultKeywords = getStringExtra(EXTRA_CUSTOM_ADULT_KEYWORDS).orEmpty()
        )
    }

    companion object {
        const val ACTION_START = "com.shuyuan.helper.action.START_CHECK"
        const val ACTION_STOP = "com.shuyuan.helper.action.STOP_CHECK"
        const val ACTION_PAUSE = "com.shuyuan.helper.action.PAUSE_CHECK"
        const val ACTION_RESUME = "com.shuyuan.helper.action.RESUME_CHECK"
        const val EXTRA_PATH = "import_file"
        const val EXTRA_STANDARD = "standard_mode"
        const val EXTRA_TIMEOUT_SEC = "timeout_sec"
        const val EXTRA_CONCURRENCY = "concurrency"
        const val EXTRA_KEYWORD = "keyword"
        const val EXTRA_ADULT_INSPECT = "adult_inspect"
        const val EXTRA_CUSTOM_ADULT_KEYWORDS = "custom_adult_keywords"
        private const val ID = 1001
        private const val CHANNEL_ID = "source_check"

        fun start(context: Context, importFile: String, settings: CheckSettings) {
            val intent = Intent(context, CheckService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PATH, importFile)
                putExtra(EXTRA_STANDARD, settings.mode == CheckMode.STANDARD)
                putExtra(EXTRA_TIMEOUT_SEC, settings.timeoutSec)
                putExtra(EXTRA_CONCURRENCY, settings.concurrency)
                putExtra(EXTRA_KEYWORD, settings.keyword)
                putExtra(EXTRA_ADULT_INSPECT, settings.adultInspect)
                putExtra(EXTRA_CUSTOM_ADULT_KEYWORDS, settings.customAdultKeywords)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, CheckService::class.java).setAction(ACTION_STOP)
            )
        }

        fun pause(context: Context) {
            context.startService(
                Intent(context, CheckService::class.java).setAction(ACTION_PAUSE)
            )
        }

        fun resume(context: Context) {
            context.startService(
                Intent(context, CheckService::class.java).setAction(ACTION_RESUME)
            )
        }
    }
}

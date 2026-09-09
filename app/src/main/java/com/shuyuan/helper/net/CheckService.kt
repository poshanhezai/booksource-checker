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
                    startForeground(ID, notification("正在准备书源…"))
                    CheckManager.setRunning(true)
                    job?.cancel()
                    job = scope.launch {
                        runCheck(path, settings)
                    }
                }
            }

            ACTION_STOP -> {
                job?.cancel()
                finish(stoppedByUser = true)
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runCheck(path: String, settings: CheckSettings) {
        val parsed = withContext(Dispatchers.IO) {
            val text = File(path).readText()
            SourceImporter.parse(text)
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
            AppLog.append(this, AppLog.Tag.CHECK, "检测出错：${e.message}")
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
        job?.cancel()
        scope.cancel()
    }

    private fun Intent.settings(): CheckSettings {
        val mode = if (getBooleanExtra(EXTRA_STANDARD, true)) CheckMode.STANDARD else CheckMode.QUICK
        return CheckSettings(
            mode = mode,
            timeoutSec = getLongExtra(EXTRA_TIMEOUT_SEC, 12L),
            concurrency = getIntExtra(EXTRA_CONCURRENCY, 12),
            keyword = getStringExtra(EXTRA_KEYWORD) ?: "我"
        )
    }

    companion object {
        const val ACTION_START = "com.shuyuan.helper.action.START_CHECK"
        const val ACTION_STOP = "com.shuyuan.helper.action.STOP_CHECK"
        const val EXTRA_PATH = "import_file"
        const val EXTRA_STANDARD = "standard_mode"
        const val EXTRA_TIMEOUT_SEC = "timeout_sec"
        const val EXTRA_CONCURRENCY = "concurrency"
        const val EXTRA_KEYWORD = "keyword"
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
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, CheckService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}

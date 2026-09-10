package com.shuyuan.helper

import android.app.Application
import com.shuyuan.helper.data.AppLog

class ShuyuanApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)

        val original = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                AppLog.error(
                    AppLog.Tag.APP,
                    "未捕获异常：线程=${thread.name}",
                    throwable
                )
            }
            original?.uncaughtException(thread, throwable)
        }
    }
}

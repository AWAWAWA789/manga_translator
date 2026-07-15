package com.manga.translator

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import com.manga.translator.debug.DebugManager
import com.manga.translator.service.ScreenCaptureService

/**
 * 自定义 Application。
 *
 * 职责：
 * 1. 提前初始化 DebugManager，避免 ScreenCaptureService 因 START_STICKY 被系统重建时
 *    先于 MainActivity 访问 DebugManager 导致 lateinit 崩溃。
 * 2. 提前创建前台服务通知渠道，避免 ScreenCaptureService.startForeground 时
 *    首次创建渠道失败导致前台服务启动失败。
 */
class MangaTranslatorApp : Application() {

    companion object {
        private const val TAG = "MangaTranslator"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "MangaTranslatorApp onCreate")

        // 初始化 DebugManager：使用 applicationContext，确保在任何 Activity/Service 之前完成
        DebugManager.initialize(applicationContext)

        // 提前创建前台服务通知渠道，避免 Service.startForeground 时首次创建渠道失败
        createScreenCaptureNotificationChannel()
    }

    private fun createScreenCaptureNotificationChannel() {
        // minSdk 26 后通知渠道一律必须创建，无需版本判断
        val channel = NotificationChannel(
            ScreenCaptureService.CHANNEL_ID,
            "屏幕翻译服务",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}

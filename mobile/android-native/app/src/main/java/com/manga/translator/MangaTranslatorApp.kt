package com.manga.translator

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.manga.translator.debug.DebugManager
import com.manga.translator.di.ServiceLocator
import com.manga.translator.service.ScreenCaptureService
import com.manga.translator.util.AppLog
import com.manga.translator.util.CrashHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 自定义 Application。
 *
 * 职责：
 * 1. 提前初始化 DebugManager，避免 ScreenCaptureService 因 START_STICKY 被系统重建时
 *    先于 MainActivity 访问 DebugManager 导致 lateinit 崩溃。
 * 2. 提前创建前台服务通知渠道，避免 Service.startForeground 时
 *    首次创建渠道失败导致前台服务启动失败。
 * 3. 提供全局 [appScope] 协程作用域，用于不绑定到特定组件（Service/Activity）的
 *    顶级协程任务；子作用域（如 Service 的 serviceScope）应自行管理生命周期，
 *    不要使用本作用域，避免 Service 销毁后协程仍在运行。
 */
class MangaTranslatorApp : Application() {

    companion object {
        /**
         * 应用级协程作用域：SupervisorJob 确保子协程异常不会互相取消；
         * Dispatchers.Default 适用于 CPU 密集与轻量 IO，阻塞 IO 应使用 Dispatchers.IO。
         * 生命周期与 Application 相同，进程退出时自动回收。
         */
        val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    override fun onCreate() {
        super.onCreate()
        AppLog.d("MangaTranslator", "[启动] Application onCreate")

        // 注册全局崩溃处理器：崩溃堆栈写入本地文件，便于事后排查
        CrashHandler.init(applicationContext)

        // 初始化 DebugManager：使用 applicationContext，确保在任何 Activity/Service 之前完成
        DebugManager.initialize(applicationContext)

        // 初始化 ServiceLocator：集中管理依赖，便于后续接口分离和单元测试替换
        ServiceLocator.init(applicationContext)

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

package com.manga.translator.util

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃处理器：捕获未处理异常，将堆栈写入本地文件。
 * 崩溃文件存储在 /data/data/<package>/files/crash/ 目录，按日期命名。
 */
object CrashHandler : Thread.UncaughtExceptionHandler {

    private const val TAG = "CrashHandler"
    private const val CRASH_DIR = "crash"

    @Volatile
    private var context: Context? = null

    @Volatile
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    fun init(context: Context) {
        this.context = context.applicationContext
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(t: Thread, e: Throwable) {
        // 1. 写入崩溃日志到文件
        writeCrashToFile(t, e)
        // 2. 交回系统默认处理器（弹"应用已停止"对话框）
        defaultHandler?.uncaughtException(t, e)
    }

    private fun writeCrashToFile(thread: Thread, throwable: Throwable) {
        val ctx = context ?: run {
            Log.e(TAG, "CrashHandler 未初始化，跳过崩溃日志写入")
            return
        }
        try {
            val crashDir = File(ctx.filesDir, CRASH_DIR)
            if (!crashDir.exists()) crashDir.mkdirs()
            val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.CHINA)
            val fileName = "crash_${dateFormat.format(Date())}.txt"
            val crashFile = File(crashDir, fileName)

            val sw = StringWriter()
            val pw = PrintWriter(sw)
            throwable.printStackTrace(pw)

            val log = buildString {
                append("====== 崩溃时间: ${Date()} ======\n")
                append("线程: ${thread.name}\n")
                append("设备: ${Build.MANUFACTURER} ${Build.MODEL}\n")
                append("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
                append("====== 堆栈 ======\n")
                append(sw.toString())
            }
            crashFile.writeText(log)
            Log.e(TAG, "崩溃日志已写入: ${crashFile.absolutePath}")
        } catch (ioe: Exception) {
            Log.e(TAG, "写入崩溃日志失败: ${ioe.message}")
        }
    }

    /**
     * 获取所有崩溃日志文件列表（供设置页查看/导出）。
     * 按修改时间降序排列（最新在前）。
     */
    fun getCrashFiles(): List<File> {
        val ctx = context ?: return emptyList()
        val crashDir = File(ctx.filesDir, CRASH_DIR)
        return crashDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
}

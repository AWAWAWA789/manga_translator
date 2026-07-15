package com.manga.translator.util

import android.util.Log
import com.manga.translator.BuildConfig

/**
 * 统一日志工具。
 * 格式：[模块][事件] key=value 或 [模块] message
 * Release 包自动屏蔽 DEBUG 级别日志。
 */
object AppLog {
    fun d(module: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(module, message)
    }
    fun i(module: String, message: String) {
        Log.i(module, message)
    }
    fun w(module: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.w(module, message, throwable) else Log.w(module, message)
    }
    fun e(module: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.e(module, message, throwable) else Log.e(module, message)
    }
}

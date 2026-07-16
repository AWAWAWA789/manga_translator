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

    /**
     * 惰性求值版本：仅 DEBUG 构建时计算 [msg]，Release 完全跳过字符串拼接。
     * 用于热路径含 ${} 模板的日志；inline 让 R8 在 Release 消除整个调用。
     */
    inline fun d(module: String, crossinline msg: () -> String) {
        if (BuildConfig.DEBUG) Log.d(module, msg())
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

package com.manga.translator.debug

import android.content.Context
import android.content.SharedPreferences

object DebugManager {

    private const val PREFS_NAME = "debug_config"
    private const val KEY_SHOW_DEBUG = "show_debug"
    private const val KEY_SHOW_BUBBLES = "show_bubbles"
    private const val KEY_SHOW_OCR = "show_ocr"
    private const val KEY_SHOW_MAPPINGS = "show_mappings"
    private const val KEY_SHOW_ORDER = "show_order"

    // 持有 applicationContext，避免传入 Activity Context 导致泄漏
    @Volatile
    private var appContext: Context? = null

    // 惰性初始化：首次访问 getter 时才创建 SharedPreferences，消除对 initialize() 调用时机的硬依赖
    private val preferences: SharedPreferences by lazy {
        val ctx = appContext
            ?: throw IllegalStateException("DebugManager 未初始化，请先调用 initialize(applicationContext)")
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun isDebugEnabled(): Boolean = preferences.getBoolean(KEY_SHOW_DEBUG, false)

    fun setDebugEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_SHOW_DEBUG, enabled).apply()
    }

    fun isShowBubbles(): Boolean = preferences.getBoolean(KEY_SHOW_BUBBLES, false)

    fun setShowBubbles(show: Boolean) {
        preferences.edit().putBoolean(KEY_SHOW_BUBBLES, show).apply()
    }

    fun isShowOcr(): Boolean = preferences.getBoolean(KEY_SHOW_OCR, false)

    fun setShowOcr(show: Boolean) {
        preferences.edit().putBoolean(KEY_SHOW_OCR, show).apply()
    }

    fun isShowMappings(): Boolean = preferences.getBoolean(KEY_SHOW_MAPPINGS, false)

    fun setShowMappings(show: Boolean) {
        preferences.edit().putBoolean(KEY_SHOW_MAPPINGS, show).apply()
    }

    fun isShowOrder(): Boolean = preferences.getBoolean(KEY_SHOW_ORDER, false)

    fun setShowOrder(show: Boolean) {
        preferences.edit().putBoolean(KEY_SHOW_ORDER, show).apply()
    }

    fun getConfig(): DebugOverlayConfig {
        return DebugOverlayConfig(
            showBubbleRects = isShowBubbles(),
            showOcrRects = isShowOcr(),
            showMappingLines = isShowMappings(),
            showReadingOrder = isShowOrder(),
        )
    }
}

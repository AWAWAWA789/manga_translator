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

    // 使用 lateinit 替代可空声明：必须在应用启动时调用 initialize() 完成初始化，
    // 之后所有 getter 直接访问，消除重复的 ?: false 兜底分支
    private lateinit var preferences: SharedPreferences

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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
            showReadingOrder = isShowOrder()
        )
    }
}

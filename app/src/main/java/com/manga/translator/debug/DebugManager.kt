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
    
    private var preferences: SharedPreferences? = null
    
    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    fun isDebugEnabled(): Boolean {
        return preferences?.getBoolean(KEY_SHOW_DEBUG, false) ?: false
    }
    
    fun setDebugEnabled(enabled: Boolean) {
        preferences?.edit()?.putBoolean(KEY_SHOW_DEBUG, enabled)?.apply()
    }
    
    fun isShowBubbles(): Boolean {
        return preferences?.getBoolean(KEY_SHOW_BUBBLES, false) ?: false
    }
    
    fun setShowBubbles(show: Boolean) {
        preferences?.edit()?.putBoolean(KEY_SHOW_BUBBLES, show)?.apply()
    }
    
    fun isShowOcr(): Boolean {
        return preferences?.getBoolean(KEY_SHOW_OCR, false) ?: false
    }
    
    fun setShowOcr(show: Boolean) {
        preferences?.edit()?.putBoolean(KEY_SHOW_OCR, show)?.apply()
    }
    
    fun isShowMappings(): Boolean {
        return preferences?.getBoolean(KEY_SHOW_MAPPINGS, false) ?: false
    }
    
    fun setShowMappings(show: Boolean) {
        preferences?.edit()?.putBoolean(KEY_SHOW_MAPPINGS, show)?.apply()
    }
    
    fun isShowOrder(): Boolean {
        return preferences?.getBoolean(KEY_SHOW_ORDER, false) ?: false
    }
    
    fun setShowOrder(show: Boolean) {
        preferences?.edit()?.putBoolean(KEY_SHOW_ORDER, show)?.apply()
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
package com.manga.translator.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.manga.translator.debug.DebugOverlayConfig
import com.manga.translator.debug.DebugOverlayData
import com.manga.translator.model.TranslationCard
import kotlin.math.abs

class FloatingWindowService : Service() {

    companion object {
        private const val TAG = "MangaTranslator"
        private const val AUTO_REMOVE_MS = 30000L

        enum class TranslateMode { REALTIME, MANUAL }
        enum class RecognitionDirection { HORIZONTAL, VERTICAL }

        var onModeChanged: ((TranslateMode) -> Unit)? = null
        var onManualTranslate: (() -> Unit)? = null
        var onPauseChanged: ((Boolean) -> Unit)? = null
        var onTouchStateChanged: ((Boolean) -> Unit)? = null
        var onRecognitionDirectionChanged: ((RecognitionDirection) -> Unit)? = null

        /** 安全调用回调，防止目标服务已销毁导致崩溃 */
        private inline fun safeCallback(noinline callback: (() -> Unit)?) {
            try { callback?.invoke() } catch (e: Exception) {
                Log.e(TAG, "回调执行异常: ${e.message}")
            }
        }
        private inline fun <T> safeCallbackArg(noinline callback: ((T) -> Unit)?, arg: T) {
            try { callback?.invoke(arg) } catch (e: Exception) {
                Log.e(TAG, "回调执行异常: ${e.message}")
            }
        }

        @Volatile var currentMode: TranslateMode = TranslateMode.MANUAL
            private set

        @Volatile var isPaused: Boolean = false
            private set

        @Volatile var isTouching: Boolean = false
            private set

        @Volatile var recognitionDirection: RecognitionDirection = RecognitionDirection.VERTICAL

        @Volatile var debugConfig: DebugOverlayConfig = DebugOverlayConfig()
            private set

        @Suppress("StaticFieldLeak")
        // @Volatile 保证跨线程可见性：instance 在主线程 onCreate 赋值，被 executor 线程读取
        @Volatile
        private var instance: FloatingWindowService? = null

        fun showTranslationsWithDebug(
            @Suppress("UNUSED_PARAMETER") context: Context,
            results: List<TranslationCard>,
            debugData: DebugOverlayData,
        ) {
            instance?.let { service ->
                service.handler.post { service.addTranslationOverlaysWithDebug(results, debugData) }
            }
        }

        fun clearAllTranslations() {
            instance?.let { service ->
                service.handler.post { service.removeAllTranslationOverlays() }
            }
        }

        fun setDebugConfig(config: DebugOverlayConfig) {
            debugConfig = config
            instance?.translationOverlayView?.setDebugConfig(config)
        }

        fun hideFloatingUI() {
            instance?.let { service ->
                service.handler.post { service.hideUIForCapture() }
            }
        }

        fun showFloatingUI() {
            instance?.let { service ->
                service.handler.post { service.restoreUIAfterCapture() }
            }
        }

        fun setStatusText(text: String) {
            instance?.let { service ->
                service.handler.post { service.modeTextView?.text = text }
            }
        }

        fun updateMainAppearance() {
            instance?.let { service ->
                service.handler.post { service.updateMainViewAppearance() }
            }
        }
    }

    private lateinit var handler: Handler
    private var windowManager: WindowManager? = null
    private var mainView: FrameLayout? = null
    private var mainParams: WindowManager.LayoutParams? = null
    private var modeTextView: TextView? = null
    private var menuView: View? = null
    private var isMenuShowing = false

    private var translationOverlayView: TranslationOverlayView? = null
    private var translationRemoveRunnable: Runnable? = null
    private var isDragging = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var lastMainX = 0
    private var lastMainY = 0
    private var isUIHiddenForCapture = false
    private var uiVisibilityBeforeCapture = 0
    private var longPressRunnable: Runnable? = null
    private var longPressTriggered = false

    override fun onCreate() {
        super.onCreate()
        handler = Handler(Looper.getMainLooper())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        instance = this
        createMainFloatingWindow()
    }

    override fun onDestroy() {
        instance = null
        cleanup()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ==================== 主悬浮球 ====================

    private fun createMainFloatingWindow() {
        try {
            val density = resources.displayMetrics.density
            val height = (40 * density).toInt()
            val width = (80 * density).toInt()

            mainView = FrameLayout(this).apply {
                background = createPillDrawable(currentMode)
                elevation = 6 * density
                setPadding((14 * density).toInt(), (6 * density).toInt(), (14 * density).toInt(), (6 * density).toInt())
            }

            modeTextView = TextView(this).apply {
                text = "翻译"
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                paint.isFakeBoldText = true
                gravity = Gravity.CENTER
            }
            mainView?.addView(
                modeTextView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )

            mainParams = WindowManager.LayoutParams(
                width, height, getWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = (16 * density).toInt()
                y = (300 * density).toInt()
            }

            windowManager?.addView(mainView, mainParams)
            mainView?.setOnTouchListener { v, event -> onTouchMainView(v, event) }
        } catch (e: Exception) {
            Log.e(TAG, "主悬浮球创建失败: ${e.message}")
        }
    }

    private fun createPillDrawable(mode: TranslateMode): GradientDrawable {
        val (c1, c2) = when {
            isPaused -> Pair(0x99616161.toInt(), 0x999E9E9E.toInt())
            mode == TranslateMode.REALTIME -> Pair(0xCC00C853.toInt(), 0xCC4CAF50.toInt())
            else -> Pair(0xCC6200EE.toInt(), 0xCC9C27B0.toInt())
        }
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 20 * resources.displayMetrics.density
            gradientType = GradientDrawable.LINEAR_GRADIENT
            orientation = GradientDrawable.Orientation.LEFT_RIGHT
            colors = intArrayOf(c1, c2)
            setStroke((1 * resources.displayMetrics.density).toInt(), 0x33FFFFFF)
        }
    }

    private fun updateMainViewAppearance() {
        mainView?.background = createPillDrawable(currentMode)
        modeTextView?.text = when {
            isPaused -> "暂停"
            currentMode == TranslateMode.REALTIME -> "实时"
            else -> "翻译"
        }
    }

    private fun onTouchMainView(view: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = false
                isTouching = true
                longPressTriggered = false
                safeCallbackArg(onTouchStateChanged, true)
                lastTouchX = event.rawX
                lastTouchY = event.rawY
                mainParams?.let {
                    lastMainX = it.x
                    lastMainY = it.y
                }
                view.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).start()

                longPressRunnable?.let { handler.removeCallbacks(it) }
                longPressRunnable = Runnable {
                    if (!isDragging && isTouching) {
                        longPressTriggered = true
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        if (isMenuShowing) hideMenu() else showMenu()
                    }
                }
                handler.postDelayed(longPressRunnable!!, 500L)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - lastTouchX
                val dy = event.rawY - lastTouchY
                if (!isDragging && (abs(dx) > 5 || abs(dy) > 5)) {
                    isDragging = true
                    longPressRunnable?.let { handler.removeCallbacks(it) }
                }
                if (isDragging) {
                    mainParams?.let { p ->
                        p.x = lastMainX - dx.toInt()
                        p.y = lastMainY + dy.toInt()
                        try { windowManager?.updateViewLayout(view, p) } catch (_: Exception) {}
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                view.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                isTouching = false
                safeCallbackArg(onTouchStateChanged, false)
                longPressRunnable?.let { handler.removeCallbacks(it) }
                longPressRunnable = null

                if (!isDragging && !longPressTriggered) {
                    view.performClick()
                    onMainViewClicked()
                }
                return true
            }
        }
        return false
    }

    private fun onMainViewClicked() {
        if (isMenuShowing) {
            hideMenu()
        } else {
            setStatusText("...")
            safeCallback(onManualTranslate)
        }
    }

    // ==================== 菜单 ====================

    private fun showMenu() {
        if (isMenuShowing) return
        try {
            val d = resources.displayMetrics.density
            val dp8 = (8 * d).toInt()
            val dp12 = (12 * d).toInt()

            val menuLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = 16 * d
                    setColor(0xFAFFFFFF.toInt())
                    setStroke((1 * d).toInt(), 0x22000000)
                }
                setPadding(dp12, dp8, dp12, dp8)
                elevation = 16 * d
            }

            // Row 1: 实时翻译 | 手动翻译
            val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            addMenuButton(row1, "实时翻译", d, currentMode == TranslateMode.REALTIME) {
                isPaused = false
                currentMode = TranslateMode.REALTIME
                safeCallbackArg(onModeChanged, currentMode)
                safeCallbackArg(onPauseChanged, false)
                updateMainViewAppearance()
                hideMenu()
            }
            addMenuButton(row1, "手动翻译", d, currentMode == TranslateMode.MANUAL) {
                isPaused = false
                currentMode = TranslateMode.MANUAL
                safeCallbackArg(onModeChanged, currentMode)
                safeCallbackArg(onPauseChanged, false)
                updateMainViewAppearance()
                hideMenu()
            }
            menuLayout.addView(
                row1,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )

            addMenuDivider(menuLayout, d)

            // Row 2: 横向识别 | 竖向识别
            val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            addMenuButton(row2, "横向识别", d, recognitionDirection == RecognitionDirection.HORIZONTAL) {
                if (recognitionDirection != RecognitionDirection.HORIZONTAL) {
                    recognitionDirection = RecognitionDirection.HORIZONTAL
                    safeCallbackArg(onRecognitionDirectionChanged, RecognitionDirection.HORIZONTAL)
                    Log.d(TAG, "识别方向切换为: HORIZONTAL")
                }
                updateMainViewAppearance()
                hideMenu()
            }
            addMenuButton(row2, "竖向识别", d, recognitionDirection == RecognitionDirection.VERTICAL) {
                if (recognitionDirection != RecognitionDirection.VERTICAL) {
                    recognitionDirection = RecognitionDirection.VERTICAL
                    safeCallbackArg(onRecognitionDirectionChanged, RecognitionDirection.VERTICAL)
                    Log.d(TAG, "识别方向切换为: VERTICAL")
                }
                updateMainViewAppearance()
                hideMenu()
            }
            menuLayout.addView(
                row2,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )

            addMenuDivider(menuLayout, d)

            // Row 3: 暂停翻译 | 关闭菜单
            val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            addMenuButton(row3, if (isPaused) "恢复翻译" else "暂停翻译", d, false) {
                isPaused = !isPaused
                safeCallbackArg(onPauseChanged, isPaused)
                updateMainViewAppearance()
                hideMenu()
            }
            addMenuButton(row3, "关闭菜单", d, false) {
                hideMenu()
            }
            menuLayout.addView(
                row3,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )

            menuView = menuLayout
            val mainY = mainParams?.y ?: (300 * d).toInt()
            val menuParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                getWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = (20 * d).toInt()
                y = mainY + (40 * d).toInt() + (8 * d).toInt()
            }

            windowManager?.addView(menuView, menuParams)
            isMenuShowing = true
            // 菜单滑入动画
            menuView?.alpha = 0f
            menuView?.translationY = -20f * d
            menuView?.animate()
                ?.alpha(1f)
                ?.translationY(0f)
                ?.setDuration(200)
                ?.setInterpolator(android.view.animation.DecelerateInterpolator())
                ?.start()
        } catch (e: Exception) {
            Log.e(TAG, "菜单显示失败: ${e.message}")
            isMenuShowing = false
        }
    }

    private fun addMenuButton(c: LinearLayout, text: String, d: Float, selected: Boolean, onClick: () -> Unit) {
        val dp10 = (10 * d).toInt()
        val dp6 = (6 * d).toInt()
        val tv = TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFF333333.toInt())
            gravity = Gravity.CENTER
            setPadding(dp10, dp6, dp10, dp6)
            isClickable = true
            isFocusable = true
            background = if (selected) {
                GradientDrawable().apply {
                    cornerRadius = 8 * d
                    setColor(0xFF6200EE.toInt())
                }
            } else {
                GradientDrawable().apply {
                    cornerRadius = 8 * d
                    setColor(0xFFF5F5F5.toInt())
                }
            }
            setOnClickListener { onClick() }
        }
        val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = (2 * d).toInt()
            marginEnd = (2 * d).toInt()
        }
        c.addView(tv, params)
    }

    private fun addMenuDivider(c: LinearLayout, d: Float) {
        c.addView(
            View(this).apply { setBackgroundColor(0xFFEEEEEE.toInt()) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * d).toInt()),
        )
    }

    private fun hideMenu() {
        if (!isMenuShowing) return
        val view = menuView ?: return
        val d = resources.displayMetrics.density
        try {
            view.animate()
                .alpha(0f)
                .translationY(-20f * d)
                .setDuration(150)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .withEndAction {
                    try { windowManager?.removeViewImmediate(view) } catch (_: Exception) {}
                    menuView = null
                    isMenuShowing = false
                }
                .start()
        } catch (e: Exception) {
            Log.w(TAG, "菜单隐藏失败: ${e.message}")
            try { windowManager?.removeViewImmediate(view) } catch (_: Exception) {}
            menuView = null
            isMenuShowing = false
        }
    }

    // ==================== 翻译悬浮层 ====================

    private fun addTranslationOverlays(results: List<TranslationCard>) {
        addTranslationOverlaysWithDebug(results, DebugOverlayData())
    }

    private fun addTranslationOverlaysWithDebug(results: List<TranslationCard>, debugData: DebugOverlayData) {
        val overlayItems = results.mapNotNull { result ->
            val originalText = result.originalText.trim()
            val translatedText = result.translatedText.trim()
            val boundingBox = result.sourceRect
            if (translatedText.isBlank()) return@mapNotNull null
            if (translatedText.length < 2) return@mapNotNull null
            if (translatedText.startsWith("翻译失败")) return@mapNotNull null
            if (translatedText.startsWith("翻译错误")) return@mapNotNull null
            if (containsJapanese(translatedText)) return@mapNotNull null
            if (translatedText.matches(Regex("^[\\p{Punct}\\s]+$"))) return@mapNotNull null
            val lengthRatio = translatedText.length.toFloat() / maxOf(1, originalText.length)
            if (lengthRatio < 0.2f || lengthRatio > 10.0f) return@mapNotNull null

            TranslationOverlayView.TranslationItem(
                originalText = originalText,
                translatedText = translatedText,
                sourceRect = Rect(boundingBox),
                isVerticalSource = result.isVertical,
            )
        }

        if (overlayItems.isEmpty()) return

        // 先 limit 到 16 再 dedup，避免在 OCR 返回大量结果时执行 O(n²) 全量去重
        val limitedItems = if (overlayItems.size > 16) {
            overlayItems.sortedByDescending { it.translatedText.length }.take(16)
        } else {
            overlayItems
        }

        val dedupedItems = deduplicateTranslations(limitedItems)

        Log.d(TAG, "翻译覆盖层: 输入=${results.size}, 有效=${overlayItems.size}, 限流后=${limitedItems.size}, 去重后=${dedupedItems.size}")
        Log.d(TAG, "翻译覆盖层: 显示=${dedupedItems.size}")

        // 更新现有覆盖层，而非重建
        val existing = translationOverlayView
        if (existing != null) {
            existing.setTranslations(dedupedItems)
            existing.setDebugConfig(debugConfig)
            existing.setDebugData(debugData)
            translationRemoveRunnable?.let { handler.removeCallbacks(it) }
            translationRemoveRunnable = Runnable { removeAllTranslationOverlays() }
            handler.postDelayed(translationRemoveRunnable!!, AUTO_REMOVE_MS)
            return
        }

        val overlay = TranslationOverlayView(this).apply {
            setTranslations(dedupedItems)
            setDebugConfig(debugConfig)
            setDebugData(debugData)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            getWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        try {
            windowManager?.addView(overlay, params)
            translationOverlayView = overlay
            translationRemoveRunnable = Runnable { removeAllTranslationOverlays() }
            handler.postDelayed(translationRemoveRunnable!!, AUTO_REMOVE_MS)
        } catch (e: Exception) {
            Log.e(TAG, "添加翻译覆盖层失败: ${e.message}")
        }
    }

    /**
     * 去重：翻译文本高度相似的只保留一条
     */
    private fun deduplicateTranslations(items: List<TranslationOverlayView.TranslationItem>): List<TranslationOverlayView.TranslationItem> {
        if (items.size <= 1) return items
        val kept = mutableListOf<TranslationOverlayView.TranslationItem>()
        for (item in items) {
            val isDuplicate = kept.any { existing ->
                val textSim = calculateTextSimilarity(item.translatedText, existing.translatedText)
                val rectOverlap = calculateRectOverlap(item.sourceRect, existing.sourceRect)
                textSim > 0.80f || (textSim > 0.60f && rectOverlap > 0.30f)
            }
            if (!isDuplicate) {
                kept.add(item)
            }
        }
        return kept
    }

    private fun calculateTextSimilarity(a: String, b: String): Float {
        return com.manga.translator.util.StringUtils.similarity(a, b)
    }

    private fun calculateRectOverlap(a: Rect, b: Rect): Float {
        val overlapLeft = maxOf(a.left, b.left)
        val overlapTop = maxOf(a.top, b.top)
        val overlapRight = minOf(a.right, b.right)
        val overlapBottom = minOf(a.bottom, b.bottom)
        if (overlapLeft >= overlapRight || overlapTop >= overlapBottom) return 0f
        val overlapArea = (overlapRight - overlapLeft) * (overlapBottom - overlapTop)
        val minArea = minOf(a.width() * a.height(), b.width() * b.height())
        if (minArea <= 0) return 0f
        return overlapArea.toFloat() / minArea.toFloat()
    }

    /**
     * 检测是否包含日文字符
     */
    private fun containsJapanese(text: String): Boolean {
        for (char in text) {
            val code = char.code
            // 平假名
            if (code in 0x3040..0x309F) return true
            // 片假名
            if (code in 0x30A0..0x30FF) return true
        }
        return false
    }

    private fun removeAllTranslationOverlays() {
        translationRemoveRunnable?.let { handler.removeCallbacks(it) }
        translationRemoveRunnable = null
        try {
            translationOverlayView?.clearTranslations()
            translationOverlayView?.let { windowManager?.removeViewImmediate(it) }
        } catch (e: Exception) {
            Log.w(TAG, "移除翻译覆盖层失败: ${e.message}")
        }
        translationOverlayView = null
    }

    private fun hideUIForCapture() {
        if (isUIHiddenForCapture) return
        isUIHiddenForCapture = true
        try {
            mainView?.let {
                uiVisibilityBeforeCapture = it.visibility
                it.visibility = View.INVISIBLE
            }
            hideMenu()
            removeAllTranslationOverlays()
        } catch (e: Exception) {
            // 截图前隐藏 UI 失败会导致悬浮球/覆盖层被截入画面，需记录便于排查
            Log.w(TAG, "截图前隐藏UI失败: ${e.message}")
        }
    }

    private fun restoreUIAfterCapture() {
        if (!isUIHiddenForCapture) return
        isUIHiddenForCapture = false
        try {
            mainView?.let {
                it.visibility = View.VISIBLE
            }
        } catch (e: Exception) {
            // 恢复失败会导致悬浮球长期不可见，需记录
            Log.w(TAG, "截图后恢复UI失败: ${e.message}")
        }
    }

    private fun setStatusText(text: String) {
        modeTextView?.text = text
    }

    private fun getWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun cleanup() {
        // 1. 先同步移除所有 View（含 menuView），不依赖 hideMenu 动画的 withEndAction 回调，
        //    避免 windowManager 被置空后回调被 ?. 安全跳过，导致 menuView 仍挂在 WindowManager 上
        try { menuView?.let { windowManager?.removeViewImmediate(it) } } catch (_: Exception) {}
        isMenuShowing = false
        removeAllTranslationOverlays()
        try { mainView?.let { windowManager?.removeViewImmediate(it) } } catch (_: Exception) {}
        // 2. 清除所有 pending message（含 hideMenu 动画 withEndAction 投递的回调）
        handler.removeCallbacksAndMessages(null)
        longPressRunnable = null
        // 3. 最后 null 化引用
        menuView = null
        mainView = null
        modeTextView = null
        mainParams = null
        windowManager = null
    }
}

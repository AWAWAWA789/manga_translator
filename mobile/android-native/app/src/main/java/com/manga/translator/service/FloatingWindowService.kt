package com.manga.translator.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.math.abs

class FloatingWindowService : Service() {

    companion object {
        private const val TAG = "MangaTranslator"
        private const val AUTO_REMOVE_MS = 30000L

        enum class RecognitionDirection { HORIZONTAL, VERTICAL }

        @Volatile var onManualTranslate: (() -> Unit)? = null

        @Volatile var onRecognitionDirectionChanged: ((RecognitionDirection) -> Unit)? = null

        @Volatile var onAiVisionModeChanged: ((Boolean) -> Unit)? = null

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

        @Volatile var isTouching: Boolean = false
            private set

        @Volatile var recognitionDirection: RecognitionDirection = RecognitionDirection.VERTICAL

        /** AI 多模态识别开关：手动翻译时是否使用 AI 视觉管线 */
        @Volatile var useAiVisionMode: Boolean = false
            private set

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
            instance?.handler?.post { instance?.translationOverlayView?.setDebugConfig(config) }
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
    private var menuParams: WindowManager.LayoutParams? = null
    private var isMenuShowing = false
    private var isAnimating = false

    // 动画超时兜底：withEndAction 在动画异常时不触发，isAnimating 会永久卡死导致菜单无法操作
    private val animTimeoutRunnable = Runnable { isAnimating = false }

    @Volatile private var translationOverlayView: TranslationOverlayView? = null
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

    /**
     * Service 协程作用域：SupervisorJob 防止子协程异常相互取消，Dispatchers.Main 用于 UI 操作。
     * onDestroy 中 cancel()，所有子协程自动取消，避免 Service 销毁后协程泄漏。
     * 后续任务 2.6 将把 Handler.post 替换为 serviceScope.launch。
     */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        handler = Handler(Looper.getMainLooper())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        instance = this
        val prefs = getSharedPreferences("translation_config", Context.MODE_PRIVATE)
        useAiVisionMode = prefs.getBoolean("ai_bubble_detection", false)
        createMainFloatingWindow()
    }

    override fun onDestroy() {
        instance = null
        // 清理静态回调引用，避免持有 ScreenCaptureService 导致双向 Service 泄漏
        onManualTranslate = null
        onRecognitionDirectionChanged = null
        onAiVisionModeChanged = null
        // 取消所有子协程，避免 Service 销毁后协程仍在运行导致内存泄漏与崩溃
        serviceScope.cancel()
        // 清除所有待执行消息，避免销毁后 Runnable 访问已释放的 View
        handler.removeCallbacksAndMessages(null)
        cleanup()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 切换 AI 多模态识别开关，并持久化到 SharedPreferences */
    fun setAiVisionMode(enabled: Boolean) {
        useAiVisionMode = enabled
        getSharedPreferences("translation_config", Context.MODE_PRIVATE)
            .edit().putBoolean("ai_bubble_detection", enabled).apply()
        // 通知 ScreenCaptureService 同步 pluginManager 状态，避免菜单切换后翻译仍用旧模式
        safeCallbackArg(onAiVisionModeChanged, enabled)
    }

    // ==================== 主悬浮球 ====================

    private fun createMainFloatingWindow() {
        try {
            val density = resources.displayMetrics.density
            val height = (40 * density).toInt()
            val width = (80 * density).toInt()

            mainView = FrameLayout(this).apply {
                background = createPillDrawable()
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

    private fun createPillDrawable(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 20 * resources.displayMetrics.density
            gradientType = GradientDrawable.LINEAR_GRADIENT
            orientation = GradientDrawable.Orientation.LEFT_RIGHT
            colors = intArrayOf(0xCC6200EE.toInt(), 0xCC9C27B0.toInt())
            setStroke((1 * resources.displayMetrics.density).toInt(), 0x33FFFFFF)
        }
    }

    private fun updateMainViewAppearance() {
        mainView?.background = createPillDrawable()
        modeTextView?.text = "翻译"
    }

    private fun onTouchMainView(view: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = false
                isTouching = true
                longPressTriggered = false
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
                        try { windowManager?.updateViewLayout(view, p) } catch (e: Exception) {
                            Log.w(TAG, "拖动 updateViewLayout 失败: ${e.message}")
                        }
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                view.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                isTouching = false
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

    /** 菜单行右侧指示器类型 */
    private enum class IndicatorType { CHECKMARK, DOT, NONE }

    private fun showMenu() {
        if (isMenuShowing || isAnimating) return
        val mainViewRef = mainView ?: return
        isAnimating = true
        isMenuShowing = true
        // 兜底：动画链总时长约 350ms，1 秒后强制重置避免 withEndAction 异常不触发导致卡死
        handler.postDelayed(animTimeoutRunnable, 1000)

        // 设置悬浮球中心为缩放支点
        mainViewRef.pivotX = mainViewRef.width / 2f
        mainViewRef.pivotY = mainViewRef.height / 2f

        // 悬浮球消失动画：scale 0.9→0 + alpha 1→0（150ms）
        mainViewRef.animate()
            .scaleX(0f)
            .scaleY(0f)
            .alpha(0f)
            .setDuration(150)
            .withEndAction {
                // 隐藏悬浮球
                mainViewRef.visibility = View.INVISIBLE
                // 准备悬浮球出现动画的初始状态
                mainViewRef.scaleX = 0.9f
                mainViewRef.scaleY = 0.9f
                mainViewRef.alpha = 0f
                try {
                    buildMenuAndShow()
                } catch (e: Exception) {
                    Log.e(TAG, "菜单显示失败: ${e.message}")
                    // 失败时恢复悬浮球显示
                    mainViewRef.visibility = View.VISIBLE
                    mainViewRef.scaleX = 1f
                    mainViewRef.scaleY = 1f
                    mainViewRef.alpha = 1f
                    isMenuShowing = false
                    handler.removeCallbacks(animTimeoutRunnable)
                    isAnimating = false
                }
            }
            .start()
    }

    /** 构建菜单视图并播放菜单出现动画（在悬浮球消失动画结束后调用） */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun buildMenuAndShow() {
        // 截图模式下不显示菜单，恢复悬浮球状态以备 restoreUIAfterCapture
        if (isUIHiddenForCapture) {
            mainView?.scaleX = 1f
            mainView?.scaleY = 1f
            mainView?.alpha = 1f
            isMenuShowing = false
            handler.removeCallbacks(animTimeoutRunnable)
            isAnimating = false
            return
        }
        val d = resources.displayMetrics.density
        val dp12 = (12 * d).toInt()

        // 菜单容器：圆角 20dp + elevation 8dp + 半透明白底 + padding 12dp
        val menuLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 20 * d
                setColor(0xF5FFFFFF.toInt())
                setStroke((1 * d).toInt(), 0x22000000)
            }
            setPadding(dp12, dp12, dp12, dp12)
            elevation = 8 * d
        }

        // 用局部变量持有各行标签/指示器，点击后刷新选中状态
        var aiLabel: TextView? = null
        var aiIndicator: TextView? = null
        var horizontalLabel: TextView? = null
        var horizontalIndicator: TextView? = null
        var verticalLabel: TextView? = null
        var verticalIndicator: TextView? = null

        // 刷新各行选中状态：勾选标记 + 文字颜色/加粗
        fun refreshSelection() {
            val aiOn = useAiVisionMode
            aiLabel?.apply {
                setTextColor(if (aiOn) 0xFF6200EE.toInt() else 0xFF333333.toInt())
                paint.isFakeBoldText = aiOn
            }
            aiIndicator?.text = if (aiOn) "✓" else ""

            val horizontalSelected = recognitionDirection == RecognitionDirection.HORIZONTAL
            horizontalLabel?.apply {
                setTextColor(if (horizontalSelected) 0xFF6200EE.toInt() else 0xFF333333.toInt())
                paint.isFakeBoldText = horizontalSelected
            }
            horizontalIndicator?.text = if (horizontalSelected) "●" else ""

            val verticalSelected = recognitionDirection == RecognitionDirection.VERTICAL
            verticalLabel?.apply {
                setTextColor(if (verticalSelected) 0xFF6200EE.toInt() else 0xFF333333.toInt())
                paint.isFakeBoldText = verticalSelected
            }
            verticalIndicator?.text = if (verticalSelected) "●" else ""
        }

        // Row 1: AI 多模态识别（勾选项，蓝色色条）
        val (r1Label, r1Indicator) = addMenuRow(
            menuLayout,
            "AI 多模态识别",
            "✨",
            0xFF2196F3.toInt(),
            IndicatorType.CHECKMARK,
            d,
        ) {
            setAiVisionMode(!useAiVisionMode)
            Log.d(TAG, "AI 多模态识别切换为: $useAiVisionMode")
            refreshSelection()
        }
        aiLabel = r1Label
        aiIndicator = r1Indicator

        // Row 2: 横向识别（单选，绿色色条）
        val (r2Label, r2Indicator) = addMenuRow(
            menuLayout,
            "横向识别",
            "≡",
            0xFF00C853.toInt(),
            IndicatorType.DOT,
            d,
        ) {
            if (recognitionDirection != RecognitionDirection.HORIZONTAL) {
                recognitionDirection = RecognitionDirection.HORIZONTAL
                safeCallbackArg(onRecognitionDirectionChanged, RecognitionDirection.HORIZONTAL)
                Log.d(TAG, "识别方向切换为: HORIZONTAL")
            }
            refreshSelection()
        }
        horizontalLabel = r2Label
        horizontalIndicator = r2Indicator

        // Row 3: 竖向识别（单选，紫色色条）
        val (r3Label, r3Indicator) = addMenuRow(
            menuLayout,
            "竖向识别",
            "‖",
            0xFF6200EE.toInt(),
            IndicatorType.DOT,
            d,
        ) {
            if (recognitionDirection != RecognitionDirection.VERTICAL) {
                recognitionDirection = RecognitionDirection.VERTICAL
                safeCallbackArg(onRecognitionDirectionChanged, RecognitionDirection.VERTICAL)
                Log.d(TAG, "识别方向切换为: VERTICAL")
            }
            refreshSelection()
        }
        verticalLabel = r3Label
        verticalIndicator = r3Indicator

        // Row 4: 关闭菜单（灰色色条，无指示器）
        addMenuRow(
            menuLayout,
            "关闭菜单",
            "✕",
            0xFF9E9E9E.toInt(),
            IndicatorType.NONE,
            d,
        ) {
            hideMenu()
        }

        // 初始化各行选中状态
        refreshSelection()

        menuView = menuLayout

        // 菜单位置基于悬浮球当前位置（mainParams 的 x, y）
        val mainX = mainParams?.x ?: (16 * d).toInt()
        val mainY = mainParams?.y ?: (300 * d).toInt()
        // 固定宽度避免 WRAP_CONTENT + MATCH_PARENT 子项 + weight=1 在 AT_MOST 约束下测量失控
        menuParams = WindowManager.LayoutParams(
            (220 * d).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            getWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = mainX
            y = mainY + (40 * d).toInt() + (8 * d).toInt()
        }

        // 菜单出现动画初始状态：scale 0.8 + alpha 0
        menuLayout.scaleX = 0.8f
        menuLayout.scaleY = 0.8f
        menuLayout.alpha = 0f

        windowManager?.addView(menuView, menuParams)

        // 监听菜单外部点击：ACTION_OUTSIDE 时关闭菜单
        menuView?.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                hideMenu()
                true
            } else {
                false
            }
        }

        // 菜单出现动画：scale 0.8→1 + alpha 0→1（200ms）
        // 需等 view 完成布局后再设置 pivot，确保以中心为支点缩放
        menuLayout.post {
            menuLayout.pivotX = menuLayout.width / 2f
            menuLayout.pivotY = menuLayout.height / 2f
            menuLayout.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(200)
                .withEndAction {
                    handler.removeCallbacks(animTimeoutRunnable)
                    isAnimating = false
                }
                .start()
        }
    }

    /**
     * 创建一行菜单项：[4dp色条] [16dp间距] [图标] [8dp间距] [文字(weight=1)] [指示器] [16dp右边距]
     * 高度 44dp，行间添加 0.5dp 分隔线（左边距 48dp）。
     * 返回 (标签 TextView, 指示器 TextView)，供外部刷新选中状态。
     */
    private fun addMenuRow(
        container: LinearLayout,
        label: String,
        icon: String,
        barColor: Int,
        indicatorType: IndicatorType,
        d: Float,
        onClick: () -> Unit,
    ): Pair<TextView, TextView> {
        val dp44 = (44 * d).toInt()
        val dp4 = (4 * d).toInt()
        val dp16 = (16 * d).toInt()
        val dp8 = (8 * d).toInt()
        val dp48 = (48 * d).toInt()
        // 0.5dp 分隔线高度，至少 1px 避免在高密度屏上不可见
        val dividerHeight = maxOf(1, (0.5 * d).toInt())

        // 行间分隔线（第一行之前不添加）
        if (container.childCount > 0) {
            val divider = View(this).apply {
                setBackgroundColor(0xFFEEEEEE.toInt())
            }
            val dividerParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dividerHeight,
            ).apply {
                leftMargin = dp48
            }
            container.addView(divider, dividerParams)
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp44
            isClickable = true
            isFocusable = true
        }

        // 左侧 4dp 色条
        val barView = View(this).apply {
            setBackgroundColor(barColor)
        }
        row.addView(
            barView,
            LinearLayout.LayoutParams(dp4, LinearLayout.LayoutParams.MATCH_PARENT),
        )

        // 16dp 间距
        val spacer1 = View(this)
        row.addView(
            spacer1,
            LinearLayout.LayoutParams(dp16, LinearLayout.LayoutParams.WRAP_CONTENT),
        )

        // 图标
        val iconTv = TextView(this).apply {
            text = icon
            textSize = 16f
            setTextColor(0xFF333333.toInt())
            gravity = Gravity.CENTER
        }
        row.addView(
            iconTv,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        // 8dp 间距
        val spacer2 = View(this)
        row.addView(
            spacer2,
            LinearLayout.LayoutParams(dp8, LinearLayout.LayoutParams.WRAP_CONTENT),
        )

        // 文字
        val labelTv = TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(0xFF333333.toInt())
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }
        row.addView(
            labelTv,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )

        // 右侧指示器
        val indicatorTv = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF6200EE.toInt())
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            paint.isFakeBoldText = true
        }
        // 关闭菜单行无指示器
        if (indicatorType == IndicatorType.NONE) {
            indicatorTv.visibility = View.GONE
        }
        row.addView(
            indicatorTv,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        // 16dp 右边距
        val spacer3 = View(this)
        row.addView(
            spacer3,
            LinearLayout.LayoutParams(dp16, LinearLayout.LayoutParams.WRAP_CONTENT),
        )

        row.setOnClickListener { onClick() }
        container.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        return labelTv to indicatorTv
    }

    private fun hideMenu() {
        if (!isMenuShowing || isAnimating) return
        val view = menuView ?: return
        isAnimating = true
        // 兜底：动画链总时长约 350ms，1 秒后强制重置
        handler.postDelayed(animTimeoutRunnable, 1000)

        // 设置菜单中心为缩放支点
        view.pivotX = view.width / 2f
        view.pivotY = view.height / 2f

        // 菜单消失动画：scale 1→0.8 + alpha 1→0（150ms）
        view.animate()
            .scaleX(0.8f)
            .scaleY(0.8f)
            .alpha(0f)
            .setDuration(150)
            .withEndAction {
                // 移除菜单视图
                try { windowManager?.removeViewImmediate(view) } catch (e: Exception) {
                    Log.w(TAG, "hideMenu removeViewImmediate 失败: ${e.message}")
                }
                menuView = null
                menuParams = null
                isMenuShowing = false

                // 悬浮球出现动画：scale 0.9→1 + alpha 0→1（200ms）
                val mv = mainView
                if (mv == null) {
                    handler.removeCallbacks(animTimeoutRunnable)
                    isAnimating = false
                } else if (isUIHiddenForCapture) {
                    // 截图模式下保持悬浮球不可见，仅恢复缩放/透明度状态
                    mv.scaleX = 1f
                    mv.scaleY = 1f
                    mv.alpha = 1f
                    handler.removeCallbacks(animTimeoutRunnable)
                    isAnimating = false
                } else {
                    mv.visibility = View.VISIBLE
                    mv.pivotX = mv.width / 2f
                    mv.pivotY = mv.height / 2f
                    mv.scaleX = 0.9f
                    mv.scaleY = 0.9f
                    mv.alpha = 0f
                    mv.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setDuration(200)
                        .withEndAction {
                            handler.removeCallbacks(animTimeoutRunnable)
                            isAnimating = false
                        }
                        .start()
                }
            }
            .start()
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
        // minSdk 26 后一律使用 TYPE_APPLICATION_OVERLAY
        return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    }

    private fun cleanup() {
        // 1. 先同步移除所有 View（含 menuView），不依赖 hideMenu 动画的 withEndAction 回调，
        //    避免 windowManager 被置空后回调被 ?. 安全跳过，导致 menuView 仍挂在 WindowManager 上
        try { menuView?.let { windowManager?.removeViewImmediate(it) } } catch (e: Exception) {
            Log.w(TAG, "cleanup menuView removeViewImmediate 失败: ${e.message}")
        }
        isMenuShowing = false
        handler.removeCallbacks(animTimeoutRunnable)
        isAnimating = false
        removeAllTranslationOverlays()
        try { mainView?.let { windowManager?.removeViewImmediate(it) } } catch (e: Exception) {
            Log.w(TAG, "cleanup mainView removeViewImmediate 失败: ${e.message}")
        }
        // 2. 清除所有 pending message（含 hideMenu 动画 withEndAction 投递的回调）
        handler.removeCallbacksAndMessages(null)
        longPressRunnable = null
        // 3. 最后 null 化引用
        menuView = null
        menuParams = null
        mainView = null
        modeTextView = null
        mainParams = null
        windowManager = null
    }
}

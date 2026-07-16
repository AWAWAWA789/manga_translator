package com.manga.translator.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.ConditionVariable
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.manga.translator.MangaTranslatorApp
import com.manga.translator.R
import com.manga.translator.di.ServiceLocator
import com.manga.translator.model.TranslationCard
import com.manga.translator.ocr.OcrProcessor
import com.manga.translator.presentation.TranslationController
import com.manga.translator.util.AppLog
import com.manga.translator.util.PerfTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class ScreenCaptureService : Service() {

    companion object {
        const val TAG = "MangaTranslator"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        private const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "screen_capture_channel"
    }

    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = 1080
    private var screenHeight = 1920
    private var screenDensity = 320
    private var realScreenWidth = 1080
    private var realScreenHeight = 1920
    private var scaleFactor = 1.0f
    private var scaleFactorY = 1.0f // Y 方向独立缩放因子（screenHeight 可能被 clamp 到 1920）

    // @Volatile：主线程赋值、executor 线程读取、协程置 null，需保证跨线程可见性
    @Volatile private var ocrProcessor: OcrProcessor? = null

    @Volatile private var pluginManager: TranslationController? = null

    private var handler: Handler? = null

    // 后台 HandlerThread：帧监听器的 Bitmap 转换在后台线程执行，避免阻塞主线程
    private var captureHandlerThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private val executor = java.util.concurrent.ThreadPoolExecutor(
        3,
        3,
        60L,
        java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue(16),
        { r -> Thread(r, "MangaTranslator-Worker") },
        java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy(),
    )

    /**
     * Service 协程作用域：SupervisorJob 防止子协程异常相互取消，Dispatchers.IO 用于阻塞任务。
     * onDestroy 中 cancel()，所有子协程自动取消。
     * 后续任务 2.6/2.7 将把 executor.submit / Thread.start / Handler.postDelayed 逐步迁移至此作用域。
     * 在迁移完成前，executor 与 serviceScope 并存，新代码优先使用 serviceScope。
     */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 安全提交任务到 executor，防止 Service 销毁后 submit 抛 RejectedExecutionException 导致主线程崩溃。
     * DiscardOldestPolicy 在队列满时丢弃最旧任务，但 executor 已 shutdown 时仍会抛 RejectedExecutionException。
     */
    private fun safeSubmit(action: () -> Unit): Boolean {
        if (executor.isShutdown) {
            AppLog.w("ScreenCapture", "executor 已关闭，跳过任务提交")
            return false
        }
        return try {
            executor.submit(action)
            true
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            AppLog.w("ScreenCapture", "任务被拒绝: ${e.message}")
            false
        }
    }

    private val isRunning = AtomicBoolean(false)
    private val isProcessing = AtomicBoolean(false)
    private val isManualTranslating = AtomicBoolean(false)

    // 记录本次翻译流程开始时间，用于检测 isProcessing 卡死（8 秒超时强制重置）
    // AtomicLong 保证跨线程可见性，与 AtomicBoolean isProcessing 一致
    private val processingStartTimeMs = AtomicLong(0L)

    // 帧缓存：OnImageAvailableListener 持续消费帧，始终保持最新帧可用
    @Volatile private var cachedBitmap: Bitmap? = null
    private val cachedBitmapLock = Any()

    @Volatile private var lastFrameTimeMs = 0L

    // 限流：最小帧处理间隔，避免 60fps 全屏 Bitmap 分配导致 OOM
    private val MIN_FRAME_INTERVAL_MS = 200L

    // 帧到达信号：替代 waitForFreshFrame 中的 Thread.sleep 轮询，新帧到达时 open
    private val frameAvailableSignal = ConditionVariable()

    private val imageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        val image = reader.acquireLatestImage() ?: return@OnImageAvailableListener
        try {
            // 限流：距离上一帧处理不足 200ms 则跳过，仅关闭 Image 释放缓冲区
            val now = System.currentTimeMillis()
            if (now - lastFrameTimeMs < MIN_FRAME_INTERVAL_MS) {
                return@OnImageAvailableListener
            }
            val bitmap = imageToBitmap(image)
            if (bitmap != null) {
                if (lastFrameTimeMs == 0L) {
                    AppLog.d("ScreenCapture", "首帧到达: ${image.width}x${image.height}")
                }
                synchronized(cachedBitmapLock) {
                    cachedBitmap?.recycle()
                    cachedBitmap = bitmap
                    lastFrameTimeMs = System.currentTimeMillis()
                }
                // 通知 waitForFreshFrame 有新帧到达
                frameAvailableSignal.open()
            }
        } catch (e: Throwable) {
            // 捕获 Throwable 而非 Exception，防止 OutOfMemoryError 杀死 HandlerThread Looper
            AppLog.e("ScreenCapture", "帧缓存更新失败: ${e.message}")
        } finally {
            image.close()
        }
    }

    override fun onCreate() {
        super.onCreate()
        handler = Handler(Looper.getMainLooper())
        // 创建后台 HandlerThread 用于帧监听器，避免全屏像素拷贝阻塞主线程
        captureHandlerThread = HandlerThread("FrameCapture").also { thread ->
            thread.start()
            captureHandler = Handler(thread.looper)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.d("ScreenCapture", "ScreenCaptureService onStartCommand")

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }

        if (resultCode == 0 || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            startForeground(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            getScreenMetrics()
            ocrProcessor = ServiceLocator.createOcrProcessor()
            pluginManager = ServiceLocator.createTranslationController().apply {
                initialize()
                val preferences = getSharedPreferences("translation_config", MODE_PRIVATE)
                val aiBubbleEnabled = preferences.getBoolean("ai_bubble_detection", false)
                setUseAiVisionMode(aiBubbleEnabled)
                AppLog.d("ScreenCapture", "AI气泡检测: ${if (aiBubbleEnabled) "启用" else "禁用"}")
            }
        } catch (e: Exception) {
            AppLog.e("ScreenCapture", "组件初始化失败: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            createMediaProjection(resultCode, data)
        } catch (e: Exception) {
            AppLog.e("ScreenCapture", "MediaProjection创建失败: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        setupCallbacks()
        isRunning.set(true)
        AppLog.d("ScreenCapture", "服务启动完成")

        return START_STICKY
    }

    private fun setupCallbacks() {
        FloatingWindowService.onManualTranslate = {
            AppLog.d("ScreenCapture", "手动翻译触发")
            executeManualTranslation()
        }

        FloatingWindowService.onRecognitionDirectionChanged = { direction ->
            AppLog.d("ScreenCapture", "识别方向变更: $direction")
            FloatingWindowService.clearAllTranslations()
        }

        FloatingWindowService.onAiVisionModeChanged = { enabled ->
            AppLog.d("ScreenCapture", "AI多模态识别变更: ${if (enabled) "启用" else "禁用"}")
            pluginManager?.setUseAiVisionMode(enabled)
        }
    }

    private fun getScreenMetrics() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        realScreenWidth = metrics.widthPixels
        realScreenHeight = metrics.heightPixels
        // 保持 VirtualDisplay 与实际屏幕的纵横比一致，避免 GPU 合成器
        // 因 swap behavior 不匹配而拒绝生产帧（导致 ImageReader 永远收不到帧）。
        // 历史问题：clamp 到 1080x1920 会把 1080x2400 的屏幕压成 1080x1920，
        // 纵横比变化触发 OpenGLRenderer "Unable to match the desired swap behavior"。
        // 新策略：以宽度为基准等比缩放，保持原始宽高比；仅当宽度超限时才缩放。
        val maxWidth = 1080
        if (metrics.widthPixels > maxWidth) {
            val scale = maxWidth.toFloat() / metrics.widthPixels.toFloat()
            screenWidth = maxWidth
            screenHeight = (metrics.heightPixels * scale).toInt()
            // density 等比缩放，保持 VirtualDisplay 与 Surface 尺寸/dpi 自洽
            screenDensity = (metrics.densityDpi * scale).toInt().coerceAtLeast(120)
        } else {
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            screenDensity = metrics.densityDpi
        }
        scaleFactor = screenWidth.toFloat() / realScreenWidth.toFloat()
        scaleFactorY = screenHeight.toFloat() / realScreenHeight.toFloat()
        AppLog.d(
            "ScreenCapture",
            "屏幕: ${realScreenWidth}x$realScreenHeight, 截图: ${screenWidth}x$screenHeight, density=$screenDensity, scaleX=$scaleFactor scaleY=$scaleFactorY",
        )
    }

    private fun createMediaProjection(resultCode: Int, data: Intent) {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, data) ?: throw Exception("MediaProjection为null")

        // 保存 callback 引用，onDestroy 中 unregisterCallback，避免回调泄漏
        mediaProjectionCallback = object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }
        mediaProjection?.registerCallback(mediaProjectionCallback!!, handler)

        // maxImages=5：更大的缓冲区减少帧丢失，给监听器更多处理时间
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 5)
        // 注册 OnImageAvailableListener：持续消费帧，防止缓冲区满导致 VirtualDisplay 停止生产
        // 使用后台 captureHandler，避免全屏像素拷贝阻塞主线程
        imageReader?.setOnImageAvailableListener(imageAvailableListener, captureHandler)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "MangaTranslator", screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader!!.surface, null, captureHandler,
        ) ?: throw Exception("VirtualDisplay为null")
    }

    // ==================== 帧缓存读取 ====================

    /**
     * 从缓存获取最新帧的副本（调用方负责 recycle）
     */
    private fun getCachedBitmapCopy(): Bitmap? {
        synchronized(cachedBitmapLock) {
            val cached = cachedBitmap ?: return null
            return try {
                Bitmap.createBitmap(cached)
            } catch (e: Throwable) {
                // 捕获 Throwable 防止 OOM 导致调用方崩溃
                AppLog.e("ScreenCapture", "复制缓存帧失败: ${e.message}")
                null
            }
        }
    }

    /**
     * 直接取走缓存帧（置 null，监听器会创建新的）
     * 当 getCachedBitmapCopy 因 OOM 失败时的回退方案
     */
    private fun stealCachedBitmap(): Bitmap? {
        synchronized(cachedBitmapLock) {
            val cached = cachedBitmap
            cachedBitmap = null
            return cached
        }
    }

    /**
     * 等待新帧到达（超时机制）
     * 使用 ConditionVariable 同步，替代旧的 Thread.sleep 轮询：
     * - 新帧到达时 imageAvailableListener 调用 frameAvailableSignal.open()
     * - 此处 block(timeout) 等待通知，避免空转占用 CPU
     * @param timeoutMs 最长等待时间
     * @param requireFresh 是否要求比 beforeTime 更新的帧
     */
    private fun waitForFreshFrame(timeoutMs: Long = 500L, requireFresh: Boolean = true): Bitmap? {
        val beforeTime = lastFrameTimeMs
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            // 满足新鲜度要求时，尝试取帧
            if (!requireFresh || lastFrameTimeMs > beforeTime) {
                Thread.sleep(20) // 等待缓存写入完成
                val copy = getCachedBitmapCopy()
                if (copy != null) return copy
                val stolen = stealCachedBitmap()
                if (stolen != null) {
                    AppLog.w("ScreenCapture", "getCachedBitmapCopy 失败，使用 stealCachedBitmap 回退")
                    return stolen
                }
                // 缓存被取走或为空，继续等待下一帧
                // 关闭信号后阻塞等待新帧到达通知，避免忙等待空转
                frameAvailableSignal.close()
            } else {
                // requireFresh=true 但尚未有新帧，关闭信号后阻塞等待
                frameAvailableSignal.close()
            }
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            // 阻塞等待新帧到达通知，避免空转占用 CPU
            if (!frameAvailableSignal.block(remaining.coerceAtLeast(1L))) break
        }
        // 超时兜底
        val copy = getCachedBitmapCopy()
        if (copy != null) return copy
        return stealCachedBitmap()
    }

    /**
     * 重建截图管线（ImageReader + VirtualDisplay）
     * 当帧缓存长时间无更新时的最终恢复手段
     */
    private fun recreateCapturePipeline() {
        AppLog.w("ScreenCapture", "重建截图管线")
        try {
            // 先解绑监听器，避免旧 ImageReader 在 close 过程中仍回调导致并发问题
            imageReader?.setOnImageAvailableListener(null, null)
            virtualDisplay?.release()
            imageReader?.close()

            // 不清空 cachedBitmap：保留最后一帧作为 fallback，避免等待新帧期间返回 null

            // 检查 HandlerThread 是否存活，若已死亡则重建
            if (captureHandlerThread?.isAlive != true) {
                AppLog.w("ScreenCapture", "FrameCapture HandlerThread 已死亡，重建中...")
                captureHandlerThread?.quitSafely()
                captureHandlerThread = HandlerThread("FrameCapture").also { thread ->
                    thread.start()
                    captureHandler = Handler(thread.looper)
                }
                AppLog.w("ScreenCapture", "HandlerThread 重建完成")
            }

            // MediaProjection 可能在用户撤销权限后失效，重建前先检查
            if (mediaProjection == null) {
                AppLog.e("ScreenCapture", "MediaProjection 已失效，无法重建截图管线")
                return
            }

            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 5)
            imageReader?.setOnImageAvailableListener(imageAvailableListener, captureHandler)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "MangaTranslator", screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader!!.surface, null, captureHandler,
            )

            AppLog.w("ScreenCapture", "截图管线重建完成: ${screenWidth}x$screenHeight@${screenDensity}dpi")
        } catch (e: Exception) {
            AppLog.e("ScreenCapture", "截图管线重建失败: ${e.message}")
        }
    }

    // ==================== 手动翻译（最高优先级）====================

    private fun executeManualTranslation() {
        executeOneShotTranslation()
    }

    private fun executeOneShotTranslation() {
        if (!isRunning.get()) return
        if (!isProcessing.compareAndSet(false, true)) {
            AppLog.w("ScreenCapture", "已有翻译任务进行中，检查是否卡住")
            // Safety: force reset if stuck for too long
            if (System.currentTimeMillis() - processingStartTimeMs.get() > 8000) {
                AppLog.w("ScreenCapture", "isProcessing 卡住超过8秒，强制重置")
                isProcessing.set(false)
                isManualTranslating.set(false)
                restoreFloatingUiAfterCapture()
                // Retry after reset：重试前必须再次校验运行状态，避免服务停止后触发无意义截图
                handler?.postDelayed({
                    if (isRunning.get()) {
                        executeOneShotTranslation()
                    } else {
                        AppLog.d("ScreenCapture", "重试前状态已变更（running=${isRunning.get()}），跳过重试")
                    }
                }, 200)
            }
            return
        }
        processingStartTimeMs.set(System.currentTimeMillis())
        isManualTranslating.set(true)
        FloatingWindowService.clearAllTranslations()
        FloatingWindowService.setStatusText("...")

        FloatingWindowService.hideFloatingUI()

        // Wait until overlay removal is reflected in MediaProjection frames.
        handler?.postDelayed({ doOneShotCapture(0) }, 400L)
    }

    private fun doOneShotCapture(retryCount: Int) {
        if (!isRunning.get() || !isManualTranslating.get()) {
            isManualTranslating.set(false)
            isProcessing.set(false)
            restoreFloatingUiAfterCapture()
            return
        }

        val submitted = safeSubmit {
            try {
                // 诊断日志
                AppLog.d(
                    "ScreenCapture",
                    "doOneShotCapture retry=$retryCount, cachedBitmap=${cachedBitmap != null}, handlerThread alive=${captureHandlerThread?.isAlive}, lastFrameMs=$lastFrameTimeMs",
                )

                // 从帧缓存获取截图（截图前已等待悬浮 UI 隐藏）
                // 重建后首帧可能需要更长等待，逐步延长超时
                val timeoutMs = if (retryCount == 0) 500L else 1000L
                val bitmap = waitForFreshFrame(timeoutMs = timeoutMs, requireFresh = false)

                if (bitmap != null) {
                    AppLog.d("ScreenCapture", "手动翻译截图成功")
                    handler?.post { processImage(bitmap) }
                    return@safeSubmit
                }

                // 缓存为空，尝试重建截图管线后重试
                if (retryCount < 4) {
                    AppLog.d("ScreenCapture", "单次翻译重试 ${retryCount + 1}/4")
                    // 每次重试都检测 HandlerThread 存活状态
                    if (captureHandlerThread?.isAlive != true) {
                        AppLog.w("ScreenCapture", "HandlerThread 已死亡，重建中...")
                        recreateCapturePipeline()
                    } else if (retryCount == 0) {
                        recreateCapturePipeline()
                    }
                    // 重建后给 VirtualDisplay 更多时间渲染首帧（首帧通常需要 500-1000ms）
                    val delayMs = if (retryCount == 0) 800L else 500L
                    handler?.postDelayed({ doOneShotCapture(retryCount + 1) }, delayMs)
                } else {
                    AppLog.e("ScreenCapture", "单次翻译失败：多次重试后仍无图像")
                    handler?.post {
                        isManualTranslating.set(false)
                        isProcessing.set(false)
                        FloatingWindowService.setStatusText("未截取到画面")
                        restoreFloatingUiAfterCapture()
                    }
                }
            } catch (e: Exception) {
                AppLog.e("ScreenCapture", "单次翻译失败: ${e.message}")
                handler?.post {
                    isManualTranslating.set(false)
                    isProcessing.set(false)
                    FloatingWindowService.setStatusText("翻译失败")
                    restoreFloatingUiAfterCapture()
                }
            }
        }
        if (!submitted) {
            // executor 已关闭或任务被拒绝，重置翻译状态
            isManualTranslating.set(false)
            isProcessing.set(false)
            restoreFloatingUiAfterCapture()
        }
    }

    private fun restoreFloatingUiAfterCapture(delayMs: Long = 100L) {
        handler?.postDelayed({
            FloatingWindowService.showFloatingUI()
            FloatingWindowService.updateMainAppearance()
        }, delayMs)
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            // 校验 Image 尺寸与预期屏幕尺寸是否一致，不一致时使用 Image 实际尺寸
            // 计算 rowPadding 与 Bitmap，避免 VirtualDisplay 尺寸变化导致像素错位
            val imageWidth = image.width
            val imageHeight = image.height
            if (imageWidth != screenWidth || imageHeight != screenHeight) {
                AppLog.w("ScreenCapture", "Image 尺寸不匹配: ${imageWidth}x$imageHeight vs 预期 ${screenWidth}x$screenHeight")
            }

            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            // rowPadding 必须用 Image 实际宽度计算，否则尺寸不匹配时会出现负数或像素错位
            val rowPadding = rowStride - pixelStride * imageWidth

            val bitmap = Bitmap.createBitmap(
                imageWidth + rowPadding / pixelStride,
                imageHeight,
                Bitmap.Config.ARGB_8888,
            )
            // copyPixelsFromBuffer 或后续 createBitmap 可能抛 OOM 等异常，
            // 此处用 try-catch 确保中间 bitmap 被回收，避免内存泄漏
            try {
                bitmap.copyPixelsFromBuffer(buffer)
            } catch (e: Throwable) {
                bitmap.recycle()
                throw e
            }

            if (rowPadding == 0) {
                bitmap
            } else {
                try {
                    val cropped = Bitmap.createBitmap(bitmap, 0, 0, imageWidth, imageHeight)
                    bitmap.recycle()
                    cropped
                } catch (e: Throwable) {
                    bitmap.recycle()
                    throw e
                }
            }
        } catch (e: Throwable) {
            AppLog.e("ScreenCapture", "图片转换失败: ${e.message}")
            null
        }
    }

    /**
     * OCR + 翻译（统一处理，确保状态正确重置）
     */
    private fun doOcrAndTranslate(bitmap: Bitmap): Boolean {
        try {
            // pluginManager 可能在异步 cleanupResources 中被置 null，
            // 此分支必须 recycle bitmap，否则调用方依赖此函数回收会导致泄漏
            val plugin = pluginManager
            if (plugin == null) {
                try {
                    bitmap.recycle()
                } catch (_: Exception) {
                }
                return false
            }

            AppLog.d("ScreenCapture", "开始OCR+翻译处理")
            FloatingWindowService.setStatusText("识")

            val direction = FloatingWindowService.recognitionDirection
            val translationCards = plugin.translateImage(
                bitmap,
                emptyList(),
                direction == FloatingWindowService.Companion.RecognitionDirection.VERTICAL,
                isManual = true,
            )
            bitmap.recycle()

            if (translationCards.isEmpty()) {
                AppLog.d("ScreenCapture", "未识别到文本或翻译结果为空")
                return false
            }

            AppLog.d("ScreenCapture", "翻译完成，获得 ${translationCards.size} 个结果")

            val debugData = plugin.getLastDebugData()

            val results = translationCards.mapNotNull { card ->
                val scaledRect = Rect(
                    (card.sourceRect.left / scaleFactor).toInt(),
                    (card.sourceRect.top / scaleFactorY).toInt(),
                    (card.sourceRect.right / scaleFactor).toInt(),
                    (card.sourceRect.bottom / scaleFactorY).toInt(),
                )
                TranslationCard(
                    originalText = card.originalText,
                    translatedText = card.translatedText,
                    sourceRect = scaledRect,
                    isVertical = card.isVertical,
                )
            }.filter { result ->
                val translated = result.translatedText.trim()
                translated.length >= 2 &&
                    !translated.startsWith("翻译失败") &&
                    !translated.startsWith("翻译错误") &&
                    !translated.matches(Regex("^[\\p{Punct}\\s]+$"))
            }

            if (results.isNotEmpty()) {
                FloatingWindowService.showTranslationsWithDebug(this, results, debugData)
                AppLog.d("ScreenCapture", "显示 ${results.size} 个翻译结果")
                return true
            } else {
                AppLog.d("ScreenCapture", "没有有效的翻译结果")
                return false
            }
        } catch (e: Exception) {
            AppLog.e("ScreenCapture", "处理失败: ${e.message}")
            try { bitmap.recycle() } catch (_: Exception) {}
            return false
        }
    }

    /**
     * 手动翻译专用处理（在当前线程执行，确保状态正确）
     */
    private fun processImage(bitmap: Bitmap) {
        // executor 可能已在 onDestroy 中被 shutdown，
        // 此时 safeSubmit 返回 false，需 recycle bitmap 并重置状态。
        // 前置检查避免不必要的 submit 尝试，TOCTOU 由 safeSubmit 返回值兜底。
        if (executor.isShutdown) {
            AppLog.w("ScreenCapture", "processImage: executor 已关闭，直接 recycle bitmap")
            try { bitmap.recycle() } catch (_: Exception) {}
            isProcessing.set(false)
            isManualTranslating.set(false)
            return
        }
        val submitted = safeSubmit {
            var completionStatusScheduled = false
            val tracker = PerfTracker.start("翻译", "一次翻译流程")
            try {
                handler?.post { FloatingWindowService.setStatusText("识别中") }
                val shown = doOcrAndTranslate(bitmap)
                tracker.end("OCR+翻译+渲染")
                if (shown) {
                    completionStatusScheduled = true
                    handler?.postDelayed({
                        FloatingWindowService.showFloatingUI()
                        FloatingWindowService.setStatusText("完成")
                        handler?.postDelayed({ FloatingWindowService.updateMainAppearance() }, 1200L)
                    }, 100L)
                } else {
                    handler?.post { FloatingWindowService.setStatusText("无结果") }
                    handler?.postDelayed({ FloatingWindowService.updateMainAppearance() }, 1200L)
                }
            } catch (e: Exception) {
                AppLog.e("ScreenCapture", "单次翻译处理失败: ${e.message}")
                handler?.post { FloatingWindowService.setStatusText("失败") }
                handler?.postDelayed({ FloatingWindowService.updateMainAppearance() }, 1200L)
            } finally {
                tracker.finish()
                isProcessing.set(false)
                isManualTranslating.set(false)
                if (!completionStatusScheduled) restoreFloatingUiAfterCapture()
            }
        }
        if (!submitted) {
            // TOCTOU：检查与 submit 之间 executor 被关闭，recycle bitmap 并重置状态
            try { bitmap.recycle() } catch (_: Exception) {}
            isProcessing.set(false)
            isManualTranslating.set(false)
        }
    }

    // ==================== 通知 ====================

    private fun createNotificationChannel() {
        // minSdk 26 后通知渠道一律必须创建，无需版本判断
        val channel = NotificationChannel(CHANNEL_ID, "屏幕翻译服务", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        createNotificationChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("漫画翻译器")
            .setContentText("正在运行...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        AppLog.d("ScreenCapture", "服务销毁")
        isRunning.set(false)
        isManualTranslating.set(false)

        // 取消所有子协程，避免 Service 销毁后协程仍在运行
        serviceScope.cancel()

        // 清除待执行消息，避免销毁后 Runnable 访问已释放资源
        handler?.removeCallbacksAndMessages(null)

        // 同步释放截图管线（快操作），防止进程被杀时 native 资源泄漏
        releaseCapturePipeline()

        // 异步释放 executor + native 插件（慢操作），避免主线程 ANR
        MangaTranslatorApp.appScope.launch { releaseNativePlugins() }

        // 清理静态回调引用，避免持有已销毁 Service 导致泄漏
        FloatingWindowService.onManualTranslate = null
        FloatingWindowService.onRecognitionDirectionChanged = null
        FloatingWindowService.onAiVisionModeChanged = null

        super.onDestroy()
    }

    /**
     * 同步释放截图管线（MediaProjection/VirtualDisplay/ImageReader/帧缓存）。
     *
     * 这些操作耗时短（<10ms），在 onDestroy 主线程同步执行，
     * 防止进程被系统杀死时 native 资源泄漏。
     */
    private fun releaseCapturePipeline() {
        // 停止后台 HandlerThread，短超时 join 避免主线程阻塞
        captureHandlerThread?.quitSafely()
        try {
            captureHandlerThread?.join(200)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        captureHandlerThread = null
        captureHandler = null

        // 每个资源独立 safeClose，避免任一 close 抛异常导致后续资源不释放
        safeClose("imageReader") { imageReader?.close() }
        imageReader = null
        safeClose("virtualDisplay") { virtualDisplay?.release() }
        virtualDisplay = null
        safeClose("mediaProjection.unregisterCallback") {
            mediaProjectionCallback?.let { mediaProjection?.unregisterCallback(it) }
        }
        mediaProjectionCallback = null
        safeClose("mediaProjection") { mediaProjection?.stop() }
        mediaProjection = null

        // 清理帧缓存
        synchronized(cachedBitmapLock) {
            cachedBitmap?.recycle()
            cachedBitmap = null
        }
    }

    /**
     * 异步释放 native 插件资源（executor/PluginManager/OcrProcessor）。
     *
     * executor.awaitTermination 最长 5s，不能在主线程执行。
     * 必须在 [releaseCapturePipeline] 之后执行，确保截图管线已停止。
     * native 资源释放必须在 executor 任务全部完成后，避免 SIGSEGV。
     */
    private fun releaseNativePlugins() {
        executor.shutdown()
        try {
            if (!executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                AppLog.w("ScreenCapture", "executor 未在 5 秒内终止，强制 shutdownNow")
                executor.shutdownNow()
                executor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)
            }
        } catch (e: InterruptedException) {
            AppLog.w("ScreenCapture", "awaitTermination 被中断: ${e.message}")
            Thread.currentThread().interrupt()
        }

        safeClose("pluginManager") { pluginManager?.close() }
        pluginManager = null
        safeClose("ocrProcessor") { ocrProcessor?.close() }
        ocrProcessor = null
    }

    /** 安全关闭资源：捕获异常并记日志，避免单资源失败阻断后续资源释放 */
    private inline fun safeClose(tag: String, action: () -> Unit) {
        try {
            action()
        } catch (e: Throwable) {
            AppLog.w("ScreenCapture", "$tag 关闭失败: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

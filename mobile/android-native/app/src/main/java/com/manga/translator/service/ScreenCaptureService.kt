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
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.manga.translator.R
import com.manga.translator.debug.DebugOverlayData
import com.manga.translator.model.TranslationCard
import com.manga.translator.ocr.OcrProcessor
import com.manga.translator.plugin.PluginManager
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : Service() {

    companion object {
        const val TAG = "MangaTranslator"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "screen_capture_channel"
        private const val STABLE_DELAY_MS = 200L
        private const val CAPTURE_INTERVAL_MS = 150L
        private const val REQUIRED_STABLE_FRAMES = 2
        private const val REQUIRED_MOVING_FRAMES = 2
        private const val OVERLAY_CLEAR_DELAY_MS = 400L
        private const val AUTO_COOLDOWN_MS = 2000L
        private const val HASH_SAMPLE_STEP = 20  // 降低采样步长，提高检测精度
        private const val HASH_CHANGE_THRESHOLD = 0.012f
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = 1080
    private var screenHeight = 1920
    private var screenDensity = 320
    private var realScreenWidth = 1080
    private var realScreenHeight = 1920
    private var scaleFactor = 1.0f
    private var scaleFactorY = 1.0f  // Y 方向独立缩放因子（screenHeight 可能被 clamp 到 1920）

    private var ocrProcessor: OcrProcessor? = null
    private var pluginManager: PluginManager? = null
    private var handler: Handler? = null
    // 后台 HandlerThread：帧监听器的 Bitmap 转换在后台线程执行，避免阻塞主线程
    private var captureHandlerThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private val executor = java.util.concurrent.ThreadPoolExecutor(
        3, 3, 60L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue(16),
        { r -> Thread(r, "MangaTranslator-Worker") },
        java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy()
    )

    private val isRunning = AtomicBoolean(false)
    private val isProcessing = AtomicBoolean(false)
    private val isRealtimeEnabled = AtomicBoolean(false)
    private val isManualTranslating = AtomicBoolean(false)

    private var lastFrameHash: Long = 0L
    private var isScreenStable = false
    private var stableRunnable: Runnable? = null
    private var captureLoopRunnable: Runnable? = null
    private var lastTranslatedHash: Long = 0L
    private var lastTranslationTimeMs = 0L
    private var lastTranslationRects = mutableListOf<Rect>()
    private var stableFrameCount = 0
    private var movingFrameCount = 0
    @Volatile private var screenChangeVersion = 0L
    // 翻译覆盖层显示期间抑制变化检测，防止覆盖层出现/消失被误判为画面变化
    @Volatile private var suppressChangeDetection = false

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
                    Log.d(TAG, "首帧到达: ${image.width}x${image.height}")
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
            Log.e(TAG, "帧缓存更新失败: ${e.message}")
        } finally {
            image.close()
        }
    }

    override fun onCreate() {
        super.onCreate()
        handler = Handler(Looper.getMainLooper())
        // 创建后台 HandlerThread 用于帧监听器，避免全屏像素拷贝阻塞主线程
        captureHandlerThread = HandlerThread("FrameCapture").also { thread -> thread.start(); captureHandler = Handler(thread.looper) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "ScreenCaptureService onStartCommand")

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }

        if (resultCode == 0 || data == null) {
            stopSelf(); return START_NOT_STICKY
        }

        try {
            startForeground(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            stopSelf(); return START_NOT_STICKY
        }

        try {
            getScreenMetrics()
            ocrProcessor = OcrProcessor(this)
            pluginManager = PluginManager(this).apply { 
                initialize()
                val preferences = getSharedPreferences("translation_config", MODE_PRIVATE)
                val aiBubbleEnabled = preferences.getBoolean("ai_bubble_detection", false)
                setUseAiVisionMode(aiBubbleEnabled)
                Log.d(TAG, "AI气泡检测: ${if (aiBubbleEnabled) "启用" else "禁用"}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "组件初始化失败: ${e.message}")
            stopSelf(); return START_NOT_STICKY
        }

        try {
            createMediaProjection(resultCode, data)
        } catch (e: Exception) {
            Log.e(TAG, "MediaProjection创建失败: ${e.message}")
            stopSelf(); return START_NOT_STICKY
        }

        setupCallbacks()
        isRunning.set(true)
        Log.d(TAG, "服务启动完成")

        return START_STICKY
    }

    private fun setupCallbacks() {
        FloatingWindowService.onModeChanged = { mode ->
            when (mode) {
                FloatingWindowService.Companion.TranslateMode.REALTIME -> {
                    Log.d(TAG, "切换到实时翻译模式")
                    isRealtimeEnabled.set(true)
                    isManualTranslating.set(false)
                    resetState()
                    startContinuousCapture()
                    handler?.postDelayed({
                        if (isRunning.get() && isRealtimeEnabled.get() && !FloatingWindowService.isPaused) {
                            FloatingWindowService.setStatusText("...")
                            executeOneShotTranslation(isManual = false)
                        }
                    }, 500L)
                }
                FloatingWindowService.Companion.TranslateMode.MANUAL -> {
                    Log.d(TAG, "切换到手动翻译模式")
                    isRealtimeEnabled.set(false)
                    isManualTranslating.set(false)
                    stopContinuousCapture()
                    FloatingWindowService.updateMainAppearance()
                }
            }
        }

        FloatingWindowService.onPauseChanged = { paused ->
            Log.d(TAG, "暂停状态: $paused")
            if (paused) {
                isRealtimeEnabled.set(false)
                stopContinuousCapture()
            } else {
                if (FloatingWindowService.currentMode == FloatingWindowService.Companion.TranslateMode.REALTIME) {
                    isRealtimeEnabled.set(true)
                    resetState()
                    startContinuousCapture()
                }
            }
        }

        FloatingWindowService.onTouchStateChanged = { touching ->
            // 只在实时模式下处理触摸状态变化
            if (isRealtimeEnabled.get()) {
                if (touching) {
                    isScreenStable = false
                    stableFrameCount = 0
                    movingFrameCount = 0
                    screenChangeVersion++
                    cancelPendingTranslation()
                } else {
                    isScreenStable = false
                    stableFrameCount = 0
                }
            }
        }

        FloatingWindowService.onManualTranslate = {
            Log.d(TAG, "手动翻译触发")
            executeManualTranslation()
        }
        
        FloatingWindowService.onRecognitionDirectionChanged = { direction ->
            Log.d(TAG, "识别方向变更: $direction")
            FloatingWindowService.clearAllTranslations()
            if (isRealtimeEnabled.get() && !FloatingWindowService.isPaused) {
                resetState()
                scheduleStableCapture()
            }
        }
    }

    private fun resetState() {
        lastFrameHash = 0L
        isScreenStable = false
        stableFrameCount = 0
        movingFrameCount = 0
        screenChangeVersion++
        lastTranslatedHash = 0L
        cancelPendingTranslation()
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
        Log.d(TAG, "屏幕: ${realScreenWidth}x${realScreenHeight}, 截图: ${screenWidth}x${screenHeight}, density=$screenDensity, scaleX=$scaleFactor scaleY=$scaleFactorY")
    }

    private fun createMediaProjection(resultCode: Int, data: Intent) {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, data) ?: throw Exception("MediaProjection为null")

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, handler)

        // maxImages=5：更大的缓冲区减少帧丢失，给监听器更多处理时间
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 5)
        // 注册 OnImageAvailableListener：持续消费帧，防止缓冲区满导致 VirtualDisplay 停止生产
        // 使用后台 captureHandler，避免全屏像素拷贝阻塞主线程
        imageReader?.setOnImageAvailableListener(imageAvailableListener, captureHandler)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "MangaTranslator", screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader!!.surface, null, captureHandler
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
                Log.e(TAG, "复制缓存帧失败: ${e.message}")
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
                    Log.w(TAG, "getCachedBitmapCopy 失败，使用 stealCachedBitmap 回退")
                    return stolen
                }
                // 缓存被取走或为空，继续等待下一帧
            }
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            // 关闭信号以便下次 block；close 后再次校验新鲜度，避免错过 close 与 block 之间到达的帧
            frameAvailableSignal.close()
            if (!requireFresh || lastFrameTimeMs > beforeTime) continue
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
        Log.w(TAG, "重建截图管线")
        try {
            // 先解绑监听器，避免旧 ImageReader 在 close 过程中仍回调导致并发问题
            imageReader?.setOnImageAvailableListener(null, null)
            virtualDisplay?.release()
            imageReader?.close()

            // 不清空 cachedBitmap：保留最后一帧作为 fallback，避免等待新帧期间返回 null

            // 检查 HandlerThread 是否存活，若已死亡则重建
            if (captureHandlerThread?.isAlive != true) {
                Log.w(TAG, "FrameCapture HandlerThread 已死亡，重建中...")
                captureHandlerThread?.quitSafely()
                captureHandlerThread = HandlerThread("FrameCapture").also { thread -> thread.start(); captureHandler = Handler(thread.looper) }
                Log.w(TAG, "HandlerThread 重建完成")
            }

            // MediaProjection 可能在用户撤销权限后失效，重建前先检查
            if (mediaProjection == null) {
                Log.e(TAG, "MediaProjection 已失效，无法重建截图管线")
                return
            }

            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 5)
            imageReader?.setOnImageAvailableListener(imageAvailableListener, captureHandler)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "MangaTranslator", screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader!!.surface, null, captureHandler
            )

            Log.w(TAG, "截图管线重建完成: ${screenWidth}x${screenHeight}@${screenDensity}dpi")
        } catch (e: Exception) {
            Log.e(TAG, "截图管线重建失败: ${e.message}")
        }
    }

    // ==================== 手动翻译（最高优先级）====================

    private fun executeManualTranslation() {
        executeOneShotTranslation(isManual = true)
    }

    private fun executeOneShotTranslation(isManual: Boolean) {
        if (!isRunning.get()) return
        if (!isProcessing.compareAndSet(false, true)) {
            Log.w(TAG, "已有翻译任务进行中，检查是否卡住")
            // Safety: force reset if stuck for too long
            if (System.currentTimeMillis() - lastTranslationTimeMs > 8000) {
                Log.w(TAG, "isProcessing 卡住超过8秒，强制重置")
                isProcessing.set(false)
                isManualTranslating.set(false)
                restoreFloatingUiAfterCapture()
                // Retry after reset：重试前必须再次校验运行/暂停状态，避免在用户暂停或服务停止后触发无意义截图
                handler?.postDelayed({
                    if (isRunning.get() && !FloatingWindowService.isPaused) {
                        executeOneShotTranslation(isManual)
                    } else {
                        Log.d(TAG, "重试前状态已变更（running=${isRunning.get()}, paused=${FloatingWindowService.isPaused}），跳过重试")
                    }
                }, 200)
            }
            return
        }
        isManualTranslating.set(isManual)
        cancelPendingTranslation()
        FloatingWindowService.clearAllTranslations()
        FloatingWindowService.setStatusText("...")

        FloatingWindowService.hideFloatingUI()

        // Wait until overlay removal is reflected in MediaProjection frames.
        handler?.postDelayed({ doOneShotCapture(0, isManual) }, OVERLAY_CLEAR_DELAY_MS)
    }

    private fun doOneShotCapture(retryCount: Int, isManual: Boolean) {
        if (!isRunning.get() || (isManual && !isManualTranslating.get())) {
            isManualTranslating.set(false)
            isProcessing.set(false)
            restoreFloatingUiAfterCapture()
            return
        }

        executor.submit {
            try {
                // 诊断日志
                Log.d(TAG, "doOneShotCapture retry=$retryCount, cachedBitmap=${cachedBitmap != null}, handlerThread alive=${captureHandlerThread?.isAlive}, lastFrameMs=$lastFrameTimeMs")

                // 从帧缓存获取截图（OVERLAY_CLEAR_DELAY_MS 已在调用前等待）
                // 重建后首帧可能需要更长等待，逐步延长超时
                val timeoutMs = if (retryCount == 0) 500L else 1000L
                val bitmap = waitForFreshFrame(timeoutMs = timeoutMs, requireFresh = false)

                if (bitmap != null) {
                    Log.d(TAG, if (isManual) "手动翻译截图成功" else "自动单次翻译截图成功")
                    handler?.post { processImage(bitmap, isManual) }
                    return@submit
                }

                // 缓存为空，尝试重建截图管线后重试
                if (retryCount < 4) {
                    Log.d(TAG, "单次翻译重试 ${retryCount + 1}/4")
                    // 每次重试都检测 HandlerThread 存活状态
                    if (captureHandlerThread?.isAlive != true) {
                        Log.w(TAG, "HandlerThread 已死亡，重建中...")
                        recreateCapturePipeline()
                    } else if (retryCount == 0) {
                        recreateCapturePipeline()
                    }
                    // 重建后给 VirtualDisplay 更多时间渲染首帧（首帧通常需要 500-1000ms）
                    val delayMs = if (retryCount == 0) 800L else 500L
                    handler?.postDelayed({ doOneShotCapture(retryCount + 1, isManual) }, delayMs)
                } else {
                    Log.e(TAG, "单次翻译失败：多次重试后仍无图像")
                    handler?.post {
                        isManualTranslating.set(false)
                        isProcessing.set(false)
                        FloatingWindowService.setStatusText("未截取到画面")
                        restoreFloatingUiAfterCapture()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "单次翻译失败: ${e.message}")
                handler?.post {
                    isManualTranslating.set(false)
                    isProcessing.set(false)
                    FloatingWindowService.setStatusText("翻译失败")
                    restoreFloatingUiAfterCapture()
                }
            }
        }
    }

    private fun restoreFloatingUiAfterCapture(delayMs: Long = 100L) {
        handler?.postDelayed({
            FloatingWindowService.showFloatingUI()
            FloatingWindowService.updateMainAppearance()
        }, delayMs)
    }

    // ==================== 持续截图 + 画面变化检测 ====================

    private fun startContinuousCapture() {
        stopContinuousCapture()
        Log.d(TAG, "启动持续截图")
        val runnable = object : Runnable {
            override fun run() {
                if (!isRunning.get()) return
                if (!isManualTranslating.get() && !FloatingWindowService.isPaused && isRealtimeEnabled.get()) {
                    checkScreenChange()
                }
                handler?.postDelayed(this, CAPTURE_INTERVAL_MS)
            }
        }
        captureLoopRunnable = runnable
        handler?.post(runnable)
    }

    private fun stopContinuousCapture() {
        Log.d(TAG, "停止持续截图")
        captureLoopRunnable?.let { handler?.removeCallbacks(it) }
        captureLoopRunnable = null
        cancelPendingTranslation()
    }

    private fun checkScreenChange() {
        if (!isRunning.get() || isManualTranslating.get()) return

        try {
            // 直接在缓存 Bitmap 上计算哈希，避免每 150ms 创建全屏 Bitmap 副本
            val currentHash = synchronized(cachedBitmapLock) {
                val cached = cachedBitmap ?: return
                computePixelHash(cached)
            }
            
            // 翻译覆盖层显示期间，仅更新基线哈希，不检测变化
            // 防止覆盖层出现/消失被误判为画面变化导致气泡被清除
            if (suppressChangeDetection) {
                lastFrameHash = currentHash
                return
            }
            
            if (lastFrameHash == 0L) {
                lastFrameHash = currentHash
                stableFrameCount = 1
                return
            }
            val changed = isFrameChanged(lastFrameHash, currentHash)

            if (changed) {
                isScreenStable = false
                stableFrameCount = 0
                movingFrameCount++
                if (movingFrameCount >= REQUIRED_MOVING_FRAMES) {
                    screenChangeVersion++
                    cancelPendingTranslation()
                    FloatingWindowService.clearAllTranslations()
                }
            } else {
                movingFrameCount = 0
                stableFrameCount++
                if (!isScreenStable && stableFrameCount >= REQUIRED_STABLE_FRAMES) {
                    isScreenStable = true
                }
                val inCooldown = System.currentTimeMillis() - lastTranslationTimeMs < AUTO_COOLDOWN_MS
                if (isScreenStable && currentHash != lastTranslatedHash && stableRunnable == null && !isProcessing.get() && !inCooldown) {
                    scheduleStableCapture(screenChangeVersion, currentHash)
                }
            }

            lastFrameHash = currentHash
        } catch (_: Exception) {}
    }

    /**
     * 批量读取像素（性能优化：比逐个getPixel快10倍+）
     */
    private fun computePixelHash(bitmap: Bitmap): Long {
        var hash = 0L
        val step = HASH_SAMPLE_STEP
        val w = bitmap.width
        val h = bitmap.height
        val rowPixels = IntArray(w)
        for (y in 0 until h step step) {
            bitmap.getPixels(rowPixels, 0, w, 0, y, w, 1)
            for (x in 0 until w step step) {
                hash = hash * 31 + (rowPixels[x] and 0xFF)
            }
        }
        return hash
    }

    private fun isFrameChanged(oldHash: Long, newHash: Long): Boolean {
        return oldHash != newHash
    }

    private fun scheduleStableCapture(expectedVersion: Long = screenChangeVersion, expectedHash: Long = 0L) {
        cancelPendingTranslation()
        stableRunnable = Runnable {
            stableRunnable = null
            if (isRunning.get() && !FloatingWindowService.isPaused && !isManualTranslating.get() && screenChangeVersion == expectedVersion) {
                captureAndProcess(expectedVersion, expectedHash)
            }
        }
        handler?.postDelayed(stableRunnable!!, STABLE_DELAY_MS)
    }

    private fun cancelPendingTranslation() {
        stableRunnable?.let { handler?.removeCallbacks(it) }
        stableRunnable = null
    }

    // ==================== 自动截图处理 ====================

    private fun captureAndProcess(expectedVersion: Long, expectedHash: Long) {
        if (!isRunning.get() || FloatingWindowService.isPaused || isManualTranslating.get()) return
        if (System.currentTimeMillis() - lastTranslationTimeMs < AUTO_COOLDOWN_MS) return
        if (!isProcessing.compareAndSet(false, true)) return
        FloatingWindowService.hideFloatingUI()

        executor.submit {
            try {
                if (screenChangeVersion != expectedVersion) return@submit
                Thread.sleep(OVERLAY_CLEAR_DELAY_MS)
                if (screenChangeVersion != expectedVersion) return@submit
                // 从帧缓存获取截图
                val bitmap = waitForFreshFrame(timeoutMs = 500L, requireFresh = false)
                if (bitmap != null) {
                    val currentHash = computePixelHash(bitmap)
                    if (expectedHash != 0L && currentHash != expectedHash) {
                        screenChangeVersion++
                        isScreenStable = false
                        stableFrameCount = 0
                        bitmap.recycle()
                        return@submit
                    }
                    // doOcrAndTranslate 内部负责 recycle bitmap，此处不再回收
                    val shown = doOcrAndTranslate(bitmap, expectedVersion)
                    if (shown) {
                        lastTranslatedHash = currentHash
                        lastTranslationTimeMs = System.currentTimeMillis()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "截图失败: ${e.message}")
            } finally {
                isProcessing.set(false)
                restoreFloatingUiAfterCapture()
            }
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride, screenHeight, Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            if (rowPadding == 0) bitmap
            else {
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
                bitmap.recycle(); cropped
            }
        } catch (e: Throwable) {
            Log.e(TAG, "图片转换失败: ${e.message}"); null
        }
    }

    /**
     * OCR + 翻译（统一处理，确保状态正确重置）
     */
    private fun doOcrAndTranslate(bitmap: Bitmap, expectedVersion: Long = screenChangeVersion, allowManualResult: Boolean = false): Boolean {
        try {
            val plugin = pluginManager ?: return false

            Log.d(TAG, "开始OCR+翻译处理")
            if (allowManualResult) FloatingWindowService.setStatusText("识")

            val direction = FloatingWindowService.recognitionDirection
            val filterRects = if (allowManualResult) emptyList() else lastTranslationRects
            val translationCards = plugin.translateImage(bitmap, filterRects, direction == FloatingWindowService.Companion.RecognitionDirection.VERTICAL, isManual = allowManualResult)
            bitmap.recycle()

            if ((!allowManualResult && screenChangeVersion != expectedVersion) || (!allowManualResult && FloatingWindowService.isPaused) || (!allowManualResult && isManualTranslating.get())) {
                Log.d(TAG, "画面已变化，丢弃过期翻译结果")
                return false
            }

            if (translationCards.isEmpty()) {
                Log.d(TAG, "未识别到文本或翻译结果为空")
                return false
            }

            Log.d(TAG, "翻译完成，获得 ${translationCards.size} 个结果")

            val debugData = plugin.getLastDebugData()

            val results = translationCards.mapNotNull { card ->
                val scaledRect = Rect(
                    (card.sourceRect.left / scaleFactor).toInt(),
                    (card.sourceRect.top / scaleFactorY).toInt(),
                    (card.sourceRect.right / scaleFactor).toInt(),
                    (card.sourceRect.bottom / scaleFactorY).toInt()
                )
                TranslationCard(
                    originalText = card.originalText,
                    translatedText = card.translatedText,
                    sourceRect = scaledRect,
                    isVertical = card.isVertical
                )
            }.filter { result ->
                val translated = result.translatedText.trim()
                translated.length >= 2 &&
                    !translated.startsWith("翻译失败") &&
                    !translated.startsWith("翻译错误") &&
                    !translated.matches(Regex("^[\\p{Punct}\\s]+$"))
            }

            if (results.isNotEmpty()) {
                // 抑制变化检测：覆盖层即将出现，防止被误判为画面变化导致气泡被清除
                suppressChangeDetection = true
                handler?.postDelayed({ suppressChangeDetection = false }, 800L)
                
                FloatingWindowService.showTranslationsWithDebug(this, results, debugData)
                lastTranslationRects.clear()
                lastTranslationRects.addAll(results.map { it.sourceRect })
                Log.d(TAG, "显示 ${results.size} 个翻译结果")
                return true
            } else {
                Log.d(TAG, "没有有效的翻译结果")
                return false
            }

        } catch (e: Exception) {
            Log.e(TAG, "处理失败: ${e.message}")
            try { bitmap.recycle() } catch (_: Exception) {}
            return false
        }
    }

    /**
     * 手动翻译专用处理（在当前线程执行，确保状态正确）
     */
    private fun processImage(bitmap: Bitmap, isManual: Boolean) {
        executor.submit {
            var completionStatusScheduled = false
            try {
                handler?.post { FloatingWindowService.setStatusText("识别中") }
                val shown = doOcrAndTranslate(bitmap, allowManualResult = isManual)
                if (shown) {
                    lastTranslationTimeMs = System.currentTimeMillis()
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
                Log.e(TAG, "单次翻译处理失败: ${e.message}")
                handler?.post { FloatingWindowService.setStatusText("失败") }
                handler?.postDelayed({ FloatingWindowService.updateMainAppearance() }, 1200L)
            } finally {
                isProcessing.set(false)
                isManualTranslating.set(false)
                if (!completionStatusScheduled) restoreFloatingUiAfterCapture()
            }
        }
    }

    // ==================== 通知 ====================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "屏幕翻译服务", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
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
        Log.d(TAG, "服务销毁")
        isRunning.set(false)
        isRealtimeEnabled.set(false)
        isManualTranslating.set(false)
        stopContinuousCapture()
        executor.shutdown()
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()

        // 清理帧缓存
        synchronized(cachedBitmapLock) {
            cachedBitmap?.recycle()
            cachedBitmap = null
        }

        // 清理后台 HandlerThread
        captureHandlerThread?.quitSafely()
        captureHandlerThread = null
        captureHandler = null

        lastFrameHash = 0L

        pluginManager?.close()
        pluginManager = null

        FloatingWindowService.onModeChanged = null
        FloatingWindowService.onManualTranslate = null
        FloatingWindowService.onPauseChanged = null
        FloatingWindowService.onTouchStateChanged = null
        FloatingWindowService.onRecognitionDirectionChanged = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

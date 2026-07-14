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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.manga.translator.R
import com.manga.translator.debug.DebugOverlayData
import com.manga.translator.ocr.OcrProcessor
import com.manga.translator.plugin.PluginManager
import java.util.concurrent.Executors
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

    private var ocrProcessor: OcrProcessor? = null
    private var pluginManager: PluginManager? = null
    private var handler: Handler? = null
    private val executor = Executors.newFixedThreadPool(3) { r -> Thread(r, "MangaTranslator-Worker") }

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

    override fun onCreate() {
        super.onCreate()
        handler = Handler(Looper.getMainLooper())
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
        screenWidth = minOf(metrics.widthPixels, 1080)
        screenHeight = minOf(metrics.heightPixels, 1920)
        screenDensity = metrics.densityDpi
        scaleFactor = screenWidth.toFloat() / realScreenWidth.toFloat()
    }

    private fun createMediaProjection(resultCode: Int, data: Intent) {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, data) ?: throw Exception("MediaProjection为null")

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, handler)

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "MangaTranslator", screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader!!.surface, null, handler
        ) ?: throw Exception("VirtualDisplay为null")
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
                // Retry after reset
                handler?.postDelayed({ executeOneShotTranslation(isManual) }, 200)
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
                // Flush one stale frame from buffer
                synchronized(imageReader ?: Object()) { imageReader?.acquireLatestImage()?.close() }
                Thread.sleep(33)

                val image = synchronized(imageReader ?: Object()) { imageReader?.acquireLatestImage() }
                if (image != null) {
                    var bitmap: Bitmap? = null
                    try {
                        bitmap = imageToBitmap(image)
                        if (bitmap != null) {
                            Log.d(TAG, if (isManual) "手动翻译截图成功" else "自动单次翻译截图成功")
                            handler?.post { processImage(bitmap, isManual) }
                            return@submit
                        }
                    } finally {
                        image.close()
                    }
                }

                if (retryCount < 3) {
                    Log.d(TAG, "单次翻译重试 ${retryCount + 1}/3")
                    handler?.postDelayed({ doOneShotCapture(retryCount + 1, isManual) }, 100)
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
            val image = synchronized(imageReader ?: Object()) { imageReader?.acquireLatestImage() } ?: return
            var bitmap: Bitmap? = null
            try {
                bitmap = imageToBitmap(image)
                if (bitmap == null) return

                val currentHash = computePixelHash(bitmap)
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
            } finally {
                image.close()
                bitmap?.recycle()
            }
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
                // Flush one stale frame from buffer
                synchronized(imageReader ?: Object()) { imageReader?.acquireLatestImage()?.close() }
                Thread.sleep(33)
                val image = synchronized(imageReader ?: Object()) { imageReader?.acquireLatestImage() }
                if (image != null) {
                    var bitmap: Bitmap? = null
                    try {
                        bitmap = imageToBitmap(image)
                        if (bitmap != null) {
                            val currentHash = computePixelHash(bitmap)
                            if (expectedHash != 0L && currentHash != expectedHash) {
                                screenChangeVersion++
                                isScreenStable = false
                                stableFrameCount = 0
                                return@submit
                            }
                            val shown = doOcrAndTranslate(bitmap, expectedVersion)
                            bitmap = null  // doOcrAndTranslate 已经 recycle
                            if (shown) {
                                lastTranslatedHash = currentHash
                                lastTranslationTimeMs = System.currentTimeMillis()
                            }
                        }
                    } finally {
                        image.close()
                        bitmap?.recycle()
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
        } catch (e: Exception) {
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
                    (card.sourceRect.top / scaleFactor).toInt(),
                    (card.sourceRect.right / scaleFactor).toInt(),
                    (card.sourceRect.bottom / scaleFactor).toInt()
                )
                FloatingWindowService.Companion.TranslationDisplayResult(
                    originalText = card.originalText,
                    translatedText = card.translatedText,
                    sourceRect = scaledRect,
                    isVertical = direction == FloatingWindowService.Companion.RecognitionDirection.VERTICAL
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

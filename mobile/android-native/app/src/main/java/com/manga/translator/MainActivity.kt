package com.manga.translator

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.manga.translator.databinding.ActivityMainBinding
import com.manga.translator.service.FloatingWindowService
import com.manga.translator.service.ScreenCaptureService
import com.manga.translator.util.OpenCVHelper

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MangaTranslator"
    }

    private lateinit var binding: ActivityMainBinding
    private var isServiceRunning = false
    private var pendingStart = false

    // 权限请求防重复发起标志：请求期间禁用按钮，避免用户连点导致多次发起请求
    private var isRequesting = false

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // 权限请求结束，恢复按钮可用状态
        isRequesting = false
        binding.btnStart.isEnabled = true
        // 异步回调需先检查生命周期，避免 Activity 销毁后更新 UI / 启动服务导致崩溃
        if (isFinishing || isDestroyed) return@registerForActivityResult

        Log.d(TAG, "屏幕录制权限结果: resultCode=${result.resultCode}")

        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            Log.d(TAG, "屏幕录制权限已授予")
            startServices(result.resultCode, result.data!!)
        } else {
            Log.e(TAG, "屏幕录制权限被拒绝")
            Toast.makeText(this, "需要屏幕录制权限才能翻译", Toast.LENGTH_LONG).show()
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        // 权限请求结束，恢复按钮可用状态
        isRequesting = false
        binding.btnStart.isEnabled = true
        // 异步回调需先检查生命周期，避免 Activity 销毁后更新 UI 导致崩溃
        if (isFinishing || isDestroyed) return@registerForActivityResult

        if (Settings.canDrawOverlays(this)) {
            Log.d(TAG, "悬浮窗权限已授予")
            if (pendingStart) {
                pendingStart = false
                requestScreenCapture()
            }
        } else {
            // 悬浮窗权限被拒：显式重置 pendingStart，避免下次直接点击跳过权限检查
            pendingStart = false
            Toast.makeText(this, "需要悬浮窗权限才能显示翻译", Toast.LENGTH_LONG).show()
        }
    }

    // 通知权限请求（Android 13+）：前台服务通知需要此权限
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        Log.d(TAG, "通知权限授予: $granted")
        // 通知权限被拒不影响核心功能，前台服务仍可运行，只是不显示通知
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 初始化OpenCV
        val opencvInitialized = OpenCVHelper.initialize(this)
        if (!opencvInitialized) {
            Log.e(TAG, "OpenCV初始化失败，气泡检测功能可能不可用")
        }

        // DebugManager 已在 MangaTranslatorApp.onCreate 中初始化，确保在任何 Service 之前完成

        // Android 13+ 需要运行时请求通知权限，前台服务通知才能显示
        requestNotificationPermissionIfNeeded()

        // 通过 ActivityManager 查询 ScreenCaptureService 实际运行状态，
        // 避免旋转重建后 isServiceRunning 重置为 false 导致重复启动服务
        isServiceRunning = isScreenCaptureServiceRunning()
        if (isServiceRunning) {
            binding.btnStart.text = "停止翻译"
            binding.tvStatus.text = "状态：运行中"
        }

        setupUI()
        Log.d(TAG, "MainActivity onCreate")
    }

    /**
     * 通过 ActivityManager 查询 ScreenCaptureService 是否正在运行。
     * 注意：getRunningServices 自 Android 8 起仅返回本应用服务，对本场景足够。
     */
    private fun isScreenCaptureServiceRunning(): Boolean {
        val manager = getSystemService(ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val className = ScreenCaptureService::class.java.name
        // Android 8+ 仅返回自身应用服务；为兼容性做 try-catch 保护
        return try {
            @Suppress("DEPRECATION")
            manager.getRunningServices(Int.MAX_VALUE).any { it.service.className == className }
        } catch (e: Exception) {
            Log.w(TAG, "查询服务运行状态失败: ${e.message}")
            false
        }
    }

    /**
     * Android 13+ (API 33+) 需要运行时请求 POST_NOTIFICATIONS 权限。
     * 前台服务通知（ScreenCaptureService 的运行状态通知）依赖此权限。
     * 权限被拒不影响核心功能，前台服务仍可运行。
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.d(TAG, "请求通知权限")
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun setupUI() {
        binding.btnStart.setOnClickListener {
            // 权限请求期间禁用按钮，避免重复发起
            if (isRequesting) {
                return@setOnClickListener
            }
            if (isServiceRunning) {
                stopServices()
            } else {
                startTranslation()
            }
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun startTranslation() {
        Log.d(TAG, "startTranslation")

        // 检查悬浮窗权限
        if (!Settings.canDrawOverlays(this)) {
            Log.d(TAG, "需要悬浮窗权限")
            pendingStart = true
            requestOverlayPermission()
            return
        }

        // 请求屏幕录制权限
        requestScreenCapture()
    }

    private fun requestOverlayPermission() {
        // 标记请求进行中，禁用按钮防重复发起
        isRequesting = true
        binding.btnStart.isEnabled = false
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        overlayPermissionLauncher.launch(intent)
    }

    private fun requestScreenCapture() {
        Log.d(TAG, "请求屏幕录制权限")
        // 标记请求进行中，禁用按钮防重复发起
        isRequesting = true
        binding.btnStart.isEnabled = false
        val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun stopServices() {
        Log.d(TAG, "停止服务")

        stopService(Intent(this, ScreenCaptureService::class.java))
        stopService(Intent(this, FloatingWindowService::class.java))

        isServiceRunning = false
        binding.btnStart.text = "开始翻译"
        binding.tvStatus.text = "状态：已停止"

        Toast.makeText(this, "翻译服务已停止", Toast.LENGTH_SHORT).show()
    }

    private fun startServices(resultCode: Int, data: Intent) {
        Log.d(TAG, "启动服务, resultCode=$resultCode")

        // 启动屏幕截图服务
        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_DATA, data)
        }

        // 异常保护：部分厂商 ROM 在后台启动受限时会抛 IllegalStateException / SecurityException
        // minSdk 26 后 startForegroundService 一律可用，无需版本判断
        try {
            startForegroundService(serviceIntent)
            Log.d(TAG, "屏幕截图服务已启动")
        } catch (e: Exception) {
            Log.e(TAG, "启动屏幕截图服务失败: ${e.message}", e)
            Toast.makeText(this, "启动翻译服务失败: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }

        // 启动悬浮窗服务
        val floatingIntent = Intent(this, FloatingWindowService::class.java)
        try {
            startService(floatingIntent)
            Log.d(TAG, "悬浮窗服务已启动")
        } catch (e: Exception) {
            // 悬浮窗启动失败时必须回滚：停止已启动的 ScreenCaptureService，重置 UI 状态，
            // 否则 ScreenCaptureService 会空跑录屏耗电，用户却无悬浮球可交互
            Log.e(TAG, "启动悬浮窗服务失败: ${e.message}", e)
            stopService(Intent(this, ScreenCaptureService::class.java))
            isServiceRunning = false
            binding.btnStart.text = "开始翻译"
            binding.tvStatus.text = "状态：已停止"
            Toast.makeText(this, "悬浮窗服务启动失败：${e.message}，请检查悬浮窗权限", Toast.LENGTH_LONG).show()
            return
        }

        isServiceRunning = true
        binding.btnStart.text = "停止翻译"
        binding.tvStatus.text = "状态：运行中"

        Toast.makeText(this, "翻译服务已启动，请点击悬浮窗选择模式", Toast.LENGTH_LONG).show()
    }
}

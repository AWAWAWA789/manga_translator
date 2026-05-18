package com.manga.translator

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
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
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            Log.d(TAG, "悬浮窗权限已授予")
            if (pendingStart) {
                pendingStart = false
                requestScreenCapture()
            }
        } else {
            Toast.makeText(this, "需要悬浮窗权限才能显示翻译", Toast.LENGTH_LONG).show()
        }
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

        setupUI()
        Log.d(TAG, "MainActivity onCreate")
    }

    private fun setupUI() {
        binding.btnStart.setOnClickListener {
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
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlayPermissionLauncher.launch(intent)
    }

    private fun requestScreenCapture() {
        Log.d(TAG, "请求屏幕录制权限")
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Log.d(TAG, "屏幕截图服务已启动")

        // 启动悬浮窗服务
        val floatingIntent = Intent(this, FloatingWindowService::class.java)
        startService(floatingIntent)
        Log.d(TAG, "悬浮窗服务已启动")

        isServiceRunning = true
        binding.btnStart.text = "停止翻译"
        binding.tvStatus.text = "状态：运行中"

        Toast.makeText(this, "翻译服务已启动，请点击悬浮窗选择模式", Toast.LENGTH_LONG).show()
    }
}

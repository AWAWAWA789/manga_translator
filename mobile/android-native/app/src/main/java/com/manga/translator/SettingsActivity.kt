package com.manga.translator

import android.app.AlertDialog
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.manga.translator.databinding.ActivitySettingsBinding
import com.manga.translator.translation.TranslationPlugin
import com.manga.translator.translation.TranslatorType
import com.manga.translator.util.SecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var translationPlugin: TranslationPlugin

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 使用 applicationContext 避免 TranslationPlugin 持有 Activity Context 造成内存泄漏
        translationPlugin = TranslationPlugin(applicationContext)
        setupUI()
        loadSettings()
    }

    override fun onDestroy() {
        super.onDestroy()
        // TranslationPlugin 内部持有 OkHttp 线程池，Activity 销毁时必须 close 避免线程泄漏
        if (::translationPlugin.isInitialized) {
            translationPlugin.close()
        }
    }

    private fun setupUI() {
        binding.btnSaveBaidu.setOnClickListener {
            saveBaiduConfig()
        }

        binding.btnSaveMimo.setOnClickListener {
            saveMimoConfig()
        }

        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnTestBaidu.setOnClickListener {
            testBaiduApi()
        }

        binding.btnTestMimo.setOnClickListener {
            testMimoApi()
        }

        binding.btnSaveDefaultTranslator.setOnClickListener {
            saveDefaultTranslator()
        }

        binding.btnViewCrashLogs.setOnClickListener {
            showCrashLogs()
        }
    }

    private fun loadSettings() {
        val baidu = translationPlugin.getBaiduTranslator()
        val mimo = translationPlugin.getMimoTranslator()

        binding.etBaiduAppId.setText(baidu.getAppId())
        binding.etBaiduSecretKey.setText(baidu.getSecretKey())

        binding.etMimoApiKey.setText(mimo.getApiKey())
        binding.etMimoBaseUrl.setText(mimo.getBaseUrl())
        binding.etMimoModel.setText(mimo.getModel())

        val defaultTranslator = translationPlugin.getDefaultTranslator()
        when (defaultTranslator) {
            TranslatorType.BAIDU -> binding.radioBaidu.isChecked = true
            TranslatorType.MIMO -> binding.radioMimo.isChecked = true
        }

        updateTranslatorStatus()
    }

    private fun updateTranslatorStatus() {
        val baiduConfigured = translationPlugin.getBaiduTranslator().isConfigured()
        val mimoConfigured = translationPlugin.getMimoTranslator().isConfigured()

        binding.tvBaiduStatus.text = if (baiduConfigured) "已配置" else "未配置"
        binding.tvMimoStatus.text = if (mimoConfigured) "已配置" else "未配置"
    }

    private fun saveBaiduConfig() {
        val appId = binding.etBaiduAppId.text.toString().trim()
        val secretKey = binding.etBaiduSecretKey.text.toString().trim()

        if (appId.isBlank() || secretKey.isBlank()) {
            Toast.makeText(this, "请输入百度翻译API密钥", Toast.LENGTH_SHORT).show()
            return
        }

        translationPlugin.getBaiduTranslator().setConfig(appId, secretKey)
        updateTranslatorStatus()
        if (SecurePrefs.isEncryptionFailed(this)) {
            Toast.makeText(this, "警告：加密存储初始化失败，API Key 将以明文保存", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "百度配置已保存", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveMimoConfig() {
        val apiKey = binding.etMimoApiKey.text.toString().trim()
        val baseUrl = binding.etMimoBaseUrl.text.toString().trim()
        val model = binding.etMimoModel.text.toString().trim()

        if (apiKey.isBlank()) {
            Toast.makeText(this, "请输入MiMo API Key", Toast.LENGTH_SHORT).show()
            return
        }

        val finalBaseUrl = if (baseUrl.isBlank()) "https://api.xiaomimimo.com/v1/chat/completions" else baseUrl
        val finalModel = if (model.isBlank()) "mimo-v2.5" else model

        // setConfig 内部 validateBaseUrl 会抛 IllegalArgumentException（baseUrl 缺 scheme 或 http 非 localhost），
        // 用户输入不合法时必须捕获并 Toast 提示，否则会通过 CrashHandler 触发"应用已停止"
        try {
            translationPlugin.getMimoTranslator().setConfig(apiKey, finalBaseUrl, finalModel)
        } catch (e: IllegalArgumentException) {
            Toast.makeText(this, "保存失败：${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        updateTranslatorStatus()
        if (SecurePrefs.isEncryptionFailed(this)) {
            Toast.makeText(this, "警告：加密存储初始化失败，API Key 将以明文保存", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "MiMo配置已保存", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveDefaultTranslator() {
        val type = when {
            binding.radioBaidu.isChecked -> TranslatorType.BAIDU
            binding.radioMimo.isChecked -> TranslatorType.MIMO
            else -> TranslatorType.MIMO
        }

        translationPlugin.setDefaultTranslator(type)
        Toast.makeText(this, "默认翻译器已保存", Toast.LENGTH_SHORT).show()
    }

    private fun testBaiduApi() {
        val appId = binding.etBaiduAppId.text.toString().trim()
        val secretKey = binding.etBaiduSecretKey.text.toString().trim()

        if (appId.isBlank() || secretKey.isBlank()) {
            Toast.makeText(this, "请先输入百度翻译API密钥", Toast.LENGTH_SHORT).show()
            return
        }

        // 防重入：测试期间禁用按钮，避免重复点击堆叠线程与对话框
        binding.btnTestBaidu.isEnabled = false

        translationPlugin.getBaiduTranslator().setConfig(appId, secretKey)
        Toast.makeText(this, "正在测试百度翻译API...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { translationPlugin.getBaiduTranslator().test() }
                if (!isFinishing && !isDestroyed) binding.btnTestBaidu.isEnabled = true
                if (isFinishing || isDestroyed) return@launch
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(if (result.success) "测试成功" else "测试失败")
                    .setMessage(result.message + if (result.translatedText != null) "\n\n译文: ${result.translatedText}" else "")
                    .setPositiveButton("确定", null)
                    .show()
            } catch (e: Exception) {
                if (!isFinishing && !isDestroyed) binding.btnTestBaidu.isEnabled = true
                if (isFinishing || isDestroyed) return@launch
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("测试异常")
                    .setMessage("错误信息: ${e.message}")
                    .setPositiveButton("确定", null)
                    .show()
            }
        }
    }

    private fun testMimoApi() {
        val apiKey = binding.etMimoApiKey.text.toString().trim()
        val baseUrl = binding.etMimoBaseUrl.text.toString().trim()
        val model = binding.etMimoModel.text.toString().trim()

        if (apiKey.isBlank()) {
            Toast.makeText(this, "请先输入MiMo API Key", Toast.LENGTH_SHORT).show()
            return
        }

        // 防重入：测试期间禁用按钮，避免重复点击堆叠线程与对话框
        binding.btnTestMimo.isEnabled = false

        val finalBaseUrl = if (baseUrl.isBlank()) "https://api.xiaomimimo.com/v1/chat/completions" else baseUrl
        val finalModel = if (model.isBlank()) "mimo-v2.5" else model

        // setConfig 校验失败时抛 IllegalArgumentException，需捕获后恢复按钮状态并提示，
        // 否则 btnTestMimo 会被永久禁用且无用户反馈
        try {
            translationPlugin.getMimoTranslator().setConfig(apiKey, finalBaseUrl, finalModel)
        } catch (e: IllegalArgumentException) {
            if (!isFinishing && !isDestroyed) binding.btnTestMimo.isEnabled = true
            Toast.makeText(this, "配置校验失败：${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "正在测试MiMo API...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { translationPlugin.getMimoTranslator().test() }
                if (!isFinishing && !isDestroyed) binding.btnTestMimo.isEnabled = true
                if (isFinishing || isDestroyed) return@launch
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(if (result.success) "测试成功" else "测试失败")
                    .setMessage(result.message + if (result.translatedText != null) "\n\n译文: ${result.translatedText}" else "")
                    .setPositiveButton("确定", null)
                    .show()
            } catch (e: Exception) {
                if (!isFinishing && !isDestroyed) binding.btnTestMimo.isEnabled = true
                if (isFinishing || isDestroyed) return@launch
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("测试异常")
                    .setMessage("错误信息: ${e.message}")
                    .setPositiveButton("确定", null)
                    .show()
            }
        }
    }

    /**
     * 显示崩溃日志列表，点击单条查看详细堆栈。
     * 崩溃文件存储在 app private 目录，非 root 设备用户无法直接访问，
     * 必须提供此入口让用户能查看并反馈崩溃信息。
     */
    private fun showCrashLogs() {
        val crashFiles = com.manga.translator.util.CrashHandler.getCrashFiles()
        if (crashFiles.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("崩溃日志")
                .setMessage("暂无崩溃记录")
                .setPositiveButton("确定", null)
                .show()
            return
        }

        val displayItems = crashFiles.map { file ->
            // 文件名格式 crash_yyyy-MM-dd_HH-mm-ss.txt，显示时去掉前缀和扩展名
            file.name.removePrefix("crash_").removeSuffix(".txt").replace('_', ' ')
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("崩溃日志（共${crashFiles.size}条）")
            .setItems(displayItems) { _, which ->
                val file = crashFiles[which]
                val content = try {
                    file.readText()
                } catch (e: Exception) {
                    "读取失败: ${e.message}"
                }
                // 崩溃堆栈可能很长，用 ScrollView 包裹的 TextView 显示
                val scrollView = android.widget.ScrollView(this)
                val textView = android.widget.TextView(this).apply {
                    text = content
                    textSize = 12f
                    setPadding(48, 32, 48, 32)
                    setTextIsSelectable(true)
                }
                scrollView.addView(textView)
                AlertDialog.Builder(this)
                    .setTitle(displayItems[which])
                    .setView(scrollView)
                    .setPositiveButton("关闭", null)
                    .show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}

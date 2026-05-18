package com.manga.translator

import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.manga.translator.databinding.ActivitySettingsBinding
import com.manga.translator.translation.TranslatorType
import com.manga.translator.translation.TranslationPlugin

class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MangaTranslator"
    }

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var translationPlugin: TranslationPlugin

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        translationPlugin = TranslationPlugin(this)
        setupUI()
        loadSettings()
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
        Toast.makeText(this, "百度配置已保存", Toast.LENGTH_SHORT).show()
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
        val finalModel = if (model.isBlank()) "mimo-vl-7b" else model
        
        translationPlugin.getMimoTranslator().setConfig(apiKey, finalBaseUrl, finalModel)
        updateTranslatorStatus()
        Toast.makeText(this, "MiMo配置已保存", Toast.LENGTH_SHORT).show()
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

        translationPlugin.getBaiduTranslator().setConfig(appId, secretKey)
        Toast.makeText(this, "正在测试百度翻译API...", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val result = translationPlugin.getBaiduTranslator().test()
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle(if (result.success) "测试成功" else "测试失败")
                        .setMessage(result.message + if (result.translatedText != null) "\n\n译文: ${result.translatedText}" else "")
                        .setPositiveButton("确定", null)
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("测试异常")
                        .setMessage("错误信息: ${e.message}")
                        .setPositiveButton("确定", null)
                        .show()
                }
            }
        }.start()
    }
    
    private fun testMimoApi() {
        val apiKey = binding.etMimoApiKey.text.toString().trim()
        val baseUrl = binding.etMimoBaseUrl.text.toString().trim()
        val model = binding.etMimoModel.text.toString().trim()

        if (apiKey.isBlank()) {
            Toast.makeText(this, "请先输入MiMo API Key", Toast.LENGTH_SHORT).show()
            return
        }

        val finalBaseUrl = if (baseUrl.isBlank()) "https://api.xiaomimimo.com/v1/chat/completions" else baseUrl
        val finalModel = if (model.isBlank()) "mimo-vl-7b" else model
        
        translationPlugin.getMimoTranslator().setConfig(apiKey, finalBaseUrl, finalModel)
        Toast.makeText(this, "正在测试MiMo API...", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val result = translationPlugin.getMimoTranslator().test()
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle(if (result.success) "测试成功" else "测试失败")
                        .setMessage(result.message + if (result.translatedText != null) "\n\n译文: ${result.translatedText}" else "")
                        .setPositiveButton("确定", null)
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("测试异常")
                        .setMessage("错误信息: ${e.message}")
                        .setPositiveButton("确定", null)
                        .show()
                }
            }
        }.start()
    }
}
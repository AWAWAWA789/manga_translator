package com.manga.translator.translation

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.TimeUnit

class BaiduTranslator(private val context: Context) {
    
    companion object {
        private const val TAG = "BaiduTranslator"
        private const val PREFS_NAME = "translation_config"
        private const val KEY_APP_ID = "baidu_app_id"
        private const val KEY_SECRET_KEY = "baidu_secret_key"
    }
    
    private val preferences: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    
    data class TranslateResponse(
        @SerializedName("from") val from: String?,
        @SerializedName("to") val to: String?,
        @SerializedName("trans_result") val transResult: List<TransResult>?,
        @SerializedName("error_code") val errorCode: String?,
        @SerializedName("error_msg") val errorMsg: String?
    )
    
    data class TransResult(
        @SerializedName("src") val src: String?,
        @SerializedName("dst") val dst: String?
    )
    
    data class TestResult(
        val success: Boolean,
        val message: String,
        val translatedText: String?
    )
    
    fun isConfigured(): Boolean {
        return getAppId().isNotEmpty() && getSecretKey().isNotEmpty()
    }
    
    fun getAppId(): String {
        return preferences.getString(KEY_APP_ID, "") ?: ""
    }
    
    fun getSecretKey(): String {
        return preferences.getString(KEY_SECRET_KEY, "") ?: ""
    }
    
    fun setConfig(appId: String, secretKey: String) {
        preferences.edit()
            .putString(KEY_APP_ID, appId)
            .putString(KEY_SECRET_KEY, secretKey)
            .apply()
    }
    
    fun translate(text: String): String {
        if (!isConfigured()) {
            return "请配置百度翻译API"
        }
        
        return try {
            callBaiduApi(text, getAppId(), getSecretKey())
        } catch (e: Exception) {
            Log.e(TAG, "翻译失败: ${e.message}")
            "翻译失败: ${e.message}"
        }
    }
    
    fun test(): TestResult {
        if (!isConfigured()) {
            return TestResult(false, "请先配置百度翻译API", null)
        }
        
        return try {
            val result = callBaiduApi("こんにちは", getAppId(), getSecretKey())
            if (result.startsWith("翻译失败")) {
                TestResult(false, result, null)
            } else {
                TestResult(true, "测试成功", result)
            }
        } catch (e: Exception) {
            TestResult(false, "测试失败: ${e.message}", null)
        }
    }
    
    private fun callBaiduApi(text: String, appId: String, secretKey: String): String {
        val salt = (10000 + Random().nextInt(89999)).toString()
        val sign = generateSign(appId, text, salt, secretKey)
        
        val url = "https://fanyi-api.baidu.com/api/trans/vip/translate?" +
            "q=${java.net.URLEncoder.encode(text, "UTF-8")}" +
            "&from=jp" +
            "&to=zh" +
            "&appid=$appId" +
            "&salt=$salt" +
            "&sign=$sign"
        
        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("Content-Type", "application/json")
            .build()
        
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw Exception("响应为空")
        
        if (!response.isSuccessful) {
            throw Exception("HTTP错误: ${response.code}")
        }
        
        val result = gson.fromJson(responseBody, TranslateResponse::class.java)
        
        if (result.errorCode != null) {
            throw Exception("百度错误: ${result.errorCode} - ${result.errorMsg}")
        }
        
        return result.transResult?.firstOrNull()?.dst ?: throw Exception("翻译结果为空")
    }
    
    private fun generateSign(appId: String, query: String, salt: String, secretKey: String): String {
        val signStr = appId + query + salt + secretKey
        return md5(signStr)
    }
    
    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
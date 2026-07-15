package com.manga.translator.translation

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.manga.translator.util.HttpClientProvider
import com.manga.translator.util.SecurePrefs
import okhttp3.FormBody
import okhttp3.Request
import java.security.MessageDigest
import java.util.concurrent.ThreadLocalRandom

class BaiduTranslator(private val context: Context) {

    companion object {
        private const val TAG = "BaiduTranslator"
        private const val PREFS_NAME = "translation_config"
        private const val KEY_APP_ID = "baidu_app_id"
        private const val KEY_SECRET_KEY = "baidu_secret_key"
    }

    private val preferences: SharedPreferences = SecurePrefs.get(context)
    private val gson = Gson()

    private val client = HttpClientProvider.textClient

    data class TranslateResponse(
        @SerializedName("from") val from: String?,
        @SerializedName("to") val to: String?,
        @SerializedName("trans_result") val transResult: List<TransResult>?,
        @SerializedName("error_code") val errorCode: String?,
        @SerializedName("error_msg") val errorMsg: String?,
    )

    data class TransResult(
        @SerializedName("src") val src: String?,
        @SerializedName("dst") val dst: String?,
    )

    data class TestResult(
        val success: Boolean,
        val message: String,
        val translatedText: String?,
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
        val salt = (10000 + ThreadLocalRandom.current().nextInt(89999)).toString()
        val sign = generateSign(appId, text, salt, secretKey)

        // 使用 POST 表单提交，避免长文本导致 URL 超过长度限制（一般 2KB-8KB）
        val formBody = FormBody.Builder()
            .add("q", text)
            .add("from", "jp")
            .add("to", "zh")
            .add("appid", appId)
            .add("salt", salt)
            .add("sign", sign)
            .build()

        val request = Request.Builder()
            .url("https://fanyi-api.baidu.com/api/trans/vip/translate")
            .post(formBody)
            .addHeader("Content-Type", "application/x-www-form-urlencoded")
            .build()

        // 使用 use{} 保证 Response 资源释放，异常路径也会正确关闭
        return client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: throw Exception("响应为空")

            if (!response.isSuccessful) {
                // 非成功状态码时把响应体拼入异常信息，便于排查
                throw Exception("HTTP错误: ${response.code} 响应: $responseBody")
            }

            val result = gson.fromJson(responseBody, TranslateResponse::class.java)
                ?: throw Exception("响应解析失败")

            if (result.errorCode != null) {
                throw Exception("百度错误: ${result.errorCode} - ${result.errorMsg}")
            }

            // 拼接全部非空 dst，避免多句文本仅取首条被截断
            val translations = result.transResult ?: throw Exception("翻译结果为空")
            val output = translations.joinToString("") { it.dst ?: "" }
            if (output.isBlank()) throw Exception("翻译结果为空")
            output
        }
    }

    private fun generateSign(appId: String, query: String, salt: String, secretKey: String): String {
        val signStr = appId + query + salt + secretKey
        return md5(signStr)
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        // 显式指定 UTF-8，避免依赖平台默认编码导致签名不一致
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

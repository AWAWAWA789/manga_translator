package com.manga.translator.translation

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.manga.translator.util.HttpClientProvider
import com.manga.translator.util.SecurePrefs
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class MimoTranslator(private val context: Context) {

    companion object {
        private const val TAG = "MimoTranslator"
        private const val PREFS_NAME = "translation_config"
        private const val KEY_API_KEY = "mimo_api_key"
        private const val KEY_BASE_URL = "mimo_base_url"
        private const val KEY_MODEL = "mimo_model"
        private const val DEFAULT_BASE_URL = "https://api.xiaomimimo.com/v1/chat/completions"
        private const val DEFAULT_MODEL = "mimo-v2.5"
        private const val MAX_BATCH_SIZE = 10

        // 复用编译好的正则常量，避免每次调用重新编译
        private val REGEX_PREFIX_TRANSLATION = Regex("^译文[：:]\\s*")
        private val REGEX_PREFIX_FANYI = Regex("^翻译[：:]\\s*")
        private val REGEX_PREFIX_CHINESE = Regex("^中文[：:]\\s*")
        private val REGEX_PREFIX_NUMBER = Regex("^\\d+[.、)）]\\s*")
        private val REGEX_BATCH_NUMBER = Regex("^(\\d+)[.、)）]\\s*(.+)$")
    }

    // 敏感数据（API Key）使用加密存储，非敏感配置使用普通存储
    // 惰性初始化，避免在构造器中阻塞主线程（EncryptedSharedPreferences 首次创建涉及密钥派生）
    private val securePreferences: SharedPreferences by lazy { SecurePrefs.get(context) }
    private val preferences: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    private val client = HttpClientProvider.mimoTextClient

    data class ChatRequest(
        val model: String,
        val messages: List<ChatMessage>,
        val temperature: Double = 0.25,
        val max_tokens: Int = 2048,
    )

    data class ChatMessage(
        val role: String,
        val content: String,
    )

    data class ChatResponse(
        @SerializedName("choices") val choices: List<Choice>?,
        @SerializedName("error") val error: Error?,
    )

    data class Choice(
        @SerializedName("message") val message: ChatMessage?,
    )

    data class Error(
        @SerializedName("message") val message: String?,
    )

    data class TestResult(
        val success: Boolean,
        val message: String,
        val translatedText: String?,
    )

    fun isConfigured(): Boolean {
        return getApiKey().isNotEmpty()
    }

    fun getApiKey(): String {
        return securePreferences.getString(KEY_API_KEY, "") ?: ""
    }

    fun getBaseUrl(): String {
        return preferences.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
    }

    fun getModel(): String {
        return preferences.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
    }

    fun setConfig(apiKey: String, baseUrl: String, model: String) {
        // 落库前校验 scheme，避免后续 Authorization header 经 http 明文传输 API Key
        val safeBaseUrl = validateBaseUrl(baseUrl)
        securePreferences.edit().putString(KEY_API_KEY, apiKey).apply()
        preferences.edit()
            .putString(KEY_BASE_URL, safeBaseUrl)
            .putString(KEY_MODEL, model)
            .apply()
    }

    /**
     * 校验 baseUrl 必须使用 https://，避免 Authorization header 明文传输 API Key。
     * 开发调试场景允许 localhost / 127.0.0.1 走 http。
     */
    private fun validateBaseUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim()
        return when {
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("http://", ignoreCase = true) -> {
                val host = trimmed.substringAfter("://").substringBefore(':').substringBefore('/')
                if (host == "localhost" || host == "127.0.0.1") {
                    trimmed
                } else {
                    throw IllegalArgumentException("baseUrl 必须使用 https://，禁止 http:// 明文传输 API Key")
                }
            }
            else -> throw IllegalArgumentException("baseUrl 必须以 http:// 或 https:// 开头")
        }
    }

    fun translate(text: String): String {
        if (!isConfigured()) {
            return "请配置MiMo API"
        }

        return try {
            val prompt = buildPrompt(text)
            cleanOutput(callMimoApi(prompt))
        } catch (e: Exception) {
            Log.e(TAG, "翻译失败: ${e.message}")
            "翻译失败: ${e.message}"
        }
    }

    fun translateBatch(texts: List<String>): List<String> {
        if (!isConfigured()) {
            return texts.map { "请配置MiMo API" }
        }
        if (texts.isEmpty()) return emptyList()

        if (texts.size > MAX_BATCH_SIZE) {
            return texts.chunked(MAX_BATCH_SIZE).flatMap { chunk -> translateBatch(chunk) }
        }

        return try {
            val prompt = buildBatchPrompt(texts)
            parseBatchOutput(callMimoApi(prompt), texts.size) ?: List(texts.size) { "翻译失败: 批量结果格式错误" }
        } catch (e: Exception) {
            Log.e(TAG, "批量翻译失败: ${e.message}")
            texts.map { "翻译失败: ${e.message}" }
        }
    }

    fun test(): TestResult {
        if (!isConfigured()) {
            return TestResult(false, "请先配置MiMo API", null)
        }

        return try {
            val result = translate("この島は見るな！！")
            if (result.startsWith("翻译失败")) {
                TestResult(false, result, null)
            } else {
                TestResult(true, "测试成功", result)
            }
        } catch (e: Exception) {
            TestResult(false, "测试失败: ${e.message}", null)
        }
    }

    private fun buildPrompt(text: String): String {
        return """你是专业漫画汉化翻译。请将以下日文漫画对白翻译成自然中文。

要求：
1. 保留人物语气
2. 不解释
3. 不添加原文
4. 不输出多余内容
5. 只输出中文译文

日文：
$text"""
    }

    private fun buildBatchPrompt(texts: List<String>): String {
        val source = texts.mapIndexed { index, text ->
            "${index + 1}. $text"
        }.joinToString("\n")

        return """你是专业漫画汉化译者。下面是同一页漫画对白，已按阅读顺序排列。请结合前后语境逐条翻译成自然中文。

翻译原则：
1. 保留角色口吻、吐槽、急促感和漫画对白节奏
2. 代词、人名、称呼要参考上下文，不要逐字硬译
3. 拟声词、语气词按中文漫画习惯处理
4. 每一条只输出对应中文译文，不要解释，不要输出日文
5. 必须保持条目数量完全一致

输出格式必须严格为：
1. 中文译文
2. 中文译文

日文对白：
$source"""
    }

    private fun callMimoApi(prompt: String): String {
        val apiKey = getApiKey()
        // 调用时二次校验 scheme，防止 prefs 被篡改为 http 导致 API Key 明文传输
        val baseUrl = validateBaseUrl(getBaseUrl())
        val model = getModel()

        val request = ChatRequest(
            model = model,
            messages = listOf(
                ChatMessage(role = "user", content = prompt),
            ),
        )

        val requestBody = gson.toJson(request).toRequestBody("application/json".toMediaType())

        val httpRequest = Request.Builder()
            .url(baseUrl)
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $apiKey")
            .build()

        val response = client.newCall(httpRequest).execute()
        val responseBody = response.body?.string() ?: throw Exception("响应为空")

        if (!response.isSuccessful) {
            throw Exception("HTTP错误: ${response.code}")
        }

        val result = gson.fromJson(responseBody, ChatResponse::class.java)

        if (result.error != null) {
            throw Exception("MiMo错误: ${result.error.message}")
        }

        val content = result.choices?.firstOrNull()?.message?.content
            ?: throw Exception("翻译结果为空")

        return content.trim()
    }

    private fun cleanOutput(text: String): String {
        return text.trim()
            .replace(REGEX_PREFIX_TRANSLATION, "")
            .replace(REGEX_PREFIX_FANYI, "")
            .replace(REGEX_PREFIX_CHINESE, "")
            .replace(REGEX_PREFIX_NUMBER, "")
            .trim()
    }

    private fun parseBatchOutput(output: String, expectedSize: Int): List<String>? {
        val lines = output.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val pairs = lines.mapNotNull { line ->
            val match = REGEX_BATCH_NUMBER.find(line) ?: return@mapNotNull null
            val index = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            index to cleanOutput(match.groupValues[2])
        }
        // 检测重复编号并记日志；associateBy 在 key 冲突时保留最后一条
        pairs.groupingBy { it.first }.eachCount()
            .filter { it.value > 1 }
            .forEach { (index, count) ->
                Log.w(TAG, "批量结果编号重复($index 出现${count}次)，保留最后一条")
            }
        val numbered = pairs.associateBy({ it.first }, { it.second })

        // 优先用编号匹配：只要编号覆盖了期望数量就接受，缺失的留空由调用方回退
        if (numbered.isNotEmpty()) {
            val ordered = (1..expectedSize).map { numbered[it].orEmpty() }
            // 全部非空直接返回；部分缺失也返回（由 validateAndFallback 逐条回退），避免整批失败
            if (ordered.count { it.isNotBlank() } >= (expectedSize + 1) / 2) {
                return ordered
            }
        }

        // 无编号时，行数匹配则直接使用
        if (lines.size == expectedSize) {
            return lines.map { cleanOutput(it) }
        }

        // 行数多于期望（可能多输出了解释行）：取前 expectedSize 行
        if (lines.size > expectedSize) {
            return lines.take(expectedSize).map { cleanOutput(it) }
        }

        return null
    }
}

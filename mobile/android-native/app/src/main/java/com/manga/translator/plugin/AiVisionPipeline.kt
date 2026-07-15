package com.manga.translator.plugin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.manga.translator.model.TranslationCard
import com.manga.translator.translation.MimoTranslator
import com.manga.translator.util.HttpClientProvider
import com.manga.translator.util.TextFilter
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream

class AiVisionPipeline(private val context: Context) {

    companion object {
        private const val TAG = "AiVisionPipeline"
    }

    data class AiBubbleResult(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val text: String,
        val isVertical: Boolean,
        val translation: String,
        val readingOrder: Int,
    )

    data class AiAnalysisResult(
        val bubbles: List<AiBubbleResult>,
        val rawResponse: String,
        // 解析失败时携带错误信息，由调用方决定回退还是报错
        val error: String? = null,
    )

    private val mimoTranslator = MimoTranslator(context)
    private val gson = Gson()

    private val client = HttpClientProvider.visionClient

    fun isMiMoConfigured(): Boolean = mimoTranslator.isConfigured()

    /**
     * 释放资源：OkHttpClient 为共享实例（HttpClientProvider），不在此关闭；
     * 该 pipeline 无独占的 native 资源需释放。
     */
    fun close() {
        // 无需释放的 native 资源；OkHttpClient 为共享实例，不在此关闭
    }

    fun analyzeImage(bitmap: Bitmap, imageWidth: Int, imageHeight: Int): AiAnalysisResult {
        Log.d(TAG, "开始AI多模态分析，图片尺寸: ${imageWidth}x$imageHeight")

        if (!mimoTranslator.isConfigured()) {
            throw IllegalStateException("MiMo API未配置")
        }

        val (base64Image, scale) = bitmapToBase64(bitmap)
        // AI 看到的图片可能被缩放，prompt 和解析都基于缩放后尺寸，最后统一转换回原始尺寸
        val scaledWidth = (imageWidth * scale).toInt().coerceAtLeast(1)
        val scaledHeight = (imageHeight * scale).toInt().coerceAtLeast(1)
        val prompt = buildPrompt(scaledWidth, scaledHeight)
        val response = callMimoVisionApi(base64Image, prompt)
        val result = parseResponse(response, scaledWidth, scaledHeight)

        // 将坐标从缩放坐标系转换回原始坐标系
        val scaledBackBubbles = if (scale < 1.0f) {
            result.bubbles.map { bubble ->
                bubble.copy(
                    x = (bubble.x / scale).toInt(),
                    y = (bubble.y / scale).toInt(),
                    width = (bubble.width / scale).toInt(),
                    height = (bubble.height / scale).toInt(),
                )
            }
        } else {
            result.bubbles
        }
        val finalResult = result.copy(bubbles = scaledBackBubbles)

        Log.d(TAG, "AI识别到 ${finalResult.bubbles.size} 个气泡")
        return finalResult
    }

    fun toTranslationCards(result: AiAnalysisResult, lastTranslationRects: List<Rect> = emptyList()): List<TranslationCard> {
        return result.bubbles
            .sortedBy { it.readingOrder }
            .filter { bubble ->
                bubble.text.isNotBlank() &&
                    bubble.translation.isNotBlank() &&
                    !bubble.translation.startsWith("翻译失败") &&
                    TextFilter.isValidOcrText(bubble.text)
            }
            .filter { bubble ->
                val rect = Rect(bubble.x, bubble.y, bubble.x + bubble.width, bubble.y + bubble.height)
                lastTranslationRects.none { existing ->
                    val overlapLeft = maxOf(rect.left, existing.left)
                    val overlapTop = maxOf(rect.top, existing.top)
                    val overlapRight = minOf(rect.right, existing.right)
                    val overlapBottom = minOf(rect.bottom, existing.bottom)
                    if (overlapLeft < overlapRight && overlapTop < overlapBottom) {
                        val overlapArea = (overlapRight - overlapLeft) * (overlapBottom - overlapTop)
                        val rectArea = maxOf(1, rect.width() * rect.height())
                        overlapArea.toFloat() / rectArea.toFloat() > 0.25f
                    } else {
                        false
                    }
                }
            }
            .map { bubble ->
                TranslationCard(
                    originalText = bubble.text,
                    translatedText = bubble.translation,
                    sourceRect = Rect(bubble.x, bubble.y, bubble.x + bubble.width, bubble.y + bubble.height),
                    isVertical = bubble.isVertical,
                )
            }
    }

    /**
     * 将 Bitmap 转为 base64，必要时缩放到 maxDim 以内。
     * @return Pair<base64String, scale> scale 为缩放比例（缩放后/原始），未缩放时为 1.0
     */
    private fun bitmapToBase64(bitmap: Bitmap): Pair<String, Float> {
        // 入口校验零尺寸，防止 createScaledBitmap/compress 在异常 Bitmap 上崩溃
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            throw IllegalArgumentException("Bitmap 尺寸非法: ${bitmap.width}x${bitmap.height}")
        }
        // 限制最大尺寸为 1536px，避免高分辨率截图产生超大 base64 字符串
        val maxDim = 1536
        val scale = if (bitmap.width > maxDim || bitmap.height > maxDim) {
            maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
        } else {
            1.0f
        }
        val source = if (scale < 1.0f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else {
            bitmap
        }
        try {
            val outputStream = ByteArrayOutputStream()
            source.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
            val byteArray = outputStream.toByteArray()
            return Pair(Base64.encodeToString(byteArray, Base64.NO_WRAP), scale)
        } finally {
            // 确保 scaled bitmap 在异常路径（OOM 等）也被回收
            if (source !== bitmap) {
                try {
                    source.recycle()
                } catch (ignore: Exception) {
                    Log.w("MangaTranslator", "bitmapToBase64 recycle 异常: ${ignore.message}")
                }
            }
        }
    }

    private fun buildPrompt(imageWidth: Int, imageHeight: Int): String {
        return """你是漫画翻译专家。请分析这张漫画图片：

任务：
1. 识别图片中所有对话气泡的位置（像素坐标）
2. 识别每个气泡内的日文原文
3. 将日文翻译成自然中文
4. 判断文字是竖排还是横排
5. 按阅读顺序（日漫从右到左、从上到下）编号

输出严格JSON格式，不要其他内容：
{
  "bubbles": [
    {
      "x": 气泡左上角X像素,
      "y": 气泡左上角Y像素,
      "width": 气泡宽度像素,
      "height": 气泡高度像素,
      "text": "日文原文",
      "is_vertical": true或false,
      "translation": "中文翻译",
      "reading_order": 1
    }
  ]
}

坐标基于图片尺寸 ${imageWidth}x$imageHeight。
如果图片中没有对话气泡，返回 {"bubbles": []}。
仔细识别所有气泡，包括小的、不规则形状的。
翻译要自然流畅，保留漫画对白的语气。"""
    }

    private fun callMimoVisionApi(base64Image: String, prompt: String): String {
        val apiKey = mimoTranslator.getApiKey()
        val baseUrl = mimoTranslator.getBaseUrl()
        val model = mimoTranslator.getModel()

        val request = mapOf(
            "model" to model,
            "messages" to listOf(
                mapOf(
                    "role" to "user",
                    "content" to listOf(
                        mapOf("type" to "text", "text" to prompt),
                        mapOf(
                            "type" to "image_url",
                            "image_url" to mapOf(
                                "url" to "data:image/jpeg;base64,$base64Image",
                            ),
                        ),
                    ),
                ),
            ),
            "temperature" to 0.1,
            "max_tokens" to 8192,
        )

        val requestBody = gson.toJson(request)
            .toRequestBody("application/json".toMediaType())

        val httpRequest = Request.Builder()
            .url(baseUrl)
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $apiKey")
            .build()

        val response = client.newCall(httpRequest).execute()
        response.use { resp ->
            val responseBody = resp.body?.string() ?: throw Exception("响应为空")

            if (!resp.isSuccessful) {
                throw Exception("HTTP错误: ${resp.code}")
            }

            val result = gson.fromJson(responseBody, MimoTranslator.ChatResponse::class.java)

            if (result.error != null) {
                throw Exception("MiMo错误: ${result.error.message}")
            }

            return result.choices?.firstOrNull()?.message?.content
                ?: throw Exception("识别结果为空")
        }
    }

    private fun parseResponse(response: String, imageWidth: Int, imageHeight: Int): AiAnalysisResult {
        return try {
            val jsonStr = response.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            val result = gson.fromJson(jsonStr, AiDetectionResult::class.java)

            val bubbles = result.bubbles?.mapNotNull { bubble ->
                val x = bubble.x ?: return@mapNotNull null
                val y = bubble.y ?: return@mapNotNull null
                val width = bubble.width ?: return@mapNotNull null
                val height = bubble.height ?: return@mapNotNull null
                val text = bubble.text ?: return@mapNotNull null
                val translation = bubble.translation ?: return@mapNotNull null

                val safeX = x.coerceIn(0, imageWidth - 1)
                val safeY = y.coerceIn(0, imageHeight - 1)
                val safeWidth = width.coerceIn(1, imageWidth - safeX)
                val safeHeight = height.coerceIn(1, imageHeight - safeY)

                AiBubbleResult(
                    x = safeX,
                    y = safeY,
                    width = safeWidth,
                    height = safeHeight,
                    text = text,
                    isVertical = bubble.isVertical ?: false,
                    translation = translation,
                    readingOrder = bubble.readingOrder ?: 0,
                )
            } ?: emptyList()

            AiAnalysisResult(bubbles, response)
        } catch (e: Exception) {
            // 不再吞掉异常静默返回空列表，改为通过 error 字段把错误信息透出给调用方决定回退还是报错
            Log.e(TAG, "解析AI响应失败: ${e.message}")
            Log.d(TAG, "原始响应: $response")
            AiAnalysisResult(emptyList(), response, error = "解析AI响应失败: ${e.message}")
        }
    }

    private data class AiDetectionResult(
        val bubbles: List<AiBubble>?,
    )

    private data class AiBubble(
        val x: Int?,
        val y: Int?,
        val width: Int?,
        val height: Int?,
        val text: String?,
        val isVertical: Boolean?,
        val translation: String?,
        @SerializedName("reading_order") val readingOrder: Int?,
    )
}

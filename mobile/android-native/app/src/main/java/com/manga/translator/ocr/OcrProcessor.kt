package com.manga.translator.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

class OcrProcessor(private val context: Context) {

    companion object {
        private const val TAG = "MangaTranslator"
        private const val TIMEOUT_SECONDS = 15L
        private const val MIN_TEXT_LENGTH = 2
        private const val MAX_OCR_SIZE = 1600
        private const val OVERLAP_THRESHOLD = 45

        // Read the full screen. App UI and stale overlays are filtered elsewhere.
        private const val CROP_TOP_RATIO = 0.0f
        private const val CROP_BOTTOM_RATIO = 1.0f

        // 应用UI文本黑名单（与 TextFilter 保持一致）
        private val UI_TEXT_BLACKLIST = setOf(
            "暂停翻译", "暂停翻真", "实时翻译", "手动翻译", "恢复翻译",
            "横向模式", "竖向模式", "开始翻译", "停止翻译", "设置",
            "翻译器", "漫画翻译", "正在运行", "翻译结果", "翻译中",
            "截图", "截图中", "正在截图", "截图完成"
        )
    }

    private val textRecognizer: TextRecognizer = TextRecognition.getClient(
        JapaneseTextRecognizerOptions.Builder().build()
    )

    // 每个识别结果单独返回，不合并
    data class OcrResult(
        val text: String,
        val boundingBox: Rect?,
        val confidence: Float = 0f
    )

    fun recognizeText(bitmap: Bitmap, verticalOnly: Boolean = false): List<OcrResult> {
        Log.d(TAG, "开始OCR识别，图片尺寸: ${bitmap.width}x${bitmap.height}，模式: ${if (verticalOnly) "竖向" else "横向"}")

        val cropTop = (bitmap.height * CROP_TOP_RATIO).toInt()
        val cropBottom = (bitmap.height * CROP_BOTTOM_RATIO).toInt()
        val croppedBitmap = Bitmap.createBitmap(bitmap, 0, cropTop, bitmap.width, cropBottom - cropTop)
        Log.d(TAG, "裁剪区域: top=$cropTop, bottom=$cropBottom")

        val scaledBitmap = ensureMinSize(croppedBitmap, 1100)
        val enhancedBitmap = enhanceForOcr(scaledBitmap, 1.6f)
        val binaryBitmap = binarizeForOcr(scaledBitmap)

        try {
            val horizontalResults: List<OcrResult>
            val verticalResults: List<OcrResult>

            if (verticalOnly) {
                val enhancedVertical = recognizeWithRotation(enhancedBitmap, 90f, scaledBitmap.width, scaledBitmap.height)
                val rawVertical = recognizeWithRotation(scaledBitmap, 90f, scaledBitmap.width, scaledBitmap.height)
                val binaryVertical = recognizeWithRotation(binaryBitmap, 90f, scaledBitmap.width, scaledBitmap.height)
                verticalResults = deduplicateResults(deduplicateResults(enhancedVertical, rawVertical), binaryVertical)
                horizontalResults = emptyList()
            } else {
                val horizontalFuture = CompletableFuture.supplyAsync {
                    val enhanced = recognizeWithRotation(enhancedBitmap, 0f, scaledBitmap.width, scaledBitmap.height)
                    val raw = recognizeWithRotation(scaledBitmap, 0f, scaledBitmap.width, scaledBitmap.height)
                    val binary = recognizeWithRotation(binaryBitmap, 0f, scaledBitmap.width, scaledBitmap.height)
                    deduplicateResults(deduplicateResults(enhanced, raw), binary)
                }
                val verticalFuture = CompletableFuture.supplyAsync {
                    val enhanced = recognizeWithRotation(enhancedBitmap, 90f, scaledBitmap.width, scaledBitmap.height)
                    val raw = recognizeWithRotation(scaledBitmap, 90f, scaledBitmap.width, scaledBitmap.height)
                    val binary = recognizeWithRotation(binaryBitmap, 90f, scaledBitmap.width, scaledBitmap.height)
                    deduplicateResults(deduplicateResults(enhanced, raw), binary)
                }
                horizontalResults = horizontalFuture.get()
                verticalResults = verticalFuture.get()
            }
            Log.d(TAG, "横向识别: ${horizontalResults.size} 个，竖向识别: ${verticalResults.size} 个")

            val allResults = deduplicateResults(horizontalResults, verticalResults)

            val scaleX = croppedBitmap.width.toFloat() / scaledBitmap.width.toFloat()
            val scaleY = croppedBitmap.height.toFloat() / scaledBitmap.height.toFloat()
            val scaledResults = allResults.map { result ->
                result.copy(
                    boundingBox = result.boundingBox?.let { rect ->
                        Rect(
                            (rect.left * scaleX).toInt(),
                            (rect.top * scaleY + cropTop).toInt(),
                            (rect.right * scaleX).toInt(),
                            (rect.bottom * scaleY + cropTop).toInt()
                        )
                    }
                )
            }

            Log.d(TAG, "最终结果: ${scaledResults.size} 个")
            Log.d(TAG, "方向合并后: ${scaledResults.size} 个")
            return scaledResults
        } finally {
            if (enhancedBitmap !== scaledBitmap) try { enhancedBitmap.recycle() } catch (_: Exception) {}
            if (binaryBitmap !== scaledBitmap) try { binaryBitmap.recycle() } catch (_: Exception) {}
            if (scaledBitmap !== croppedBitmap) try { scaledBitmap.recycle() } catch (_: Exception) {}
            if (croppedBitmap != bitmap) try { croppedBitmap.recycle() } catch (_: Exception) {}
        }
    }
    
    private fun deduplicateResults(
        horizontal: List<OcrResult>,
        vertical: List<OcrResult>
    ): List<OcrResult> {
        val all = mutableListOf<OcrResult>()
        for (result in horizontal + vertical) {
            var duplicateIndex = -1
            for (i in all.indices) {
                val existing = all[i]
                val overlap = calculateOverlapRatio(result.boundingBox, existing.boundingBox)
                val duplicate = when {
                    overlap > 0.70f -> true
                    overlap > 0.55f -> isTextSimilar(result.text, existing.text)
                    else -> false
                }
                if (duplicate) {
                    duplicateIndex = i
                    break
                }
            }

            if (duplicateIndex >= 0) {
                if (isBetterOcrResult(result, all[duplicateIndex])) {
                    all[duplicateIndex] = result
                }
            } else {
                all.add(result)
            }
        }
        return all
    }

    private fun isBetterOcrResult(candidate: OcrResult, current: OcrResult): Boolean {
        val candidateScore = candidate.text.length * 0.7f + candidate.confidence * 10f
        val currentScore = current.text.length * 0.7f + current.confidence * 10f
        return candidateScore > currentScore
    }

    private fun isTextSimilar(text1: String, text2: String): Boolean {
        if (text1 == text2) return true
        if (text1.contains(text2) || text2.contains(text1)) return true
        val clean1 = text1.replace(" ", "").replace("　", "")
        val clean2 = text2.replace(" ", "").replace("　", "")
        if (clean1 == clean2) return true
        if (clean1.contains(clean2) || clean2.contains(clean1)) return true
        if (clean1.length < 3 || clean2.length < 3) return false
        return calculateSimilarity(clean1, clean2) > 0.7f
    }

    private fun calculateSimilarity(s1: String, s2: String): Float {
        if (s1.isEmpty() || s2.isEmpty()) return 0f
        val maxLen = max(s1.length, s2.length)
        val distance = levenshteinDistance(s1, s2)
        return 1f - (distance.toFloat() / maxLen)
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[s1.length][s2.length]
    }

    private fun ensureMinSize(bitmap: Bitmap, minSize: Int): Bitmap {
        if (bitmap.width >= minSize && bitmap.height >= minSize) return bitmap
        val scale = minSize.toFloat() / min(bitmap.width, bitmap.height).toFloat()
        val newWidth = (bitmap.width * scale).toInt()
        val newHeight = (bitmap.height * scale).toInt()
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    private fun enhanceForOcr(bitmap: Bitmap, contrast: Float): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val needScale = width > MAX_OCR_SIZE || height > MAX_OCR_SIZE
        val targetWidth = if (needScale) (width * MAX_OCR_SIZE.toFloat() / max(width, height)).toInt() else width
        val targetHeight = if (needScale) (height * MAX_OCR_SIZE.toFloat() / max(width, height)).toInt() else height

        val result = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        if (needScale) {
            val scale = targetWidth.toFloat() / width.toFloat()
            canvas.scale(scale, scale)
        }

        val paint = Paint().apply {
            val cm = ColorMatrix(floatArrayOf(0.33f, 0.33f, 0.33f, 0f, 0f, 0.33f, 0.33f, 0.33f, 0f, 0f, 0.33f, 0.33f, 0.33f, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
            val translate = (-0.5f * contrast + 0.5f) * 255f
            val contrastMatrix = ColorMatrix(floatArrayOf(contrast, 0f, 0f, 0f, translate, 0f, contrast, 0f, 0f, translate, 0f, 0f, contrast, 0f, translate, 0f, 0f, 0f, 1f, 0f))
            contrastMatrix.postConcat(cm)
            colorFilter = ColorMatrixColorFilter(contrastMatrix)
        }
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return result
    }

    private fun binarizeForOcr(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val needScale = width > MAX_OCR_SIZE || height > MAX_OCR_SIZE
        val targetWidth = if (needScale) (width * MAX_OCR_SIZE.toFloat() / max(width, height)).toInt() else width
        val targetHeight = if (needScale) (height * MAX_OCR_SIZE.toFloat() / max(width, height)).toInt() else height
        val source = if (needScale) Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true) else bitmap
        val result = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)

        val pixels = IntArray(targetWidth * targetHeight)
        source.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)

        var sum = 0L
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            sum += (r * 30 + g * 59 + b * 11) / 100
        }
        val avg = (sum / pixels.size).toInt()
        val threshold = avg.coerceIn(120, 205)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val gray = (r * 30 + g * 59 + b * 11) / 100
            val v = if (gray < threshold) 0 else 255
            pixels[i] = 0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
        }

        result.setPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)
        if (source !== bitmap) source.recycle()
        return result
    }

    private fun recognizeWithRotation(bitmap: Bitmap, degrees: Float, origWidth: Int, origHeight: Int): List<OcrResult> {
        if (degrees == 0f) return recognizeBitmap(bitmap, origWidth, origHeight, 0f)
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        val results = recognizeBitmap(rotatedBitmap, origWidth, origHeight, degrees)
        rotatedBitmap.recycle()
        return results
    }

    private fun recognizeBitmap(bitmap: Bitmap, origWidth: Int, origHeight: Int, degrees: Float): List<OcrResult> {
        val results = mutableListOf<OcrResult>()
        val latch = CountDownLatch(1)
        val image = InputImage.fromBitmap(bitmap, 0)

        textRecognizer.process(image)
            .addOnSuccessListener { visionText ->
                for (block in visionText.textBlocks) {
                    val lines = block.lines
                    if (lines.isEmpty()) continue

                    val lineInfos = mutableListOf<Triple<String, Rect?, Float>>()
                    var blockBoundingBox: Rect? = null

                    for (line in lines) {
                        val text = line.text.trim()
                        if (text.isEmpty()) continue

                        val lineBox = line.boundingBox
                        val confidences = line.elements.mapNotNull { it.confidence }
                        val avgConfidence = if (confidences.isEmpty()) 1f else confidences.average().toFloat()
                        lineInfos.add(Triple(text, lineBox, avgConfidence))

                        if (lineBox != null) {
                            blockBoundingBox = if (blockBoundingBox == null) {
                                Rect(lineBox)
                            } else {
                                Rect(
                                    minOf(blockBoundingBox.left, lineBox.left),
                                    minOf(blockBoundingBox.top, lineBox.top),
                                    maxOf(blockBoundingBox.right, lineBox.right),
                                    maxOf(blockBoundingBox.bottom, lineBox.bottom)
                                )
                            }
                        }
                    }

                    if (lineInfos.isEmpty()) continue

                    val isVerticalBlock = blockBoundingBox?.let {
                        it.height() > it.width() * 1.5
                    } ?: false

                    val sortedLines = if (isVerticalBlock) {
                        lineInfos.sortedWith(compareByDescending<Triple<String, Rect?, Float>> {
                            it.second?.centerX() ?: 0
                        }.thenBy {
                            it.second?.top ?: 0
                        })
                    } else {
                        lineInfos.sortedWith(compareBy<Triple<String, Rect?, Float>> {
                            it.second?.top ?: 0
                        }.thenBy {
                            it.second?.left ?: 0
                        })
                    }

                    val mergedText = sortedLines.joinToString("") { it.first }
                    val avgConfidence = sortedLines.map { it.third }.average().toFloat()

                    if (mergedText.length < MIN_TEXT_LENGTH) continue
                    if (isAppUiText(mergedText)) continue
                    if (avgConfidence < 0.05f) continue

                    val originalBox = blockBoundingBox?.let {
                        mapRectBack(it, bitmap.width, bitmap.height, origWidth, origHeight, degrees)
                    }
                    results.add(OcrResult(mergedText, originalBox, avgConfidence))
                    Log.d(TAG, "识别: $mergedText (置信度: $avgConfidence, 竖向: $isVerticalBlock)")
                }
                latch.countDown()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "OCR失败: ${e.message}")
                latch.countDown()
            }

        latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return results
    }

    private fun mapRectBack(rect: Rect?, rotWidth: Int, rotHeight: Int, origWidth: Int, origHeight: Int, degrees: Float): Rect? {
        if (rect == null) return null
        return when (degrees) {
            90f -> Rect(rect.top, origHeight - rect.right, rect.bottom, origHeight - rect.left)
            180f -> Rect(origWidth - rect.right, origHeight - rect.bottom, origWidth - rect.left, origHeight - rect.top)
            270f -> Rect(origWidth - rect.bottom, rect.left, origWidth - rect.top, rect.right)
            else -> rect
        }
    }

    private fun calculateOverlapRatio(r1: Rect?, r2: Rect?): Float {
        if (r1 == null || r2 == null) return 0f
        val overlapX = max(0, min(r1.right, r2.right) - max(r1.left, r2.left))
        val overlapY = max(0, min(r1.bottom, r2.bottom) - max(r1.top, r2.top))
        val overlapArea = overlapX * overlapY
        val minArea = min(r1.width() * r1.height(), r2.width() * r2.height())
        if (minArea <= 0) return 0f
        return overlapArea.toFloat() / minArea.toFloat()
    }

    private fun isAppUiText(text: String): Boolean {
        if (UI_TEXT_BLACKLIST.contains(text)) return true
        val keywords = listOf("暂停", "翻译", "实时", "手动", "模式", "开始", "停止", "设置")
        for (keyword in keywords) {
            if (text.contains(keyword) && isPureChinese(text) && text.length <= 6) return true
        }
        return false
    }

    private fun isPureChinese(text: String): Boolean {
        var hasKanji = false
        var hasKana = false
        for (char in text) {
            val code = char.code
            if (code in 0x3040..0x309F || code in 0x30A0..0x30FF) hasKana = true
            if (code in 0x4E00..0x9FFF) hasKanji = true
        }
        return hasKanji && !hasKana
    }

    fun close() {
        textRecognizer.close()
    }
}

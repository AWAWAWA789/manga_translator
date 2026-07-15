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
import com.manga.translator.util.TextFilter
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

        // OCR 专用线程池：6 遍识别全并行，避免横向/竖向串行等待
        private val ocrExecutor = java.util.concurrent.Executors.newFixedThreadPool(6) { r ->
            Thread(r, "OcrWorker").apply { isDaemon = true }
        }
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
            // 6 遍全并行策略：每个版本(enhanced/raw/binary) × 每个方向(0°/90°) 独立提交到线程池
            // 相比旧的"横竖向并行但内部串行"，总耗时从 3×15s=45s 降至 max(15s)=15s
            // 注意：ML Kit TextRecognizer 内部是线程安全的，可并发调用 process()
            val futures = mutableListOf<CompletableFuture<List<OcrResult>>>()

            // 竖向版本（90° 旋转）
            futures.add(CompletableFuture.supplyAsync({
                recognizeWithRotation(enhancedBitmap, 90f, scaledBitmap.width, scaledBitmap.height)
            }, ocrExecutor))
            futures.add(CompletableFuture.supplyAsync({
                recognizeWithRotation(scaledBitmap, 90f, scaledBitmap.width, scaledBitmap.height)
            }, ocrExecutor))
            futures.add(CompletableFuture.supplyAsync({
                recognizeWithRotation(binaryBitmap, 90f, scaledBitmap.width, scaledBitmap.height)
            }, ocrExecutor))

            // 横向版本（0°）仅在非竖向模式时执行
            if (!verticalOnly) {
                futures.add(CompletableFuture.supplyAsync({
                    recognizeWithRotation(enhancedBitmap, 0f, scaledBitmap.width, scaledBitmap.height)
                }, ocrExecutor))
                futures.add(CompletableFuture.supplyAsync({
                    recognizeWithRotation(scaledBitmap, 0f, scaledBitmap.width, scaledBitmap.height)
                }, ocrExecutor))
                futures.add(CompletableFuture.supplyAsync({
                    recognizeWithRotation(binaryBitmap, 0f, scaledBitmap.width, scaledBitmap.height)
                }, ocrExecutor))
            }

            // 等待全部完成，单遍最长 20s 超时
            val results = futures.mapIndexed { idx, future ->
                try {
                    future.get(20, TimeUnit.SECONDS)
                } catch (e: java.util.concurrent.TimeoutException) {
                    Log.e(TAG, "OCR 第${idx + 1}遍超时")
                    emptyList()
                } catch (e: Exception) {
                    Log.e(TAG, "OCR 第${idx + 1}遍异常: ${e.message}")
                    emptyList()
                }
            }

            // 逐层去重合并
            val allResults = results.reduce { acc, list -> deduplicateResults(acc, list) }
            Log.d(TAG, "方向合并后: ${allResults.size} 个")

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
        return com.manga.translator.util.StringUtils.similarity(s1, s2)
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
        if (degrees == 0f) {
            // 0度不需要创建副本，bitmap 由调用方管理
            val (results, _) = recognizeBitmap(bitmap, origWidth, origHeight, 0f)
            return results
        }
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        val (results, completed) = recognizeBitmap(rotatedBitmap, origWidth, origHeight, degrees)
        // 仅在回调已完成时回收，超时则由 GC 处理，避免 ML Kit 仍在访问已回收的 Bitmap
        if (completed) rotatedBitmap.recycle()
        return results
    }

    private fun recognizeBitmap(bitmap: Bitmap, origWidth: Int, origHeight: Int, degrees: Float): Pair<List<OcrResult>, Boolean> {
        val results = mutableListOf<OcrResult>()
        val latch = CountDownLatch(1)
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        val image = InputImage.fromBitmap(bitmap, 0)

        textRecognizer.process(image)
            .addOnSuccessListener { visionText ->
                completed.set(true)
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
                completed.set(true)
                Log.e(TAG, "OCR失败: ${e.message}")
                latch.countDown()
            }

        latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return Pair(results, completed.get())
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
        return TextFilter.isAppUiText(text)
    }

    private fun isPureChinese(text: String): Boolean {
        return TextFilter.isPureChinese(text)
    }

    fun close() {
        textRecognizer.close()
    }
}

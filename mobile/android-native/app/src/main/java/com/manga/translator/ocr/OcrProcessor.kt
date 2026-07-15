package com.manga.translator.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.manga.translator.util.AppLog
import com.manga.translator.util.TextFilter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

class OcrProcessor(private val context: Context) {

    companion object {
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
        JapaneseTextRecognizerOptions.Builder().build(),
    )

    // 每个识别结果单独返回，不合并
    data class OcrResult(
        val text: String,
        val boundingBox: Rect?,
        val confidence: Float = 0f,
        // 由 recognizeBitmap 基于 isVerticalBlock 直接传出，避免上层二次推断导致判定不一致
        val isVertical: Boolean = false,
    )

    fun recognizeText(bitmap: Bitmap, verticalOnly: Boolean = false): List<OcrResult> {
        // 零尺寸校验，防止上游传入异常 Bitmap 导致 createBitmap 崩溃
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            AppLog.w("OcrProcessor", "recognizeText 收到零尺寸 Bitmap: ${bitmap.width}x${bitmap.height}")
            return emptyList()
        }
        AppLog.d("OcrProcessor", "开始OCR识别，图片尺寸: ${bitmap.width}x${bitmap.height}，模式: ${if (verticalOnly) "竖向" else "横向"}")

        val cropTop = (bitmap.height * CROP_TOP_RATIO).toInt()
        val cropBottom = (bitmap.height * CROP_BOTTOM_RATIO).toInt()
        val croppedBitmap = Bitmap.createBitmap(bitmap, 0, cropTop, bitmap.width, cropBottom - cropTop)
        AppLog.d("OcrProcessor", "裁剪区域: top=$cropTop, bottom=$cropBottom")

        val scaledBitmap = ensureMinSize(croppedBitmap, 1100)
        val enhancedBitmap = enhanceForOcr(scaledBitmap, 1.6f)
        val binaryBitmap = binarizeForOcr(scaledBitmap)

        try {
            // 6 遍全并行策略：每个版本(enhanced/raw/binary) × 每个方向(0°/90°) 独立提交到线程池
            // 相比旧的"横竖向并行但内部串行"，总耗时从 3×15s=45s 降至 max(15s)=15s
            // 注意：ML Kit TextRecognizer 内部是线程安全的，可并发调用 process()
            val futures = mutableListOf<CompletableFuture<List<OcrResult>>>()

            // 竖向版本（90° 旋转）
            futures.add(
                CompletableFuture.supplyAsync({
                    recognizeWithRotation(enhancedBitmap, 90f, scaledBitmap.width, scaledBitmap.height)
                }, ocrExecutor),
            )
            futures.add(
                CompletableFuture.supplyAsync({
                    recognizeWithRotation(scaledBitmap, 90f, scaledBitmap.width, scaledBitmap.height)
                }, ocrExecutor),
            )
            futures.add(
                CompletableFuture.supplyAsync({
                    recognizeWithRotation(binaryBitmap, 90f, scaledBitmap.width, scaledBitmap.height)
                }, ocrExecutor),
            )

            // 横向版本（0°）仅在非竖向模式时执行
            if (!verticalOnly) {
                futures.add(
                    CompletableFuture.supplyAsync({
                        recognizeWithRotation(enhancedBitmap, 0f, scaledBitmap.width, scaledBitmap.height)
                    }, ocrExecutor),
                )
                futures.add(
                    CompletableFuture.supplyAsync({
                        recognizeWithRotation(scaledBitmap, 0f, scaledBitmap.width, scaledBitmap.height)
                    }, ocrExecutor),
                )
                futures.add(
                    CompletableFuture.supplyAsync({
                        recognizeWithRotation(binaryBitmap, 0f, scaledBitmap.width, scaledBitmap.height)
                    }, ocrExecutor),
                )
            }

            // 等待全部完成，单遍最长 20s 超时
            val results = futures.mapIndexed { idx, future ->
                try {
                    future.get(20, TimeUnit.SECONDS)
                } catch (e: java.util.concurrent.TimeoutException) {
                    AppLog.e("OcrProcessor", "OCR 第${idx + 1}遍超时")
                    emptyList()
                } catch (e: Exception) {
                    AppLog.e("OcrProcessor", "OCR 第${idx + 1}遍异常: ${e.message}")
                    emptyList()
                }
            }

            // 逐层去重合并
            val allResults = results.reduce { acc, list -> deduplicateResults(acc, list) }
            AppLog.d("OcrProcessor", "方向合并后: ${allResults.size} 个")

            val scaleX = croppedBitmap.width.toFloat() / scaledBitmap.width.toFloat()
            val scaleY = croppedBitmap.height.toFloat() / scaledBitmap.height.toFloat()
            val scaledResults = allResults.map { result ->
                result.copy(
                    boundingBox = result.boundingBox?.let { rect ->
                        Rect(
                            (rect.left * scaleX).toInt(),
                            (rect.top * scaleY + cropTop).toInt(),
                            (rect.right * scaleX).toInt(),
                            (rect.bottom * scaleY + cropTop).toInt(),
                        )
                    },
                )
            }

            AppLog.d("OcrProcessor", "最终结果: ${scaledResults.size} 个")
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
        vertical: List<OcrResult>,
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
            val cm =
                ColorMatrix(
                    floatArrayOf(0.33f, 0.33f, 0.33f, 0f, 0f, 0.33f, 0.33f, 0.33f, 0f, 0f, 0.33f, 0.33f, 0.33f, 0f, 0f, 0f, 0f, 0f, 1f, 0f),
                )
            val translate = (-0.5f * contrast + 0.5f) * 255f
            val contrastMatrix =
                ColorMatrix(
                    floatArrayOf(contrast, 0f, 0f, 0f, translate, 0f, contrast, 0f, 0f, translate, 0f, 0f, contrast, 0f, translate, 0f, 0f, 0f, 1f, 0f),
                )
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

        try {
            val pixels = IntArray(targetWidth * targetHeight)
            source.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)

            var sum = 0L
            for (pixel in pixels) {
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                sum += (r * 30 + g * 59 + b * 11) / 100
            }
            val avg = if (pixels.isEmpty()) 128 else (sum / pixels.size).toInt()
            // 放宽阈值范围，避免极端图像（全黑/全白背景漫画）被强制使用 120 或 205 导致 OCR 失效
            val threshold = avg.coerceIn(80, 220)

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
            return result
        } catch (e: Exception) {
            // 异常路径回收 result，避免泄漏
            try {
                result.recycle()
            } catch (_: Exception) {
            }
            throw e
        } finally {
            // 确保 scaled source 在异常路径也被回收
            if (source !== bitmap) {
                try {
                    source.recycle()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun recognizeWithRotation(bitmap: Bitmap, degrees: Float, origWidth: Int, origHeight: Int): List<OcrResult> {
        if (degrees == 0f) {
            // 0度不需要创建副本，bitmap 由调用方管理
            // origWidth/origHeight 是 scaledBitmap 尺寸，bitmap 可能是 enhanced/binary（已缩放到≤1600）
            // recognizeBitmap 内部会做坐标缩放
            return recognizeBitmap(bitmap, bitmap.width, bitmap.height, origWidth, origHeight, 0f)
        }
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        try {
            // 传入 rotatedBitmap 的实际尺寸用于 mapRectBack，origWidth/origHeight 是 scaledBitmap 尺寸用于最终缩放
            return recognizeBitmap(rotatedBitmap, rotatedBitmap.width, rotatedBitmap.height, origWidth, origHeight, degrees)
        } finally {
            // Tasks.await 是同步的，返回时 ML Kit 已完成访问，安全回收
            rotatedBitmap.recycle()
        }
    }

    /**
     * 同步执行 ML Kit OCR，使用 Tasks.await 替代 latch+异步回调，彻底消除 Bitmap 回收竞态。
     *
     * 坐标系说明：
     * - rectBitmapWidth/Height：ML Kit 返回的 rect 所在 bitmap（rotated 或原 bitmap）的实际尺寸
     * - origWidth/Height：scaledBitmap 的尺寸，用于最终把 rect 缩放回 scaledBitmap 坐标系
     * - degrees：旋转角度，用于把 rotated bitmap 空间的 rect 逆变换回原 bitmap 空间
     *
     * 流程：rect（rotatedBitmap 空间）→ mapRectBack（转回 bitmap 空间）→ 缩放到 origWidth/Height 空间
     */
    private fun recognizeBitmap(
        bitmap: Bitmap,
        rectBitmapWidth: Int,
        rectBitmapHeight: Int,
        origWidth: Int,
        origHeight: Int,
        degrees: Float,
    ): List<OcrResult> {
        val results = mutableListOf<OcrResult>()
        val image = InputImage.fromBitmap(bitmap, 0)

        val visionText = try {
            // 同步等待 ML Kit 结果，超时抛 TimeoutException
            Tasks.await(textRecognizer.process(image), TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: Exception) {
            AppLog.e("OcrProcessor", "OCR失败: ${e.message}")
            return results
        }

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
                            maxOf(blockBoundingBox.bottom, lineBox.bottom),
                        )
                    }
                }
            }

            if (lineInfos.isEmpty()) continue

            val isVerticalBlock = blockBoundingBox?.let {
                it.height() > it.width() * 1.5
            } ?: false

            val sortedLines = if (isVerticalBlock) {
                lineInfos.sortedWith(
                    compareByDescending<Triple<String, Rect?, Float>> {
                        it.second?.centerX() ?: 0
                    }.thenBy {
                        it.second?.top ?: 0
                    },
                )
            } else {
                lineInfos.sortedWith(
                    compareBy<Triple<String, Rect?, Float>> {
                        it.second?.top ?: 0
                    }.thenBy {
                        it.second?.left ?: 0
                    },
                )
            }

            val mergedText = sortedLines.joinToString("") { it.first }
            val avgConfidence = sortedLines.map { it.third }.average().toFloat()

            if (mergedText.length < MIN_TEXT_LENGTH) continue
            if (isAppUiText(mergedText)) continue
            if (avgConfidence < 0.05f) continue

            // mapRectBack 把 rect 从 rotatedBitmap 空间转回 bitmap 空间（基于 rectBitmapWidth/Height）
            val originalBox = blockBoundingBox?.let {
                mapRectBack(it, rectBitmapWidth, rectBitmapHeight, origWidth, origHeight, degrees)
            }
            results.add(OcrResult(mergedText, originalBox, avgConfidence, isVerticalBlock))
            AppLog.d("OcrProcessor", "识别: $mergedText (置信度: $avgConfidence, 竖向: $isVerticalBlock)")
        }
        return results
    }

    /**
     * 把 ML Kit 返回的 rect 从 rotated/processed bitmap 空间映射回 scaledBitmap 空间。
     *
     * @param rect ML Kit 返回的 rect（在 rectBitmapWidth × rectBitmapHeight 的 bitmap 上）
     * @param rectBitmapWidth ML Kit 处理的 bitmap 实际宽度（可能是 enhanced/binary 缩放后的尺寸）
     * @param rectBitmapHeight ML Kit 处理的 bitmap 实际高度
     * @param origWidth scaledBitmap 宽度（最终目标坐标系）
     * @param origHeight scaledBitmap 高度
     * @param degrees 旋转角度
     */
    private fun mapRectBack(
        rect: Rect?,
        rectBitmapWidth: Int,
        rectBitmapHeight: Int,
        origWidth: Int,
        origHeight: Int,
        degrees: Float,
    ): Rect? {
        if (rect == null) return null
        // 先在 rectBitmap 空间内做旋转变换
        val rotated = when (degrees) {
            90f -> Rect(rect.top, rectBitmapHeight - rect.right, rect.bottom, rectBitmapHeight - rect.left)
            180f -> Rect(
                rectBitmapWidth - rect.right,
                rectBitmapHeight - rect.bottom,
                rectBitmapWidth - rect.left,
                rectBitmapHeight - rect.top,
            )
            270f -> Rect(rectBitmapWidth - rect.bottom, rect.left, rectBitmapWidth - rect.top, rect.right)
            else -> rect
        }
        // 再从 rectBitmap 空间缩放到 origWidth × origHeight 空间
        // 90/270 度旋转后，rotated 的宽高对应关系：width↔rectBitmapHeight, height↔rectBitmapWidth
        val scaleX = when (degrees) {
            90f, 270f -> origWidth.toFloat() / rectBitmapHeight.toFloat()
            else -> origWidth.toFloat() / rectBitmapWidth.toFloat()
        }
        val scaleY = when (degrees) {
            90f, 270f -> origHeight.toFloat() / rectBitmapWidth.toFloat()
            else -> origHeight.toFloat() / rectBitmapHeight.toFloat()
        }
        return Rect(
            (rotated.left * scaleX).toInt(),
            (rotated.top * scaleY).toInt(),
            (rotated.right * scaleX).toInt(),
            (rotated.bottom * scaleY).toInt(),
        )
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

    /**
     * 释放 ML Kit TextRecognizer 资源。
     * 必须在 Service onDestroy 中调用，避免 native 资源泄漏。
     */
    fun close() {
        try {
            textRecognizer.close()
        } catch (e: Exception) {
            AppLog.w("OcrProcessor", "TextRecognizer close 异常: ${e.message}")
        }
    }
}

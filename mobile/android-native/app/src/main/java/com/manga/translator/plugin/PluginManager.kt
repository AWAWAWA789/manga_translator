package com.manga.translator.plugin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.manga.translator.debug.DebugOverlayData
import com.manga.translator.domain.detection.BubbleInfo
import com.manga.translator.domain.detection.PanelInfo
import com.manga.translator.domain.translation.TranslationRepository
import com.manga.translator.model.OcrBlock
import com.manga.translator.model.TranslationCard
import com.manga.translator.translation.TranslationPlugin
import com.manga.translator.util.AppLog
import com.manga.translator.util.ComicImageCropper
import com.manga.translator.util.OpenCVHelper
import com.manga.translator.util.PerfTracker
import com.manga.translator.util.ScreenCropConfig
import com.manga.translator.util.TextFilter
import kotlin.math.abs

/**
 * 翻译仓储实现：组合 OCR + 翻译 + 检测，编排图片翻译流程。
 *
 * 架构分层（阶段2）：
 * - 本类是 [TranslationRepository] 的实现（data 层），负责具体翻译编排
 * - presentation 层通过 [com.manga.translator.presentation.TranslationController] 调用，
 *   Controller 管理状态（cropConfig、useAiVisionMode 等）并通过 params 传入
 * - 状态不再持有于本类的 public API，仅保留内部可变字段供 translate(params) 使用
 */
class PluginManager(private val context: Context) : TranslationRepository {

    companion object {
        private const val MIN_REGION_AREA = 450
        private const val MAX_REGION_AREA_RATIO = 0.22f
        private const val MAX_TRANSLATION_REGIONS = 18
    }

    private data class TextRegion(
        val text: String,
        val textRect: Rect,
        var outputRect: Rect,
        val boundsRect: Rect,
        val ocrBlocks: List<OcrBlock>,
        val isVertical: Boolean,
    )

    private val ocrPlugin = OcrPlugin(context)
    private val translationPlugin = TranslationPlugin(context)
    private val bubbleDetector = BubbleDetector()
    private val aiVisionPipeline = AiVisionPipeline(context)
    private val panelDetector = PanelDetector()
    private val sentenceAssembler = SentenceAssembler()

    // 翻译串行化锁：ScreenCaptureService 的 isProcessing 在 8 秒超时强制重置后可能产生并发，
    // 用此锁确保 translateImage 串行执行，防止内部状态（lastDebugData 等）竞态。
    // 阶段 2 协程迁移后将被 Mutex 替代。
    private val translateLock = Any()

    // 跨线程访问的可变状态：@Volatile 保证可见性，对象本身为不可变 data class
    @Volatile private var isInitialized = false

    @Volatile private var cropConfig = ScreenCropConfig()

    @Volatile private var lastDebugData = DebugOverlayData()

    @Volatile private var useAiVisionMode = false

    override fun initialize() {
        if (isInitialized) return

        ocrPlugin.initialize()

        if (!OpenCVHelper.isInitialized()) {
            OpenCVHelper.initialize(context)
        }

        isInitialized = true
        AppLog.d("PluginManager", "插件管理器初始化完成")
    }

    /**
     * 翻译图片入口。
     *
     * Bitmap 生命周期契约：
     * - 此方法不回收传入的 bitmap，调用方负责回收
     * - 内部裁剪产生的 croppedBitmap 由本方法回收
     * - AI 路径的 base64 转换不持有 bitmap 引用
     *
     * @param bitmap 待翻译的截图，调用方负责 recycle
     * @param lastTranslationRects 上一帧翻译区域，用于去重过滤
     * @param verticalOnly 是否强制竖向识别模式
     * @param isManual 是否为手动翻译（影响 AI 路径选择）
     * @return 翻译卡片列表
     */
    private fun translateImage(
        bitmap: Bitmap,
        lastTranslationRects: List<Rect> = emptyList(),
        verticalOnly: Boolean = false,
        isManual: Boolean = false,
    ): List<TranslationCard> = synchronized(translateLock) {
        if (!isInitialized) {
            AppLog.e("PluginManager", "插件管理器未初始化")
            return emptyList()
        }

        val tracker = PerfTracker.start("PluginManager", "翻译图片")
        AppLog.d("PluginManager", "开始翻译图片: ${bitmap.width}x${bitmap.height}")
        AppLog.d("PluginManager", "=== PluginManager v3 已加载 ===")

        val cropRect = ComicImageCropper.getCropRect(bitmap.width, bitmap.height, cropConfig)
        val croppedBitmap = ComicImageCropper.cropComicArea(bitmap, cropConfig)
        AppLog.d("PluginManager", "裁剪后: ${croppedBitmap.width}x${croppedBitmap.height}")

        try {
            // AI 多模态路径：手动翻译 + AI开启 + MiMo已配置
            if (isManual && useAiVisionMode && aiVisionPipeline.isMiMoConfigured()) {
                try {
                    AppLog.d("PluginManager", "使用AI多模态识别")
                    val aiResult = aiVisionPipeline.analyzeImage(croppedBitmap, croppedBitmap.width, croppedBitmap.height)
                    tracker.end("AI多模态识别")
                    // 检查解析错误：parseResponse 失败时返回带 error 的结果，需回退 OCR 流程
                    if (aiResult.error != null) {
                        AppLog.w("PluginManager", "AI响应解析失败，回退OCR流程: ${aiResult.error}")
                    } else {
                        val rawCards = aiVisionPipeline.toTranslationCards(aiResult, lastTranslationRects)
                        val cards = rawCards.map { card -> card.withOffset(cropRect.left, cropRect.top) }
                        AppLog.d("PluginManager", "AI多模态: ${cards.size} 个翻译结果")
                        // 用本次 AI 结果构建调试覆盖数据（裁剪坐标系，再统一应用偏移）
                        lastDebugData = DebugOverlayData(
                            bubbles = emptyList(),
                            ocrBlocks = rawCards.map { it.sourceRect },
                            mappings = rawCards.map { it.sourceRect to it.sourceRect },
                            orderedBubbles = rawCards.map { it.sourceRect },
                        ).withOffset(cropRect.left, cropRect.top)
                        return cards
                    }
                } catch (e: Exception) {
                    AppLog.e("PluginManager", "AI多模态失败，回退现有流程: ${e.message}")
                    // 继续走下面的现有流程
                }
            }

            val panelStart = System.currentTimeMillis()
            val panels = if (OpenCVHelper.isInitialized()) {
                panelDetector.detectPanels(croppedBitmap)
            } else {
                emptyList()
            }
            PerfTracker.record("PluginManager", "PanelDetect", System.currentTimeMillis() - panelStart)
            AppLog.d("PluginManager", "分镜检测: ${panels.size} 个分镜")

            val bubbleStart = System.currentTimeMillis()
            val bubbles = if (OpenCVHelper.isInitialized()) {
                bubbleDetector.detectBubbles(croppedBitmap)
            } else {
                emptyList()
            }
            PerfTracker.record("PluginManager", "BubbleDetect", System.currentTimeMillis() - bubbleStart)
            AppLog.d("PluginManager", "气泡检测: ${bubbles.size} 个气泡")

            val ocrBlocks = ocrPlugin.recognize(croppedBitmap, verticalOnly)
            tracker.end("OCR识别")
            AppLog.d("PluginManager", "OCR识别: ${ocrBlocks.size} 个块")

            val textFilteredBlocks = ocrBlocks.filter { TextFilter.isValidOcrText(it.text) }
            AppLog.d("PluginManager", "文本过滤后: ${textFilteredBlocks.size} 个块")

            val filteredBlocks = textFilteredBlocks
                .filter { block -> !overlapsAnyTranslationRect(block.rect, lastTranslationRects, cropRect) }
            AppLog.d("PluginManager", "覆盖层过滤后: ${filteredBlocks.size} 个块")

            val translatedCropResult = translateCore(
                panels = panels,
                bubbles = bubbles,
                ocrBlocks = filteredBlocks,
                bitmap = croppedBitmap,
            )
            tracker.end("翻译")
            val result = translatedCropResult.map { card -> card.withOffset(cropRect.left, cropRect.top) }
            lastDebugData = lastDebugData.withOffset(cropRect.left, cropRect.top)

            return result
        } finally {
            tracker.finish()
            // 统一在 finally 中回收 croppedBitmap，避免异常路径下资源泄漏
            if (croppedBitmap != bitmap) {
                croppedBitmap.recycle()
            }
        }
    }

    private fun translateCore(
        panels: List<PanelInfo>,
        bubbles: List<BubbleInfo>,
        ocrBlocks: List<OcrBlock>,
        bitmap: Bitmap,
    ): List<TranslationCard> {
        if (ocrBlocks.isEmpty()) return emptyList()

        val debugBubbles = bubbles.map { it.rect }
        val debugOcrBlocks = ocrBlocks.map { it.rect }

        val debugMappings = mutableListOf<Pair<Rect, Rect>>()
        val orderedBubbles = mutableListOf<Rect>()

        val sentenceRegions = buildSentenceFirstRegions(ocrBlocks, panels, bubbles, bitmap)
        for (region in sentenceRegions) {
            val textRect = region.textRect
            val panel = if (panels.isNotEmpty()) panelDetector.findPanelForRect(panels, textRect) else null
            if (panel != null) {
                orderedBubbles.add(panel.rect)
                debugMappings.add(textRect to panel.rect)
            }
        }
        val regionCandidates = sentenceRegions

        val regions = suppressOverlappingRegions(regionCandidates)

        lastDebugData = DebugOverlayData(
            bubbles = debugBubbles,
            ocrBlocks = debugOcrBlocks,
            mappings = debugMappings,
            orderedBubbles = orderedBubbles,
        )

        val limitedRegions = limitRegions(regions)
        AppLog.d(
            "PluginManager",
            "=== 句子优先: 输入${ocrBlocks.size}块, 句子${sentenceRegions.size}个, 去重${regions.size}, 最终${limitedRegions.size}区域 ===",
        )

        val translatedTexts = translationPlugin.translateBatch(limitedRegions.map { it.text })
        return limitedRegions.mapIndexed { index, region ->
            TranslationCard(
                originalText = region.text,
                translatedText = translatedTexts.getOrNull(index).orEmpty(),
                sourceRect = region.outputRect,
                isVertical = region.isVertical,
            )
        }
    }

    private fun buildSentenceFirstRegions(
        ocrBlocks: List<OcrBlock>,
        panels: List<PanelInfo>,
        bubbles: List<BubbleInfo>,
        bitmap: Bitmap,
    ): List<TextRegion> {
        val blocks = dedupeBlocksInBubble(ocrBlocks)
        val sentenceGroups = sentenceAssembler.assemble(blocks)
        AppLog.d("PluginManager", "句子优先组装: ${ocrBlocks.size}块→去重${blocks.size}块→${sentenceGroups.size}句")

        return sentenceGroups.mapNotNull { sentenceBlocks ->
            val textRect = unionRects(sentenceBlocks.map { it.rect })
            val trustedBubble = findTrustedBubbleForSentence(bubbles, textRect)
            val boundsRect = trustedBubble ?: textBoundsWithMargin(textRect, bitmap.width, bitmap.height)
            val region = buildSentenceRegion(sentenceBlocks, boundsRect, trustedBubble, bitmap)
            if (region != null) {
                logSentenceDebug(sentenceBlocks, region)
            }
            region
        }
    }

    private fun logSentenceDebug(blocks: List<OcrBlock>, region: TextRegion) {
        val blockSummary = blocks.joinToString(" | ") { block ->
            "${block.text.trim()}@[${block.rect.left},${block.rect.top},${block.rect.right},${block.rect.bottom}]"
        }
        AppLog.d(
            "PluginManager",
            "句子调试: text='${region.text}' anchor=[${region.outputRect.centerX()},${region.outputRect.centerY()}] textRect=${region.textRect.toShortString()} out=${region.outputRect.toShortString()} blocks=$blockSummary",
        )
    }

    private fun textBoundsWithMargin(textRect: Rect, imageWidth: Int, imageHeight: Int): Rect {
        val bounds = Rect(textRect)
        bounds.inset((-textRect.width() * 0.15f).toInt(), (-textRect.height() * 0.12f).toInt())
        bounds.left = bounds.left.coerceAtLeast(0)
        bounds.top = bounds.top.coerceAtLeast(0)
        bounds.right = bounds.right.coerceAtMost(imageWidth)
        bounds.bottom = bounds.bottom.coerceAtMost(imageHeight)
        return bounds
    }

    private fun findTrustedBubbleForSentence(bubbles: List<BubbleInfo>, textRect: Rect): Rect? {
        if (bubbles.isEmpty()) return null
        val best = bubbles.maxByOrNull { bubble -> trustedBubbleScore(bubble.rect, textRect) } ?: return null
        val score = trustedBubbleScore(best.rect, textRect)
        // 阈值从 0.45 降到 0.40，提高气泡检出率，让更多文本能匹配到真实气泡框
        return if (score >= 0.40f) best.rect else null
    }

    private fun trustedBubbleScore(bubbleRect: Rect, textRect: Rect): Float {
        val overlap = rectOverlapRatio(textRect, bubbleRect)
        // overlap 阈值从 0.35 降到 0.30，让轻微偏移的文本也能命中气泡
        if (overlap > 0.30f) return 1f
        val expanded = Rect(bubbleRect)
        // 扩大匹配范围从 20% 到 25%，覆盖气泡边缘检测不完整的情况
        expanded.inset((-bubbleRect.width() * 0.25f).toInt(), (-bubbleRect.height() * 0.25f).toInt())
        if (Rect.intersects(expanded, textRect)) return 0.65f
        val dx = abs(textRect.centerX() - bubbleRect.centerX()).toFloat() / maxOf(1, bubbleRect.width())
        val dy = abs(textRect.centerY() - bubbleRect.centerY()).toFloat() / maxOf(1, bubbleRect.height())
        return (1f - (dx + dy)).coerceIn(0f, 1f) * 0.35f
    }

    private fun buildSentenceRegion(
        blocks: List<OcrBlock>,
        boundsRect: Rect,
        bubbleRect: Rect?,
        bitmap: Bitmap,
    ): TextRegion? {
        if (blocks.isEmpty()) return null
        val isVertical = blocks.count { it.isVertical } >= blocks.size / 2
        val sorted = if (isVertical) {
            blocks.sortedWith(compareByDescending<OcrBlock> { it.rect.centerX() }.thenBy { it.rect.top })
        } else {
            blocks.sortedWith(compareBy<OcrBlock> { it.rect.top }.thenBy { it.rect.left })
        }
        val text = sorted.joinToString("") { it.text.trim() }
        if (!TextFilter.isValidOcrText(text)) return null
        val textRect = unionRects(sorted.map { it.rect })
        val anchor = pickAnchorBlock(sorted, textRect)
        val estimated = if (bubbleRect != null) {
            val est = estimateRectByAnchor(anchor.rect, textRect, isVertical, trustedBubble = bubbleRect)
            constrainRectToAnchor(est, bubbleRect, anchor.rect, isVertical)
        } else {
            val est = estimateRectByAnchor(anchor.rect, textRect, isVertical, trustedBubble = null)
            constrainRectToAnchor(est, boundsRect, anchor.rect, isVertical)
        }
        if (!isValidOutputRect(estimated, bitmap.width, bitmap.height)) return null
        return TextRegion(
            text = text,
            textRect = textRect,
            outputRect = estimated,
            boundsRect = boundsRect,
            ocrBlocks = sorted,
            isVertical = isVertical,
        )
    }

    private fun pickAnchorBlock(blocks: List<OcrBlock>, textRect: Rect): OcrBlock {
        val anchor = blocks.minByOrNull { block ->
            val dx = abs(block.rect.centerX() - textRect.centerX()).toFloat()
            val dy = abs(block.rect.centerY() - textRect.centerY()).toFloat()
            dx + dy - block.confidence * 40f
        } ?: blocks.first()
        AppLog.d(
            "PluginManager",
            "锚点选择: textRect=${textRect.toShortString()} anchor='${anchor.text.trim()}' rect=${anchor.rect.toShortString()} conf=${anchor.confidence}",
        )
        return anchor
    }

    private fun estimateRectByAnchor(anchorRect: Rect, textRect: Rect, isVertical: Boolean, trustedBubble: Rect? = null): Rect {
        // 优先使用 BubbleDetector 检测到的气泡框，提升气泡定位准确性
        if (trustedBubble != null) {
            return Rect(trustedBubble)
        }
        // 无气泡框时回退到启发式估计：以锚点为中心，最小尺寸兜底
        val targetWidth = maxOf(textRect.width(), if (isVertical) 42 else 78)
        val targetHeight = maxOf(textRect.height(), if (isVertical) 88 else 36)
        val cx = anchorRect.centerX()
        val cy = anchorRect.centerY()
        return Rect(
            cx - targetWidth / 2,
            cy - targetHeight / 2,
            cx + targetWidth / 2,
            cy + targetHeight / 2,
        )
    }

    private fun dedupeBlocksInBubble(blocks: List<OcrBlock>): List<OcrBlock> {
        val kept = mutableListOf<OcrBlock>()
        for (candidate in blocks.sortedByDescending { it.text.length + it.confidence * 10f }) {
            val duplicateIndex = kept.indexOfFirst { existing ->
                val overlap = rectOverlapRatio(candidate.rect, existing.rect)
                when {
                    overlap > 0.70f -> true
                    overlap > 0.55f -> areTextsSimilar(candidate.text, existing.text)
                    else -> false
                }
            }
            if (duplicateIndex < 0) {
                kept.add(candidate)
            }
        }
        return kept
    }

    private fun areTextsSimilar(a: String, b: String): Boolean {
        val ca = a.trim().replace(" ", "").replace("　", "")
        val cb = b.trim().replace(" ", "").replace("　", "")
        if (ca == cb) return true
        if (ca.contains(cb) || cb.contains(ca)) return true
        if (ca.length <= 2 || cb.length <= 2) return false
        val common = ca.count { cb.contains(it) }
        return common.toFloat() / minOf(ca.length, cb.length).toFloat() > 0.7f
    }

    private fun limitRegions(regions: List<TextRegion>): List<TextRegion> {
        if (regions.size <= MAX_TRANSLATION_REGIONS) return regions
        return regions.sortedByDescending { region ->
            region.text.length + region.ocrBlocks.size * 4
        }.take(MAX_TRANSLATION_REGIONS)
            .sortedWith(compareBy<TextRegion> { it.outputRect.top }.thenByDescending { it.outputRect.left })
    }

    private fun suppressOverlappingRegions(regions: List<TextRegion>): List<TextRegion> {
        val kept = mutableListOf<TextRegion>()
        val sorted = regions.sortedByDescending { region ->
            region.text.length + region.ocrBlocks.size * 4
        }

        for (candidate in sorted) {
            val overlapIndex = kept.indexOfFirst { existing ->
                rectOverlapRatio(candidate.outputRect, existing.outputRect) > 0.55f
            }

            if (overlapIndex < 0) {
                kept.add(candidate)
            } else if (isBetterRegion(candidate, kept[overlapIndex])) {
                kept[overlapIndex] = candidate
            }
        }

        return kept.sortedWith(compareBy<TextRegion> { it.outputRect.top }.thenByDescending { it.outputRect.left })
    }

    private fun isBetterRegion(candidate: TextRegion, current: TextRegion): Boolean {
        val candidateScore = candidate.text.length + candidate.ocrBlocks.size * 4 - candidate.outputRect.width() * candidate.outputRect.height() / 2500
        val currentScore = current.text.length + current.ocrBlocks.size * 4 - current.outputRect.width() * current.outputRect.height() / 2500
        return candidateScore > currentScore
    }

    private fun unionRects(rects: List<Rect>): Rect {
        val union = Rect(rects.first())
        for (i in 1 until rects.size) union.union(rects[i])
        return union
    }

    private fun rectOverlapRatio(a: Rect, b: Rect): Float {
        val overlapLeft = maxOf(a.left, b.left)
        val overlapTop = maxOf(a.top, b.top)
        val overlapRight = minOf(a.right, b.right)
        val overlapBottom = minOf(a.bottom, b.bottom)
        if (overlapLeft >= overlapRight || overlapTop >= overlapBottom) return 0f
        val overlapArea = (overlapRight - overlapLeft) * (overlapBottom - overlapTop)
        val minArea = minOf(a.width() * a.height(), b.width() * b.height())
        if (minArea <= 0) return 0f
        return overlapArea.toFloat() / minArea.toFloat()
    }

    private fun constrainRectToAnchor(rect: Rect, anchorRect: Rect, textRect: Rect, isVertical: Boolean): Rect {
        if (anchorRect.isEmpty) return rect

        val constrained = Rect(rect)
        val maxWidth = if (isVertical) {
            maxOf(textRect.width() + 28, (anchorRect.width() * 0.55f).toInt())
        } else {
            maxOf(textRect.width() + 44, (anchorRect.width() * 0.70f).toInt())
        }
        val maxHeight = if (isVertical) {
            maxOf(textRect.height() + 42, (anchorRect.height() * 0.78f).toInt())
        } else {
            maxOf(textRect.height() + 32, (anchorRect.height() * 0.58f).toInt())
        }

        if (constrained.width() > maxWidth) {
            val cx = textRect.centerX().coerceIn(anchorRect.left, anchorRect.right)
            constrained.left = cx - maxWidth / 2
            constrained.right = constrained.left + maxWidth
        }
        if (constrained.height() > maxHeight) {
            val cy = textRect.centerY().coerceIn(anchorRect.top, anchorRect.bottom)
            constrained.top = cy - maxHeight / 2
            constrained.bottom = constrained.top + maxHeight
        }

        shiftInside(constrained, anchorRect)
        if (!Rect.intersects(constrained, anchorRect)) return rect
        return constrained
    }

    private fun shiftInside(rect: Rect, bounds: Rect) {
        if (rect.left < bounds.left) rect.offset(bounds.left - rect.left, 0)
        if (rect.right > bounds.right) rect.offset(bounds.right - rect.right, 0)
        if (rect.top < bounds.top) rect.offset(0, bounds.top - rect.top)
        if (rect.bottom > bounds.bottom) rect.offset(0, bounds.bottom - rect.bottom)
    }

    private fun isValidOutputRect(rect: Rect, imageWidth: Int, imageHeight: Int): Boolean {
        if (rect.width() <= 0 || rect.height() <= 0) return false
        if (rect.width() * rect.height() < MIN_REGION_AREA) return false
        if (imageWidth > 0 && imageHeight > 0) {
            val imageArea = imageWidth.toFloat() * imageHeight.toFloat()
            if (rect.width() * rect.height() > imageArea * MAX_REGION_AREA_RATIO) return false
        }
        val aspect = rect.width().toFloat() / rect.height().toFloat()
        return aspect in 0.12f..8f
    }

    private fun TranslationCard.withOffset(offsetX: Int, offsetY: Int): TranslationCard {
        if (offsetX == 0 && offsetY == 0) return this
        return copy(
            sourceRect = Rect(
                sourceRect.left + offsetX,
                sourceRect.top + offsetY,
                sourceRect.right + offsetX,
                sourceRect.bottom + offsetY,
            ),
        )
    }

    private fun DebugOverlayData.withOffset(offsetX: Int, offsetY: Int): DebugOverlayData {
        if (offsetX == 0 && offsetY == 0) return this
        return copy(
            bubbles = bubbles.map { it.withOffset(offsetX, offsetY) },
            ocrBlocks = ocrBlocks.map { it.withOffset(offsetX, offsetY) },
            mappings = mappings.map { Pair(it.first.withOffset(offsetX, offsetY), it.second.withOffset(offsetX, offsetY)) },
            orderedBubbles = orderedBubbles.map { it.withOffset(offsetX, offsetY) },
        )
    }

    private fun Rect.withOffset(offsetX: Int, offsetY: Int): Rect {
        return Rect(left + offsetX, top + offsetY, right + offsetX, bottom + offsetY)
    }

    private fun overlapsAnyTranslationRect(ocrRect: Rect, translationRects: List<Rect>, cropRect: Rect): Boolean {
        if (translationRects.isEmpty()) return false
        for (tr in translationRects) {
            val overlapLeft = maxOf(ocrRect.left, tr.left - cropRect.left)
            val overlapTop = maxOf(ocrRect.top, tr.top - cropRect.top)
            val overlapRight = minOf(ocrRect.right, tr.right - cropRect.left)
            val overlapBottom = minOf(ocrRect.bottom, tr.bottom - cropRect.top)
            if (overlapLeft < overlapRight && overlapTop < overlapBottom) {
                val overlapArea = (overlapRight - overlapLeft) * (overlapBottom - overlapTop)
                val ocrArea = maxOf(1, ocrRect.width() * ocrRect.height())
                if (overlapArea.toFloat() / ocrArea.toFloat() > 0.25f) {
                    return true
                }
            }
        }
        return false
    }

    override fun close() {
        // 未初始化则无需关闭，避免重复释放
        if (!isInitialized) return
        // 依次关闭各子插件，确保翻译/AI/OCR 资源均被释放
        translationPlugin.close()
        aiVisionPipeline.close()
        ocrPlugin.close()
        isInitialized = false
    }

    /**
     * [TranslationRepository] 接口实现：基于参数执行翻译，返回卡片 + 调试数据。
     *
     * 与 [translateImage] 的区别：
     * - translateImage 依赖实例状态（cropConfig、useAiVisionMode、lastDebugData），供旧调用方使用
     * - translate 从 params 读取配置，结果通过返回值透出，无状态副作用（除 lastDebugData 仍被更新以兼容旧调用方）
     *
     * 过渡期：translate 内部调用 translateImage 并读取更新后的 lastDebugData 作为返回。
     */
    override fun translate(params: TranslationRepository.TranslateParams): TranslationRepository.TranslateResult {
        // 同步实例状态到 params 指定值，保证 translateImage 使用最新配置
        cropConfig = params.cropConfig
        useAiVisionMode = params.useAiVisionMode

        val cards = translateImage(
            bitmap = params.bitmap,
            lastTranslationRects = params.lastTranslationRects,
            verticalOnly = params.verticalOnly,
            isManual = params.isManual,
        )
        return TranslationRepository.TranslateResult(
            cards = cards,
            debugData = lastDebugData,
        )
    }
}

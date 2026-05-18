package com.manga.translator.plugin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.manga.translator.debug.DebugOverlayData
import com.manga.translator.model.OcrBlock
import com.manga.translator.model.TranslationCard
import com.manga.translator.translation.TranslationPlugin
import com.manga.translator.util.ComicImageCropper
import com.manga.translator.util.OpenCVHelper
import com.manga.translator.util.ScreenCropConfig
import com.manga.translator.util.TextFilter
import kotlin.math.abs

class PluginManager(private val context: Context) {
    
    companion object {
        private const val TAG = "PluginManager"
        private const val MIN_BUBBLE_WHITE_RATIO = 0.42f
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
        val isVertical: Boolean
    )

    private data class BubbleTextGroup(
        val bubbleRect: Rect,
        val blocks: MutableList<OcrBlock> = mutableListOf()
    )
    
    private val ocrPlugin = OcrPlugin(context)
    private val translationPlugin = TranslationPlugin(context)
    private val bubbleDetector = BubbleDetector()
    private val panelDetector = PanelDetector()
    private val sentenceAssembler = SentenceAssembler()
    
    private var isInitialized = false
    private var cropConfig = ScreenCropConfig()
    private var lastDebugData = DebugOverlayData()
    
    fun initialize() {
        if (isInitialized) return
        
        ocrPlugin.initialize()
        
        if (!OpenCVHelper.isInitialized()) {
            OpenCVHelper.initialize(context)
        }
        
        isInitialized = true
        Log.d(TAG, "插件管理器初始化完成")
    }
    
    fun setCropConfig(config: ScreenCropConfig) {
        cropConfig = config
    }
    
    fun getLastDebugData(): DebugOverlayData {
        return lastDebugData
    }
    
    fun getTranslationPlugin(): TranslationPlugin {
        return translationPlugin
    }
    
    fun translateImage(bitmap: Bitmap, lastTranslationRects: List<Rect> = emptyList(), verticalOnly: Boolean = false): List<TranslationCard> {
        if (!isInitialized) {
            Log.e(TAG, "插件管理器未初始化")
            return emptyList()
        }
        
        Log.d(TAG, "开始翻译图片: ${bitmap.width}x${bitmap.height}")
        Log.w(TAG, "=== PluginManager v2 已加载 ===")
        
        val cropRect = ComicImageCropper.getCropRect(bitmap.width, bitmap.height, cropConfig)
        val croppedBitmap = ComicImageCropper.cropComicArea(bitmap, cropConfig)
        Log.d(TAG, "裁剪后: ${croppedBitmap.width}x${croppedBitmap.height}")
        
        val panels = if (OpenCVHelper.isInitialized()) {
            panelDetector.detectPanels(croppedBitmap)
        } else {
            emptyList()
        }
        Log.d(TAG, "分镜检测: ${panels.size} 个分镜")
        
        val bubbles = if (OpenCVHelper.isInitialized()) {
            bubbleDetector.detectBubbles(croppedBitmap)
        } else {
            emptyList()
        }
        Log.d(TAG, "气泡检测: ${bubbles.size} 个气泡")
        
        val ocrBlocks = ocrPlugin.recognize(croppedBitmap, verticalOnly)
        Log.d(TAG, "OCR识别: ${ocrBlocks.size} 个块")
        
        val textFilteredBlocks = ocrBlocks.filter { TextFilter.isValidOcrText(it.text) }
        Log.d(TAG, "文本过滤后: ${textFilteredBlocks.size} 个块")

        val filteredBlocks = textFilteredBlocks
            .filter { block -> !overlapsAnyTranslationRect(block.rect, lastTranslationRects, cropRect) }
        Log.d(TAG, "覆盖层过滤后: ${filteredBlocks.size} 个块")

        val translatedCropResult = translateCore(
            panels = panels,
            bubbles = bubbles,
            ocrBlocks = filteredBlocks,
            bitmap = croppedBitmap
        )
        val result = translatedCropResult.map { card -> card.withOffset(cropRect.left, cropRect.top) }
        lastDebugData = lastDebugData.withOffset(cropRect.left, cropRect.top)
        
        if (croppedBitmap != bitmap) {
            croppedBitmap.recycle()
        }
        
        return result
    }
    
    private fun translateCore(
        panels: List<PanelDetector.PanelInfo>,
        bubbles: List<BubbleDetector.BubbleInfo>,
        ocrBlocks: List<OcrBlock>,
        bitmap: Bitmap
    ): List<TranslationCard> {
        if (ocrBlocks.isEmpty()) return emptyList()

        val debugBubbles = bubbles.map { it.rect }
        val debugOcrBlocks = ocrBlocks.map { it.rect }

        val usableBubbles = emptyList<BubbleDetector.BubbleInfo>()
        val debugMappings = mutableListOf<Pair<Rect, Rect>>()
        val orderedBubbles = mutableListOf<Rect>()

        val sentenceRegions = buildSentenceFirstRegions(ocrBlocks, panels, usableBubbles, bitmap)
        for (region in sentenceRegions) {
            val textRect = region.textRect
            val panel = if (panels.isNotEmpty()) panelDetector.findPanelForRect(panels, textRect) else null
            if (panel != null) {
                orderedBubbles.add(panel.rect)
            }
        }
        val regionCandidates = sentenceRegions

        val regions = suppressOverlappingRegions(regionCandidates)

        val resolved = resolveCollisions(regions)

        lastDebugData = DebugOverlayData(
            bubbles = debugBubbles,
            ocrBlocks = debugOcrBlocks,
            mappings = debugMappings,
            orderedBubbles = orderedBubbles
        )

        val limitedRegions = limitRegions(resolved)
        Log.w(TAG, "=== 句子优先: 输入${ocrBlocks.size}块, 气泡定位已关闭, 句子${sentenceRegions.size}个, 去重${regions.size}, 最终${limitedRegions.size}区域 ===")

        val translatedTexts = translationPlugin.translateBatch(limitedRegions.map { it.text })
        return limitedRegions.mapIndexed { index, region ->
            TranslationCard(
                originalText = region.text,
                translatedText = translatedTexts.getOrNull(index).orEmpty(),
                sourceRect = region.outputRect,
                isVertical = region.isVertical
            )
        }
    }

    private fun prepareUsableBubbles(
        bubbles: List<BubbleDetector.BubbleInfo>,
        ocrBlocks: List<OcrBlock>,
        imageWidth: Int,
        imageHeight: Int
    ): List<BubbleDetector.BubbleInfo> {
        if (bubbles.isEmpty()) return emptyList()
        if (bubbles.size > 10) {
            Log.w(TAG, "气泡异常: 检出${bubbles.size}个，忽略气泡检测")
            return emptyList()
        }
        val merged = mergeNearbyBubbles(bubbles, imageWidth, imageHeight)
        if (merged.size == 1 && bubbles.size >= 4) {
            Log.w(TAG, "气泡异常: ${bubbles.size}个合并成1个，忽略气泡检测")
            return emptyList()
        }
        return filterBubblesNearText(merged, ocrBlocks)
    }

    private fun filterBubblesNearText(
        bubbles: List<BubbleDetector.BubbleInfo>,
        ocrBlocks: List<OcrBlock>
    ): List<BubbleDetector.BubbleInfo> {
        if (bubbles.isEmpty() || ocrBlocks.isEmpty()) return bubbles
        val textBounds = unionRects(ocrBlocks.map { it.rect })
        val expanded = Rect(textBounds)
        expanded.inset((-textBounds.width() * 0.15f).toInt(), (-textBounds.height() * 0.15f).toInt())
        val filtered = bubbles.filter { Rect.intersects(it.rect, expanded) }
        Log.w(TAG, "气泡过滤: ${bubbles.size}个→${filtered.size}个, 文本范围=${textBounds.toShortString()}")
        return filtered
    }

    private fun buildSentenceFirstRegions(
        ocrBlocks: List<OcrBlock>,
        panels: List<PanelDetector.PanelInfo>,
        bubbles: List<BubbleDetector.BubbleInfo>,
        bitmap: Bitmap
    ): List<TextRegion> {
        val blocks = dedupeBlocksInBubble(ocrBlocks)
        val sentenceGroups = sentenceAssembler.assemble(blocks)
        Log.w(TAG, "句子优先组装: ${ocrBlocks.size}块→去重${blocks.size}块→${sentenceGroups.size}句")

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
        Log.w(
            TAG,
            "句子调试: text='${region.text}' anchor=[${region.outputRect.centerX()},${region.outputRect.centerY()}] textRect=${region.textRect.toShortString()} out=${region.outputRect.toShortString()} blocks=$blockSummary"
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

    private fun findTrustedBubbleForSentence(bubbles: List<BubbleDetector.BubbleInfo>, textRect: Rect): Rect? {
        if (bubbles.isEmpty()) return null
        val best = bubbles.maxByOrNull { bubble -> trustedBubbleScore(bubble.rect, textRect) } ?: return null
        val score = trustedBubbleScore(best.rect, textRect)
        return if (score >= 0.45f) best.rect else null
    }

    private fun trustedBubbleScore(bubbleRect: Rect, textRect: Rect): Float {
        val overlap = rectOverlapRatio(textRect, bubbleRect)
        if (overlap > 0.35f) return 1f
        val expanded = Rect(bubbleRect)
        expanded.inset((-bubbleRect.width() * 0.20f).toInt(), (-bubbleRect.height() * 0.20f).toInt())
        if (Rect.intersects(expanded, textRect)) return 0.65f
        val dx = abs(textRect.centerX() - bubbleRect.centerX()).toFloat() / maxOf(1, bubbleRect.width())
        val dy = abs(textRect.centerY() - bubbleRect.centerY()).toFloat() / maxOf(1, bubbleRect.height())
        return (1f - (dx + dy)).coerceIn(0f, 1f) * 0.35f
    }

    private fun buildSentenceRegion(
        blocks: List<OcrBlock>,
        boundsRect: Rect,
        bubbleRect: Rect?,
        bitmap: Bitmap
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
            constrainRectToAnchor(estimateRectByAnchor(anchor.rect, textRect, isVertical), bubbleRect, anchor.rect, isVertical)
        } else {
            constrainRectToAnchor(estimateRectByAnchor(anchor.rect, textRect, isVertical), boundsRect, anchor.rect, isVertical)
        }
        if (!isValidOutputRect(estimated, bitmap.width, bitmap.height)) return null
        return TextRegion(
            text = text,
            textRect = textRect,
            outputRect = estimated,
            boundsRect = boundsRect,
            ocrBlocks = sorted,
            isVertical = isVertical
        )
    }

    private fun pickAnchorBlock(blocks: List<OcrBlock>, textRect: Rect): OcrBlock {
        val anchor = blocks.minByOrNull { block ->
            val dx = abs(block.rect.centerX() - textRect.centerX()).toFloat()
            val dy = abs(block.rect.centerY() - textRect.centerY()).toFloat()
            dx + dy - block.confidence * 40f
        } ?: blocks.first()
        Log.w(TAG, "锚点选择: textRect=${textRect.toShortString()} anchor='${anchor.text.trim()}' rect=${anchor.rect.toShortString()} conf=${anchor.confidence}")
        return anchor
    }

    private fun estimateRectByAnchor(anchorRect: Rect, textRect: Rect, isVertical: Boolean): Rect {
        val targetWidth = maxOf(textRect.width(), if (isVertical) 42 else 78)
        val targetHeight = maxOf(textRect.height(), if (isVertical) 88 else 36)
        val cx = anchorRect.centerX()
        val cy = anchorRect.centerY()
        return Rect(
            cx - targetWidth / 2,
            cy - targetHeight / 2,
            cx + targetWidth / 2,
            cy + targetHeight / 2
        )
    }

    private fun mergeNearbyBubbles(
        bubbles: List<BubbleDetector.BubbleInfo>,
        imageWidth: Int,
        imageHeight: Int
    ): List<BubbleDetector.BubbleInfo> {
        if (bubbles.size <= 1) return bubbles
        val groups = mutableListOf<MutableList<BubbleDetector.BubbleInfo>>()
        for (bubble in bubbles.sortedBy { it.rect.top }) {
            val target = groups.firstOrNull { group ->
                group.any { shouldMergeBubbleRects(it.rect, bubble.rect) }
            }
            if (target != null) target.add(bubble) else groups.add(mutableListOf(bubble))
        }

        val merged = groups.map { group ->
            if (group.size == 1) {
                group.first()
            } else {
                val rect = unionRects(group.map { it.rect })
                rect.inset((-rect.width() * 0.18f).toInt(), (-rect.height() * 0.18f).toInt())
                rect.left = rect.left.coerceAtLeast(0)
                rect.top = rect.top.coerceAtLeast(0)
                rect.right = rect.right.coerceAtMost(imageWidth)
                rect.bottom = rect.bottom.coerceAtMost(imageHeight)
                val source = group.maxByOrNull { it.area } ?: group.first()
                source.copy(
                    rect = rect,
                    area = group.sumOf { it.area },
                    centerX = rect.centerX(),
                    centerY = rect.centerY(),
                    isVertical = rect.height() > rect.width() * 1.35f
                )
            }
        }.filter { it.rect.width() >= 60 && it.rect.height() >= 60 }

        Log.w(TAG, "气泡合并: ${bubbles.size}个→${merged.size}个")
        return merged
    }

    private fun shouldMergeBubbleRects(a: Rect, b: Rect): Boolean {
        val expanded = Rect(a)
        expanded.inset((-a.width() * 0.35f).toInt(), (-a.height() * 0.35f).toInt())
        if (Rect.intersects(expanded, b)) return true
        val dx = abs(a.centerX() - b.centerX())
        val dy = abs(a.centerY() - b.centerY())
        return dx < maxOf(a.width(), b.width()) * 0.75f && dy < maxOf(a.height(), b.height()) * 0.75f
    }

    private fun findBestBubbleRect(bubbles: List<BubbleDetector.BubbleInfo>, textRect: Rect): Rect? {
        var bestRect: Rect? = null
        var bestScore = 0f

        for (bubble in bubbles) {
            val overlapLeft = maxOf(textRect.left, bubble.rect.left)
            val overlapTop = maxOf(textRect.top, bubble.rect.top)
            val overlapRight = minOf(textRect.right, bubble.rect.right)
            val overlapBottom = minOf(textRect.bottom, bubble.rect.bottom)
            val overlap = if (overlapLeft < overlapRight && overlapTop < overlapBottom) {
                (overlapRight - overlapLeft) * (overlapBottom - overlapTop)
            } else 0

            val textArea = maxOf(1, textRect.width() * textRect.height())
            val overlapRatio = overlap.toFloat() / textArea

            val dx = textRect.centerX() - bubble.rect.centerX()
            val dy = textRect.centerY() - bubble.rect.centerY()
            val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            val maxDist = maxOf(bubble.rect.width(), bubble.rect.height()).toFloat()
            val distScore = maxOf(0f, 1f - dist / maxDist)

            val score = overlapRatio * 60f + distScore * 40f
            if (score > bestScore) {
                bestScore = score
                bestRect = bubble.rect
            }
        }

        return if (bestScore >= 20f) bestRect else null
    }

    private fun buildBubbleSentenceRegions(
        ocrBlocks: List<OcrBlock>,
        bubbles: List<BubbleDetector.BubbleInfo>,
        bitmap: Bitmap
    ): List<TextRegion> {
        if (ocrBlocks.isEmpty() || bubbles.isEmpty()) return emptyList()

        if (ocrBlocks.isNotEmpty() && bubbles.isNotEmpty()) {
            val sample = ocrBlocks.first()
            val sampleBubble = bubbles.first()
            val best = findBestBubbleRect(bubbles, sample.rect)
            val ocrXRange = "${ocrBlocks.minOf { it.rect.left }}..${ocrBlocks.maxOf { it.rect.right }}"
            val ocrYRange = "${ocrBlocks.minOf { it.rect.top }}..${ocrBlocks.maxOf { it.rect.bottom }}"
            val bubbleXRange = "${bubbles.minOf { it.rect.left }}..${bubbles.maxOf { it.rect.right }}"
            val bubbleYRange = "${bubbles.minOf { it.rect.top }}..${bubbles.maxOf { it.rect.bottom }}"
            Log.w(TAG, "坐标诊断: OCR块X=$ocrXRange Y=$ocrYRange | 气泡X=$bubbleXRange Y=$bubbleYRange | 首块${sample.rect.toShortString()} 匹配=${best?.toShortString()}")
        }

        val groups = mutableMapOf<Rect, BubbleTextGroup>()
        for (block in ocrBlocks) {
            val bubbleRect = findBestBubbleRect(bubbles, block.rect) ?: continue
            groups.getOrPut(bubbleRect) { BubbleTextGroup(bubbleRect) }.blocks.add(block)
        }

        Log.w(TAG, "气泡分组: ${groups.size}个气泡, 归属${groups.values.sumOf { it.blocks.size }}/${ocrBlocks.size}块")

        val regions = mutableListOf<TextRegion>()
        for (group in groups.values) {
            val blocks = dedupeBlocksInBubble(group.blocks)
            if (blocks.isEmpty()) continue
            val isVertical = blocks.count { it.isVertical } >= blocks.size / 2

            val subGroups = subdivideByProximity(blocks, isVertical)
            Log.w(TAG, "气泡${group.bubbleRect.toShortString()}: ${group.blocks.size}块→去重${blocks.size}→分组${subGroups.size}")
            for (subGroup in subGroups) {
                val sorted = if (isVertical) {
                    subGroup.sortedWith(compareByDescending<OcrBlock> { it.rect.centerX() }.thenBy { it.rect.top })
                } else {
                    subGroup.sortedWith(compareBy<OcrBlock> { it.rect.top }.thenBy { it.rect.left })
                }
                val text = sorted.joinToString("") { it.text.trim() }
                if (!TextFilter.isValidOcrText(text)) continue

                val textRect = unionRects(sorted.map { it.rect })
                val outputRect = constrainRectToAnchor(
                    estimateOutputRect(textRect, isVertical),
                    group.bubbleRect,
                    textRect,
                    isVertical
                )
                if (!isValidOutputRect(outputRect, bitmap.width, bitmap.height)) continue
                if (!isAnchoredCloseEnough(outputRect, textRect, group.bubbleRect)) continue

                regions.add(TextRegion(
                    text = text,
                    textRect = textRect,
                    outputRect = outputRect,
                    boundsRect = group.bubbleRect,
                    ocrBlocks = sorted,
                    isVertical = isVertical
                ))
            }
        }
        return regions
    }

    private fun subdivideByProximity(blocks: List<OcrBlock>, isVertical: Boolean): List<List<OcrBlock>> {
        if (blocks.size <= 1) return listOf(blocks)
        val sorted = if (isVertical) {
            blocks.sortedWith(compareByDescending<OcrBlock> { it.rect.centerX() }.thenBy { it.rect.top })
        } else {
            blocks.sortedWith(compareBy<OcrBlock> { it.rect.top }.thenBy { it.rect.left })
        }
        val groups = mutableListOf<MutableList<OcrBlock>>()
        for (block in sorted) {
            if (isVertical) {
                val target = groups.firstOrNull { group -> shouldJoinVerticalSentence(block, group) }
                if (target != null) target.add(block) else groups.add(mutableListOf(block))
            } else {
                val target = groups.firstOrNull { group -> shouldJoinHorizontalSentence(block, group) }
                if (target != null) target.add(block) else groups.add(mutableListOf(block))
            }
        }
        return groups
    }

    private fun buildSpatialSentenceRegions(
        ocrBlocks: List<OcrBlock>,
        panels: List<PanelDetector.PanelInfo>,
        bitmap: Bitmap
    ): List<TextRegion> {
        if (ocrBlocks.isEmpty()) return emptyList()
        val blocks = dedupeBlocksInBubble(ocrBlocks)
        Log.w(TAG, "空间分组: 输入${ocrBlocks.size}块→去重${blocks.size}块")
        val sentenceGroups = sentenceAssembler.assemble(blocks)
        Log.w(TAG, "句子组装: ${blocks.size}块→${sentenceGroups.size}句")
        val regions = sentenceGroups.mapNotNull { sentenceBlocks ->
            val textRect = unionRects(sentenceBlocks.map { it.rect })
            val panelRect = panelDetector.findPanelForRect(panels, textRect)?.rect ?: Rect(0, 0, bitmap.width, bitmap.height)
            buildTightRegionFromBlocks(sentenceBlocks, panelRect, bitmap)
        }
        Log.w(TAG, "空间分组: 去重${blocks.size}块→${regions.size}区域")
        return regions
    }

    private fun buildTightRegionFromBlocks(blocks: List<OcrBlock>, boundsRect: Rect, bitmap: Bitmap): TextRegion? {
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
        val outputRect = constrainRectToAnchor(
            estimateTightOutputRect(textRect, isVertical),
            boundsRect,
            textRect,
            isVertical
        )
        if (!isValidOutputRect(outputRect, bitmap.width, bitmap.height)) return null
        return TextRegion(
            text = text,
            textRect = textRect,
            outputRect = outputRect,
            boundsRect = boundsRect,
            ocrBlocks = sorted,
            isVertical = isVertical
        )
    }

    private fun buildVerticalSpatialSentences(blocks: List<OcrBlock>, boundsRect: Rect, bitmap: Bitmap): List<TextRegion> {
        if (blocks.isEmpty()) return emptyList()
        val sorted = blocks.sortedWith(compareByDescending<OcrBlock> { it.rect.centerX() }.thenBy { it.rect.top })
        val groups = mutableListOf<MutableList<OcrBlock>>()
        for (block in sorted) {
            val target = groups.firstOrNull { group -> shouldJoinVerticalSentence(block, group) }
            if (target != null) target.add(block) else groups.add(mutableListOf(block))
        }
        return groups.mapNotNull { buildRegionFromBlocks(it, boundsRect, true, bitmap) }
    }

    private fun buildHorizontalSpatialSentences(blocks: List<OcrBlock>, boundsRect: Rect, bitmap: Bitmap): List<TextRegion> {
        if (blocks.isEmpty()) return emptyList()
        val sorted = blocks.sortedWith(compareBy<OcrBlock> { it.rect.top }.thenBy { it.rect.left })
        val groups = mutableListOf<MutableList<OcrBlock>>()
        for (block in sorted) {
            val target = groups.firstOrNull { group -> shouldJoinHorizontalSentence(block, group) }
            if (target != null) target.add(block) else groups.add(mutableListOf(block))
        }
        return groups.mapNotNull { buildRegionFromBlocks(it, boundsRect, false, bitmap) }
    }

    private fun shouldJoinVerticalSentence(block: OcrBlock, group: List<OcrBlock>): Boolean {
        val groupRect = unionRects(group.map { it.rect })
        val avgWidth = group.map { it.rect.width() }.average().toFloat().coerceAtLeast(1f)
        val xGap = horizontalGap(block.rect, groupRect)
        if (xGap > avgWidth * 1.5f) return false
        val yOverlap = overlapLength(block.rect.top, block.rect.bottom, groupRect.top, groupRect.bottom)
        val minH = minOf(block.rect.height(), groupRect.height())
        return yOverlap > minH * 0.30f
    }

    private fun shouldJoinHorizontalSentence(block: OcrBlock, group: List<OcrBlock>): Boolean {
        val groupRect = unionRects(group.map { it.rect })
        val avgHeight = group.map { it.rect.height() }.average().toFloat().coerceAtLeast(1f)
        val yGap = verticalGap(block.rect, groupRect)
        if (yGap > avgHeight * 1.5f) return false
        val xOverlap = overlapLength(block.rect.left, block.rect.right, groupRect.left, groupRect.right)
        val minW = minOf(block.rect.width(), groupRect.width())
        return xOverlap > minW * 0.30f
    }

    private fun buildRegionFromBlocks(blocks: List<OcrBlock>, boundsRect: Rect, isVertical: Boolean, bitmap: Bitmap): TextRegion? {
        if (blocks.isEmpty()) return null
        val sorted = if (isVertical) {
            blocks.sortedWith(compareByDescending<OcrBlock> { it.rect.centerX() }.thenBy { it.rect.top })
        } else {
            blocks.sortedWith(compareBy<OcrBlock> { it.rect.top }.thenBy { it.rect.left })
        }
        val text = sorted.joinToString("") { it.text.trim() }
        if (!TextFilter.isValidOcrText(text)) return null
        val textRect = unionRects(sorted.map { it.rect })
        val outputRect = constrainRectToAnchor(estimateOutputRect(textRect, isVertical), boundsRect, textRect, isVertical)
        if (!isValidOutputRect(outputRect, bitmap.width, bitmap.height)) return null
        return TextRegion(
            text = text,
            textRect = textRect,
            outputRect = outputRect,
            boundsRect = boundsRect,
            ocrBlocks = sorted,
            isVertical = isVertical
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

    private fun overlapLength(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Int {
        return maxOf(0, minOf(aEnd, bEnd) - maxOf(aStart, bStart))
    }

    private fun horizontalGap(a: Rect, b: Rect): Int {
        return when {
            a.right < b.left -> b.left - a.right
            b.right < a.left -> a.left - b.right
            else -> 0
        }
    }

    private fun verticalGap(a: Rect, b: Rect): Int {
        return when {
            a.bottom < b.top -> b.top - a.bottom
            b.bottom < a.top -> a.top - b.bottom
            else -> 0
        }
    }

    private fun resolveCollisions(regions: List<TextRegion>): List<TextRegion> {
        return regions
    }

    private fun pushApart(candidate: TextRegion, existing: TextRegion): Rect? {
        val c = candidate.outputRect
        val e = existing.outputRect
        val textCenter = candidate.textRect.centerX()
        val textCenterY = candidate.textRect.centerY()

        val shiftLeft = Rect(c)
        shiftLeft.offset(e.left - c.right - 4, 0)
        val shiftRight = Rect(c)
        shiftRight.offset(e.right - c.left + 4, 0)
        val shiftUp = Rect(c)
        shiftUp.offset(0, e.top - c.bottom - 4)
        val shiftDown = Rect(c)
        shiftDown.offset(0, e.bottom - c.top + 4)

        val bounds = candidate.boundsRect
        val options = mutableListOf<Pair<Rect, Float>>()

        if (rectInside(shiftLeft, bounds) && abs(shiftLeft.centerX() - textCenter) < c.width() * 0.55f) {
            options.add(Pair(shiftLeft, abs(shiftLeft.centerX() - textCenter).toFloat()))
        }
        if (rectInside(shiftRight, bounds) && abs(shiftRight.centerX() - textCenter) < c.width() * 0.55f) {
            options.add(Pair(shiftRight, abs(shiftRight.centerX() - textCenter).toFloat()))
        }
        if (rectInside(shiftUp, bounds) && abs(shiftUp.centerY() - textCenterY) < c.height() * 0.55f) {
            options.add(Pair(shiftUp, abs(shiftUp.centerY() - textCenterY).toFloat()))
        }
        if (rectInside(shiftDown, bounds) && abs(shiftDown.centerY() - textCenterY) < c.height() * 0.55f) {
            options.add(Pair(shiftDown, abs(shiftDown.centerY() - textCenterY).toFloat()))
        }

        return options.minByOrNull { it.second }?.first
    }

    private fun rectInside(rect: Rect, bounds: Rect): Boolean {
        return rect.left >= bounds.left && rect.top >= bounds.top &&
            rect.right <= bounds.right && rect.bottom <= bounds.bottom
    }

    private fun isAnchoredCloseEnough(outputRect: Rect, textRect: Rect, bubbleRect: Rect?): Boolean {
        if (bubbleRect != null) {
            val expandedBubble = Rect(bubbleRect)
            expandedBubble.inset((-bubbleRect.width() * 0.18f).toInt(), (-bubbleRect.height() * 0.18f).toInt())
            if (!Rect.intersects(outputRect, expandedBubble)) return false
        }

        val dx = abs(outputRect.centerX() - textRect.centerX())
        val dy = abs(outputRect.centerY() - textRect.centerY())
        return dx <= maxOf(outputRect.width(), textRect.width()) * 0.9f &&
            dy <= maxOf(outputRect.height(), textRect.height()) * 0.9f
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

    private fun centerDistance(a: Rect, b: Rect): Float {
        val dx = a.centerX() - b.centerX()
        val dy = a.centerY() - b.centerY()
        return kotlin.math.sqrt((dx * dx + dy * dy).toFloat())
    }

    private fun isLightArea(bitmap: Bitmap, rect: Rect): Boolean {
        val safeRect = Rect(
            rect.left.coerceAtLeast(0),
            rect.top.coerceAtLeast(0),
            rect.right.coerceAtMost(bitmap.width),
            rect.bottom.coerceAtMost(bitmap.height)
        )
        if (safeRect.width() <= 0 || safeRect.height() <= 0) return true
        var lightCount = 0
        var sampled = 0
        val step = maxOf(2, minOf(safeRect.width(), safeRect.height()) / 10)
        for (y in safeRect.top until safeRect.bottom step step) {
            for (x in safeRect.left until safeRect.right step step) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val brightness = (r * 0.299f + g * 0.587f + b * 0.114f)
                val maxChannel = maxOf(r, g, b)
                val minChannel = minOf(r, g, b)
                val saturation = if (maxChannel > 0) (maxChannel - minChannel).toFloat() / maxChannel else 0f
                if (brightness > 180f && saturation < 0.25f) lightCount++
                sampled++
            }
        }
        return sampled == 0 || lightCount.toFloat() / sampled.toFloat() >= 0.35f
    }

    private fun isLikelySpeechBubbleArea(bitmap: Bitmap, rect: Rect): Boolean {
        val expanded = Rect(rect)
        expanded.inset((-rect.width() * 0.55f).toInt(), (-rect.height() * 0.35f).toInt())
        expanded.left = expanded.left.coerceAtLeast(0)
        expanded.top = expanded.top.coerceAtLeast(0)
        expanded.right = expanded.right.coerceAtMost(bitmap.width)
        expanded.bottom = expanded.bottom.coerceAtMost(bitmap.height)
        if (expanded.width() <= 0 || expanded.height() <= 0) return false

        var whiteCount = 0
        var sampled = 0
        val step = maxOf(2, minOf(expanded.width(), expanded.height()) / 18)
        for (y in expanded.top until expanded.bottom step step) {
            for (x in expanded.left until expanded.right step step) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val maxChannel = maxOf(r, g, b)
                val minChannel = minOf(r, g, b)
                if (r > 225 && g > 225 && b > 220 && maxChannel - minChannel < 35) whiteCount++
                sampled++
            }
        }
        if (sampled == 0) return false
        return whiteCount.toFloat() / sampled.toFloat() >= MIN_BUBBLE_WHITE_RATIO
    }

    private fun isRegionAnchoredToBubble(bitmap: Bitmap, textRect: Rect, outputRect: Rect): Boolean {
        if (isLikelySpeechBubbleArea(bitmap, outputRect)) return true
        if (isLikelySpeechBubbleArea(bitmap, textRect)) return true

        val bridgeRect = Rect(
            minOf(textRect.left, outputRect.left),
            minOf(textRect.top, outputRect.top),
            maxOf(textRect.right, outputRect.right),
            maxOf(textRect.bottom, outputRect.bottom)
        )
        return isLikelySpeechBubbleArea(bitmap, bridgeRect)
    }

    private fun estimateOutputRect(textRect: Rect, isVertical: Boolean): Rect {
        val minWidth = if (isVertical) 56 else 92
        val minHeight = if (isVertical) 110 else 42
        val targetWidth = maxOf(minWidth, (textRect.width() * if (isVertical) 1.35f else 1.25f).toInt())
        val targetHeight = maxOf(minHeight, (textRect.height() * if (isVertical) 1.10f else 1.35f).toInt())
        val cx = textRect.centerX()
        val cy = textRect.centerY()
        return Rect(
            cx - targetWidth / 2,
            cy - targetHeight / 2,
            cx + targetWidth / 2,
            cy + targetHeight / 2
        )
    }

    private fun estimateTightOutputRect(textRect: Rect, isVertical: Boolean): Rect {
        val minWidth = if (isVertical) 42 else 48
        val minHeight = if (isVertical) 40 else 24
        val targetWidth = maxOf(minWidth, (textRect.width() * if (isVertical) 1.18f else 1.05f).toInt())
        val targetHeight = maxOf(minHeight, (textRect.height() * if (isVertical) 1.00f else 1.08f).toInt())
        val cx = textRect.centerX()
        val cy = textRect.centerY()
        return Rect(
            cx - targetWidth / 2,
            cy - targetHeight / 2,
            cx + targetWidth / 2,
            cy + targetHeight / 2
        )
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

    private fun isOutputRectReasonableForText(outputRect: Rect, textRect: Rect, imageWidth: Int, imageHeight: Int): Boolean {
        if (!isValidOutputRect(outputRect, imageWidth, imageHeight)) return false
        val textArea = maxOf(1, textRect.width() * textRect.height())
        val outputArea = outputRect.width() * outputRect.height()
        if (outputArea > textArea * 5) return false
        val centerDistanceX = kotlin.math.abs(outputRect.centerX() - textRect.centerX())
        val centerDistanceY = kotlin.math.abs(outputRect.centerY() - textRect.centerY())
        if (centerDistanceX > maxOf(outputRect.width(), textRect.width())) return false
        if (centerDistanceY > maxOf(outputRect.height(), textRect.height())) return false
        return true
    }

    private fun TranslationCard.withOffset(offsetX: Int, offsetY: Int): TranslationCard {
        if (offsetX == 0 && offsetY == 0) return this
        return copy(sourceRect = Rect(
            sourceRect.left + offsetX,
            sourceRect.top + offsetY,
            sourceRect.right + offsetX,
            sourceRect.bottom + offsetY
        ))
    }

    private fun DebugOverlayData.withOffset(offsetX: Int, offsetY: Int): DebugOverlayData {
        if (offsetX == 0 && offsetY == 0) return this
        return copy(
            bubbles = bubbles.map { it.withOffset(offsetX, offsetY) },
            ocrBlocks = ocrBlocks.map { it.withOffset(offsetX, offsetY) },
            mappings = mappings.map { Pair(it.first.withOffset(offsetX, offsetY), it.second.withOffset(offsetX, offsetY)) },
            orderedBubbles = orderedBubbles.map { it.withOffset(offsetX, offsetY) }
        )
    }

    private fun Rect.withOffset(offsetX: Int, offsetY: Int): Rect {
        return Rect(left + offsetX, top + offsetY, right + offsetX, bottom + offsetY)
    }

    private fun limitPerPanel(clusters: List<TextAwareClusterer.TextCluster>, panels: List<PanelDetector.PanelInfo>): List<TextAwareClusterer.TextCluster> {
        val panelGroups = LinkedHashMap<PanelDetector.PanelInfo?, MutableList<TextAwareClusterer.TextCluster>>()
        for (cluster in clusters) {
            val panel = panelDetector.findPanelForRect(panels, cluster.anchorRect)
            panelGroups.getOrPut(panel) { mutableListOf() }.add(cluster)
        }
        return panelGroups.flatMap { (_, group) ->
            group.sortedByDescending { it.text.length }.take(6)
        }
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
    
    fun translateText(text: String): String {
        return translationPlugin.translate(text)
    }
    
    fun isConfigured(): Boolean {
        return translationPlugin.isConfigured()
    }
    
    fun clearCache() {
        translationPlugin.clearCache()
    }
    
    fun close() {
        ocrPlugin.close()
        isInitialized = false
    }
}

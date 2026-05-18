package com.manga.translator.plugin

import android.graphics.Rect
import android.util.Log
import com.manga.translator.model.OcrBlock
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class TextAwareClusterer {

    companion object {
        private const val TAG = "TextAwareClusterer"

        // Column detection for vertical text
        private const val COLUMN_X_THRESHOLD_RATIO = 1.15f
        private const val COLUMN_Y_GAP_RATIO = 0.8f
        private const val MAX_CLUSTER_SPAN_RATIO = 3.5f
        private const val FRAGMENT_MAX_LENGTH = 3
        private const val FRAGMENT_ATTACH_DIST_RATIO = 1.2f
        private const val OUTLIER_X_RATIO = 1.5f
        private const val OUTLIER_Y_RATIO = 1.2f
    }

    data class TextCluster(
        val text: String,
        val rect: Rect,
        val anchorRect: Rect,
        val isVertical: Boolean,
        val blocks: List<OcrBlock>,
        val confidence: Float
    )

    fun cluster(blocks: List<OcrBlock>, panels: List<PanelDetector.PanelInfo> = emptyList()): List<TextCluster> {
        if (blocks.isEmpty()) return emptyList()
        if (blocks.size == 1) return singleCluster(blocks[0])?.let { listOf(it) } ?: emptyList()

        val blockGroups = if (panels.isNotEmpty()) {
            groupBlocksByPanel(blocks, panels)
        } else {
            listOf(blocks)
        }

        val clusters = mutableListOf<TextCluster>()
        for (group in blockGroups) {
            val verticalBlocks = group.filter { it.isVertical }
            val horizontalBlocks = group.filter { !it.isVertical }

            clusters.addAll(clusterVertical(verticalBlocks))
            clusters.addAll(clusterHorizontal(horizontalBlocks))
        }

        return clusters
            .filter { it.text.isNotBlank() }
            .sortedWith(compareBy<TextCluster> { it.rect.top }.thenByDescending { it.rect.left })
    }

    // ==================== Vertical: cluster by column ====================

    private fun clusterVertical(blocks: List<OcrBlock>): List<TextCluster> {
        if (blocks.isEmpty()) return emptyList()

        val sorted = blocks.sortedWith(
            compareByDescending<OcrBlock> { it.rect.centerX() }.thenBy { it.rect.top }
        )

        val columns = mutableListOf<MutableList<OcrBlock>>()
        for (block in sorted) {
            val col = columns.lastOrNull()
            if (col != null && isSameColumn(block, col)) {
                col.add(block)
            } else {
                columns.add(mutableListOf(block))
            }
        }

        val results = mutableListOf<TextCluster>()
        for (col in columns) {
            val subClusters = splitColumnByGap(col)
            for (sub in subClusters) {
                val cleaned = removeOutliers(sub)
                if (cleaned.isNotEmpty()) {
                    val frag = separateFragments(cleaned)
                    val attachedFragments = attachFragmentsToBlocks(frag.fragmentBlocks, frag.mainBlocks)
                    val mainCluster = buildCluster(frag.mainBlocks, attachedFragments)
                    if (mainCluster != null) results.add(mainCluster)
                }
            }
        }

        return results
    }

    private fun isSameColumn(block: OcrBlock, column: List<OcrBlock>): Boolean {
        val avgWidth = column.map { it.rect.width() }.average().toFloat().coerceAtLeast(1f)
        val centerX = block.rect.centerX()
        val colCenterX = column.map { it.rect.centerX() }.average().toFloat()
        return abs(centerX - colCenterX) <= avgWidth * COLUMN_X_THRESHOLD_RATIO
    }

    private fun splitColumnByGap(blocks: List<OcrBlock>): List<List<OcrBlock>> {
        if (blocks.size <= 1) return listOf(blocks)

        val sorted = blocks.sortedBy { it.rect.top }
        val avgHeight = sorted.map { it.rect.height() }.average().toFloat().coerceAtLeast(1f)
        val results = mutableListOf<MutableList<OcrBlock>>()
        var current = mutableListOf(sorted[0])

        for (i in 1 until sorted.size) {
            val gap = sorted[i].rect.top - sorted[i - 1].rect.bottom
            if (gap > avgHeight * COLUMN_Y_GAP_RATIO) {
                results.add(current)
                current = mutableListOf(sorted[i])
            } else {
                current.add(sorted[i])
            }
        }
        results.add(current)
        return results
    }

    // ==================== Horizontal: cluster by row ====================

    private fun clusterHorizontal(blocks: List<OcrBlock>): List<TextCluster> {
        if (blocks.isEmpty()) return emptyList()

        val sorted = blocks.sortedWith(
            compareBy<OcrBlock> { it.rect.top }.thenBy { it.rect.left }
        )

        val rows = mutableListOf<MutableList<OcrBlock>>()
        for (block in sorted) {
            val row = rows.lastOrNull()
            if (row != null && isSameRow(block, row)) {
                row.add(block)
            } else {
                rows.add(mutableListOf(block))
            }
        }

        val results = mutableListOf<TextCluster>()
        for (row in rows) {
            val cleaned = removeOutliers(row)
            if (cleaned.isNotEmpty()) {
                val frag = separateFragments(cleaned)
                val attachedFragments = attachFragmentsToBlocks(frag.fragmentBlocks, frag.mainBlocks)
                val mainCluster = buildCluster(frag.mainBlocks, attachedFragments)
                if (mainCluster != null) results.add(mainCluster)
            }
        }

        return results
    }

    private fun isSameRow(block: OcrBlock, row: List<OcrBlock>): Boolean {
        val avgHeight = row.map { it.rect.height() }.average().toFloat().coerceAtLeast(1f)
        val centerY = block.rect.centerY()
        val rowCenterY = row.map { it.rect.centerY() }.average().toFloat()
        val sameLine = abs(centerY - rowCenterY) <= avgHeight * 1.2f

        val avgWidth = row.map { it.rect.width() }.average().toFloat().coerceAtLeast(1f)
        val minX = minOf(row.minOf { it.rect.left }, block.rect.left)
        val maxX = maxOf(row.maxOf { it.rect.right }, block.rect.right)
        val span = maxX - minX
        val totalWidth = row.sumOf { it.rect.width() } + block.rect.width()
        val notTooFar = span <= totalWidth * MAX_CLUSTER_SPAN_RATIO

        return sameLine && notTooFar
    }

    // ==================== Fragment handling ====================

    private data class SeparatedBlocks(
        val mainBlocks: List<OcrBlock>,
        val fragmentBlocks: List<OcrBlock>
    )

    private fun separateFragments(blocks: List<OcrBlock>): SeparatedBlocks {
        if (blocks.size <= 1) return SeparatedBlocks(blocks, emptyList())

        val mainBlocks = mutableListOf<OcrBlock>()
        val fragmentBlocks = mutableListOf<OcrBlock>()

        for (block in blocks) {
            val len = block.text.trim().length
            val isPunctuationOnly = block.text.trim().all { isJapanesePunctuation(it) }
            if (len <= FRAGMENT_MAX_LENGTH && (isPunctuationOnly || len <= 1)) {
                fragmentBlocks.add(block)
            } else {
                mainBlocks.add(block)
            }
        }

        if (mainBlocks.isEmpty() && fragmentBlocks.isNotEmpty()) {
            mainBlocks.add(fragmentBlocks.removeAt(0))
        }

        return SeparatedBlocks(mainBlocks, fragmentBlocks)
    }

    private fun attachFragmentsToBlocks(fragments: List<OcrBlock>, mainBlocks: List<OcrBlock>): List<OcrBlock> {
        if (fragments.isEmpty() || mainBlocks.isEmpty()) return emptyList()
        val mainRect = unionRects(mainBlocks.map { it.rect })
        val threshold = max(mainRect.width(), mainRect.height()) * FRAGMENT_ATTACH_DIST_RATIO

        return fragments.filter { fragment ->
            val dx = fragment.rect.centerX() - mainRect.centerX()
            val dy = fragment.rect.centerY() - mainRect.centerY()
            val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            dist <= threshold
        }
    }

    // ==================== Outlier removal ====================

    private fun removeOutliers(blocks: List<OcrBlock>): List<OcrBlock> {
        if (blocks.size <= 2) return blocks

        val isVertical = blocks.count { it.isVertical } > blocks.size / 2
        return if (isVertical) removeVerticalOutliers(blocks) else removeHorizontalOutliers(blocks)
    }

    private fun removeVerticalOutliers(blocks: List<OcrBlock>): List<OcrBlock> {
        val avgWidth = blocks.map { it.rect.width() }.average().toFloat().coerceAtLeast(1f)
        val centerXs = blocks.map { it.rect.centerX().toFloat() }
        val medianX = centerXs.sorted()[centerXs.size / 2]

        return blocks.filter { block ->
            abs(block.rect.centerX() - medianX) <= avgWidth * OUTLIER_X_RATIO
        }
    }

    private fun removeHorizontalOutliers(blocks: List<OcrBlock>): List<OcrBlock> {
        val avgHeight = blocks.map { it.rect.height() }.average().toFloat().coerceAtLeast(1f)
        val centerYs = blocks.map { it.rect.centerY().toFloat() }
        val medianY = centerYs.sorted()[centerYs.size / 2]

        return blocks.filter { block ->
            abs(block.rect.centerY() - medianY) <= avgHeight * OUTLIER_Y_RATIO
        }
    }

    // ==================== Build cluster ====================

    private fun buildCluster(blocks: List<OcrBlock>, fragments: List<OcrBlock> = emptyList()): TextCluster? {
        if (blocks.isEmpty()) return null
        if (blocks.size == 1 && fragments.isEmpty()) return singleCluster(blocks[0])

        val allBlocks = blocks + fragments
        val sorted = if (allBlocks.count { it.isVertical } > allBlocks.size / 2) {
            allBlocks.sortedWith(compareByDescending<OcrBlock> { it.rect.centerX() }.thenBy { it.rect.top })
        } else {
            allBlocks.sortedWith(compareBy<OcrBlock> { it.rect.top }.thenBy { it.rect.left })
        }

        val text = sorted.joinToString("") { it.text.trim() }
        val primaryBlock = blocks.maxBy { it.confidence }
        val anchorRect = Rect(primaryBlock.rect)
        val rect = unionRects(allBlocks.map { it.rect })
        val isVertical = sorted.count { it.isVertical } > sorted.size / 2
        val confidence = sorted.map { it.confidence }.average().toFloat()

        if (isDiscardableText(text, sorted)) return null

        val span = if (isVertical) rect.height() else rect.width()
        val avgSize = if (isVertical) {
            blocks.map { it.rect.height() }.average().toFloat()
        } else {
            blocks.map { it.rect.width() }.average().toFloat()
        }

        if (avgSize > 0 && span > avgSize * blocks.size * MAX_CLUSTER_SPAN_RATIO) {
            Log.d(TAG, "跨度异常，拆分: text=${text.take(10)} span=$span avgSize=$avgSize count=${sorted.size}")
            return singleCluster(blocks.maxBy { it.text.length })
        }

        return TextCluster(
            text = text,
            rect = rect,
            anchorRect = anchorRect,
            isVertical = isVertical,
            blocks = sorted,
            confidence = confidence
        )
    }

    private fun singleCluster(block: OcrBlock): TextCluster? {
        val text = block.text.trim()
        if (isDiscardableText(text, listOf(block))) return null
        return TextCluster(
            text = text,
            rect = Rect(block.rect),
            anchorRect = Rect(block.rect),
            isVertical = block.isVertical,
            blocks = listOf(block),
            confidence = block.confidence
        )
    }

    // ==================== Utilities ====================

    private fun isJapanesePunctuation(c: Char): Boolean {
        val code = c.code
        return (code in 0x3000..0x303F && c in "。、「」『』【】・ー〜…～")
                || (code in 0xFF65..0xFF9F)
                || c == '…' || c == '～' || c == '!' || c == '?'
                || c == '！' || c == '？'
    }

    private fun isDiscardableText(text: String, blocks: List<OcrBlock>): Boolean {
        val clean = text.trim()
        if (clean.isEmpty()) return true
        if (clean.all { isJapanesePunctuation(it) }) return true
        if (clean.length == 1 && !containsKana(clean) && !containsJapanesePunctuation(clean)) return true
        if (blocks.size == 1 && clean.length <= 1) return true
        return false
    }

    private fun containsKana(text: String): Boolean {
        return text.any { c ->
            val code = c.code
            code in 0x3040..0x309F || code in 0x30A0..0x30FF || code in 0x31F0..0x31FF
        }
    }

    private fun containsJapanesePunctuation(text: String): Boolean {
        return text.any { isJapanesePunctuation(it) }
    }

    private fun groupBlocksByPanel(blocks: List<OcrBlock>, panels: List<PanelDetector.PanelInfo>): List<List<OcrBlock>> {
        val groups = LinkedHashMap<PanelDetector.PanelInfo, MutableList<OcrBlock>>()
        val fallback = mutableListOf<OcrBlock>()
        for (block in blocks) {
            val panel = findPanelForRect(panels, block.rect)
            if (panel != null) {
                groups.getOrPut(panel) { mutableListOf() }.add(block)
            } else {
                fallback.add(block)
            }
        }
        return groups.values.toList() + listOf(fallback).filter { it.isNotEmpty() }
    }

    private fun findPanelForRect(panels: List<PanelDetector.PanelInfo>, rect: Rect): PanelDetector.PanelInfo? {
        var bestPanel: PanelDetector.PanelInfo? = null
        var bestOverlap = 0
        val rectArea = max(1, rect.width() * rect.height())
        for (panel in panels) {
            val overlapLeft = max(rect.left, panel.rect.left)
            val overlapTop = max(rect.top, panel.rect.top)
            val overlapRight = min(rect.right, panel.rect.right)
            val overlapBottom = min(rect.bottom, panel.rect.bottom)
            if (overlapLeft < overlapRight && overlapTop < overlapBottom) {
                val overlap = (overlapRight - overlapLeft) * (overlapBottom - overlapTop)
                if (overlap > bestOverlap) {
                    bestOverlap = overlap
                    bestPanel = panel
                }
            }
        }
        return if (bestOverlap.toFloat() / rectArea.toFloat() >= 0.35f) bestPanel else null
    }

    private fun unionRects(rects: List<Rect>): Rect {
        val union = Rect(rects.first())
        for (i in 1 until rects.size) union.union(rects[i])
        return union
    }
}

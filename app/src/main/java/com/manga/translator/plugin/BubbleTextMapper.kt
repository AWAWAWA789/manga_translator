package com.manga.translator.plugin

import android.graphics.Rect
import com.manga.translator.model.OcrBlock
import com.manga.translator.util.TextFilter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class BubbleTextMapper {
    
    data class BubbleTextBlock(
        val bubbleRect: Rect,
        val originalText: String,
        val ocrBlocks: List<OcrBlock>,
        val isVertical: Boolean,
        val readingOrder: Int = 0
    )
    
    companion object {
        private const val TAG = "BubbleTextMapper"
        private const val SCORE_CENTER_INSIDE = 50f
        private const val SCORE_OVERLAP_MAX = 30f
        private const val SCORE_DISTANCE_MAX = 20f
        private const val SCORE_DIRECTION_MATCH = 10f
        private const val PENALTY_OUTSIDE = -20f
        private const val MIN_ASSIGN_SCORE = 30f
    }
    
    fun mapOcrToBubbles(
        bubbles: List<BubbleDetector.BubbleInfo>,
        ocrBlocks: List<OcrBlock>
    ): List<BubbleTextBlock> {
        if (bubbles.isEmpty()) return emptyList()
        
        val bubbleOcrMap = mutableMapOf<Int, MutableList<OcrBlock>>()
        
        for (i in bubbles.indices) {
            bubbleOcrMap[i] = mutableListOf()
        }
        
        val assignedOcrIndices = mutableSetOf<Int>()
        
        for (ocrIndex in ocrBlocks.indices) {
            val ocr = ocrBlocks[ocrIndex]
            var bestBubbleIndex = -1
            var bestScore = 0f
            
            for (bubbleIndex in bubbles.indices) {
                val bubble = bubbles[bubbleIndex]
                val score = calculateScore(ocr, bubble)
                
                if (score > bestScore) {
                    bestScore = score
                    bestBubbleIndex = bubbleIndex
                }
            }
            
            if (bestBubbleIndex >= 0 && bestScore >= MIN_ASSIGN_SCORE) {
                bubbleOcrMap[bestBubbleIndex]?.add(ocr)
                assignedOcrIndices.add(ocrIndex)
            }
        }
        
        val result = mutableListOf<BubbleTextBlock>()
        var readingOrder = 0
        
        for (bubbleIndex in bubbles.indices) {
            val blocks = bubbleOcrMap[bubbleIndex] ?: continue
            if (blocks.isEmpty()) continue
            
            val bubble = bubbles[bubbleIndex]
            val sortedBlocks = sortBlocksByReadingOrder(blocks, bubble.isVertical)
            val mergedText = mergeBlocksText(sortedBlocks)
            
            if (mergedText.isBlank() || !TextFilter.containsJapanese(mergedText)) continue
            
            result.add(BubbleTextBlock(
                bubbleRect = bubble.rect,
                originalText = mergedText,
                ocrBlocks = sortedBlocks,
                isVertical = bubble.isVertical,
                readingOrder = readingOrder++
            ))
        }
        
        return result.sortedWith(
            compareBy<BubbleTextBlock> { it.bubbleRect.top }
                .thenByDescending { it.bubbleRect.centerX() }
        )
    }
    
    private fun calculateScore(ocr: OcrBlock, bubble: BubbleDetector.BubbleInfo): Float {
        var score = 0f
        
        val ocrCenterX = ocr.rect.centerX()
        val ocrCenterY = ocr.rect.centerY()
        val bubbleCenterX = bubble.rect.centerX()
        val bubbleCenterY = bubble.rect.centerY()
        
        val expandedBubble = Rect(bubble.rect)
        val expandX = bubble.rect.width() * 0.1f
        val expandY = bubble.rect.height() * 0.1f
        expandedBubble.inset(-expandX.toInt(), -expandY.toInt())
        
        if (expandedBubble.contains(ocrCenterX, ocrCenterY)) {
            score += SCORE_CENTER_INSIDE
        } else {
            score += PENALTY_OUTSIDE
        }
        
        val overlapArea = calculateOverlapArea(ocr.rect, bubble.rect)
        val ocrArea = ocr.rect.width() * ocr.rect.height()
        val overlapRatio = if (ocrArea > 0) overlapArea.toFloat() / ocrArea else 0f
        score += overlapRatio * SCORE_OVERLAP_MAX
        
        val dx = ocrCenterX - bubbleCenterX
        val dy = ocrCenterY - bubbleCenterY
        val distance = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        val maxDistance = max(bubble.rect.width(), bubble.rect.height()) * 0.8f
        val distanceScore = max(0f, 1f - distance / maxDistance) * SCORE_DISTANCE_MAX
        score += distanceScore
        
        val ocrIsVertical = ocr.rect.height() > ocr.rect.width() * 1.35f
        if (ocrIsVertical == bubble.isVertical) {
            score += SCORE_DIRECTION_MATCH
        }
        
        return score
    }
    
    private fun calculateOverlapArea(rect1: Rect, rect2: Rect): Int {
        val overlapLeft = max(rect1.left, rect2.left)
        val overlapTop = max(rect1.top, rect2.top)
        val overlapRight = min(rect1.right, rect2.right)
        val overlapBottom = min(rect1.bottom, rect2.bottom)
        
        return if (overlapLeft < overlapRight && overlapTop < overlapBottom) {
            (overlapRight - overlapLeft) * (overlapBottom - overlapTop)
        } else {
            0
        }
    }
    
    private fun sortBlocksByReadingOrder(blocks: List<OcrBlock>, isVertical: Boolean): List<OcrBlock> {
        return if (isVertical) {
            blocks.sortedWith(
                compareByDescending<OcrBlock> { it.rect.centerX() }
                    .thenBy { it.rect.top }
            )
        } else {
            blocks.sortedWith(
                compareBy<OcrBlock> { it.rect.top }
                    .thenBy { it.rect.left }
            )
        }
    }
    
    private fun mergeBlocksText(blocks: List<OcrBlock>): String {
        return blocks.joinToString("") { it.text.trim() }
    }
}

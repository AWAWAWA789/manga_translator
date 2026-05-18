package com.manga.translator.plugin

import android.graphics.Rect
import com.manga.translator.model.MergedBlock
import com.manga.translator.model.OcrBlock
import com.manga.translator.util.RectUtils

class TextMerger {
    
    companion object {
        private const val HORIZONTAL_GAP_THRESHOLD = 30
        private const val VERTICAL_GAP_THRESHOLD = 30
        private const val OVERLAP_RATIO = 0.5
    }
    
    fun merge(blocks: List<OcrBlock>): List<MergedBlock> {
        if (blocks.isEmpty()) return emptyList()
        
        val sortedBlocks = sortByReadingOrder(blocks)
        
        val horizontalBlocks = sortedBlocks.filter { !it.isVertical }
        val verticalBlocks = sortedBlocks.filter { it.isVertical }
        
        val mergedHorizontal = mergeHorizontalBlocks(horizontalBlocks)
        val mergedVertical = mergeVerticalBlocks(verticalBlocks)
        
        return (mergedHorizontal + mergedVertical)
            .sortedBy { it.readingOrder }
    }
    
    private fun mergeHorizontalBlocks(blocks: List<OcrBlock>): List<MergedBlock> {
        val merged = mutableListOf<MergedBlock>()
        val used = mutableSetOf<Int>()
        
        for (i in blocks.indices) {
            if (i in used) continue
            
            val current = blocks[i]
            var currentText = current.text
            var currentRect = current.rect
            val currentBlocks = mutableListOf(current)
            used.add(i)
            
            for (j in i + 1 until blocks.size) {
                if (j in used) continue
                
                val candidate = blocks[j]
                
                if (shouldMergeHorizontal(currentRect, candidate.rect)) {
                    currentText += candidate.text
                    currentRect = RectUtils.unionRects(currentRect, candidate.rect)
                    currentBlocks.add(candidate)
                    used.add(j)
                }
            }
            
            merged.add(MergedBlock(
                mergedText = currentText,
                unionRect = currentRect,
                blocks = currentBlocks,
                isVertical = false,
                readingOrder = merged.size
            ))
        }
        
        return merged
    }
    
    private fun mergeVerticalBlocks(blocks: List<OcrBlock>): List<MergedBlock> {
        val merged = mutableListOf<MergedBlock>()
        val used = mutableSetOf<Int>()
        
        for (i in blocks.indices) {
            if (i in used) continue
            
            val current = blocks[i]
            var currentText = current.text
            var currentRect = current.rect
            val currentBlocks = mutableListOf(current)
            used.add(i)
            
            for (j in i + 1 until blocks.size) {
                if (j in used) continue
                
                val candidate = blocks[j]
                
                if (shouldMergeVertical(currentRect, candidate.rect)) {
                    currentText = candidate.text + currentText
                    currentRect = RectUtils.unionRects(currentRect, candidate.rect)
                    currentBlocks.add(candidate)
                    used.add(j)
                }
            }
            
            merged.add(MergedBlock(
                mergedText = currentText,
                unionRect = currentRect,
                blocks = currentBlocks,
                isVertical = true,
                readingOrder = merged.size
            ))
        }
        
        return merged
    }
    
    private fun shouldMergeHorizontal(rect1: Rect, rect2: Rect): Boolean {
        val verticalOverlap = RectUtils.calculateVerticalOverlap(rect1, rect2)
        val minHeight = minOf(rect1.height(), rect2.height())
        
        if (verticalOverlap < minHeight * OVERLAP_RATIO) {
            return false
        }
        
        val horizontalGap = RectUtils.calculateHorizontalGap(rect1, rect2)
        return horizontalGap <= HORIZONTAL_GAP_THRESHOLD
    }
    
    private fun shouldMergeVertical(rect1: Rect, rect2: Rect): Boolean {
        val horizontalOverlap = RectUtils.calculateHorizontalOverlap(rect1, rect2)
        val minWidth = minOf(rect1.width(), rect2.width())
        
        if (horizontalOverlap < minWidth * OVERLAP_RATIO) {
            return false
        }
        
        val verticalGap = RectUtils.calculateVerticalGap(rect1, rect2)
        return verticalGap <= VERTICAL_GAP_THRESHOLD
    }
    
    private fun sortByReadingOrder(blocks: List<OcrBlock>): List<OcrBlock> {
        return blocks.sortedWith(
            compareBy<OcrBlock> { it.rect.top }
                .thenByDescending { it.rect.left }
        )
    }
}
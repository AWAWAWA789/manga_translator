package com.manga.translator.plugin

import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.max
import kotlin.math.min

class BubbleLayout {
    
    companion object {
        private const val TAG = "BubbleLayout"
        
        // 内边距比例
        private const val PADDING_RATIO = 0.08f  // 气泡内边距比例
        
        // 最小内边距
        private const val MIN_PADDING = 8f
        
        // 最大字体大小
        private const val MAX_FONT_SIZE = 24f
        
        // 最小字体大小
        private const val MIN_FONT_SIZE = 10f
    }
    
    data class LayoutResult(
        val text: String,
        val textSize: Float,
        val textRect: Rect,
        val isVertical: Boolean,
        val layout: StaticLayout?
    )
    
    /**
     * 计算译文在气泡内的布局
     */
    fun calculateLayout(
        translatedText: String,
        bubbleRect: Rect,
        isVertical: Boolean,
        textPaint: TextPaint,
        scaledDensity: Float
    ): LayoutResult? {
        if (translatedText.isBlank() || bubbleRect.isEmpty) {
            return null
        }
        
        // 计算气泡内可用区域
        val availableRect = calculateAvailableRect(bubbleRect)
        
        // 计算字体大小
        val textSize = calculateTextSize(
            translatedText,
            availableRect,
            isVertical,
            textPaint,
            scaledDensity
        )
        
        // 计算文本区域
        val textRect = calculateTextRect(
            translatedText,
            textSize,
            availableRect,
            isVertical,
            textPaint
        )
        
        // 创建文本布局
        val layout = if (!isVertical) {
            createTextLayout(translatedText, textSize, textRect.width(), textPaint)
        } else {
            null
        }
        
        return LayoutResult(
            text = translatedText,
            textSize = textSize,
            textRect = textRect,
            isVertical = isVertical,
            layout = layout
        )
    }
    
    /**
     * 计算气泡内可用区域（考虑内边距）
     */
    private fun calculateAvailableRect(bubbleRect: Rect): Rect {
        val paddingX = max(MIN_PADDING, bubbleRect.width() * PADDING_RATIO)
        val paddingY = max(MIN_PADDING, bubbleRect.height() * PADDING_RATIO)
        
        return Rect(
            (bubbleRect.left + paddingX).toInt(),
            (bubbleRect.top + paddingY).toInt(),
            (bubbleRect.right - paddingX).toInt(),
            (bubbleRect.bottom - paddingY).toInt()
        )
    }
    
    /**
     * 计算合适的字体大小
     */
    private fun calculateTextSize(
        text: String,
        availableRect: Rect,
        isVertical: Boolean,
        textPaint: TextPaint,
        scaledDensity: Float
    ): Float {
        var textSize = MAX_FONT_SIZE * scaledDensity
        val minSize = MIN_FONT_SIZE * scaledDensity
        
        while (textSize >= minSize) {
            textPaint.textSize = textSize
            
            if (isVertical) {
                // 竖向文本：检查是否能放入可用区域
                val columns = calculateVerticalColumns(text, textSize, availableRect.height())
                val neededWidth = columns * textSize * 1.2f
                if (neededWidth <= availableRect.width()) {
                    return textSize
                }
            } else {
                // 横向文本：检查是否能放入可用区域
                val layout = createTextLayout(text, textSize, availableRect.width(), textPaint)
                if (layout.height <= availableRect.height()) {
                    return textSize
                }
            }
            
            textSize -= 1f * scaledDensity
        }
        
        return minSize
    }
    
    /**
     * 计算竖向文本需要的列数
     */
    private fun calculateVerticalColumns(text: String, textSize: Float, availableHeight: Int): Int {
        val lineHeight = textSize * 1.2f
        val charsPerColumn = max(1, (availableHeight / lineHeight).toInt())
        return (text.length + charsPerColumn - 1) / charsPerColumn
    }
    
    /**
     * 计算文本区域
     */
    private fun calculateTextRect(
        text: String,
        textSize: Float,
        availableRect: Rect,
        isVertical: Boolean,
        textPaint: TextPaint
    ): Rect {
        textPaint.textSize = textSize
        
        if (isVertical) {
            // 竖向文本
            val columns = calculateVerticalColumns(text, textSize, availableRect.height())
            val lineHeight = textSize * 1.2f
            val charsPerColumn = max(1, (availableRect.height() / lineHeight).toInt())
            val textWidth = columns * textSize * 1.2f
            val textHeight = min(text.length, charsPerColumn) * lineHeight
            
            val left = availableRect.centerX() - textWidth / 2
            val top = availableRect.centerY() - textHeight / 2
            
            return Rect(
                left.toInt(),
                top.toInt(),
                (left + textWidth).toInt(),
                (top + textHeight).toInt()
            )
        } else {
            // 横向文本
            val layout = createTextLayout(text, textSize, availableRect.width(), textPaint)
            val textWidth = min(layout.width, availableRect.width())
            val textHeight = layout.height
            
            val left = availableRect.centerX() - textWidth / 2
            val top = availableRect.centerY() - textHeight / 2
            
            return Rect(
                left.toInt(),
                top.toInt(),
                (left + textWidth).toInt(),
                (top + textHeight).toInt()
            )
        }
    }
    
    /**
     * 创建文本布局
     */
    private fun createTextLayout(text: String, textSize: Float, width: Int, textPaint: TextPaint): StaticLayout {
        textPaint.textSize = textSize
        return StaticLayout.Builder.obtain(text, 0, text.length, textPaint, max(1, width))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.1f)
            .setIncludePad(false)
            .build()
    }
}
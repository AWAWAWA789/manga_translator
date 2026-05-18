package com.manga.translator.util

import android.graphics.Rect

object RectUtils {
    
    fun calculateVerticalOverlap(rect1: Rect, rect2: Rect): Int {
        val overlapTop = maxOf(rect1.top, rect2.top)
        val overlapBottom = minOf(rect1.bottom, rect2.bottom)
        return maxOf(0, overlapBottom - overlapTop)
    }
    
    fun calculateHorizontalOverlap(rect1: Rect, rect2: Rect): Int {
        val overlapLeft = maxOf(rect1.left, rect2.left)
        val overlapRight = minOf(rect1.right, rect2.right)
        return maxOf(0, overlapRight - overlapLeft)
    }
    
    fun calculateHorizontalGap(rect1: Rect, rect2: Rect): Int {
        return if (rect1.right <= rect2.left) {
            rect2.left - rect1.right
        } else if (rect2.right <= rect1.left) {
            rect1.left - rect2.right
        } else {
            0
        }
    }
    
    fun calculateVerticalGap(rect1: Rect, rect2: Rect): Int {
        return if (rect1.bottom <= rect2.top) {
            rect2.top - rect1.bottom
        } else if (rect2.bottom <= rect1.top) {
            rect1.top - rect2.bottom
        } else {
            0
        }
    }
    
    fun unionRects(rect1: Rect, rect2: Rect): Rect {
        return Rect(
            minOf(rect1.left, rect2.left),
            minOf(rect1.top, rect2.top),
            maxOf(rect1.right, rect2.right),
            maxOf(rect1.bottom, rect2.bottom)
        )
    }
    
    fun expandRect(rect: Rect, padding: Int): Rect {
        return Rect(
            rect.left - padding,
            rect.top - padding,
            rect.right + padding,
            rect.bottom + padding
        )
    }
    
    fun isRectInBounds(rect: Rect, screenWidth: Int, screenHeight: Int): Boolean {
        return rect.left >= 0 && rect.top >= 0 && 
               rect.right <= screenWidth && rect.bottom <= screenHeight
    }
    
    fun clampRect(rect: Rect, screenWidth: Int, screenHeight: Int): Rect {
        val width = rect.width()
        val height = rect.height()
        
        var left = rect.left
        var top = rect.top
        
        if (left < 0) left = 0
        if (top < 0) top = 0
        if (left + width > screenWidth) left = screenWidth - width
        if (top + height > screenHeight) top = screenHeight - height
        
        return Rect(left, top, left + width, top + height)
    }
}
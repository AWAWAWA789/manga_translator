package com.manga.translator.model

import android.graphics.Rect

data class OcrBlock(
    val text: String,
    val rect: Rect,
    val confidence: Float = 1.0f,
    val isVertical: Boolean = false
) {
    val width: Int get() = rect.width()
    val height: Int get() = rect.height()
    val centerX: Int get() = rect.centerX()
    val centerY: Int get() = rect.centerY()
}
package com.manga.translator.model

import android.graphics.Rect

data class MergedBlock(
    val mergedText: String,
    val unionRect: Rect,
    val blocks: List<OcrBlock>,
    val isVertical: Boolean,
    val readingOrder: Int = 0
) {
    val width: Int get() = unionRect.width()
    val height: Int get() = unionRect.height()
    val centerX: Int get() = unionRect.centerX()
    val centerY: Int get() = unionRect.centerY()
}
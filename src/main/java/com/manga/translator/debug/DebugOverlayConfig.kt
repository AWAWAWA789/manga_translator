package com.manga.translator.debug

import android.graphics.Rect

data class DebugOverlayConfig(
    val showBubbleRects: Boolean = false,
    val showOcrRects: Boolean = false,
    val showMappingLines: Boolean = false,
    val showReadingOrder: Boolean = false
)

data class DebugOverlayData(
    val bubbles: List<Rect> = emptyList(),
    val ocrBlocks: List<Rect> = emptyList(),
    val mappings: List<Pair<Rect, Rect>> = emptyList(),
    val orderedBubbles: List<Rect> = emptyList()
)
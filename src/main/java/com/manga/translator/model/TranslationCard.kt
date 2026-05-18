package com.manga.translator.model

import android.graphics.Rect

data class TranslationCard(
    val originalText: String,
    val translatedText: String,
    val sourceRect: Rect,
    val isVertical: Boolean = false,
    var cardRect: Rect? = null,
    var fontSize: Float = 24f
) {
    val width: Int get() = cardRect?.width() ?: 0
    val height: Int get() = cardRect?.height() ?: 0
}
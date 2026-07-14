package com.manga.translator.model

import android.graphics.Rect

data class TranslationCard(
    val originalText: String,
    val translatedText: String,
    val sourceRect: Rect,
    val isVertical: Boolean = false
)

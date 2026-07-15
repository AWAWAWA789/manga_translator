package com.manga.translator.util

import android.graphics.Bitmap
import android.graphics.Rect

data class ScreenCropConfig(
    val topRatio: Float = 0.15f,
    val bottomRatio: Float = 0.90f,
    val leftRatio: Float = 0f,
    val rightRatio: Float = 1f,
)

object ComicImageCropper {

    fun getCropRect(width: Int, height: Int, config: ScreenCropConfig = ScreenCropConfig()): Rect {
        // 对边界做钳制，防止 ratio > 1.0 或 < 0 时导致 createBitmap 抛 IllegalArgumentException
        val left = (width * config.leftRatio).toInt().coerceIn(0, width)
        val top = (height * config.topRatio).toInt().coerceIn(0, height)
        val right = (width * config.rightRatio).toInt().coerceIn(0, width)
        val bottom = (height * config.bottomRatio).toInt().coerceIn(0, height)
        return Rect(left, top, right, bottom)
    }

    fun cropComicArea(bitmap: Bitmap, config: ScreenCropConfig = ScreenCropConfig()): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        val cropRect = getCropRect(width, height, config)
        val left = cropRect.left
        val top = cropRect.top
        val right = cropRect.right
        val bottom = cropRect.bottom

        val cropWidth = right - left
        val cropHeight = bottom - top

        if (cropWidth <= 0 || cropHeight <= 0) return bitmap
        if (left == 0 && top == 0 && right == width && bottom == height) return bitmap

        return Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight)
    }
}

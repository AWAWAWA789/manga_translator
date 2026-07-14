package com.manga.translator.util

import android.graphics.Bitmap
import android.graphics.Rect

data class ScreenCropConfig(
    val topRatio: Float = 0.15f,
    val bottomRatio: Float = 0.90f,
    val leftRatio: Float = 0f,
    val rightRatio: Float = 1f
)

object ComicImageCropper {

    fun getCropRect(width: Int, height: Int, config: ScreenCropConfig = ScreenCropConfig()): Rect {
        val left = (width * config.leftRatio).toInt()
        val top = (height * config.topRatio).toInt()
        val right = (width * config.rightRatio).toInt()
        val bottom = (height * config.bottomRatio).toInt()
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

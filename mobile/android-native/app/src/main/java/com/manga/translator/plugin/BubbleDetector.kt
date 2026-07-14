package com.manga.translator.plugin

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

class BubbleDetector {
    
    companion object {
        private const val TAG = "BubbleDetector"
        
        // 边缘检测参数
        private const val CANNY_THRESHOLD1 = 30.0
        private const val CANNY_THRESHOLD2 = 100.0
        
        // 高斯模糊参数
        private const val BLUR_SIZE = 3.0
        
        // 形态学操作参数
        private const val MORPH_KERNEL_SIZE = 3.0
        private const val MORPH_ITERATIONS = 1
        
        // 轮廓筛选参数
        private const val MIN_BUBBLE_AREA = 200.0
        private const val MAX_BUBBLE_AREA = 500000.0
        private const val MAX_BUBBLE_IMAGE_RATIO = 0.45
        private const val MIN_ASPECT_RATIO = 0.15
        private const val MAX_ASPECT_RATIO = 6.0
        private const val MIN_SOLIDITY = 0.3
    }
    
    data class BubbleInfo(
        val rect: Rect,
        val contour: MatOfPoint,
        val area: Double,
        val centerX: Int,
        val centerY: Int,
        val isVertical: Boolean
    )
    
    fun detectBubbles(bitmap: Bitmap): List<BubbleInfo> {
        Log.d(TAG, "开始气泡检测，图片尺寸: ${bitmap.width}x${bitmap.height}")
        
        try {
            val rgbaMat = Mat()
            Utils.bitmapToMat(bitmap, rgbaMat)
            
            val grayMat = Mat()
            Imgproc.cvtColor(rgbaMat, grayMat, Imgproc.COLOR_RGBA2GRAY)
            
            val blurredMat = Mat()
            Imgproc.GaussianBlur(grayMat, blurredMat, Size(BLUR_SIZE, BLUR_SIZE), 0.0)
            
            val edgesMat = Mat()
            Imgproc.Canny(blurredMat, edgesMat, CANNY_THRESHOLD1, CANNY_THRESHOLD2)
            
            val kernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(MORPH_KERNEL_SIZE, MORPH_KERNEL_SIZE)
            )
            val dilatedMat = Mat()
            Imgproc.dilate(edgesMat, dilatedMat, kernel, org.opencv.core.Point(-1.0, -1.0), MORPH_ITERATIONS)
            
            val contours = mutableListOf<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(
                dilatedMat,
                contours,
                hierarchy,
                Imgproc.RETR_EXTERNAL,
                Imgproc.CHAIN_APPROX_SIMPLE
            )
            
            Log.d(TAG, "找到 ${contours.size} 个轮廓")
            
            val bubbles = filterBubbles(contours, bitmap.width, bitmap.height)
            
            Log.d(TAG, "筛选后 ${bubbles.size} 个气泡")
            
            rgbaMat.release()
            grayMat.release()
            blurredMat.release()
            edgesMat.release()
            dilatedMat.release()
            kernel.release()
            hierarchy.release()
            
            return bubbles
            
        } catch (e: Exception) {
            Log.e(TAG, "气泡检测失败: ${e.message}")
            return emptyList()
        }
    }
    
    private fun filterBubbles(contours: List<MatOfPoint>, imageWidth: Int, imageHeight: Int): List<BubbleInfo> {
        val bubbles = mutableListOf<BubbleInfo>()
        
        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            
            if (area !in MIN_BUBBLE_AREA..MAX_BUBBLE_AREA) {
                continue
            }
            
            val rect = Imgproc.boundingRect(contour)
            val rectAreaRatio = (rect.width.toDouble() * rect.height.toDouble()) / (imageWidth.toDouble() * imageHeight.toDouble())
            if (rectAreaRatio > MAX_BUBBLE_IMAGE_RATIO) {
                continue
            }
            
            val aspectRatio = rect.width.toDouble() / rect.height.toDouble()
            if (aspectRatio !in MIN_ASPECT_RATIO..MAX_ASPECT_RATIO) {
                continue
            }
            
            val hullIndices = MatOfInt()
            Imgproc.convexHull(contour, hullIndices)
            val hullPoints = mutableListOf<org.opencv.core.Point>()
            val contourPoints = contour.toList()
            val indices = hullIndices.toList()
            for (idx in indices) {
                if (idx in contourPoints.indices) {
                    hullPoints.add(contourPoints[idx])
                }
            }
            val hull = MatOfPoint()
            hull.fromList(hullPoints)
            val hullArea = Imgproc.contourArea(hull)
            val solidity = if (hullArea > 0) area / hullArea else 0.0
            if (solidity < MIN_SOLIDITY) {
                continue
            }
            
            val safeRect = Rect(
                maxOf(0, rect.x),
                maxOf(0, rect.y),
                minOf(imageWidth, rect.x + rect.width),
                minOf(imageHeight, rect.y + rect.height)
            )
            
            val isVertical = safeRect.height() > safeRect.width() * 1.35
            
            bubbles.add(BubbleInfo(
                rect = safeRect,
                contour = contour,
                area = area,
                centerX = safeRect.centerX(),
                centerY = safeRect.centerY(),
                isVertical = isVertical
            ))
        }
        
        return bubbles.sortedByDescending { it.area }
    }
    
}

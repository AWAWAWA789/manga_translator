package com.manga.translator.plugin

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.manga.translator.domain.detection.BubbleInfo
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import com.manga.translator.domain.detection.BubbleDetector as BubbleDetectorInterface

class BubbleDetector : BubbleDetectorInterface {

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
        // MIN_BUBBLE_AREA 从 200 降到 150（减小 25%），让小气泡也能被检测到
        // MAX_BUBBLE_IMAGE_RATIO 从 0.45 提到 0.55，让大气泡也能被检测到
        private const val MIN_BUBBLE_AREA = 150.0
        private const val MAX_BUBBLE_AREA = 500000.0
        private const val MAX_BUBBLE_IMAGE_RATIO = 0.55
        private const val MIN_ASPECT_RATIO = 0.15
        private const val MAX_ASPECT_RATIO = 6.0
        private const val MIN_SOLIDITY = 0.3
    }

    override fun detectBubbles(bitmap: Bitmap): List<BubbleInfo> {
        Log.d(TAG, "开始气泡检测，图片尺寸: ${bitmap.width}x${bitmap.height}")

        val rgbaMat = Mat()
        val grayMat = Mat()
        val blurredMat = Mat()
        val edgesMat = Mat()
        val dilatedMat = Mat()
        var kernel: Mat? = null
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()

        try {
            Utils.bitmapToMat(bitmap, rgbaMat)
            Imgproc.cvtColor(rgbaMat, grayMat, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(grayMat, blurredMat, Size(BLUR_SIZE, BLUR_SIZE), 0.0)
            Imgproc.Canny(blurredMat, edgesMat, CANNY_THRESHOLD1, CANNY_THRESHOLD2)
            kernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(MORPH_KERNEL_SIZE, MORPH_KERNEL_SIZE),
            )
            val kernelMat = kernel!!
            Imgproc.dilate(edgesMat, dilatedMat, kernelMat, org.opencv.core.Point(-1.0, -1.0), MORPH_ITERATIONS)

            Imgproc.findContours(
                dilatedMat,
                contours,
                hierarchy,
                Imgproc.RETR_EXTERNAL,
                Imgproc.CHAIN_APPROX_SIMPLE,
            )

            Log.d(TAG, "找到 ${contours.size} 个轮廓")

            val bubbles = filterBubbles(contours, bitmap.width, bitmap.height)

            Log.d(TAG, "筛选后 ${bubbles.size} 个气泡")

            return bubbles
        } catch (e: Exception) {
            Log.e(TAG, "气泡检测失败: ${e.message}")
            return emptyList()
        } finally {
            // 统一在 finally 中释放所有 native 资源，避免异常路径下内存泄漏
            rgbaMat.release()
            grayMat.release()
            blurredMat.release()
            edgesMat.release()
            dilatedMat.release()
            // kernel 是 var Mat? 而非 lateinit，不会抛 UninitializedPropertyAccessException
            try { kernel?.release() } catch (_: Exception) {}
            hierarchy.release()
            for (contour in contours) {
                contour.release()
            }
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
            val hull = MatOfPoint()
            try {
                Imgproc.convexHull(contour, hullIndices)
                val hullPoints = mutableListOf<org.opencv.core.Point>()
                val contourPoints = contour.toList()
                val indices = hullIndices.toList()
                for (idx in indices) {
                    if (idx in contourPoints.indices) {
                        hullPoints.add(contourPoints[idx])
                    }
                }
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
                    minOf(imageHeight, rect.y + rect.height),
                )

                val isVertical = safeRect.height() > safeRect.width() * 1.35

                bubbles.add(
                    BubbleInfo(
                        rect = safeRect,
                        area = area,
                        centerX = safeRect.centerX(),
                        centerY = safeRect.centerY(),
                        isVertical = isVertical,
                    ),
                )
            } finally {
                // 统一在 finally 中释放中间 Mat 资源，避免 hull.fromList / contourArea 抛异常时内存泄漏
                hullIndices.release()
                hull.release()
            }
        }

        // contours 由调用方 detectBubbles 的 finally 块统一释放
        return bubbles.sortedByDescending { it.area }
    }
}

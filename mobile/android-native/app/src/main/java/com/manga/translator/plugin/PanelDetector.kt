package com.manga.translator.plugin

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.manga.translator.domain.detection.PanelInfo
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import com.manga.translator.domain.detection.PanelDetector as PanelDetectorInterface

class PanelDetector : PanelDetectorInterface {

    companion object {
        private const val TAG = "PanelDetector"
        private const val MIN_PANEL_AREA_RATIO = 0.03
        private const val MAX_PANEL_AREA_RATIO = 0.95
        private const val MIN_ASPECT_RATIO = 0.15f
        private const val MAX_ASPECT_RATIO = 6.0f
        private const val BORDER_THRESHOLD = 80.0
        private const val MORPH_KERNEL_SIZE = 5.0
    }

    override fun detectPanels(bitmap: Bitmap): List<PanelInfo> {
        Log.d(TAG, "开始分镜检测: ${bitmap.width}x${bitmap.height}")

        val rgbaMat = Mat()
        val grayMat = Mat()
        val binaryMat = Mat()
        val closedMat = Mat()
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()
        var kernel: Mat? = null

        try {
            Utils.bitmapToMat(bitmap, rgbaMat)
            Imgproc.cvtColor(rgbaMat, grayMat, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.threshold(grayMat, binaryMat, BORDER_THRESHOLD, 255.0, Imgproc.THRESH_BINARY_INV)
            kernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(MORPH_KERNEL_SIZE, MORPH_KERNEL_SIZE),
            )
            Imgproc.morphologyEx(binaryMat, closedMat, Imgproc.MORPH_CLOSE, kernel, org.opencv.core.Point(-1.0, -1.0), 2)

            Imgproc.findContours(
                closedMat,
                contours,
                hierarchy,
                Imgproc.RETR_EXTERNAL,
                Imgproc.CHAIN_APPROX_SIMPLE,
            )

            Log.d(TAG, "找到 ${contours.size} 个轮廓")

            val panels = filterPanels(contours, bitmap.width, bitmap.height)

            Log.d(TAG, "筛选后 ${panels.size} 个分镜")
            return panels
        } catch (e: Exception) {
            Log.e(TAG, "分镜检测失败: ${e.message}")
            return emptyList()
        } finally {
            // 统一在 finally 中释放所有 native 资源
            rgbaMat.release()
            grayMat.release()
            binaryMat.release()
            closedMat.release()
            kernel?.release()
            hierarchy.release()
            for (contour in contours) {
                contour.release()
            }
        }
    }

    private fun filterPanels(contours: List<MatOfPoint>, imageWidth: Int, imageHeight: Int): List<PanelInfo> {
        val imageArea = imageWidth.toLong() * imageHeight.toLong()
        val minArea = (imageArea * MIN_PANEL_AREA_RATIO).toLong()
        val maxArea = (imageArea * MAX_PANEL_AREA_RATIO).toLong()
        val panels = mutableListOf<PanelInfo>()

        for (contour in contours) {
            val area = Imgproc.contourArea(contour).toLong()
            if (area < minArea || area > maxArea) continue

            val rect = Imgproc.boundingRect(contour)
            val aspect = rect.width.toFloat() / rect.height.toFloat().coerceAtLeast(1f)
            if (aspect < MIN_ASPECT_RATIO || aspect > MAX_ASPECT_RATIO) continue

            val safeRect = Rect(
                maxOf(0, rect.x),
                maxOf(0, rect.y),
                minOf(imageWidth, rect.x + rect.width),
                minOf(imageHeight, rect.y + rect.height),
            )

            if (safeRect.width() < 50 || safeRect.height() < 50) continue

            panels.add(PanelInfo(rect = safeRect, area = area))
        }

        // 按面积降序排序后，按 IoU>0.7 合并高度重叠的分镜，保留面积更大的
        val deduped = mutableListOf<PanelInfo>()
        for (panel in panels.sortedByDescending { it.area }) {
            val isDuplicate = deduped.any { existing ->
                iou(panel.rect, existing.rect) > 0.7f
            }
            if (!isDuplicate) {
                deduped.add(panel)
            }
        }
        return deduped
    }

    /**
     * 计算两个 Rect 的交并比（Intersection over Union）
     */
    private fun iou(a: Rect, b: Rect): Float {
        val overlapLeft = maxOf(a.left, b.left)
        val overlapTop = maxOf(a.top, b.top)
        val overlapRight = minOf(a.right, b.right)
        val overlapBottom = minOf(a.bottom, b.bottom)
        if (overlapLeft >= overlapRight || overlapTop >= overlapBottom) return 0f
        val overlapArea = (overlapRight - overlapLeft).toFloat() * (overlapBottom - overlapTop).toFloat()
        val areaA = a.width().toFloat() * a.height().toFloat()
        val areaB = b.width().toFloat() * b.height().toFloat()
        val unionArea = areaA + areaB - overlapArea
        if (unionArea <= 0f) return 0f
        return overlapArea / unionArea
    }

    override fun findPanelForRect(panels: List<PanelInfo>, rect: Rect): PanelInfo? {
        var bestPanel: PanelInfo? = null
        var bestOverlap = 0L

        for (panel in panels) {
            val overlapLeft = maxOf(rect.left, panel.rect.left)
            val overlapTop = maxOf(rect.top, panel.rect.top)
            val overlapRight = minOf(rect.right, panel.rect.right)
            val overlapBottom = minOf(rect.bottom, panel.rect.bottom)

            if (overlapLeft < overlapRight && overlapTop < overlapBottom) {
                val overlapArea = (overlapRight - overlapLeft).toLong() * (overlapBottom - overlapTop)
                if (overlapArea > bestOverlap) {
                    bestOverlap = overlapArea
                    bestPanel = panel
                }
            }
        }

        if (bestPanel != null) return bestPanel

        var nearestPanel: PanelInfo? = null
        var nearestDist = Long.MAX_VALUE

        for (panel in panels) {
            val dx = rect.centerX() - panel.rect.centerX()
            val dy = rect.centerY() - panel.rect.centerY()
            val dist = (dx.toLong() * dx + dy.toLong() * dy)
            if (dist < nearestDist) {
                nearestDist = dist
                nearestPanel = panel
            }
        }

        val np = nearestPanel ?: return null
        val maxDist = maxOf(np.rect.width(), np.rect.height()).toLong() * 2
        return if (nearestDist < maxDist * maxDist) np else null
    }
}

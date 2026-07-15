package com.manga.translator.domain.detection

import android.graphics.Rect

/**
 * 气泡检测结果数据类。
 *
 * 从 [BubbleDetector] 实现类返回，由编排层（PluginManager/TranslationController）消费。
 * 纯数据类，无 Android Framework 生命周期依赖。
 */
data class BubbleInfo(
    val rect: Rect,
    val area: Double,
    val centerX: Int,
    val centerY: Int,
    val isVertical: Boolean,
)

/**
 * 气泡检测领域接口。
 *
 * 定义漫画图片中对话气泡检测的契约，与具体实现（OpenCV、AI 视觉等）解耦。
 * 实现类在 data 层（plugin.BubbleDetector）。
 */
interface BubbleDetector {

    /**
     * 检测图片中的对话气泡。
     *
     * @param bitmap 待检测图片
     * @return 气泡信息列表，包含矩形区域、面积、中心点、文字方向
     */
    fun detectBubbles(bitmap: android.graphics.Bitmap): List<BubbleInfo>
}

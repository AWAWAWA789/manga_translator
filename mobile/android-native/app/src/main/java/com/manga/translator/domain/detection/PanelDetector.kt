package com.manga.translator.domain.detection

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * 分镜检测结果数据类。
 *
 * 从 [PanelDetector] 实现类返回，由编排层（PluginManager/TranslationController）消费。
 */
data class PanelInfo(
    val rect: Rect,
    val area: Long,
)

/**
 * 分镜检测领域接口。
 *
 * 定义漫画图片中分镜（panel）检测的契约，与具体实现解耦。
 * 实现类在 data 层（plugin.PanelDetector）。
 */
interface PanelDetector {

    /**
     * 检测图片中的分镜区域。
     *
     * @param bitmap 待检测图片
     * @return 分镜信息列表，包含矩形区域和面积
     */
    fun detectPanels(bitmap: Bitmap): List<PanelInfo>

    /**
     * 在已检测的分镜列表中，找到包含指定矩形最匹配的分镜。
     *
     * @param panels 分镜列表（通常来自 [detectPanels] 的返回）
     * @param rect 待匹配的矩形（通常是文本区域）
     * @return 最匹配的分镜；无匹配时返回 null
     */
    fun findPanelForRect(panels: List<PanelInfo>, rect: Rect): PanelInfo?
}

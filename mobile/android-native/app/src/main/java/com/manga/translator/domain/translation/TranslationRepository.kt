package com.manga.translator.domain.translation

import android.graphics.Bitmap
import android.graphics.Rect
import com.manga.translator.debug.DebugOverlayData
import com.manga.translator.model.TranslationCard
import com.manga.translator.util.ScreenCropConfig

/**
 * 翻译仓储领域接口。
 *
 * 定义图片翻译的业务契约，与具体实现（PluginManager 及未来的 TranslationRepositoryImpl）解耦。
 * presentation 层（TranslationController）通过此接口调用 data 层，实现可测试性：
 * 单元测试可 mock 此接口验证 Controller 的状态管理逻辑。
 *
 * 实现类负责组合 OcrEngine + Translator + BubbleDetector + PanelDetector + AiVisionPipeline，
 * 完成裁剪 → OCR → 翻译 → 坐标偏移的完整流程。
 *
 * Bitmap 生命周期契约：
 * - 实现不回收传入的 [TranslateParams.bitmap]，调用方负责
 * - 内部裁剪产生的 Bitmap 由实现负责回收
 */
interface TranslationRepository {

    /**
     * 翻译参数。
     *
     * @param bitmap 待翻译的截图，调用方负责 recycle
     * @param cropConfig 截图裁剪配置（定义有效翻译区域）
     * @param useAiVisionMode 是否启用 AI 多模态识别（仅 isManual=true 时生效）
     * @param lastTranslationRects 上一帧翻译区域，用于去重过滤避免重复翻译
     * @param verticalOnly 是否强制竖排识别模式
     * @param isManual 是否为手动翻译（影响 AI 路径选择）
     */
    data class TranslateParams(
        val bitmap: Bitmap,
        val cropConfig: ScreenCropConfig,
        val useAiVisionMode: Boolean,
        val lastTranslationRects: List<Rect> = emptyList(),
        val verticalOnly: Boolean = false,
        val isManual: Boolean = false,
    )

    /**
     * 翻译结果。
     *
     * @param cards 翻译卡片列表（已应用坐标偏移到原始截图坐标系）
     * @param debugData 调试覆盖数据（已应用坐标偏移），用于悬浮窗调试渲染
     */
    data class TranslateResult(
        val cards: List<TranslationCard>,
        val debugData: DebugOverlayData,
    )

    /**
     * 初始化仓储依赖（OCR 引擎、OpenCV 等）。
     * 幂等，重复调用无副作用。
     */
    fun initialize()

    /**
     * 执行图片翻译。
     *
     * @param params 翻译参数
     * @return 翻译结果，包含翻译卡片和调试数据
     */
    fun translate(params: TranslateParams): TranslateResult

    /**
     * 释放资源（OCR 引擎、AI Pipeline 等）。
     * 调用后不应再调用 [translate]。
     */
    fun close()
}

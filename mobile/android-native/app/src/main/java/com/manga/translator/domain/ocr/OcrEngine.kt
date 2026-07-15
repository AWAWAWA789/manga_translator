package com.manga.translator.domain.ocr

import android.graphics.Bitmap
import com.manga.translator.model.OcrBlock

/**
 * OCR 引擎领域接口。
 *
 * 定义图片文字识别的契约，与具体 OCR 实现（ML Kit、Tesseract 等）解耦。
 * 实现类在 data 层（OcrPlugin、OcrProcessor）。
 *
 * 注意：[recognize] 是阻塞调用，调用方应在 IO 线程执行。
 * 持有 native 资源的实现在不再使用时必须调用 [close] 释放。
 */
interface OcrEngine {

    /**
     * 识别图片中的文字。
     *
     * @param bitmap 待识别图片
     * @param verticalOnly 是否强制竖排识别模式
     * @return OCR 识别块列表，包含文本、坐标、置信度、方向
     */
    fun recognize(bitmap: Bitmap, verticalOnly: Boolean = false): List<OcrBlock>

    /**
     * 释放 OCR 引擎持有的 native 资源（如 ML Kit TextRecognizer）。
     * 调用后不应再调用 [recognize]。
     */
    fun close()
}

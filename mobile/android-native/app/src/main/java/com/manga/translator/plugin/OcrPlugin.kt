package com.manga.translator.plugin

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.manga.translator.model.OcrBlock
import com.manga.translator.ocr.OcrProcessor

class OcrPlugin(private val context: Context) {

    companion object {
        private const val TAG = "OcrPlugin"
    }

    private var ocrProcessor: OcrProcessor? = null

    fun initialize() {
        ocrProcessor = OcrProcessor(context)
        Log.d(TAG, "OCR插件初始化完成")
    }

    fun recognize(bitmap: Bitmap, verticalOnly: Boolean = false): List<OcrBlock> {
        val processor = ocrProcessor ?: run {
            Log.e(TAG, "OCR插件未初始化")
            return emptyList()
        }

        val results = processor.recognizeText(bitmap, verticalOnly)
        Log.d(TAG, "OCR原始结果: ${results.size} 个")

        val blocks = results.mapNotNull { result ->
            val rect = result.boundingBox ?: return@mapNotNull null
            if (result.text.isBlank()) return@mapNotNull null
            // 置信度过滤已由 OcrProcessor.recognizeBitmap 完成，此处不再重复过滤

            OcrBlock(
                text = result.text,
                rect = rect,
                confidence = result.confidence,
                // 直接使用 OcrProcessor 基于 block 形状判定的 isVertical，避免二次推断导致不一致
                isVertical = result.isVertical,
            )
        }
        Log.d(TAG, "OCR基础过滤后: ${blocks.size} 个")
        return blocks
    }

    fun close() {
        ocrProcessor?.close()
        ocrProcessor = null
    }
}

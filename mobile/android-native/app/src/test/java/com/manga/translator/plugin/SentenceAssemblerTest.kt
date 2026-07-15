package com.manga.translator.plugin

import android.graphics.Rect
import com.manga.translator.model.OcrBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * SentenceAssembler 单元测试。
 *
 * 覆盖竖向/横向句子组装、可靠块过滤、句子合并边界条件。
 * 使用 Robolectric 提供 android.graphics.Rect 实现。
 *
 * 注：当前环境 Robolectric SDK jar 下载/JVM 崩溃，暂时 @Ignore，
 * 待网络或环境就绪后移除注解启用。
 */
@Ignore("Robolectric SDK 环境不可用，待网络/环境就绪后启用")
@RunWith(RobolectricTestRunner::class)
class SentenceAssemblerTest {

    private val assembler = SentenceAssembler()

    private fun block(
        text: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        confidence: Float = 1.0f,
        isVertical: Boolean = false,
    ) = OcrBlock(text, Rect(left, top, right, bottom), confidence, isVertical)

    @Test
    fun `空列表返回空结果`() {
        assertTrue(assembler.assemble(emptyList()).isEmpty())
    }

    @Test
    fun `不可靠块被过滤`() {
        // 无日文字符的块会被 isReliableBlock 过滤
        val blocks = listOf(
            block("hello", 50, 50, 100, 80),
            block("abc", 50, 100, 100, 130),
        )
        assertTrue(assembler.assemble(blocks).isEmpty())
    }

    @Test
    fun `低置信度短文本被过滤`() {
        // confidence < 0.25 的块被过滤
        val blocks = listOf(
            block("こんにちは", 50, 50, 100, 80, confidence = 0.1f),
        )
        assertTrue(assembler.assemble(blocks).isEmpty())
    }

    @Test
    fun `竖向两个相近块合并为一个句子`() {
        val blocks = listOf(
            block("こんにちは", 100, 50, 130, 100, isVertical = true),
            block("これは", 100, 110, 130, 160, isVertical = true),
        )
        val sentences = assembler.assemble(blocks)
        assertEquals(1, sentences.size)
        assertEquals(2, sentences[0].size)
    }

    @Test
    fun `竖向间距过大的块不合并`() {
        val blocks = listOf(
            block("こんにちは", 100, 50, 130, 100, isVertical = true),
            block("これは", 100, 400, 130, 450, isVertical = true),
        )
        val sentences = assembler.assemble(blocks)
        // 间距过大，分成两个句子
        assertEquals(2, sentences.size)
    }

    @Test
    fun `横向两个相近块合并为一个句子`() {
        val blocks = listOf(
            block("こんにちは", 50, 100, 100, 130, isVertical = false),
            block("これは", 110, 100, 160, 130, isVertical = false),
        )
        val sentences = assembler.assemble(blocks)
        assertEquals(1, sentences.size)
        assertEquals(2, sentences[0].size)
    }

    @Test
    fun `横向间距过大的块不合并`() {
        val blocks = listOf(
            block("こんにちは", 50, 100, 100, 130, isVertical = false),
            block("これは", 500, 100, 550, 130, isVertical = false),
        )
        val sentences = assembler.assemble(blocks)
        assertEquals(2, sentences.size)
    }

    @Test
    fun `句末标点阻断后续合并`() {
        // 前一块以句末标点结尾，后续块不应合并
        val blocks = listOf(
            block("こんにちは。", 50, 100, 110, 130, isVertical = false),
            block("これは", 120, 100, 170, 130, isVertical = false),
        )
        val sentences = assembler.assemble(blocks)
        assertEquals(2, sentences.size)
    }

    @Test
    fun `混合竖向与横向块各自独立组装`() {
        val blocks = listOf(
            block("こんにちは", 100, 50, 130, 100, isVertical = true),
            block("これは", 100, 110, 130, 160, isVertical = true),
            block("テスト", 50, 300, 100, 330, isVertical = false),
            block("です", 110, 300, 160, 330, isVertical = false),
        )
        val sentences = assembler.assemble(blocks)
        // 竖向1句 + 横向1句
        assertEquals(2, sentences.size)
    }
}

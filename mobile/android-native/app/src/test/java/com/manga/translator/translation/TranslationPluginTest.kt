package com.manga.translator.translation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TranslationPlugin 质量检测逻辑单元测试。
 *
 * 覆盖 hasRepeatedPatternStatic 的各种场景：
 * - 连续重复中文字符（如 "哈哈哈"）
 * - 段落内字符种类过少（如 "天天天"）
 * - 正常翻译不误判
 * - 边界情况（短文本、非中文）
 */
class TranslationPluginTest {

    @Test
    fun test_repeatedPattern_continuousChineseRepeat() {
        // 连续3个相同中文字符应判定为重复模式
        assertTrue(TranslationPlugin.hasRepeatedPatternStatic("哈哈哈"))
        assertTrue(TranslationPlugin.hasRepeatedPatternStatic("哈哈哈哈哈"))
    }

    @Test
    fun test_repeatedPattern_lowCharVariety() {
        // 段落内只有1-2种字符应判定为重复模式（即使不连续相同）
        assertTrue(TranslationPlugin.hasRepeatedPatternStatic("天天天"))
        assertTrue(TranslationPlugin.hasRepeatedPatternStatic("是是不是"))
    }

    @Test
    fun test_repeatedPattern_normalTranslationNotFlagged() {
        // 正常翻译不应被误判
        assertFalse(TranslationPlugin.hasRepeatedPatternStatic("你好世界"))
        assertFalse(TranslationPlugin.hasRepeatedPatternStatic("我喜欢吃苹果"))
        assertFalse(TranslationPlugin.hasRepeatedPatternStatic("今天天气真好"))
    }

    @Test
    fun test_repeatedPattern_shortTextNotChecked() {
        // 长度 < 3 的文本不检测
        assertFalse(TranslationPlugin.hasRepeatedPatternStatic(""))
        assertFalse(TranslationPlugin.hasRepeatedPatternStatic("哈"))
        assertFalse(TranslationPlugin.hasRepeatedPatternStatic("哈哈"))
    }

    @Test
    fun test_repeatedPattern_asciiRepeatNotFlagged() {
        // 连续3个相同 ASCII 字符不触发第一部分检测（c.code > 0x4E00 条件不满足）
        // 但第二部分（段落字符种类 <= 2）仍会检测到，这是预期行为：
        // 正常翻译结果不会是 "aaa" 这样的低种类文本
        assertTrue(TranslationPlugin.hasRepeatedPatternStatic("aaa"))
        assertTrue(TranslationPlugin.hasRepeatedPatternStatic("xxxxx"))
        // 字符种类丰富的英文文本不误判
        assertFalse(TranslationPlugin.hasRepeatedPatternStatic("hello world"))
    }

    @Test
    fun test_repeatedPattern_mixedPunctuationSegments() {
        // 标点分割后，某一段落字符种类过少应判定为重复
        assertTrue(TranslationPlugin.hasRepeatedPatternStatic("好的。天天天。再见"))
        // 所有段落均正常则不判定
        assertFalse(TranslationPlugin.hasRepeatedPatternStatic("你好。世界。再见"))
    }
}

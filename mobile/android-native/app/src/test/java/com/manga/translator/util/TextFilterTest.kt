package com.manga.translator.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextFilterTest {

    // ==================== isPureChinese ====================

    @Test
    fun `isPureChinese 纯汉字返回 true`() {
        assertTrue(TextFilter.isPureChinese("中文汉字"))
    }

    @Test
    fun `isPureChinese 含平假名返回 false`() {
        assertFalse(TextFilter.isPureChinese("こんにちは"))
    }

    @Test
    fun `isPureChinese 含片假名返回 false`() {
        assertFalse(TextFilter.isPureChinese("カタカナ"))
    }

    @Test
    fun `isPureChinese 汉字混假名返回 false`() {
        assertFalse(TextFilter.isPureChinese("日本語の"))
    }

    @Test
    fun `isPureChinese 无汉字返回 false`() {
        assertFalse(TextFilter.isPureChinese("hello"))
    }

    @Test
    fun `isPureChinese 空字符串返回 false`() {
        assertFalse(TextFilter.isPureChinese(""))
    }

    // ==================== containsJapanese ====================

    @Test
    fun `containsJapanese 含平假名返回 true`() {
        assertTrue(TextFilter.containsJapanese("これは"))
    }

    @Test
    fun `containsJapanese 含片假名返回 true`() {
        assertTrue(TextFilter.containsJapanese("カタカナ"))
    }

    @Test
    fun `containsJapanese 纯汉字返回 false`() {
        assertFalse(TextFilter.containsJapanese("日本語"))
    }

    @Test
    fun `containsJapanese 纯英文返回 false`() {
        assertFalse(TextFilter.containsJapanese("hello"))
    }

    // ==================== isAppUiText ====================

    @Test
    fun `isAppUiText 黑名单精确匹配返回 true`() {
        assertTrue(TextFilter.isAppUiText("暂停翻译"))
        assertTrue(TextFilter.isAppUiText("实时翻译"))
        assertTrue(TextFilter.isAppUiText("截图"))
    }

    @Test
    fun `isAppUiText 含关键词且纯中文短文本返回 true`() {
        assertTrue(TextFilter.isAppUiText("翻译"))
        assertTrue(TextFilter.isAppUiText("漫画翻译器"))
    }

    @Test
    fun `isAppUiText 含关键词但纯中文长度超过6返回 false`() {
        assertFalse(TextFilter.isAppUiText("漫画翻译器配置项"))
    }

    @Test
    fun `isAppUiText 含关键词但含假名非纯中文返回 false`() {
        // "翻译カナ" 含关键词"翻译"，但含片假名导致 isPureChinese=false
        assertFalse(TextFilter.isAppUiText("翻译カナ"))
    }

    @Test
    fun `isAppUiText 不含关键词返回 false`() {
        assertFalse(TextFilter.isAppUiText("こんにちは"))
        assertFalse(TextFilter.isAppUiText("普通文本"))
    }

    // ==================== isLikelyTranslatedChinese ====================

    @Test
    fun `isLikelyTranslatedChinese 长纯中文超16字返回 true`() {
        val text = "这是一个超过十六个汉字的纯中文翻译结果句子"
        assertTrue(text.length > 16)
        assertTrue(TextFilter.isLikelyTranslatedChinese(text))
    }

    @Test
    fun `isLikelyTranslatedChinese 短纯中文返回 false`() {
        assertFalse(TextFilter.isLikelyTranslatedChinese("短中文"))
    }

    @Test
    fun `isLikelyTranslatedChinese 含假名返回 false`() {
        assertFalse(TextFilter.isLikelyTranslatedChinese("こんにちはこれは長いテキストです"))
    }

    @Test
    fun `isLikelyTranslatedChinese 空字符串返回 false`() {
        assertFalse(TextFilter.isLikelyTranslatedChinese(""))
    }

    @Test
    fun `isLikelyTranslatedChinese 纯英文返回 false`() {
        assertFalse(TextFilter.isLikelyTranslatedChinese("english text only"))
    }

    // ==================== isValidOcrText ====================

    @Test
    fun `isValidOcrText 有效日文返回 true`() {
        assertTrue(TextFilter.isValidOcrText("こんにちは"))
        assertTrue(TextFilter.isValidOcrText("日本語"))
    }

    @Test
    fun `isValidOcrText 空白返回 false`() {
        assertFalse(TextFilter.isValidOcrText(""))
        assertFalse(TextFilter.isValidOcrText("   "))
    }

    @Test
    fun `isValidOcrText 单字符返回 false`() {
        assertFalse(TextFilter.isValidOcrText("a"))
    }

    @Test
    fun `isValidOcrText 纯字母数字标点返回 false`() {
        assertFalse(TextFilter.isValidOcrText("abc123"))
        assertFalse(TextFilter.isValidOcrText("!!!???"))
    }

    @Test
    fun `isValidOcrText UI文本返回 false`() {
        assertFalse(TextFilter.isValidOcrText("暂停翻译"))
        assertFalse(TextFilter.isValidOcrText("截图"))
    }

    @Test
    fun `isValidOcrText 过长纯中文译文返回 false`() {
        val text = "这是一个超过十六个汉字的纯中文翻译结果句子"
        assertFalse(TextFilter.isValidOcrText(text))
    }

    @Test
    fun `isValidOcrText 带空白的有效文本去空白后有效`() {
        assertTrue(TextFilter.isValidOcrText("  こんにちは  "))
    }

    // ==================== isJapaneseCandidate ====================

    @Test
    fun `isJapaneseCandidate 含假名返回 true`() {
        assertTrue(TextFilter.isJapaneseCandidate("こんにちは"))
    }

    @Test
    fun `isJapaneseCandidate 含日文标点返回 true`() {
        assertTrue(TextFilter.isJapaneseCandidate("テスト。"))
        assertTrue(TextFilter.isJapaneseCandidate("「日本」"))
    }

    @Test
    fun `isJapaneseCandidate 纯汉字返回 false`() {
        assertFalse(TextFilter.isJapaneseCandidate("日本語"))
    }

    @Test
    fun `isJapaneseCandidate 纯英文返回 false`() {
        assertFalse(TextFilter.isJapaneseCandidate("hello"))
    }
}

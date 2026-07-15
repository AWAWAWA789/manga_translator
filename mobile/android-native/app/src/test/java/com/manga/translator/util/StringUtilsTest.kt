package com.manga.translator.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StringUtils 单元测试。
 * 这是项目的第一个测试，验证测试管道（JaCoCo + JUnit + Robolectric）可用。
 */
class StringUtilsTest {

    @Test
    fun test_similarity_sameString() {
        val result = StringUtils.similarity("hello", "hello")
        assertEquals(1.0f, result, 0.001f)
    }

    @Test
    fun test_similarity_completelyDifferent() {
        val result = StringUtils.similarity("abc", "xyz")
        assertEquals(0.0f, result, 0.001f)
    }

    @Test
    fun test_similarity_partialMatch() {
        val result = StringUtils.similarity("hello", "hallo")
        assertTrue("相似度应大于 0.5", result > 0.5f)
    }

    @Test
    fun test_similarity_emptyStrings() {
        val result = StringUtils.similarity("", "")
        assertEquals(1.0f, result, 0.001f)
    }
}

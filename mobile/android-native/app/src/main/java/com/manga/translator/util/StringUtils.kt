package com.manga.translator.util

/**
 * 字符串相似度工具，统一 Levenshtein 距离与相似度计算。
 * 消除 OcrProcessor / FloatingWindowService 中重复实现。
 */
object StringUtils {

    /**
     * Levenshtein 编辑距离。
     */
    fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[s1.length][s2.length]
    }

    /**
     * 相似度：1 - distance / maxLen，范围 [0, 1]。
     */
    fun similarity(a: String, b: String): Float {
        if (a == b) return 1f
        if (a.isEmpty() || b.isEmpty()) return 0f
        val maxLen = maxOf(a.length, b.length)
        val distance = levenshteinDistance(a, b)
        return 1f - (distance.toFloat() / maxLen)
    }
}

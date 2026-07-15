package com.manga.translator.util

object TextFilter {

    // 预编译正则，避免每次调用重新编译
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val ALNUM_PUNCT_ONLY_REGEX = Regex("^[a-zA-Z0-9\\p{Punct}]+$")

    private val UI_TEXT_BLACKLIST = setOf(
        "暂停翻译", "暂停翻真", "实时翻译", "手动翻译", "恢复翻译",
        "横向模式", "竖向模式", "开始翻译", "停止翻译", "设置",
        "翻译器", "漫画翻译", "正在运行", "翻译结果", "翻译中",
        "截图", "截图中", "正在截图", "截图完成",
    )

    private val UI_KEYWORDS = listOf(
        "暂停", "翻译", "实时", "手动", "模式", "开始", "停止", "设置", "截图",
    )

    fun isAppUiText(text: String): Boolean {
        if (UI_TEXT_BLACKLIST.contains(text)) return true

        for (keyword in UI_KEYWORDS) {
            if (text.contains(keyword)) {
                if (isPureChinese(text) && text.length <= 6) {
                    return true
                }
            }
        }

        return false
    }

    fun isPureChinese(text: String): Boolean {
        var hasKanji = false
        var hasKana = false
        for (char in text) {
            val code = char.code
            if (code in 0x3040..0x309F) hasKana = true
            if (code in 0x30A0..0x30FF) hasKana = true
            if (code in 0x4E00..0x9FFF) hasKanji = true
        }
        return hasKanji && !hasKana
    }

    fun containsJapanese(text: String): Boolean {
        for (char in text) {
            val code = char.code
            if (code in 0x3040..0x309F) return true
            if (code in 0x30A0..0x30FF) return true
            if (code in 0x31F0..0x31FF) return true
        }
        return false
    }

    fun isJapaneseCandidate(text: String): Boolean {
        if (containsJapanese(text)) return true
        if (containsJapanesePunctuation(text)) return true
        return false
    }

    fun isLikelyTranslatedChinese(text: String): Boolean {
        val clean = text.trim().replace(WHITESPACE_REGEX, "")
        if (clean.isEmpty()) return false
        if (!isPureChinese(clean)) return false
        if (containsJapanese(clean)) return false
        // 短纯汉字常见于日文人名、地名、招式名，保留；
        // 只有过长纯汉字才可能是上一轮中文译文污染
        if (clean.length <= 16) return false
        return true
    }

    fun isValidOcrText(text: String): Boolean {
        val clean = text.trim().replace(WHITESPACE_REGEX, "")
        if (clean.isBlank()) return false
        if (clean.length < 2) return false
        if (clean.matches(ALNUM_PUNCT_ONLY_REGEX)) return false
        if (isAppUiText(clean)) return false
        if (isLikelyTranslatedChinese(clean)) return false
        return true
    }

    private fun containsJapanesePunctuation(text: String): Boolean {
        return text.any { char ->
            val code = char.code
            code in 0x3000..0x303F && char in "。、「」『』【】・ー〜…～"
        }
    }
}

package com.manga.translator.translation

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.util.LruCache
import com.manga.translator.domain.translation.Translator

class TranslationPlugin(private val context: Context) : Translator {

    companion object {
        private const val TAG = "TranslationPlugin"
        private const val PREFS_NAME = "translation_config"
        private const val KEY_DEFAULT_TRANSLATOR = "default_translator"
        private const val MAX_CACHE_SIZE = 500

        /**
         * 检测翻译结果中的重复模式，用于质量校验回退。
         * 提取为静态函数以便单元测试，无需 Context。
         */
        internal fun hasRepeatedPatternStatic(text: String): Boolean {
            if (text.length < 3) return false
            for (i in 0 until text.length - 2) {
                val c = text[i]
                if (c == text[i + 1] && c == text[i + 2] && c.code > 0x4E00) {
                    return true
                }
            }
            val segments = text.split(Regex("[，。！？、；：\\s]+")).filter { it.isNotEmpty() }
            for (seg in segments) {
                if (seg.length >= 3) {
                    val uniqueChars = seg.toSet()
                    if (uniqueChars.size <= 2) return true
                }
            }
            return false
        }
    }

    private val preferences: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val baiduTranslator = BaiduTranslator(context)
    private val mimoTranslator = MimoTranslator(context)
    private val translationCache = LruCache<String, String>(MAX_CACHE_SIZE)

    override fun isConfigured(): Boolean {
        return baiduTranslator.isConfigured() || mimoTranslator.isConfigured()
    }

    fun getDefaultTranslator(): TranslatorType {
        val value = preferences.getString(KEY_DEFAULT_TRANSLATOR, TranslatorType.MIMO.name)
        return try {
            TranslatorType.valueOf(value ?: TranslatorType.MIMO.name)
        } catch (e: Exception) {
            TranslatorType.MIMO
        }
    }

    fun setDefaultTranslator(type: TranslatorType) {
        preferences.edit()
            .putString(KEY_DEFAULT_TRANSLATOR, type.name)
            .apply()
    }

    override fun translate(text: String): String {
        translationCache.get(text)?.let {
            Log.d(TAG, "命中缓存")
            return it
        }

        val result = doTranslate(text)

        if (result.isNotEmpty() && !result.startsWith("翻译失败") && !result.startsWith("请配置")) {
            putCache(text, result)
        }

        return result
    }

    override fun translateBatch(texts: List<String>): List<String> {
        if (texts.isEmpty()) return emptyList()
        return if (shouldUseMimo()) {
            translateBatchMimo(texts)
        } else {
            translateBatchBaidu(texts)
        }
    }

    /**
     * MiMo 批量翻译路径：先查缓存，未命中的批量请求 API，全部失败时回退百度逐条翻译。
     */
    private fun translateBatchMimo(texts: List<String>): List<String> {
        // 先查缓存，分离已缓存和未缓存，避免对已缓存文本重复请求 API
        val cachedResults = MutableList(texts.size) { "" }
        val pendingTexts = mutableListOf<String>()
        val pendingIndices = mutableListOf<Int>()
        for (i in texts.indices) {
            val cached = translationCache.get(texts[i])
            if (cached != null) {
                Log.d(TAG, "批量翻译命中缓存")
                cachedResults[i] = cached
            } else {
                pendingTexts.add(texts[i])
                pendingIndices.add(i)
            }
        }

        if (pendingTexts.isNotEmpty()) {
            val translated = doTranslateBatch(pendingTexts)
            val finalTranslated = if (translated.all { it.startsWith("翻译失败") } && baiduTranslator.isConfigured()) {
                Log.d(TAG, "MiMo批量失败，回退百度翻译")
                // 走 translate() 复用缓存，避免对已缓存的文本重复请求
                pendingTexts.map { translate(it) }
            } else {
                translated
            }
            for (i in pendingIndices.indices) {
                cachedResults[pendingIndices[i]] = finalTranslated.getOrNull(i).orEmpty()
            }
        }

        val validated = validateAndFallback(texts, cachedResults)

        for (i in texts.indices) {
            val value = validated.getOrNull(i).orEmpty()
            if (value.isNotEmpty() && !value.startsWith("翻译失败") && !value.startsWith("请配置")) {
                putCache(texts[i], value)
            }
        }
        return validated
    }

    /**
     * 百度翻译路径：先查缓存，未命中的批量请求 API，单条失败时回退逐条翻译。
     */
    private fun translateBatchBaidu(texts: List<String>): List<String> {
        val results = MutableList(texts.size) { "" }
        val pendingTexts = mutableListOf<String>()
        val pendingIndices = mutableListOf<Int>()

        for (i in texts.indices) {
            val cached = translationCache.get(texts[i])
            if (cached != null) {
                results[i] = cached
            } else {
                pendingTexts.add(texts[i])
                pendingIndices.add(i)
            }
        }

        if (pendingTexts.isNotEmpty()) {
            val translated = doTranslateBatch(pendingTexts)
            for (i in pendingIndices.indices) {
                val value = translated.getOrNull(i).orEmpty().ifBlank { translate(pendingTexts[i]) }
                results[pendingIndices[i]] = value
                if (value.isNotEmpty() && !value.startsWith("翻译失败") && !value.startsWith("请配置")) {
                    putCache(pendingTexts[i], value)
                }
            }
        }

        return results
    }

    private fun validateAndFallback(originals: List<String>, translations: List<String>): List<String> {
        val results = translations.toMutableList()
        val badIndices = mutableListOf<Int>()

        for (i in originals.indices) {
            val translated = translations.getOrNull(i).orEmpty()
            if (translated.isEmpty() || translated.startsWith("翻译失败") || translated.startsWith("请配置")) {
                badIndices.add(i)
                continue
            }
            if (hasRepeatedPattern(translated)) {
                badIndices.add(i)
                continue
            }
        }

        if (badIndices.isNotEmpty() && baiduTranslator.isConfigured()) {
            Log.d(TAG, "翻译质量异常 ${badIndices.size} 条，回退逐条翻译")
            for (i in badIndices) {
                val original = originals[i]
                // 走 translate() 而非 baiduTranslator.translate()，以复用 LRU 缓存
                val fallback = translate(original)
                if (fallback.isNotEmpty() && !fallback.startsWith("翻译失败") && !hasRepeatedPattern(fallback)) {
                    results[i] = fallback
                }
            }
        }

        return results
    }

    internal fun hasRepeatedPattern(text: String): Boolean = hasRepeatedPatternStatic(text)

    private fun shouldUseMimo(): Boolean {
        val preferred = getDefaultTranslator()
        return (preferred == TranslatorType.MIMO && mimoTranslator.isConfigured()) ||
            (preferred != TranslatorType.BAIDU && mimoTranslator.isConfigured())
    }

    private fun doTranslate(text: String): String {
        val preferred = getDefaultTranslator()

        return when {
            preferred == TranslatorType.MIMO && mimoTranslator.isConfigured() -> {
                Log.d(TAG, "使用MiMo翻译")
                mimoTranslator.translate(text)
            }
            preferred == TranslatorType.BAIDU && baiduTranslator.isConfigured() -> {
                Log.d(TAG, "使用百度翻译")
                baiduTranslator.translate(text)
            }
            mimoTranslator.isConfigured() -> {
                Log.d(TAG, "MiMo可用，使用MiMo翻译")
                mimoTranslator.translate(text)
            }
            baiduTranslator.isConfigured() -> {
                Log.d(TAG, "百度可用，使用百度翻译")
                baiduTranslator.translate(text)
            }
            else -> {
                Log.e(TAG, "没有可用的翻译器")
                "请配置百度或MiMo翻译API"
            }
        }
    }

    private fun doTranslateBatch(texts: List<String>): List<String> {
        val preferred = getDefaultTranslator()

        return when {
            preferred == TranslatorType.MIMO && mimoTranslator.isConfigured() -> {
                Log.d(TAG, "使用MiMo批量翻译")
                mimoTranslator.translateBatch(texts)
            }
            preferred == TranslatorType.BAIDU && baiduTranslator.isConfigured() -> {
                Log.d(TAG, "使用百度逐条翻译")
                texts.map { baiduTranslator.translate(it) }
            }
            mimoTranslator.isConfigured() -> {
                Log.d(TAG, "MiMo可用，使用MiMo批量翻译")
                mimoTranslator.translateBatch(texts)
            }
            baiduTranslator.isConfigured() -> {
                Log.d(TAG, "百度可用，使用百度逐条翻译")
                texts.map { baiduTranslator.translate(it) }
            }
            else -> {
                Log.e(TAG, "没有可用的翻译器")
                texts.map { "请配置百度或MiMo翻译API" }
            }
        }
    }

    fun getBaiduTranslator(): BaiduTranslator = baiduTranslator
    fun getMimoTranslator(): MimoTranslator = mimoTranslator

    fun clearCache() {
        translationCache.evictAll()
    }

    /**
     * 释放资源：清空翻译缓存。无 native 资源需释放。
     */
    fun close() {
        translationCache.evictAll()
    }

    private fun putCache(key: String, value: String) {
        translationCache.put(key, value)
    }
}

package com.manga.translator.domain.translation

/**
 * 翻译领域接口。
 *
 * 定义文本翻译的契约，与具体翻译服务（百度、MiMo 等）解耦。
 * 实现类在 data 层（BaiduTranslator、MimoTranslator、TranslationPlugin）。
 *
 * 单元测试可通过 mock 此接口验证业务逻辑，无需真实网络调用。
 */
interface Translator {

    /**
     * 翻译单条文本。
     *
     * @param text 待翻译文本（原语言）
     * @return 翻译结果；失败时返回以"翻译失败"或"请配置"开头的错误提示字符串
     */
    fun translate(text: String): String

    /**
     * 批量翻译，用于减少 API 调用次数。
     *
     * @param texts 待翻译文本列表
     * @return 翻译结果列表，顺序与输入一致；实现应保证返回列表长度等于输入长度
     */
    fun translateBatch(texts: List<String>): List<String>

    /**
     * 是否已配置有效的 API 凭证。
     */
    fun isConfigured(): Boolean
}

package com.manga.translator.di

import android.content.Context
import android.util.Log
import com.manga.translator.ocr.OcrProcessor
import com.manga.translator.plugin.AiVisionPipeline
import com.manga.translator.plugin.BubbleDetector
import com.manga.translator.plugin.OcrPlugin
import com.manga.translator.plugin.PanelDetector
import com.manga.translator.plugin.PluginManager
import com.manga.translator.plugin.SentenceAssembler
import com.manga.translator.translation.BaiduTranslator
import com.manga.translator.translation.MimoTranslator
import com.manga.translator.translation.TranslationPlugin

/**
 * 手动依赖注入容器。
 *
 * 设计决策（决策 A）：
 * - 不引入 Hilt/Dagger，避免构建时间和注解复杂度
 * - 提供工厂方法创建与 Service 生命周期绑定的实例（PluginManager、OcrProcessor）
 * - 提供懒加载单例管理无状态或可复用的组件（TranslationPlugin、AiVisionPipeline 等）
 * - 单元测试可通过 [reset] 替换实现或在测试中直接构造被测类
 *
 * 生命周期：
 * - [init] 在 MangaTranslatorApp.onCreate 中调用，持有 applicationContext
 * - [reset] 在测试中使用，重置所有单例以便重新配置
 *
 * @see com.manga.translator.MangaTranslatorApp
 */
object ServiceLocator {

    private const val TAG = "ServiceLocator"

    @Volatile
    private var appContext: Context? = null

    /**
     * 初始化容器。必须在 Application.onCreate 中调用。
     * 重复调用会被忽略（幂等）。
     */
    fun init(context: Context) {
        if (appContext != null) {
            Log.d(TAG, "ServiceLocator 已初始化，跳过")
            return
        }
        appContext = context.applicationContext
        Log.d(TAG, "ServiceLocator 初始化完成")
    }

    private fun requireContext(): Context =
        appContext ?: error("ServiceLocator 未初始化，请先在 Application.onCreate 中调用 init()")

    // ==================== 工厂方法（与 Service 生命周期绑定）====================

    /**
     * 创建 PluginManager 实例。
     * PluginManager 持有可变状态且与 Service 生命周期绑定，不作为单例。
     * 调用方负责在 Service 销毁时调用 [PluginManager.close]。
     */
    fun createPluginManager(): PluginManager = PluginManager(requireContext())

    /**
     * 创建 OcrProcessor 实例。
     * OcrProcessor 持有 ML Kit TextRecognizer，需在 Service 销毁时调用 [OcrProcessor.close]。
     */
    fun createOcrProcessor(): OcrProcessor = OcrProcessor(requireContext())

    // ==================== 懒加载单例（无状态或可安全复用）====================

    @Volatile private var translationPlugin: TranslationPlugin? = null

    @Volatile private var aiVisionPipeline: AiVisionPipeline? = null

    @Volatile private var baiduTranslator: BaiduTranslator? = null

    @Volatile private var mimoTranslator: MimoTranslator? = null

    @Volatile private var bubbleDetector: BubbleDetector? = null

    @Volatile private var panelDetector: PanelDetector? = null

    @Volatile private var sentenceAssembler: SentenceAssembler? = null

    @Volatile private var ocrPlugin: OcrPlugin? = null

    fun translationPlugin(): TranslationPlugin =
        translationPlugin ?: synchronized(this) {
            translationPlugin ?: TranslationPlugin(requireContext()).also {
                translationPlugin = it
                Log.d(TAG, "创建 TranslationPlugin 单例")
            }
        }

    fun aiVisionPipeline(): AiVisionPipeline =
        aiVisionPipeline ?: synchronized(this) {
            aiVisionPipeline ?: AiVisionPipeline(requireContext()).also {
                aiVisionPipeline = it
                Log.d(TAG, "创建 AiVisionPipeline 单例")
            }
        }

    fun baiduTranslator(): BaiduTranslator =
        baiduTranslator ?: synchronized(this) {
            baiduTranslator ?: BaiduTranslator(requireContext()).also {
                baiduTranslator = it
            }
        }

    fun mimoTranslator(): MimoTranslator =
        mimoTranslator ?: synchronized(this) {
            mimoTranslator ?: MimoTranslator(requireContext()).also {
                mimoTranslator = it
            }
        }

    fun bubbleDetector(): BubbleDetector =
        bubbleDetector ?: synchronized(this) {
            bubbleDetector ?: BubbleDetector().also {
                bubbleDetector = it
            }
        }

    fun panelDetector(): PanelDetector =
        panelDetector ?: synchronized(this) {
            panelDetector ?: PanelDetector().also {
                panelDetector = it
            }
        }

    fun sentenceAssembler(): SentenceAssembler =
        sentenceAssembler ?: synchronized(this) {
            sentenceAssembler ?: SentenceAssembler().also {
                sentenceAssembler = it
            }
        }

    fun ocrPlugin(): OcrPlugin =
        ocrPlugin ?: synchronized(this) {
            ocrPlugin ?: OcrPlugin(requireContext()).also {
                ocrPlugin = it
            }
        }

    /**
     * 重置所有单例。仅用于单元测试，生产代码不要调用。
     */
    fun reset() {
        synchronized(this) {
            translationPlugin = null
            aiVisionPipeline = null
            baiduTranslator = null
            mimoTranslator = null
            bubbleDetector = null
            panelDetector = null
            sentenceAssembler = null
            ocrPlugin = null
        }
        Log.d(TAG, "ServiceLocator 已重置")
    }
}

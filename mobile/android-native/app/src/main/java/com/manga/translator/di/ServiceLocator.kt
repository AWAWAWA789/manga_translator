package com.manga.translator.di

import android.content.Context
import android.util.Log
import com.manga.translator.ocr.OcrProcessor
import com.manga.translator.plugin.PluginManager
import com.manga.translator.presentation.TranslationController

/**
 * 手动依赖注入容器。
 *
 * 设计决策（决策 A）：
 * - 不引入 Hilt/Dagger，避免构建时间和注解复杂度
 * - 提供工厂方法创建与 Service 生命周期绑定的实例（PluginManager、OcrProcessor）
 * - 子插件由 PluginManager 内部构造，不在此暴露懒加载单例
 *
 * 生命周期：
 * - [init] 在 MangaTranslatorApp.onCreate 中调用，持有 applicationContext
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
     * 创建 TranslationController 实例（presentation 层）。
     *
     * 内部创建 PluginManager（TranslationRepository 实现）并包装为 Controller。
     * Service 通过 Controller 调用翻译流程，不直接接触 PluginManager。
     *
     * 调用方负责在 Service 销毁时调用 [TranslationController.close]。
     */
    fun createTranslationController(): TranslationController {
        val repository = PluginManager(requireContext())
        return TranslationController(repository)
    }

    /**
     * 创建 OcrProcessor 实例。
     * OcrProcessor 持有 ML Kit TextRecognizer，需在 Service 销毁时调用 [OcrProcessor.close]。
     */
    fun createOcrProcessor(): OcrProcessor = OcrProcessor(requireContext())
}

package com.manga.translator.presentation

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.manga.translator.debug.DebugOverlayData
import com.manga.translator.domain.translation.TranslationRepository
import com.manga.translator.model.TranslationCard
import com.manga.translator.util.ScreenCropConfig

/**
 * 翻译控制器（presentation 层）。
 *
 * 职责：
 * 1. 管理翻译流程的状态（cropConfig、useAiVisionMode、lastDebugData）
 * 2. 通过 [TranslationRepository] 接口调用 data 层执行实际翻译
 * 3. 对外提供与旧 PluginManager 兼容的 API，便于 Service 层平滑迁移
 *
 * 架构定位（决策 A）：
 * - 属于 presentation 层，持有 domain 层接口引用，不直接依赖具体实现
 * - 状态管理在此层，业务逻辑在 data 层（TranslationRepository 实现）
 * - 单元测试可 mock TranslationRepository 验证 Controller 的状态管理逻辑
 *
 * 线程安全：
 * - 跨线程可变状态使用 @Volatile 保证可见性
 * - translateImage 串行化由 Service 层的 serviceScope/Mutex 保证（阶段2 协程迁移后）
 * - 过渡期保留 synchronized 兼容旧调用方
 */
class TranslationController(private val repository: TranslationRepository) {

    companion object {
        private const val TAG = "TranslationController"
    }

    // 跨线程可变状态：@Volatile 保证可见性，对象本身为不可变 data class
    @Volatile
    private var cropConfig = ScreenCropConfig()

    @Volatile
    private var lastDebugData = DebugOverlayData()

    @Volatile
    private var useAiVisionMode = false

    // 翻译串行化锁：过渡期保留，阶段2 协程迁移后替换为 Mutex
    private val translateLock = Any()

    /**
     * 初始化仓储依赖。
     * 幂等，委托给 [repository.initialize]。
     */
    fun initialize() {
        repository.initialize()
        Log.d(TAG, "TranslationController 初始化完成")
    }

    fun setCropConfig(config: ScreenCropConfig) {
        cropConfig = config
    }

    fun setUseAiVisionMode(enabled: Boolean) {
        useAiVisionMode = enabled
        Log.d(TAG, "AI多模态识别: ${if (enabled) "启用" else "禁用"}")
    }

    fun isAiVisionModeEnabled(): Boolean = useAiVisionMode

    fun getLastDebugData(): DebugOverlayData = lastDebugData

    /**
     * 翻译图片入口。
     *
     * Bitmap 生命周期契约：
     * - 此方法不回收传入的 bitmap，调用方负责
     * - 内部裁剪产生的 Bitmap 由 repository 实现（PluginManager）负责回收
     *
     * @param bitmap 待翻译的截图，调用方负责 recycle
     * @param lastTranslationRects 上一帧翻译区域，用于去重过滤
     * @param verticalOnly 是否强制竖向识别模式
     * @param isManual 是否为手动翻译（影响 AI 路径选择）
     * @return 翻译卡片列表
     */
    fun translateImage(
        bitmap: Bitmap,
        lastTranslationRects: List<Rect> = emptyList(),
        verticalOnly: Boolean = false,
        isManual: Boolean = false,
    ): List<TranslationCard> = synchronized(translateLock) {
        val params = TranslationRepository.TranslateParams(
            bitmap = bitmap,
            cropConfig = cropConfig,
            useAiVisionMode = useAiVisionMode,
            lastTranslationRects = lastTranslationRects,
            verticalOnly = verticalOnly,
            isManual = isManual,
        )
        val result = repository.translate(params)
        lastDebugData = result.debugData
        result.cards
    }

    /**
     * 释放资源，委托给 [repository.close]。
     */
    fun close() {
        repository.close()
        Log.d(TAG, "TranslationController 已关闭")
    }
}

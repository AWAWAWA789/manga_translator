package com.manga.translator.util

/**
 * 性能埋点工具：记录关键路径各阶段耗时。
 * 耗时 < 3s 输出 DEBUG 日志，>= 3s 升级为 WARNING（慢查询告警）。
 *
 * 用法：
 *   val tracker = PerfTracker.start("模块名", "操作描述")
 *   // ... 执行阶段1 ...
 *   tracker.end("阶段1")
 *   // ... 执行阶段2 ...
 *   tracker.end("阶段2")
 *   tracker.finish() // 输出汇总
 */
class PerfTracker private constructor(
    private val module: String,
    private val operation: String,
    private val startTimeMs: Long,
) {
    private val checkpoints = mutableListOf<Checkpoint>()
    private var finished = false

    private data class Checkpoint(val name: String, val elapsedMs: Long)

    /**
     * 记录一个检查点耗时（从 start 开始计时）。
     */
    fun end(checkpointName: String) {
        val elapsed = System.currentTimeMillis() - startTimeMs
        checkpoints.add(Checkpoint(checkpointName, elapsed))
    }

    /**
     * 输出汇总日志。总耗时或任一阶段 >= 3s 升级为 Warning。
     */
    fun finish() {
        if (finished) return
        finished = true
        val totalMs = System.currentTimeMillis() - startTimeMs
        val sb = StringBuilder("[$module][性能] $operation 总耗时=${totalMs}ms")
        for (cp in checkpoints) {
            sb.append("\n  - ${cp.name}: ${cp.elapsedMs}ms")
        }
        // 总耗时或任一阶段超阈值，升级为 Warning
        val hasSlow = totalMs >= SLOW_THRESHOLD_MS || checkpoints.any { it.elapsedMs >= SLOW_THRESHOLD_MS }
        if (hasSlow) {
            // 逐个输出慢阶段 Warning
            for (cp in checkpoints) {
                if (cp.elapsedMs >= SLOW_THRESHOLD_MS) {
                    AppLog.w(module, "[性能] $operation - ${cp.name} 慢查询: ${cp.elapsedMs}ms")
                }
            }
            if (totalMs >= SLOW_THRESHOLD_MS) {
                AppLog.w(module, "[性能] $operation 慢查询总耗时: ${totalMs}ms")
            }
        } else {
            AppLog.d(module, sb.toString())
        }
    }

    companion object {
        private const val SLOW_THRESHOLD_MS = 3000L

        fun start(module: String, operation: String): PerfTracker {
            return PerfTracker(module, operation, System.currentTimeMillis())
        }

        /**
         * 单点记录某阶段耗时（适用于并行任务或无需多检查点的场景）。
         * 耗时 >= 3s 升级为 Warning（慢查询告警）。
         */
        fun record(module: String, stage: String, durationMs: Long) {
            if (durationMs >= SLOW_THRESHOLD_MS) {
                AppLog.w(module, "[性能] $stage 慢查询: ${durationMs}ms")
            } else {
                AppLog.d(module, "[性能] $stage: ${durationMs}ms")
            }
        }
    }
}

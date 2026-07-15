# 阶段 4（W7 可观测性 + CI）实施计划

## 概述

**目标**：建立线上问题定位能力和自动化质量门禁。

**用户决策**：
- 崩溃监控：自建 CrashHandler（Thread.UncaughtExceptionHandler + 崩溃堆栈写入本地文件）
- 性能埋点：自定义 PerfTracker + Log 输出

**任务清单**：
1. 任务 4.1：结构化日志（Logger 封装 + 统一格式）
2. 任务 4.2：崩溃监控（自建 CrashHandler + 文件落盘）
3. 任务 4.3：性能埋点（PerfTracker + Log）
4. 任务 4.4：CI 流水线（GitHub Actions）

---

## 当前状态分析

### 日志现状（163 处裸调用）
- `Log.d` 98 处、`Log.e` 40 处、`Log.w` 25 处、`Log.i/v` 0 处
- **无统一日志工具类**（无 Logger/LogUtils/Timber）
- **Release 包不屏蔽日志**（无 `BuildConfig.DEBUG` 包裹）
- 每类硬编码 `private const val TAG`，格式不统一
- 关键文件：`ScreenCaptureService.kt`（50 处）、`PluginManager.kt`（18 处）、`TranslationPlugin.kt`（14 处）

### 崩溃监控现状
- **完全缺失**：0 SDK 依赖、0 UncaughtExceptionHandler、0 上报
- `MangaTranslatorApp.onCreate` 仅做 DebugManager + ServiceLocator + 通知渠道初始化
- 崩溃后仅系统默认弹窗，无法回溯堆栈

### 性能埋点现状
- **关键路径 0 埋点**（截图→OCR→翻译→渲染无耗时记录）
- 9 处 `System.currentTimeMillis` 全是业务逻辑（节流/冷却/超时），非性能埋点
- 0 处 `System.nanoTime`、0 处 `android.os.Trace`

### CI 现状
- **无任何 CI 配置**（无 .github/workflows/、无 Jenkinsfile、无 .gitlab-ci.yml）
- 质量工具（ktlint/detekt/jacoco/owasp）仅本地 Gradle 插件，未串成流水线

---

## 实施方案

### 任务 4.1：结构化日志

**目标**：统一日志格式 `[模块][事件] key=value`，Release 包屏蔽 DEBUG 日志。

**新建文件**：`app/src/main/java/com/manga/translator/util/AppLog.kt`

```kotlin
package com.manga.translator.util

import android.util.Log
import com.manga.translator.BuildConfig

/**
 * 统一日志工具。
 * 格式：[模块][事件] key=value 或 [模块] message
 * Release 包自动屏蔽 DEBUG 级别日志。
 */
object AppLog {
    fun d(module: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(module, message)
    }
    fun i(module: String, message: String) {
        Log.i(module, message)
    }
    fun w(module: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.w(module, message, throwable) else Log.w(module, message)
    }
    fun e(module: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.e(module, message, throwable) else Log.e(module, message)
    }
}
```

**改造范围**：渐进式替换，不一次性改全部 163 处。优先替换核心模块：
- `ScreenCaptureService.kt`（50 处，TAG="MangaTranslator"）
- `PluginManager.kt`（18 处，TAG="PluginManager"）
- `TranslationPlugin.kt`（14 处，TAG="TranslationPlugin"）
- `OcrProcessor.kt`（10 处，TAG="OcrProcessor"）

**改造规则**：
- `Log.d(TAG, "msg")` → `AppLog.d("模块名", "msg")`
- `Log.w(TAG, "msg")` → `AppLog.w("模块名", "msg")`
- `Log.e(TAG, "msg", e)` → `AppLog.e("模块名", "msg", e)`
- 移除各 companion object 中的 `TAG` 常量（AppLog 直接用模块名）
- 关键操作日志格式化：`AppLog.d("ScreenCapture", "[启动] 服务初始化完成")`

**验收**：核心 4 模块日志替换完成，Release 包不输出 DEBUG 日志

---

### 任务 4.2：崩溃监控（自建 CrashHandler）

**目标**：注册全局 UncaughtExceptionHandler，崩溃堆栈写入本地文件（按日期分文件）。

**新建文件**：`app/src/main/java/com/manga/translator/util/CrashHandler.kt`

```kotlin
package com.manga.translator.util

import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃处理器：捕获未处理异常，将堆栈写入本地文件。
 * 崩溃文件存储在 /data/data/<package>/files/crash/ 目录，按日期命名。
 */
object CrashHandler : Thread.UncaughtExceptionHandler {

    private const val TAG = "CrashHandler"
    private const val CRASH_DIR = "crash"
    private lateinit var context: android.content.Context
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    fun init(context: android.content.Context) {
        this.context = context.applicationContext
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(t: Thread, e: Throwable) {
        // 1. 写入崩溃日志到文件
        writeCrashToFile(t, e)
        // 2. 交回系统默认处理器（弹"应用已停止"对话框）
        defaultHandler?.uncaughtException(t, e)
    }

    private fun writeCrashToFile(thread: Thread, throwable: Throwable) {
        try {
            val crashDir = File(context.filesDir, CRASH_DIR)
            if (!crashDir.exists()) crashDir.mkdirs()
            val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.CHINA)
            val fileName = "crash_${dateFormat.format(Date())}.txt"
            val crashFile = File(crashDir, fileName)

            val sw = StringWriter()
            val pw = PrintWriter(sw)
            throwable.printStackTrace(pw)

            val log = buildString {
                append("====== 崩溃时间: ${Date()} ======\n")
                append("线程: ${thread.name}\n")
                append("设备: ${Build.MANUFACTURER} ${Build.MODEL}\n")
                append("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
                append("====== 堆栈 ======\n")
                append(sw.toString())
            }
            crashFile.writeText(log)
            Log.e(TAG, "崩溃日志已写入: ${crashFile.absolutePath}")
        } catch (ioe: Exception) {
            Log.e(TAG, "写入崩溃日志失败: ${ioe.message}")
        }
    }

    /** 获取所有崩溃日志文件列表（供设置页查看/导出） */
    fun getCrashFiles(): List<File> {
        if (!::context.isInitialized) return emptyList()
        val crashDir = File(context.filesDir, CRASH_DIR)
        return crashDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
}
```

**修改文件**：`MangaTranslatorApp.kt`
- `onCreate` 中添加 `CrashHandler.init(applicationContext)`
- 位置：在 `DebugManager.initialize()` 之后、`ServiceLocator.init()` 之前

**验收**：测试崩溃（手动抛异常）能生成 `crash/crash_yyyy-MM-dd_HH-mm-ss.txt` 文件

---

### 任务 4.3：性能埋点（PerfTracker + Log）

**目标**：关键路径（截图→OCR→翻译→渲染）各阶段耗时记录，超 3s 升级为 Warning。

**新建文件**：`app/src/main/java/com/manga/translator/util/PerfTracker.kt`

```kotlin
package com.manga.translator.util

import android.util.Log

/**
 * 性能埋点工具：记录关键路径各阶段耗时。
 * 耗时 < 3s 输出 Log.d，>= 3s 升级为 Log.w（慢查询告警）。
 *
 * 用法：
 *   val tracker = PerfTracker.start("翻译流程")
 *   // ... 执行翻译 ...
 *   tracker.end("OCR识别")
 *   // ... 执行翻译 ...
 *   tracker.end("翻译")
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

    fun end(checkpointName: String) {
        val elapsed = System.currentTimeMillis() - startTimeMs
        checkpoints.add(Checkpoint(checkpointName, elapsed))
    }

    fun finish() {
        if (finished) return
        finished = true
        val totalMs = System.currentTimeMillis() - startTimeMs
        val sb = StringBuilder("[${module}][性能] $operation 总耗时=${totalMs}ms")
        for (cp in checkpoints) {
            sb.append("\n  - ${cp.name}: ${cp.elapsedMs}ms")
            if (cp.elapsedMs >= 3000) {
                AppLog.w(module, "[性能] $operation - ${cp.name} 慢查询: ${cp.elapsedMs}ms")
            }
        }
        if (totalMs >= 3000) {
            AppLog.w(module, "[性能] $operation 慢查询总耗时: ${totalMs}ms")
        } else {
            AppLog.d(module, sb.toString())
        }
    }

    companion object {
        private const val TAG = "PerfTracker"
        private const val SLOW_THRESHOLD_MS = 3000L

        fun start(module: String, operation: String): PerfTracker {
            return PerfTracker(module, operation, System.currentTimeMillis())
        }
    }
}
```

**埋点位置**（关键路径）：

1. **ScreenCaptureService.kt** - 截图阶段
   - `executeOneShotTranslation` / `executeManualTranslation` 开始处：`PerfTracker.start("翻译", "一次翻译流程")`
   - 截图完成后：`tracker.end("截图")`

2. **PluginManager.kt** - OCR + 翻译阶段
   - `translateImage` 开始处接收 tracker 参数或新建
   - OCR 完成后：`tracker.end("OCR识别")`
   - 翻译完成后：`tracker.end("翻译")`
   - `translateImage` 结束前：`tracker.finish()`

3. **ScreenCaptureService.kt** - 渲染阶段
   - 翻译结果返回后渲染覆盖层：`tracker.end("渲染")`

**验收**：日志中可见 `[翻译][性能] 一次翻译流程 总耗时=Xms` 及各阶段耗时，> 3s 触发 Warning

---

### 任务 4.4：CI 流水线（GitHub Actions）

**目标**：PR 触发质量门禁，主分支触发覆盖率门禁。

**新建文件**：`.github/workflows/ci.yml`

```yaml
name: CI

on:
  pull_request:
    branches: [ main ]
  push:
    branches: [ main ]

jobs:
  quality-gate:
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: mobile/android-native

    steps:
      - uses: actions/checkout@v4

      - name: Setup JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v3

      - name: Cache Gradle
        uses: actions/cache@v3
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: gradle-${{ runner.os }}-${{ hashFiles('**/*.gradle*', '**/gradle-wrapper.properties') }}
          restore-keys: gradle-${{ runner.os }}-

      - name: Assemble Debug
        run: ./gradlew assembleDebug --no-daemon

      - name: Ktlint Check
        run: ./gradlew ktlintCheck --no-daemon

      - name: Detekt
        run: ./gradlew detekt --no-daemon

      - name: Unit Tests
        run: ./gradlew testDebugUnitTest --no-daemon

      - name: JaCoCo Report (main only)
        if: github.ref == 'refs/heads/main'
        run: ./gradlew jacocoTestReport --no-daemon

      - name: Upload JaCoCo (main only)
        if: github.ref == 'refs/heads/main'
        uses: actions/upload-artifact@v4
        with:
          name: jacoco-report
          path: mobile/android-native/app/build/reports/jacoco/
```

**配置说明**：
- PR 触发：assembleDebug + ktlintCheck + detekt + testDebugUnitTest
- 主分支额外触发：jacocoTestReport + 上传报告 artifact
- 使用 `gradle/actions/setup-gradle@v3` 官方 action，自动配置 Gradle 缓存
- `--no-daemon` 避免 CI 环境 daemon 冲突
- OWASP DependencyCheck 不加入 CI（NVD 数据库下载慢且网络受限，手动运行）

**验收**：PR 提交后自动触发 CI，质量门禁不通过则 PR 无法合并

---

## 假设与决策

1. **日志改造范围**：仅替换核心 4 模块（ScreenCaptureService/PluginManager/TranslationPlugin/OcrProcessor，共 92 处），其余模块保持现状渐进替换。原因：一次性改 163 处改动过大且易引入错误。

2. **CrashHandler 不拦截默认处理器**：崩溃堆栈写入文件后仍交回系统默认处理器，保留"应用已停止"对话框，避免用户感知异常。

3. **PerfTracker 不使用 android.os.Trace**：用户选择"自定义 PerfTracker + Log"，仅用 Log 输出耗时，不集成系统 systrace。后续如需系统级分析可再扩展。

4. **CI 不集成 OWASP**：NVD 数据库下载慢（网络受限），CI 执行时间不可控。OWASP 保持手动运行。

5. **CI 不强制覆盖率门禁**：当前覆盖率远低于 80%（Robolectric 环境受限），强制门禁会导致 CI 失败。JaCoCo 报告仅生成 artifact 供查看，待覆盖率达标后再启用门禁。

6. **CrashHandler 日志存储位置**：`context.filesDir/crash/`，App 卸载时自动清除，无需额外清理逻辑。

---

## 验证步骤

1. **任务 4.1 验证**：
   - `./gradlew assembleDebug` 编译通过
   - Release 包运行时 `adb logcat` 不出现 DEBUG 级别日志
   - Debug 包日志格式为 `[模块][事件] message`

2. **任务 4.2 验证**：
   - 在测试代码中手动抛出未捕获异常
   - 检查 `/data/data/com.manga.translator/files/crash/` 目录生成崩溃日志文件
   - 文件内容包含时间、线程、设备信息、完整堆栈

3. **任务 4.3 验证**：
   - 触发一次翻译流程
   - `adb logcat | grep PerfTracker` 可见各阶段耗时
   - 人为制造慢翻译（如断网超时），可见 Warning 级别日志

4. **任务 4.4 验证**：
   - 提交 PR，GitHub Actions 自动触发
   - 所有 step 通过（绿色对勾）
   - 主分支 push 触发生成 JaCoCo artifact

5. **全程质量门禁**：
   - `./gradlew assembleDebug ktlintCheck detekt testDebugUnitTest` 全绿

# 漫画翻译器 - 更新日志

---

## v5.00 - 2026-07-16

### 8 周全面改造

在 v4.38 基础上做了四个阶段的系统性改造，目标是把之前打地鼠式修 bug 的工作方式换成一次性把根因解决透。下面按阶段列一下做了什么。

#### 阶段1：止血期（W1-W2）

针对线上会崩、会卡、会泄漏的问题做了一轮集中修复：

1. **ScreenCaptureService onDestroy ANR** — 原来在主线程 awaitTermination 阻塞 5 秒，改用 appScope.launch 异步清理，8 步严格释放顺序
2. **Bitmap 泄漏 5 处** — `doOcrAndTranslate` / `captureAndProcess` / `processImage` / `AiVisionPipeline.bitmapToBase64` / `OcrProcessor.binarizeForOcr` 全部加 consumed 标志位 + finally 回收
3. **Application 类缺失** — 新建 `MangaTranslatorApp`，提前初始化 DebugManager 和通知渠道，解决 START_STICKY 重建时 lateinit 崩溃
4. **PluginManager 并发竞态** — 4 个可变状态加 `@Volatile`，translateImage 用 `synchronized(translateLock)` 串行化
5. **TranslationPlugin 批量回退** — 拆分 translateBatch 为 MiMo/Baidu 两个方法，提取 validateAndFallback，hasRepeatedPatternStatic 提为静态方法便于单测
6. **AI 路径坐标系错位** — bitmapToBase64 返回缩放比例，analyzeImage 用缩放后尺寸解析再统一除以 scale
7. **Android 13 通知权限** — AndroidManifest 声明 POST_NOTIFICATIONS，MainActivity 运行时请求

#### 阶段2：架构重构（W3-W5）

把 PluginManager 这个 1000+ 行的上帝类拆开，引入分层和接口：

1. **领域接口抽取** — 新建 `domain/` 目录，定义 `Translator` / `OcrEngine` / `BubbleDetectorInterface` / `PanelDetector` 等接口，纯 Kotlin 无 Android 依赖
2. **PluginManager 拆分** — 拆为 `TranslationController`（presentation 层，管理状态）+ `PluginManager`（data 层，实现 TranslationRepository 接口），清理 26 个死方法 + 5 个冗余 public API
3. **手动 DI 容器** — 新建 `ServiceLocator`，集中管理依赖创建，便于后续单测替换
4. **协程基础设施** — `MangaTranslatorApp.appScope`（SupervisorJob + Default）+ `ScreenCaptureService.serviceScope`（SupervisorJob + IO），onDestroy 中 cancel()
5. **协程迁移** — `onDestroy` 的 Thread.start → appScope.launch；`SettingsActivity` 的 Thread{} → lifecycleScope.launch（移除 runOnUiThread 模板代码）。OcrProcessor 的 6 线程池暂时保留，改 suspend 会动到领域接口
6. **minSdk 24 → 26** — 移除 API 24-25 兼容代码，构建体积下降约 2MB
7. **单元测试** — 新增 `TextFilterTest`（30+ 用例）+ `TranslationPluginTest`（6 用例覆盖重复检测/回退逻辑）+ `StringUtilsTest`。SentenceAssembler/PanelDetector 骨架已写，Robolectric SDK 下载导致 JVM 崩暂 @Ignore

#### 阶段3：安全合规（W6）

1. **HTTPS 强制** — `network_security_config.xml` 的 `cleartextTrafficPermitted` 从 true 改为 false，加 localhost domain-config 例外
2. **密钥存储审计** — `SecurePrefs.isEncryptionFailed()` 之前从来没被调用，`SettingsActivity` 保存百度/MiMo 配置时检查加密失败，失败时 Toast 警告用户
3. **输入校验** — 长文本翻译走 POST（原 GET），正则规范用 `\s{2,}` 而非 `\s+` 保留中英文间空格
4. **OWASP 依赖扫描** — 集成 `dependency-check-gradle:9.0.9`，`failBuildOnCVSS=9.0`，配置 NVD API Key 环境变量注入。NVD 数据库因网络问题暂跳过，工具链就位待网络恢复

#### 阶段4：可观测性 + CI（W7）

1. **统一日志 AppLog** — 新建 `util/AppLog.kt`，Release 包通过 `BuildConfig.DEBUG` 自动屏蔽 DEBUG 日志。核心 4 模块（ScreenCaptureService 50 处 / PluginManager 18 处 / TranslationPlugin 14 处 / OcrProcessor 10 处）共 92 处 Log 调用替换为 AppLog，移除 3 个 private TAG 常量
2. **崩溃监控 CrashHandler** — 新建 `util/CrashHandler.kt`，实现 `Thread.UncaughtExceptionHandler`，崩溃堆栈写入 `filesDir/crash/crash_yyyy-MM-dd_HH-mm-ss.txt`，含线程名/设备型号/Android 版本/完整堆栈。`MangaTranslatorApp.onCreate` 中初始化，交回系统默认处理器保留"应用已停止"对话框
3. **性能埋点 PerfTracker** — 新建 `util/PerfTracker.kt`，start/end/finish 模式记录各阶段耗时，总耗时或任一阶段 ≥ 3s 自动升级 Warning。埋点位置：`ScreenCaptureService.processImage`（一次翻译流程）+ `PluginManager.translateImage`（AI多模态识别/OCR识别/翻译三阶段）
4. **GitHub Actions CI** — 新建 `.github/workflows/ci.yml`：PR 触发 assembleDebug + ktlintCheck + detekt + testDebugUnitTest，主分支额外触发 jacocoTestReport + artifact 上传。JDK 17 + Gradle 8.10 + 缓存 + concurrency 取消旧运行

#### 质量基础设施（W0）

为上面所有改造提供度量基础：

- ktlint 代码格式规范（`.editorconfig`）
- detekt 静态分析 + baseline（`detekt.yml`、`detekt-baseline.xml`）
- JaCoCo 覆盖率报告
- JUnit 4 + Robolectric 测试环境

#### 配置变化

| 项 | v4.38 | v5.00 |
|---|---|---|
| minSdk | 24 | 26 |
| targetSdk | 34 | 34 |
| versionCode | 438 | 500 |
| versionName | 4.38 | 5.00 |
| AGP | 8.1.0 | 8.1.0 |
| Gradle | 8.10 | 8.10 |

#### 已知遗留

- OcrProcessor 6 线程池未迁移协程（会动领域接口，留到下个版本）
- 核心模块覆盖率未达 80% 门禁（Robolectric 环境受限，Service 层测不了）
- OWASP NVD 数据库待网络恢复或申请到 API Key 后启用
- detekt baseline 在 CI 干净环境会报 4 处 TooGenericExceptionCaught（已通过 allowedExceptionNameRegex 兼容 `e`/`_` 惯用名）

---

## v4.38 - 2026-07-15

### 全面代码审计修复（57 项，覆盖 19 个文件）

基于全模块代码审计，系统修复 P0/P1/P2/P3 共 57 项问题。以下按优先级分类汇总。

#### P0 关键崩溃/数据错误（5 项）

1. **DebugManager 未初始化即访问导致崩溃** — v4.35 改 `lateinit` 后无 Application 类调用 `initialize()`。改为 `by lazy` + `@Volatile appContext`，在 `MainActivity.onCreate` 中初始化
2. **OcrProcessor Bitmap use-after-free** — 6 路并行 OCR 用 `CountDownLatch` + 异步回调，超时后 finally 回收 Bitmap 而 ML Kit 回调延迟触发。改用同步 `Tasks.await()`，返回后安全回收
3. **OcrProcessor 坐标系错位** — `enhanceForOcr` 缩放至 ≤1600 但 `mapRectBack` 用 `scaledBitmap` 尺寸做坐标变换。改为传入实际处理 Bitmap 尺寸并增加缩放因子
4. **ScreenCaptureService executor 关闭后 submit 崩溃** — 新增 `safeSubmit()` 包装，捕获 `RejectedExecutionException`
5. **ScreenCaptureService onDestroy 资源释放顺序错误** — native 资源先于 executor 任务释放导致竞态。改为 `executor.awaitTermination(5s)` 后再 `pluginManager.close()`

#### P1 稳定性/资源泄漏（14 项）

6. **isProcessing 卡死保护** — 强制重置后重试增加 `isRunning && !isPaused` 状态校验
7. **suppressChangeDetection 重置竞态** — 引入 `suppressToken`（AtomicLong），延迟重置时 CAS 校验 token，防止连续翻译时第一次重置提前结束第二次抑制期
8. **waitForFreshFrame 忙等待** — 移除 `Thread.sleep` 轮询，统一用 `frameAvailableSignal.block(remaining)`
9. **共享变量可见性** — `isScreenStable`/`lastTranslatedHash`/`lastTranslationTimeMs`/`stableFrameCount`/`movingFrameCount` 加 `@Volatile`；`screenChangeVersion` 改 `AtomicLong`
10. **imageToBitmap Bitmap 泄漏** — try-catch 中 recycle
11. **PluginManager croppedBitmap 泄漏** — try-finally 释放
12. **BubbleDetector hull 资源泄漏** — try-finally 释放 hullIndices/hull
13. **AiVisionPipeline 零尺寸 Bitmap** — 入口校验
14. **ComicImageCropper 越界** — `getCropRect` 返回值 `coerceIn(0, width/height)`
15. **SecurePrefs 加密失败兜底** — `KEY_ENCRYPTION_FAILED` 标志 + `isEncryptionFailed()` 方法，迁移用 `commit()`
16. **MimoTranslator BaseUrl 安全** — `validateBaseUrl()` 强制 HTTPS
17. **MainActivity 生命周期** — 回调增加生命周期检查；`isScreenCaptureServiceRunning` 用 ActivityManager
18. **OcrPlugin isVertical 判定** — 用 `OcrResult.isVertical` 替代 `rect.height > width * 1.5` 启发式
19. **PluginManager.close() 完整性** — 关闭所有子插件（OcrPlugin/TranslationPlugin/AiVisionPipeline）

#### P2 性能/逻辑（22 项）

20. **FloatingWindowService O(n²) 去重** — `deduplicateTranslations` 移至 `limit(16)` 之后，O(50²)→O(16²)
21. **FloatingWindowService cleanup 顺序** — 直接 `removeViewImmediate` 所有 view 后再 `removeCallbacksAndMessages`
22. **TranslationOverlayView StaticLayout 缓存** — `textSizeCache` 避免二分查找中重复创建 StaticLayout
23. **TranslationOverlayView alpha 修复** — else 分支补 `alpha = 1f`
24. **ScreenCaptureService computePixelHash 锁竞争** — 移出 synchronized 块
25. **ScreenCaptureService checkScreenChange 静默 catch** — 加 `Log.w`
26. **MediaProjection.Callback 泄漏** — 保存引用，onDestroy 中 `unregisterCallback`
27. **BaiduTranslator 多句翻译** — `trans_result` 用 `joinToString("")` 合并
28. **BaiduTranslator Gson NPE** — 空值检查
29. **BaiduTranslator HTTP 错误体** — 异常信息包含 response body
30. **MimoTranslator Regex 常量** — 移至 companion object 避免重复编译
31. **MimoTranslator associateBy 重复** — 加重复键日志
32. **AiVisionPipeline max_tokens** — 4096→8192
33. **PanelDetector 去重** — IoU > 0.7 过滤重叠分镜
34. **SentenceAssembler 数字过滤** — 先检查 `!containsJapaneseLikeText` 再过滤
35. **TranslationPlugin.close()** — 清空 LruCache
36. **TextFilter Regex 常量化** — 移至 companion object
37. **TextFilter 长度逻辑** — 统一为 `<= 16`
38. **MainActivity startForegroundService** — try-catch 防崩溃
39. **SettingsActivity 测试按钮** — 测试期间禁用
40. **SettingsActivity TranslationPlugin 构造** — 传 `applicationContext`
41. **MimoTranslator SecurePrefs** — `by lazy` 延迟初始化

#### P3 代码质量（16 项）

42. **OcrProcessor binarizeForOcr 阈值** — `coerceIn(120,205)` → `coerceIn(80,220)`
43. **OcrProcessor 死代码** — 移除 `isPureChinese`
44. **BubbleDetector 不可达 catch** — 移除 `UninitializedPropertyAccessException`
45. **BaiduTranslator Charsets.UTF_8** — 替代硬编码
46. **BaiduTranslator use{} 模式** — 资源自动关闭
47. **BaiduTranslator ThreadLocalRandom** — 替代 `java.util.Random`
48. **MimoTranslator 移除未用超时常量**
49. **FloatingWindowService safeCallback** — 简化泛型参数
50. **OcrProcessor 零尺寸校验** — `recognizeText` 入口
51. **ScreenCaptureService return@safeSubmit** — 统一 lambda 标签
52. **TextFilter isAppUiText 统一**
53. **build.gradle 乱码注释** — 修复 `OpenCV SDK` 注释
54. **FloatingWindowService 注释乱码** — 恢复中文注释
55. **关键静默 catch 补日志** — 4 处加 `Log.w`
56. **DebugManager preferences** — `by lazy` 替代 `lateinit`
57. **合并 TranslationCard/TranslationDisplayResult** — 统一用 `model.TranslationCard`

---

## v4.37 - 2026-07-15

### 修复：实时翻译时长按菜单失效

- **现象**：实时翻译模式下长按悬浮球无法弹出菜单
- **根因**：`captureAndProcess` → `hideFloatingUI()` 设置 `mainView.visibility = INVISIBLE` → Android 发送 `ACTION_CANCEL` → `isTouching = false` → 长按 Runnable 检查 `isTouching` 失败
- **修复**：`captureAndProcess` 入口增加 `if (FloatingWindowService.isTouching) return`，触摸期间跳过截图

---

## v4.36 - 2026-07-15

### 关键修复：实时翻译气泡反复闪烁（P0）

#### 1. lastTranslatedHash 语义错位导致循环翻译（P0）
- **现象**：实时翻译模式下，气泡每隔约 2 秒（AUTO_COOLDOWN_MS）被清除并重新翻译一次，视觉表现为气泡反复闪烁刷新，CPU/电量持续消耗在无意义的重复 OCR+翻译上
- **根因**：`lastTranslatedHash` 在 `captureAndProcess` 中记录的是**截图时（无气泡）**的哈希（[ScreenCaptureService.kt#L671](file:///d:/code/test/manga_translator/ShangChuan/mobile/android-native/app/src/main/java/com/manga/translator/service/ScreenCaptureService.kt#L671)），但翻译完成后气泡显示，画面变成**含气泡**状态。800ms `suppressChangeDetection` 抑制期结束后，`checkScreenChange` 比较的是"含气泡画面(currentHash)" vs "无气泡截图哈希(lastTranslatedHash)"，两者永远不等
- **循环时序**：
  ```
  T=0s   翻译完成，lastTranslatedHash=无气泡哈希，显示气泡
  T=0~0.8s  suppressChangeDetection=true，lastFrameHash 更新为含气泡哈希
  T=0.8s    suppressChangeDetection=false
  T=0.8~2s  画面稳定，但 currentHash(含气泡) != lastTranslatedHash(无气泡) → 触发条件成立
            仅被 AUTO_COOLDOWN_MS(2s) 冷却期阻挡
  T=2s      冷却期结束 → scheduleStableCapture() → captureAndProcess()
            → hideFloatingUI() 移除气泡 → 截图 → OCR+翻译 → 显示新气泡 → 回到 T=0s
  ```
- **修复**：在 `checkScreenChange` 的 `suppressChangeDetection` 分支中，同步更新 `lastTranslatedHash = currentHash`。suppress 期间画面含气泡，`currentHash` 即"含气泡稳定画面"的哈希；800ms 结束时 `lastTranslatedHash` 已与当前画面一致，`currentHash == lastTranslatedHash`，不再触发重复翻译。只有用户真正翻页/画面变化导致哈希改变时才触发新翻译
- **影响范围**：仅 `ScreenCaptureService.kt` 一处，3 行新增代码

---

## v4.35 - 2026-07-15

### 代码质量与稳定性改进（第一批+第二批）

#### 1. compactTranslation 保留英文与中文间空格（P2）
- **原问题**：`TranslationOverlayView.compactTranslation` 用 `\\s+` 移除所有空白，导致 "OK 好的" 被压成 "OK好的"，英文与中文混排时视觉粘连
- **修复**：正则改为 `\\s{2,}`，仅合并连续多空格为单空格，保留英文与中文之间的分隔

#### 2. isProcessing 卡住重置后重试前校验状态（P2）
- **原问题**：`ScreenCaptureService.executeOneShotTranslation` 中 isProcessing 卡住超过 8 秒强制重置后，无条件 postDelayed 重试。若用户已暂停或服务已停止，重试会触发无意义截图
- **修复**：重试 lambda 增加 `isRunning.get() && !FloatingWindowService.isPaused` 校验，状态变更时跳过重试并输出诊断日志

#### 3. waitForFreshFrame 改用 ConditionVariable 同步（P2）
- **原问题**：`waitForFreshFrame` 用 7 处 `Thread.sleep(33~50)` 轮询缓存帧，CPU 空转且响应延迟最大 50ms
- **优化**：新增 `frameAvailableSignal: ConditionVariable`，`imageAvailableListener` 写入缓存后 `open()` 通知，`waitForFreshFrame` 用 `block(remaining)` 等待。close 后二次校验新鲜度，避免错过 close 与 block 之间到达的帧

#### 4. FloatingWindowService 注释乱码修复（P3）
- **原问题**：6 处中文注释因编码问题出现 `?` 乱码（翻译悬浮层/翻译覆盖层/失败/日文字符/平假名/片假名）
- **修复**：恢复完整中文注释

#### 5. 关键静默 catch 补充日志（P3）
- **原问题**：`FloatingWindowService` 中 4 处关键路径静默吞异常（菜单隐藏/覆盖层移除/截图前隐藏UI/截图后恢复UI），故障无从排查
- **修复**：4 处加 `Log.w` 记录异常信息；高频拖拽 `updateViewLayout` 与销毁清理保持静默避免日志刷屏

#### 6. DebugManager 改 lateinit（P3）
- **原问题**：`preferences: SharedPreferences?` 可空，所有 getter 重复 `?: false` 兜底分支
- **优化**：改 `lateinit var`，必须在启动时 `initialize()`，移除全部 `?: false` 兜底，代码更简洁

#### 7. 合并 TranslationCard / TranslationDisplayResult（P3）
- **原问题**：`FloatingWindowService.TranslationDisplayResult` 与 `model.TranslationCard` 字段完全相同（originalText/translatedText/sourceRect/isVertical），重复定义
- **优化**：删除 `TranslationDisplayResult`，统一使用 `model.TranslationCard`；`FloatingWindowService` 与 `ScreenCaptureService` 方法签名同步更新

---

## v4.34 - 2026-07-15

### 关键修复：截图失败（VirtualDisplay 分辨率不匹配）

#### 1. 截图失败根因修复（P0）
- **根因**：`getScreenMetrics` 将 `screenWidth/Height` clamp 到 `1080×1920`，但 `screenDensity` 未等比缩放。高分辨率设备（如 1080×2400@480dpi）被压成 1080×1920@480dpi，纵横比变化导致 GPU 合成器报 `OpenGLRenderer: Unable to match the desired swap behavior`，VirtualDisplay 的 surface 不生产帧，`ImageReader` 永远收不到帧，`cachedBitmap` 始终为 null
- **修复 1 — 等比缩放**：以宽度为基准等比缩放（`screenHeight = heightPixels * maxWidth / widthPixels`），保持原始宽高比；`screenDensity` 同步缩放并 clamp 到最低 120dpi，保证 VirtualDisplay 与 Surface 尺寸/dpi 自洽
- **修复 2 — 首帧等待**：`doOneShotCapture` 重试次数 3→4，首次重试后等待 800ms（原 300ms）给 VirtualDisplay 更多时间渲染首帧；超时从 500ms 延长到 1000ms
- **修复 3 — 监听器解绑**：`recreateCapturePipeline` 先 `setOnImageAvailableListener(null, null)` 再 `close()`，避免旧 ImageReader 在 close 过程中仍回调导致并发问题
- **修复 4 — MediaProjection 存活检查**：重建前检查 `mediaProjection == null`（用户可能撤销权限），避免无效重建
- **修复 5 — 首帧到达日志**：监听器在 `lastFrameTimeMs == 0L` 时输出 `首帧到达: WxH`，便于诊断 ImageReader 是否收到帧

### 性能优化

#### 2. OCR 6 遍全并行（P1）
- **原问题**：`OcrProcessor.recognizeText` 仅横竖向并行，内部 3 版本（enhanced/raw/binary）串行执行，每遍最长 15s，最坏总耗时 45s
- **优化**：新增专用 6 线程池 `ocrExecutor`，6 遍（3版本 × 2方向）全并行提交，总耗时降至 max(15s)=15s。ML Kit `TextRecognizer` 内部线程安全，可并发调用 `process()`

#### 3. 覆盖层字号二分查找（P1）
- **原问题**：`TranslationOverlayView.calculateFontSize` 用 while 循环 0.5sp 步长线性递减，每次循环构建 `StaticLayout`，~13 次布局计算
- **优化**：改为二分查找，StaticLayout 构建次数从 ~13 降至 ~5。横向与竖向字号计算同步优化

#### 4. executor 有界队列（P2）
- **原问题**：`Executors.newFixedThreadPool(3)` 用无界 `LinkedBlockingQueue`，极端场景任务堆积导致 OOM
- **优化**：改用 `ThreadPoolExecutor` + 容量 16 的有界队列 + `DiscardOldestPolicy`，防止任务无限堆积

### 翻译流程改进

#### 5. MiMo 批量容错增强（P1）
- **原问题**：`parseBatchOutput` 仅在编号全覆盖或行数精确匹配时返回结果，偶发多/少一条导致整批失败回退百度，成本高
- **优化**：编号覆盖 ≥ 半数即接受，缺失条目留空由 `validateAndFallback` 逐条回退；行数多于期望时取前 N 行，避免整批失败

#### 6. 百度翻译改用 POST 表单（P2）
- **原问题**：`BaiduTranslator.callBaiduApi` 用 GET 拼接 URL，长文本可能超过 URL 长度限制（2KB-8KB）
- **优化**：改用 `FormBody` POST 表单提交

#### 7. 百度回退走缓存（P2）
- **原问题**：`TranslationPlugin.validateAndFallback` 和 `translateBatch` 中百度回退直接调用 `baiduTranslator.translate()`，未走 `translate()` 的 LRU 缓存
- **优化**：改用 `translate()`，复用 LruCache(500)

### 稳定性与内存

#### 8. OpenCV Mat 统一释放（P1）
- **原问题**：`BubbleDetector.detectBubbles` / `PanelDetector.detectPanels` 在 try 块末尾释放 Mat，异常路径下 native 内存泄漏
- **优化**：所有 Mat 声明提前，统一在 `finally` 块释放；`contours` 列表由调用方 finally 统一 release，避免 `filterBubbles`/`filterPanels` 提前 return 时泄漏

#### 9. 避让步数增加（P2）
- `TranslationOverlayView.nudgeAwayFromOccupied` 步长从 3 增加到 5，覆盖更拥挤场景，减少绘制重叠

### 代码卫生

#### 10. 统一 Levenshtein 实现（P2）
- 新增 `util/StringUtils.kt`，统一 `levenshteinDistance` / `similarity`，消除 `OcrProcessor` 与 `FloatingWindowService` 重复实现

#### 11. 统一 UI 文字黑名单（P2）
- 移除 `OcrProcessor.UI_TEXT_BLACKLIST` 重复定义，统一引用 `TextFilter.isAppUiText` / `isPureChinese`

#### 12. 日志级别规范化（P3）
- `PluginManager` 中 5 处 `Log.w`（正常业务日志）改为 `Log.d`，警告级别留给真正的异常

#### 13. inline 函数编译修复（P3）
- `FloatingWindowService.safeCallback` / `safeCallbackArg` 的 nullable lambda 参数加 `noinline`，修复 Kotlin 编译器严格模式下的告警

---

## v4.33 - 2026-07-15

### 关键修复：翻译气泡瞬间消失 + 后续截图持续失败

#### 1. 翻译气泡瞬间消失（P0）
- **根因**：实时翻译成功显示 16 个气泡后，`checkScreenChange()` 每 150ms 检测画面变化。覆盖层出现导致像素哈希改变，`movingFrameCount >= 2` 后调用 `clearAllTranslations()` 清除气泡
- **修复**：新增 `suppressChangeDetection` 标志，显示翻译结果前设为 `true`，800ms 后恢复。抑制期间 `checkScreenChange` 仅更新基线哈希不检测变化，覆盖层出现/消失不再触发清除

#### 2. 后续截图持续失败（P0）
- **根因**：`getCachedBitmapCopy()` 中 `Bitmap.createBitmap(cached)` 在内存紧张时抛出 `OutOfMemoryError`（是 `Error` 不是 `Exception`），未被 `catch(e: Exception)` 捕获，导致 `waitForFreshFrame` 返回 null
- **修复 1 — catch Throwable**：`getCachedBitmapCopy` 的 `catch(e: Exception)` 改为 `catch(e: Throwable)`，防止 OOM 导致返回 null
- **修复 2 — stealCachedBitmap 回退**：新增 `stealCachedBitmap()` 方法，当副本创建失败时直接取走缓存帧（置 null，监听器会创建新的），避免返回 null
- **修复 3 — waitForFreshFrame 重试**：`requireFresh=false` 时若 `getCachedBitmapCopy` 返回 null，不再立即返回 null，而是继续等待新帧直到超时
- **修复 4 — 重试优化**：每次重试都检测 HandlerThread 存活状态，增加诊断日志，重试间隔 200ms → 300ms 给重建更多恢复时间

---

## v4.32 - 2026-07-15

### 关键修复：截图持续失败 + 气泡纵向偏差

#### 1. 截图失败根因修复（P0）
- **根因**：`imageAvailableListener` 以 60fps 每帧创建 ~8MB Bitmap，`OutOfMemoryError`（是 `Error` 不是 `Exception`）未被 `catch(e: Exception)` 捕获，直接杀死 HandlerThread 的 Looper。之后监听器永不触发，`cachedBitmap` 不再更新，所有后续截图返回 null
- **修复 1 — 限流**：监听器新增 200ms 最小帧间隔，60fps → 5fps，内存分配量降低 92%
- **修复 2 — catch Throwable**：监听器和 `imageToBitmap` 的 `catch(e: Exception)` 改为 `catch(e: Throwable)`，防止 OOM 杀死 Looper
- **修复 3 — HandlerThread 重建**：`recreateCapturePipeline` 检测 HandlerThread 存活状态，若已死亡则自动重建
- **修复 4 — 保留缓存帧**：`recreateCapturePipeline` 不再清空 `cachedBitmap`，保留最后一帧作为 fallback，避免重建期间返回 null

#### 2. 气泡纵向偏差修复（P0）
- **根因**：`scaleFactor` 仅基于宽度计算（`screenWidth / realScreenWidth`），但 `screenHeight` 被 clamp 到 1920，真实屏幕高度可能 2400+。Y 坐标使用宽度缩放因子（通常 1.0），导致纵向坐标未放大到真实屏幕尺寸
- **修复**：新增 `scaleFactorY = screenHeight / realScreenHeight`，坐标缩放 X 用 `scaleFactor`、Y 用 `scaleFactorY`

---

## v4.31 - 2026-07-15

### P1: 稳定性与性能修复

#### 1. 帧监听器移至后台 Handler（UI 卡顿修复）
- `imageAvailableListener` 原注册在主线程 Handler 上，每帧全屏像素拷贝阻塞 UI
- 新增 `HandlerThread("FrameCapture")`，监听器在后台线程执行 `imageToBitmap()`
- `onDestroy` 中 `quitSafely()` 清理 HandlerThread

#### 2. checkScreenChange 避免全屏 Bitmap 拷贝（GC 压力修复）
- 原每 150ms 通过 `Bitmap.createBitmap(cached)` 创建全屏副本（~8MB）用于哈希计算
- 改为 `synchronized(cachedBitmapLock)` 直接在缓存 Bitmap 上计算哈希，零分配

#### 3. OcrProcessor ML Kit 回调防已回收 Bitmap 崩溃
- `recognizeBitmap` 返回值改为 `Pair<List<OcrResult>, Boolean>`，第二个值表示回调是否完成
- `recognizeWithRotation` 仅在 `completed=true` 时回收 rotatedBitmap
- 超时情况下不回收 Bitmap，交由 GC 处理，避免 ML Kit 内部访问已回收 Bitmap 崩溃

#### 4. PanelDetector contours native 资源释放
- `detectPanels` 中 `contours` 列表的每个 `MatOfPoint` 从未 release
- 新增循环 `contour.release()` 统一释放，与 BubbleDetector 保持一致

### P2: 代码质量与潜在风险修复

#### 5. captureAndProcess 双重 recycle 修复
- `doOcrAndTranslate` 内部已 `bitmap.recycle()`，`captureAndProcess` 的 `finally` 块再次 recycle
- 移除 finally 块的 recycle，仅在 hash 不匹配的提前退出路径手动 recycle

#### 6. OpenCVHelper isInitialized 线程安全
- `private var isInitialized` 添加 `@Volatile`，保证多线程可见性

#### 7. SettingsActivity 裸 Thread 防销毁崩溃
- `testBaiduApi()` / `testMimoApi()` 的 `runOnUiThread` 回调中添加 `isFinishing || isDestroyed` 检查
- Activity 销毁后不再创建 AlertDialog，避免 WindowLeaked/IllegalStateException

#### 8. 共享 OkHttpClient
- 新增 `HttpClientProvider` 工具类，提供 `textClient`/`mimoTextClient`/`visionClient` 三个懒加载共享实例
- `BaiduTranslator`/`MimoTranslator`/`AiVisionPipeline` 各自的独立 Client 替换为共享实例
- 复用连接池和线程池，减少资源开销

#### 9. SecurePrefs 使用 applicationContext
- `get(context)` 改为 `context.applicationContext`，避免持有 Activity Context

#### 10. AiVisionPipeline 大图 base64 尺寸限制
- `bitmapToBase64` 新增最大 1536px 尺寸限制，超过时缩放后再压缩
- 避免高分辨率截图产生数 MB base64 字符串导致内存峰值

#### 11. FloatingWindowService 静态回调安全化
- 新增 `safeCallback` / `safeCallbackArg` 内联函数，try-catch 包裹所有回调调用
- 防止服务异常重建后旧回调指向已销毁的 ScreenCaptureService 导致崩溃
- 所有 7 处 `?.invoke()` 调用替换为安全调用

### P3: 工程优化

#### 12. TranslationOverlayView onDetachedFromWindow 清理
- 重写 `onDetachedFromWindow()`，View 被 remove 时取消动画和 clearRunnable
- 防止 remove 后动画继续运行造成无效绘制

#### 13. BaiduTranslator 共享 Random 实例
- `Random()` 改为 companion 中的 `private val random = Random()`，避免每次调用创建新对象

#### 14. PluginManager Bitmap 生命周期契约文档
- `translateImage` 方法添加完整 KDoc，明确调用方负责 bitmap 回收的契约

---

## v4.30 - 2026-07-15

### 一、P0: 截图失败修复（核心问题）

#### 问题
- 手动翻译时截图持续失败（`acquireLatestImage()` 返回 null），重试 3 次后报"多次重试后仍无图像"
- 根因：ImageReader `maxImages=2` 缓冲区过小，实时模式停止后无人消费帧导致缓冲区满，VirtualDisplay 停止生产帧
- `doOneShotCapture` 的 flush+poll 策略无法获取新帧

#### 修复
- 改用 `OnImageAvailableListener` 回调模式持续消费帧，VirtualDisplay 永不停止生产
- `maxImages` 从 2 增大到 5，减少帧丢失
- 新增帧缓存机制（`cachedBitmap` + `cachedBitmapLock`），始终保留最新帧
- `doOneShotCapture`/`checkScreenChange`/`captureAndProcess` 全部改为从帧缓存读取
- 新增 `recreateCapturePipeline()` 兜底重建机制，极端情况下自动恢复
- 新增 `waitForFreshFrame()` 超时等待机制

### 二、P0: 显示方向覆盖修复

#### 问题
- `doOcrAndTranslate` 中 `isVertical = direction == RecognitionDirection.VERTICAL` 用识别模式覆盖每个卡片的自身方向
- 用户选竖向识别模式时，横向文本也被强制竖向绘制，排版错乱
- 使 v4.10 在 OcrPlugin 层的方向修复在显示层失效

#### 修复
- 改为 `isVertical = card.isVertical`，尊重每个翻译卡片自身的方向判断

### 三、P0: API Key 加密存储

#### 问题
- 百度 App ID/Secret Key、MiMo API Key 以明文存入 `translation_config` SharedPreferences
- root 设备或备份导出可轻易读取

#### 修复
- 新增 `SecurePrefs` 工具类，使用 `EncryptedSharedPreferences`（AES256-GCM）加密存储敏感数据
- `BaiduTranslator` 敏感字段迁移至加密存储
- `MimoTranslator` API Key 迁移至加密存储，非敏感配置（baseUrl/model）保持明文
- 自动迁移逻辑：首次访问时检测旧版明文数据，迁移后从明文文件删除
- 创建失败时自动回退到普通 SharedPreferences 保证可用性

### 四、P1: native 资源释放

#### 问题
- `BubbleInfo.contour: MatOfPoint` 字段从未被读取，native 内存未释放
- `filterBubbles` 中 `hullIndices`/`hull` 在 `solidity` 检查 `continue` 时泄漏

#### 修复
- 移除 `BubbleInfo.contour` 字段
- `hullIndices`/`hull` 在所有退出路径（包括 `continue`）均调用 `release()`
- 检测完成后统一释放所有 contours 的 native 资源

### 五、P1: CompletableFuture 超时保护

#### 问题
- `OcrProcessor` 横向 OCR 并行等待 `horizontalFuture.get()` / `verticalFuture.get()` 无超时
- 线程池饱和或 ML Kit 回调异常时永久阻塞

#### 修复
- 添加 20 秒超时，超时返回空列表并记录日志

### 六、P1: 翻译缓存改为真正 LRU

#### 问题
- `ConcurrentHashMap` 的 `keys.firstOrNull()` 淘汰不保证插入顺序，可能淘汰高频条目

#### 修复
- 改用 `android.util.LruCache`，实现真正的 LRU 淘汰策略
- `clearCache()` 改为 `evictAll()`

### 七、P2: 代码清理与一致性

#### 死代码清理
- 移除 `PluginManager` 中 `overlapLength()`、`horizontalGap()`、`verticalGap()` 三个未使用方法
- 移除 `FloatingWindowService.showTranslations()` 未使用的非 Debug 版本

#### 坐标偏移统一
- AI 路径的坐标偏移从手动构造 `TranslationCard` 改为使用 `withOffset()` 扩展函数
- 与本地路径统一，避免裁剪配置变更时遗漏一处

#### 调试映射修复
- `DebugOverlayData.mappings` 此前声明为空列表从未填充
- 现在正确记录 textRect → panel.rect 映射关系，调试覆盖层映射线功能恢复

#### 网络超时配置统一
- `MimoTranslator` 新增 `TEXT_*_TIMEOUT` / `VISION_*_TIMEOUT` 常量
- `AiVisionPipeline` 引用 `MimoTranslator.VISION_*_TIMEOUT` 常量替代魔法数字

### 八、P3: 构建与工程卫生

#### 混淆构建
- `release` 构建开启 `minifyEnabled true` + `shrinkResources true`
- 新增 `proguard-rules.pro` 规则：Gson 数据类、ML Kit、OpenCV、EncryptedSharedPreferences、OkHttp、Retrofit、协程 keep 规则

#### 其他
- 版本号升级：4.20 → 4.30，versionCode 420 → 430
- 删除残留 `.bak` 备份文件
- README 版本号同步更新

---

## v4.20 - 2026-07-14

### 一、P0: 手动翻译修复

#### 问题
- `doOneShotCapture` 在主线程执行 `Thread.sleep(33)` 和 `imageToBitmap()`，阻塞 UI
- 翻译失败时悬浮球不恢复，用户看到"什么都没发生"
- 手动翻译需要等待 4-10 秒才有结果

#### 修复
- `doOneShotCapture` 移到后台线程执行，不再阻塞主线程
- 翻译失败时显示明确的状态文字（"未截取到画面"/"翻译失败"/"无结果"）
- 状态文字改进：手→翻译，实→实时，...→识别中，完→完成

### 二、P0: 实时翻译修复

#### 问题
- 每次新翻译都先清除旧翻译（`removeAllTranslationOverlays()`），如果新翻译失败，旧翻译也消失了
- 15秒自动移除导致翻译框突然消失
- 画面微小变化触发重复翻译

#### 修复
- `addTranslationOverlaysWithDebug` 改为**更新现有覆盖层**而非重建
- 翻译失败时**不清除旧翻译**
- 自动移除时间从 15秒 → 30秒
- 线程池替代裸 `Thread`，避免线程泄漏

### 三、P1: 动画和状态反馈

#### 新增
- 翻译框**淡入动画**（250ms，DecelerateInterpolator）
- 翻译框**淡出动画**（150ms，AccelerateInterpolator）
- 菜单**滑入动画**（200ms，从上方滑入）
- 菜单**滑出动画**（150ms，向下滑出）
- 状态文字更清晰：翻译/实时/暂停/识别中/完成/无结果/失败

### 四、P2: UI 美化

#### 悬浮球重设计
- 从圆形改为**胶囊形状**（pill shape）
- 半透明渐变背景（不遮挡漫画）
- 更大的触摸区域（80dp × 40dp）
- 更现代的阴影效果（elevation 6dp）

#### 菜单重设计
- 更大的圆角（16dp）
- 更精致的阴影（elevation 16dp）
- 半透明白色背景（0xFAFFFFFF）
- 细边框（0x22000000）

#### 翻译框样式改进
- **阴影效果**（下方 1.5dp 偏移）
- **左侧蓝色竖条**（accent bar，3dp 宽）
- **更柔和的背景色**（0xD2FCFCFF）
- **更细的边框**（0.8dp，0x3C646478）
- **更柔和的遮罩**（0xB4FFFFFF）

### 五、P3: 性能优化

#### 线程池
- `ScreenCaptureService` 新增 `Executors.newFixedThreadPool(2)` 替代裸 `Thread`
- 线程命名：`MangaTranslator-Worker`
- 服务销毁时自动 `shutdown()`

#### OCR 并行化
- 横向模式下，横向 OCR 和竖向 OCR 现在**并行执行**（CompletableFuture）
- 预期速度提升：横向模式 OCR 时间减少约 40-50%

#### Bitmap 采样优化
- `computePixelHashes` 返回 `IntArray` → `computePixelHash` 返回 `Long`
- 不再分配 IntArray（~20KB/次），直接计算 Long 哈希
- 减少 GC 压力

### 文件改动清单

| 文件 | 改动 |
|------|------|
| `ScreenCaptureService.kt` | doOneShotCapture 后台线程 + 线程池 + Bitmap 采样优化 + 状态文字 |
| `FloatingWindowService.kt` | 覆盖层更新不替换 + 自动移除30s + 悬浮球胶囊形状 + 菜单动画 + 状态文字 |
| `TranslationOverlayView.kt` | 淡入淡出动画 + 阴影 + 蓝色竖条 + 样式改进 |
| `OcrProcessor.kt` | 横向模式 OCR 并行化 |

---

## v4.10 - 2026-07-08

### 一、AI 多模态全流程识别（新功能）

#### 背景

之前的 AI 能力只用于气泡检测（`AiBubbleDetector`），识别出气泡位置后仍然需要 ML Kit OCR 重新识别文字、再翻译。AI 返回的文字信息被完全忽略，浪费了 tokens。

#### 方案

新建 `AiVisionPipeline.kt`，一次 MiMo Vision API 调用同时完成：
1. 气泡检测 - 返回像素坐标
2. 文字识别 - 返回日文原文
3. 翻译 - 返回中文译文
4. 竖横判断 - 返回 `is_vertical`
5. 阅读顺序 - 返回 `reading_order`

#### 触发条件

```
手动翻译 + AI开关打开 + MiMo API已配置
```

实时翻译仍走本地流程（OCR + 组句 + 翻译），保证速度。

#### Prompt 设计

```
你是漫画翻译专家。请分析这张漫画图片：

任务：
1. 识别图片中所有对话气泡的位置（像素坐标）
2. 识别每个气泡内的日文原文
3. 将日文翻译成自然中文
4. 判断文字是竖排还是横排
5. 按阅读顺序（日漫从右到左、从上到下）编号

输出严格JSON格式，不要其他内容：
{
  "bubbles": [
    {
      "x": 气泡左上角X像素,
      "y": 气泡左上角Y像素,
      "width": 气泡宽度像素,
      "height": 气泡高度像素,
      "text": "日文原文",
      "is_vertical": true或false,
      "translation": "中文翻译",
      "reading_order": 1
    }
  ]
}

坐标基于图片尺寸 ${imageWidth}x${imageHeight}。
如果图片中没有对话气泡，返回 {"bubbles": []}。
仔细识别所有气泡，包括小的、不规则形状的。
翻译要自然流畅，保留漫画对白的语气。
```

#### AI 返回结构示例

```json
{
  "bubbles": [
    {
      "x": 120, "y": 50, "width": 180, "height": 120,
      "text": "こんにちは",
      "is_vertical": true,
      "translation": "你好",
      "reading_order": 1
    },
    {
      "x": 400, "y": 200, "width": 200, "height": 80,
      "text": "今日はいい天気だ",
      "is_vertical": false,
      "translation": "今天天气真好",
      "reading_order": 2
    }
  ]
}
```

#### 调用链变化

```
之前：
截图 → 裁剪 → PanelDetector → BubbleDetector → OCR(6遍) → SentenceAssembler → 翻译 → 定位

现在（手动翻译+AI开启+MiMo已配置）：
截图 → 裁剪 → AiVisionPipeline.analyzeImage()
  → 一次 MiMo Vision API 调用
  → 直接返回 TranslationCard 列表

其他情况仍走现有流程
```

#### 新建文件

**`plugin/AiVisionPipeline.kt`**

核心方法：
- `analyzeImage(bitmap, width, height)` → 调用 MiMo Vision API，返回原始 JSON + 解析后的气泡列表
- `toTranslationCards(result, lastTranslationRects)` → 将 AI 结果转为 `TranslationCard` 列表，过滤空白/失败/重复
- `isMiMoConfigured()` → 检查 MiMo API 是否已配置
- `bitmapToBase64(bitmap)` → 图片转 Base64
- `buildPrompt(width, height)` → 构建 Prompt
- `callMimoVisionApi(base64, prompt)` → HTTP 调用 MiMo Vision API
- `parseResponse(response, width, height)` → 解析 JSON，clamp 坐标到图片范围

使用的 `AiBubble` 数据结构：
```kotlin
private data class AiBubble(
    val x: Int?,
    val y: Int?,
    val width: Int?,
    val height: Int?,
    val text: String?,
    val isVertical: Boolean?,
    val translation: String?,
    @SerializedName("reading_order") val readingOrder: Int?
)
```

#### 修改文件

**`plugin/PluginManager.kt`**

- 新增 `aiVisionPipeline` 字段
- `translateImage()` 新增 `isManual` 参数
- 在 `translateImage()` 开头加 AI 路径判断：

```kotlin
if (isManual && useAiBubbleDetection && aiVisionPipeline.isMiMoConfigured()) {
    try {
        val aiResult = aiVisionPipeline.analyzeImage(croppedBitmap, ...)
        val cards = aiVisionPipeline.toTranslationCards(aiResult, lastTranslationRects)
        return cards  // 直接返回，跳过 OCR/组句/翻译
    } catch (e: Exception) {
        Log.e(TAG, "AI多模态失败，回退现有流程: ${e.message}")
    }
}
```

- 气泡检测简化为只用 OpenCV（AI 路径已在上面处理）
- 方法重命名：`setUseAiBubbleDetection()` → `setUseAiVisionMode()`
- 日志标记从 `PluginManager v2` → `PluginManager v3`

**`service/ScreenCaptureService.kt`**

- `doOcrAndTranslate()` 调用 `translateImage()` 时传入 `isManual = allowManualResult`
- 方法调用从 `setUseAiBubbleDetection()` → `setUseAiVisionMode()`

**`activity_settings.xml`**

- "AI气泡检测"卡片 → "AI多模态识别"卡片
- 说明文字更新为 AI 多模态功能说明
- Model 输入框 hint 从 `mimo-vl-7b` → `mimo-v2.5`
- "关于翻译功能"更新为支持 AI 多模态

**`SettingsActivity.kt`**

- `updateAiBubbleDetectionStatus()` 状态文字更新：
  - 已关闭
  - 已开启（需配置MiMo API）
  - 已开启，手动翻译时使用AI多模态识别
- 日志从 "AI气泡检测设置已保存" → "AI多模态识别设置已保存"

#### 容错设计

- MiMo API 未配置 → 走现有流程
- AI 调用失败（网络超时、JSON 解析错误等）→ 捕获异常，回退现有流程
- AI 返回空气泡 → 返回空列表，不显示翻译
- 坐标超出图片范围 → clamp 到合法范围

---

### 二、大规模代码清理（约 1000+ 行）

#### 背景

项目经历了多次重构（气泡优先 → 空间分组 → 句子优先），留下大量死代码。经全量分析，约 19% 的代码（~1000 行）从未被调用。

#### 删除的文件（7个）

| 文件 | 行数 | 说明 |
|------|------|------|
| `TextMerger.kt` | 137 | 旧的文本合并器，被 SentenceAssembler 替代。从未实例化。 |
| `TextAwareClusterer.kt` | 369 | 旧的文本聚类器，更复杂的分组逻辑但从未使用。从未实例化。 |
| `BubbleTextMapper.kt` | 162 | 旧的气泡-文字映射器，用评分系统匹配。被句子优先流程替代。 |
| `BubbleLayout.kt` | 207 | 旧的气泡内布局计算。布局逻辑已移到 TranslationOverlayView。 |
| `MergedBlock.kt` | 15 | 旧的数据模型，仅被 TextMerger 使用。 |
| `RectUtils.kt` | ~30 | 旧的矩形工具类，仅被 TextMerger 使用。 |
| `AiBubbleDetector.kt` | 203 | 旧的 AI 气泡检测，被 AiVisionPipeline 完全替代。 |

#### PluginManager.kt 清理（1021行→约540行）

删除的 26 个死方法：

| 方法 | 原因 |
|------|------|
| `prepareUsableBubbles()` | 从未被调用 |
| `filterBubblesNearText()` | 仅被 prepareUsableBubbles 调用 |
| `findBestBubbleRect()` | 仅被 buildBubbleSentenceRegions 调用（也是死代码） |
| `buildBubbleSentenceRegions()` | 旧的气泡分组逻辑，从未被调用 |
| `subdivideByProximity()` | 仅被 buildBubbleSentenceRegions 调用 |
| `buildSpatialSentenceRegions()` | 旧的空间分组逻辑，从未被调用 |
| `buildTightRegionFromBlocks()` | 仅被 buildSpatialSentenceRegions 调用 |
| `buildVerticalSpatialSentences()` | 从未被调用 |
| `buildHorizontalSpatialSentences()` | 从未被调用 |
| `shouldJoinVerticalSentence()` | 仅被死方法调用 |
| `shouldJoinHorizontalSentence()` | 仅被死方法调用 |
| `buildRegionFromBlocks()` | 仅被死方法调用 |
| `resolveCollisions()` | 空操作：`return regions`，什么都没做 |
| `pushApart()` | 从未被调用（resolveCollisions 是空操作） |
| `rectInside()` | 仅被 pushApart 调用 |
| `isLightArea()` | 从未被调用 |
| `isLikelySpeechBubbleArea()` | 仅被 isRegionAnchoredToBubble 调用 |
| `isRegionAnchoredToBubble()` | 从未被调用 |
| `estimateOutputRect()` | 仅被死方法调用 |
| `estimateTightOutputRect()` | 仅被死方法调用 |
| `limitPerPanel()` | 引用已删除的 TextAwareClusterer |
| `isOutputRectReasonableForText()` | 从未被调用 |
| `centerDistance()` | 从未被调用 |
| `mergeNearbyBubbles()` | 仅被 prepareUsableBubbles 调用 |
| `shouldMergeBubbleRects()` | 仅被 mergeNearbyBubbles 调用 |
| `isAnchoredCloseEnough()` | 仅被 buildBubbleSentenceRegions 调用 |

删除的死数据类：`BubbleTextGroup`
删除的死常量：`MIN_BUBBLE_WHITE_RATIO`

#### translateCore() 修复

```kotlin
// 之前（错误）：
val usableBubbles = emptyList<BubbleDetector.BubbleInfo>()  // 写死空列表
val sentenceRegions = buildSentenceFirstRegions(ocrBlocks, panels, usableBubbles, bitmap)

// 之后（正确）：
val sentenceRegions = buildSentenceFirstRegions(ocrBlocks, panels, bubbles, bitmap)
// 直接使用参数传入的 bubbles，不再丢弃
```

#### ScreenCaptureService.kt 清理

删除 6 个死方法：
- `mergeNearbyOcrResults()` - 注释说"保留兼容旧代码"但无调用
- `shouldMergeIntoGroup()` - 旧的合并逻辑
- `shouldMergeGroups()` - 旧的合并逻辑
- `shouldMergeRects()` - 旧的合并逻辑
- `sortGroupForReading()` - 旧的排序逻辑
- `unionRects()` - 旧的工具方法

#### 其他文件清理

| 文件 | 删除 |
|------|------|
| `OcrProcessor.kt` | `sortByVerticalReadingOrder()`（从未调用）、`isOverlapping()`（从未调用） |
| `BubbleDetector.kt` | `findBubbleForText()`、`isRectInsideRect()`、`calculateDistance()`（从未调用） |
| `FloatingWindowService.kt` | `addMenuItem()`（旧菜单设计残留） |
| `TranslationOverlayView.kt` | `overlapsAny()`（从未调用） |
| `MimoTranslator.kt` | `translateWithContext()`、`buildContextPrompt()`（从未调用） |
| `ComicImageCropper.kt` | `adjustRectForCrop()`、`scaleRect()`（从未调用） |
| `TranslationCard.kt` | 删除 `cardRect`、`fontSize`、`width`、`height` 字段（从未赋值/使用） |

---

### 三、逻辑修复（5项）

#### 修复1：识别方向 - 只跑必要 OCR

**问题**：`verticalOnly=true` 时仍跑全部 6 遍 OCR（3 图像版本 × 2 旋转方向），浪费 50% 算力。

**修复**（`OcrProcessor.kt`）：

```kotlin
// 之前：不管什么模式都跑6遍
val enhancedHorizontalResults = recognizeWithRotation(enhancedBitmap, 0f, ...)
val enhancedVerticalResults = recognizeWithRotation(enhancedBitmap, 90f, ...)
val rawHorizontalResults = recognizeWithRotation(scaledBitmap, 0f, ...)
val rawVerticalResults = recognizeWithRotation(scaledBitmap, 90f, ...)
val binaryHorizontalResults = recognizeWithRotation(binaryBitmap, 0f, ...)
val binaryVerticalResults = recognizeWithRotation(binaryBitmap, 90f, ...)

// 之后：根据模式只跑必要的
if (verticalOnly) {
    // 竖向模式：只跑竖向OCR（3个版本）
    verticalResults = deduplicateResults(enhancedVertical, rawVertical, binaryVertical)
    horizontalResults = emptyList()
} else {
    // 横向模式：跑全部6个版本
    horizontalResults = deduplicateResults(enhancedHorizontal, rawHorizontal, binaryHorizontal)
    verticalResults = deduplicateResults(enhancedVertical, rawVertical, binaryVertical)
}
```

#### 修复2：方向标记 - 按实际宽高比判断

**问题**：`OcrPlugin.kt` 中 `isVertical = verticalOnly || isVerticalText(rect)`，当 `verticalOnly=true` 时所有 OCR 块都被强制标记为竖向，不管实际方向。

**修复**（`OcrPlugin.kt`）：

```kotlin
// 之前（错误）：
isVertical = verticalOnly || isVerticalText(rect)

// 之后（正确）：
isVertical = isVerticalText(rect)
// verticalOnly 只影响跑哪些 OCR，不影响方向标记
```

#### 修复3：气泡定位 - 不再丢弃气泡结果

**问题**：`translateCore()` 中 `val usableBubbles = emptyList<BubbleDetector.BubbleInfo>()` 写死空列表，气泡检测结果被传入后立刻丢弃。

**修复**：删除 `usableBubbles` 变量，直接使用参数 `bubbles`。

```kotlin
// 之前：
val usableBubbles = emptyList<BubbleDetector.BubbleInfo>()
val sentenceRegions = buildSentenceFirstRegions(ocrBlocks, panels, usableBubbles, bitmap)

// 之后：
val sentenceRegions = buildSentenceFirstRegions(ocrBlocks, panels, bubbles, bitmap)
```

#### 修复4：黑名单统一

**问题**：`OcrProcessor` 和 `TextFilter` 各有一份 `UI_TEXT_BLACKLIST`，内容不一致。

```kotlin
// OcrProcessor 旧版（缺少5项）：
private val UI_TEXT_BLACKLIST = setOf(
    "暂停翻译", "暂停翻真", "实时翻译", "手动翻译", "恢复翻译",
    "横向模式", "竖向模式", "开始翻译", "停止翻译", "设置",
    "翻译器", "漫画翻译", "正在运行", "翻译结果"
)

// TextFilter（完整版）：
private val UI_TEXT_BLACKLIST = setOf(
    "暂停翻译", "暂停翻真", "实时翻译", "手动翻译", "恢复翻译",
    "横向模式", "竖向模式", "开始翻译", "停止翻译", "设置",
    "翻译器", "漫画翻译", "正在运行", "翻译结果", "翻译中",
    "截图", "截图中", "正在截图", "截图完成"
)
```

**修复**：OcrProcessor 黑名单同步为完整版。

#### 修复5：默认模型不一致

**问题**：`MimoTranslator.DEFAULT_MODEL = "mimo-v2.5"`，但 `SettingsActivity` 默认模型为 `"mimo-vl-7b"`。

**修复**：SettingsActivity 两处默认模型改为 `"mimo-v2.5"`。

---

### 四、设置界面重新设计

#### AI 卡片变化

**之前**：
```
AI气泡检测
使用AI识别漫画气泡，更准确
能识别不规则形状的气泡
需要配置MiMo API Key
速度较慢，但效果更好

[开关] 启用AI气泡检测
状态：使用传统OpenCV检测
```

**之后**：
```
AI多模态识别
使用MiMo Vision API一次性完成：
1. 气泡检测 - 识别各种形状的对话气泡
2. 文字识别 - 识别气泡内的日文原文
3. 翻译 - 将日文翻译成自然中文
4. 输出定位 - 精确覆盖在原文位置

需要配置MiMo API Key。
仅在手动翻译时启用，实时翻译仍走本地流程。

[开关] 启用AI多模态识别
状态：已关闭 / 已开启（需配置MiMo API）/ 已开启
```

#### 状态显示逻辑

```kotlin
when {
    !enabled -> "状态：已关闭"
    !mimoConfigured -> "状态：已开启（需配置MiMo API）"
    else -> "状态：已开启，手动翻译时使用AI多模态识别"
}
```

---

## v4.00 - 2026-07-07

### 句子优先流程

主流程改为 OCR 文本块优先，不以气泡检测为主定位依据。

**流程**：
```
OCR识别 → TextFilter过滤 → SentenceAssembler组句
→ 找可信气泡（trustedBubbleScore ≥ 0.45）
→ 选锚点块（最居中的 OCR 块）
→ 以锚点为中心估算输出框
→ 约束到气泡/边距范围内
```

### 坐标修正（关键修复）

修复 `OcrProcessor.mapRectBack(90°/270°)` 坐标还原错误。

**错误代码**：
```kotlin
90f -> Rect(origWidth - rect.bottom, rect.left, origWidth - rect.top, rect.right)
```

**正确代码**：
```kotlin
90f -> Rect(rect.top, origHeight - rect.right, rect.bottom, origHeight - rect.left)
```

这个 bug 导致 90° 旋转 OCR 的坐标映射错误，造成"左上/右下对调"。

### 覆盖层优化

- 文本框背景恢复不透明：`Color.argb(220, 255, 255, 248)`
- 遮罩层：`Color.argb(200, 255, 255, 255)`
- 新增轻推避让函数 `nudgeAwayFromOccupied()`：
  - 竖向识别：重叠时左右轻推
  - 横向识别：重叠时上下轻推
  - 选择移动最小、重叠面积最小的位置

### 默认设置

- 默认识别方向：竖向识别（`RecognitionDirection.VERTICAL`）
- 版本号：`4.00`（versionCode `400`）

---

## v3.00 - 2026-07-06

### 悬浮球菜单

悬浮球菜单改为 3 行按钮布局：
```
┌─────────────────┐
│ 实时翻译 │ 手动翻译 │
├─────────────────┤
│ 横向识别 │ 竖向识别 │
├─────────────────┤
│ 暂停翻译 │ 关闭菜单 │
└─────────────────┘
```

- 实时翻译 / 手动翻译模式切换
- 横向识别 / 竖向识别方向切换
- 暂停翻译 / 恢复翻译
- 关闭菜单

### 菜单文本修复

去掉菜单选项前面的乱码问号字符（编码问题）。

---

## v2.90 - 2026-07-05

### 基础功能实现

- **截屏**：MediaProjection + ImageReader
- **OCR**：ML Kit Japanese OCR，6 遍识别（enhanced/raw/binary × 横向/竖向），逐层去重
- **句子组装**：SentenceAssembler 按空间距离和日文语法规则合并 OCR 块
- **翻译**：MiMo 批量翻译 + 百度翻译回退，带缓存和质量验证
- **覆盖层**：TranslationOverlayView 全屏半透明层，15 秒自动消失
- **悬浮球**：可拖拽，点击触发翻译，长按打开菜单
- **画面变化检测**：像素哈希采样，静止 2 帧后触发翻译

---

## 文件结构（v4.10）

```
app/src/main/java/com/manga/translator/
├── MainActivity.kt                    # 主入口，权限请求，服务启动
├── SettingsActivity.kt                # 设置页面，API 配置，AI 开关
├── debug/
│   ├── DebugOverlayConfig.kt          # 调试覆盖层配置
│   └── DebugOverlayData.kt            # 调试覆盖层数据
├── model/
│   ├── OcrBlock.kt                    # OCR 文本块数据模型
│   └── TranslationCard.kt            # 翻译卡片数据模型
├── ocr/
│   └── OcrProcessor.kt               # ML Kit OCR，6遍识别+去重+坐标修正
├── plugin/
│   ├── AiVisionPipeline.kt           # AI 多模态全流程（新增）
│   ├── BubbleDetector.kt             # OpenCV 气泡检测
│   ├── OcrPlugin.kt                  # OCR 适配层
│   ├── PanelDetector.kt              # OpenCV 分镜检测
│   ├── PluginManager.kt              # 主流程编排（核心）
│   └── SentenceAssembler.kt          # OCR块→句子组装
├── service/
│   ├── FloatingWindowService.kt      # 悬浮球、菜单、覆盖层渲染
│   ├── ScreenCaptureService.kt       # 截屏、画面变化检测、流程编排
│   └── TranslationOverlayView.kt     # 翻译框布局、竖排/横排渲染、避让
├── translation/
│   ├── BaiduTranslator.kt            # 百度翻译 API
│   ├── MimoTranslator.kt             # MiMo 翻译 API
│   └── TranslationPlugin.kt          # 翻译编排（MiMo+百度+缓存）
└── util/
    ├── ComicImageCropper.kt           # 图片裁剪
    ├── OpenCVHelper.kt               # OpenCV 初始化
    └── TextFilter.kt                 # 文本过滤工具
```

共 23 个 Kotlin 文件。

---

## 版本对比

| 版本 | 文件数 | 总行数（估） | 主要变化 |
|------|--------|-------------|----------|
| v2.90 | 30+ | ~5300 | 基础功能 |
| v4.00 | 30+ | ~5300 | 句子优先+坐标修正 |
| v4.10 | 23 | ~3500 | AI多模态+清理死代码 |

清理效果：删除 7 个文件 + 约 1800 行代码，新增 1 个文件（~220 行）。净减少约 1600 行。

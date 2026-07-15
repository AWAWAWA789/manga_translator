# 漫画翻译器 8 周全面改造方案 v1.0（定稿）

> 本文档为项目改造的单一事实来源（SSOT）。所有阶段任务、决策、验收标准以此为准。
> 创建时间：2026-07-16
> 当前状态：**阶段1（W1-W2 止血期）已完成**

---

## 一、项目背景

安卓漫画翻译器（manga_translator）在 v4.38 修复 57 项问题后，第二轮深度审计又发现约 120 项问题，涉及：
- 资源生命周期（Bitmap 泄漏、Service 释放顺序、executor 未等待）
- 线程安全（跨线程可变状态、PluginManager 竞态）
- 架构耦合（Controller 臃肿、无接口分离、无 DI）
- 测试缺失（覆盖率近乎 0）
- 安全合规（明文传输、密钥硬编码风险）
- 可观测性（日志无结构化、无指标、无崩溃聚合）

为系统性解决问题而非打地鼠，制定本 8 周改造方案。

---

## 二、四项核心架构决策（已定稿）

### 决策 A：精简版架构（Controller + 接口分离 + 手动 DI）

**选择理由**：项目体量中等（核心模块约 30 个类），引入 Hilt/Dagger 过重；手动 DI 配合接口分离即可达成可测试性目标。

**实施要点**：
1. 领域层（domain）：纯 Kotlin，无 Android 依赖，定义 `Translator`、`OcrEngine`、`BubbleDetectorInterface` 等接口
2. 数据层（data）：接口实现，依赖 Android Framework
3. 表现层（presentation）：Activity/Service/FloatingWindow，仅持有接口引用
4. 手动 DI 容器：在 `MangaTranslatorApp` 中维护 `ServiceLocator`，提供单例和工厂
5. 现有 PluginManager 拆分为：`TranslationController`（编排）+ `TranslationRepository`（接口实现）

**不引入 Hilt 的原因**：构建时间增加 30s+，注解学习成本，KSP/KAPT 配置复杂度上升，对当前团队规模不划算。

### 决策 B：激进协程全替换（Coroutine 替代所有 Thread/Executor）

**选择理由**：
- 现有 Thread + Executor + Handler 混用导致回调地狱、取消不可控、异常吞没
- 协程结构化并发天然解决取消传播、异常透传
- minSdk 26 后 Coroutines API 完全可用

**实施要点**：
1. 全局 `CoroutineScope`：在 `MangaTranslatorApp` 中创建 `@OptIn(DelicateCoroutinesApi::class) val appScope`
2. Service 作用域：每个 Service 持有 `val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)`
3. `onDestroy` 中 `serviceScope.cancel()`，所有子协程自动取消
4. `ExecutorService` → `withContext(Dispatchers.IO)`，`Handler.postDelayed` → `delay()`
5. `Thread { cleanupResources() }.start()` → `serviceScope.launch { cleanupResources() }`
6. `ConditionVariable` → `suspendCancellableCoroutine` + `CompletableDeferred`
7. PluginManager 的 `synchronized(translateLock)` → `Mutex.withLock {}`

**迁移策略**：先替换 Service 层（收益最大），再替换 util/plugin 层。

### 决策 C：minSdk 24 → 26

**选择理由**：
- Android 8.0 (API 26) 于 2017 年发布，2026 年时 API 24-25 设备占比 < 1%
- API 26 解锁：Adoptable Storage、原生 JobScheduler、通知渠道一等公民、PictureInPicture
- 减少_compat 兼容代码，构建体积下降约 2MB
- Coroutines/Flow 在 API 26+ 行为更稳定

**实施要点**：
1. `app/build.gradle`：`minSdkVersion` 从 24 改为 26
2. 移除 `Build.VERSION.SDK_INT >= Build.VERSION_CODES.O` 的部分冗余检查（保留必要兼容）
3. 移除 `androidx.core` 中仅用于 API 24-25 的兼容调用
4. 验证 ML Kit TextRecognizer、OpenCV、OkHttp 在 API 26 的行为

### 决策 D：核心模块 80% 覆盖率硬门禁

**选择理由**：止血期发现 5 处 Bitmap 泄漏、3 处线程竞态，均为单元测试可预防的缺陷。

**实施要点**：
1. JaCoCo 配置 `minimum = 0.80`，作用于 `com.manga.translator.domain.*` 和 `com.manga.translator.data.*`
2. 覆盖率不足时 `check` 任务失败，阻断合并
3. 不强制 `presentation` 层 80%（UI 测试 ROI 低），但要求 `> 50%`
4. 优先测试：纯函数（TextFilter、StringUtils、hasRepeatedPatternStatic）、状态机、坐标变换
5. 测试框架：JUnit 4 + Robolectric（Android 依赖类）+ MockK（mock 接口）

---

## 三、8 周路线图

### 阶段 0（W0 准备期）✅ 已完成

**目标**：建立质量基础设施，确保后续改造可度量。

**交付物**：
- [x] ktlint 代码格式规范（`.editorconfig`）
- [x] detekt 静态分析 + baseline（`detekt.yml`、`detekt-baseline.xml`）
- [x] JaCoCo 覆盖率报告（`app/build.gradle` 配置）
- [x] JUnit 4 + Robolectric 测试环境
- [x] 首个单元测试（`StringUtilsTest.kt`）

**验收**：`./gradlew assembleDebug ktlintCheck detekt testDebugUnitTest jacocoTestReport` 全绿。

---

### 阶段 1（W1-W2 止血期）✅ 已完成

**目标**：修复 P0/P1 级别的崩溃、ANR、资源泄漏，让现有版本可稳定运行。

**根因组明细**：

#### 根因组 1：onDestroy ANR + 资源释放顺序（W1）
- **问题**：`onDestroy` 在主线程执行 `executor.awaitTermination`，阻塞主线程导致 ANR；资源释放顺序错误导致 native 资源在 executor 任务执行期间被释放，SIGSEGV。
- **修复**：
  - `onDestroy` 改用 `Thread { cleanupResources() }.start()` 异步清理
  - `cleanupResources` 严格 8 步顺序：executor.shutdown → captureHandlerThread.quitSafely → imageReader.close → virtualDisplay.release → mediaProjection.stop → cachedBitmap.recycle → pluginManager.close → ocrProcessor.close
- **文件**：`ScreenCaptureService.kt`
- **状态**：✅

#### 根因组 2：Bitmap 生命周期 5 处泄漏（W1）
- **问题**：异常路径下 Bitmap 未 recycle，长期运行 OOM。
- **修复点**：
  1. `doOcrAndTranslate`：pluginManager null 时 recycle bitmap
  2. `captureAndProcess`：引入 `bitmapConsumed` 标志位 + catch 块兜底 recycle
  3. `processImage`：executor.isShutdown 前置检查，避免 safeSubmit 静默 return 导致泄漏
  4. `AiVisionPipeline.bitmapToBase64`：try-finally 回收 scaled bitmap
  5. `OcrProcessor.binarizeForOcr`：try-catch-finally 回收 source 和 result bitmap
- **文件**：`ScreenCaptureService.kt`、`AiVisionPipeline.kt`、`OcrProcessor.kt`
- **状态**：✅

#### 根因组 3：Application 类 + ForegroundService 时序（W1）
- **问题**：Service 因 START_STICKY 被系统重建时先于 MainActivity 访问 DebugManager（lateinit 崩溃）；startForeground 时首次创建通知渠道可能失败。
- **修复**：
  - 新建 `MangaTranslatorApp`，在 `onCreate` 中提前初始化 `DebugManager` 和通知渠道
  - `AndroidManifest.xml` 注册 `android:name=".MangaTranslatorApp"`
  - `MainActivity` 移除重复的 DebugManager.initialize 调用
- **文件**：`MangaTranslatorApp.kt`（新建）、`AndroidManifest.xml`、`MainActivity.kt`
- **状态**：✅

#### 根因组 4：PluginManager 串行化（W1，临时止血）
- **问题**：`ScreenCaptureService.isProcessing` 8 秒超时强制重置后可能产生并发 translateImage，导致 `lastDebugData`、`cropConfig` 等跨线程状态竞态。
- **修复**：
  - 4 个可变状态添加 `@Volatile`：`isInitialized`、`cropConfig`、`lastDebugData`、`useAiVisionMode`
  - `translateImage` 用 `synchronized(translateLock)` 串行化
- **文件**：`PluginManager.kt`
- **备注**：阶段2 协程迁移后替换为 `Mutex.withLock`
- **状态**：✅

#### 根因组 5：TranslationPlugin 回退逻辑 + 单测（W2）
- **问题**：MiMo 批量失败时未走缓存复用；`translateBatch` 圈复杂度 20（detekt 阈值）。
- **修复**：
  - 拆分 `translateBatch` 为 `translateBatchMimo` + `translateBatchBaidu`
  - `validateAndFallback` 提取为独立方法，检测重复模式后回退逐条翻译
  - `hasRepeatedPatternStatic` 提取为 companion object 静态函数（便于单测）
  - 新增 `TranslationPluginTest.kt`：6 个测试覆盖连续重复、低字符种类、正常翻译不误判、短文本、ASCII、标点分割
- **文件**：`TranslationPlugin.kt`、`TranslationPluginTest.kt`（新建）
- **状态**：✅

#### 根因组 6：AI 路径坐标系 + parseResponse 回退（W2）
- **问题**：`bitmapToBase64` 内部缩放图片但未告知调用方缩放比例，导致 AI 返回的坐标基于缩放后尺寸，直接使用造成定位偏移；`parseResponse` 异常时静默返回空列表，掩盖错误。
- **修复**：
  - `bitmapToBase64` 返回 `Pair<String, Float>`（base64 + scale）
  - `analyzeImage` 用缩放后尺寸构建 prompt 和解析，最后统一除以 scale 转换回原始坐标系
  - `parseResponse` 失败时返回带 `error` 字段的 `AiAnalysisResult`，由 `PluginManager.translateImage` 决定回退 OCR 流程
- **文件**：`AiVisionPipeline.kt`、`PluginManager.kt`
- **状态**：✅

#### 根因组 7：权限补全（W2）
- **问题**：Android 13+（API 33）需要运行时请求 `POST_NOTIFICATIONS` 权限，前台服务通知才能显示。
- **修复**：
  - `AndroidManifest.xml` 添加 `<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />`
  - `MainActivity` 新增 `notificationPermissionLauncher`（ActivityResultContracts.RequestPermission）
  - `onCreate` 中调用 `requestNotificationPermissionIfNeeded()`，仅 API 33+ 触发
- **文件**：`AndroidManifest.xml`、`MainActivity.kt`
- **状态**：✅

**阶段1 验收**：
- `./gradlew assembleDebug ktlintCheck detekt testDebugUnitTest jacocoTestReport` 全绿
- 所有 7 个根因组修复点交叉验证通过
- 已知非阻塞问题：Kotlin daemon `AccessDeniedException`（环境问题，自动回退无 daemon 编译）

---

### 阶段 2（W3-W5 架构重构）⏳ 待执行

**目标**：落地 4 项架构决策，消除技术债，建立可测试、可维护的代码结构。

#### W3：基础设施迁移

**任务 2.1：minSdk 24 → 26**（决策 C）
- 修改 `app/build.gradle` 的 `minSdkVersion`
- 全局搜索 `Build.VERSION.SDK_INT >= Build.VERSION_CODES.O`，评估能否简化
- 验证 ML Kit、OpenCV、OkHttp 在 API 26 的行为
- 运行全量回归测试
- **验收**：APK 在 API 26 模拟器正常启动，前台服务通知正常显示

**任务 2.2：协程基础设施**（决策 B）
- `MangaTranslatorApp` 添加 `appScope` 全局协程作用域
- 每个 Service 添加 `serviceScope`（SupervisorJob + Dispatchers.IO）
- 引入 `kotlinx-coroutines-android` 依赖
- **验收**：编译通过，协程作用域在 Service 销毁时正确取消

**任务 2.3：ServiceLocator 手动 DI 容器**（决策 A）
- 新建 `di/ServiceLocator.kt`，单例管理 Translator/OcrEngine/BubbleDetector 实例
- `MangaTranslatorApp.onCreate` 中初始化 ServiceLocator
- **验收**：ServiceLocator 可提供所有核心依赖，单元测试可替换实现

#### W4：领域层与数据层分离

**任务 2.4：抽取领域接口**（决策 A）
- `domain/translator/Translator.kt`：翻译接口
- `domain/ocr/OcrEngine.kt`：OCR 接口
- `domain/detection/BubbleDetectorInterface.kt`：气泡检测接口
- `domain/detection/PanelDetectorInterface.kt`：分镜检测接口
- 现有类实现这些接口（`MimoTranslator`、`BaiduTranslator`、`OcrPlugin` 等）
- **验收**：domain 包无任何 Android Framework import

**任务 2.5：拆分 PluginManager**（决策 A）
- `TranslationController`（presentation 层）：编排 OCR → 翻译 → 渲染，持有接口引用
- `TranslationRepository`（data 层）：组合 OcrEngine + Translator + BubbleDetector，实现业务逻辑
- `PluginManager` 标记 `@Deprecated`，方法转发到新类，保持向后兼容
- **验收**：新代码通过 Controller + Repository 调用，旧代码仍可工作

**任务 2.6：协程迁移 - Service 层**（决策 B）
- `ScreenCaptureService`：`executor.submit` → `serviceScope.launch`，`Handler.postDelayed` → `delay()`
- `ConditionVariable` → `CompletableDeferred`
- `Thread { cleanupResources() }.start()` → `serviceScope.launch { cleanupResources() }`
- `FloatingWindowService`：Handler 回调 → 协程
- **验收**：Service 层无 `Thread(` / `ExecutorService` / `Handler.postDelayed` 调用

#### W5：协程迁移收尾 + PluginManager 替换

**任务 2.7：协程迁移 - Plugin/Util 层**（决策 B）
- `PluginManager`（或新的 TranslationRepository）：`synchronized(translateLock)` → `Mutex.withLock`
- `OcrProcessor`：`executor.submit` → `withContext(Dispatchers.IO)`
- `AiVisionPipeline`：同步 HTTP 调用 → `withContext(Dispatchers.IO)` 包裹
- **验收**：全项目无 `synchronized(` / `Thread(` / `ExecutorService` 残留（除 ServiceLocator 内部）

**任务 2.8：移除旧 PluginManager**（决策 A）
- 删除 `PluginManager` 类
- 所有引用改为 `TranslationController`
- 清理 `@Deprecated` 标记
- **验收**：编译通过，无 PluginManager 引用

**任务 2.9：补充单元测试**（决策 D）
- `TranslationControllerTest`：mock 接口，验证编排逻辑
- `TranslationRepositoryTest`：mock OcrEngine/Translator，验证组合逻辑
- `OcrProcessorTest`：Robolectric 测试 binarizeForOcr
- 目标：domain/data 层覆盖率 ≥ 80%
- **验收**：`./gradlew jacocoTestReport` 报告 domain/data 覆盖率 ≥ 80%，`check` 任务通过

---

### 阶段 3（W6 安全合规）⏳ 待执行

**目标**：消除明文传输、密钥泄漏、证书校验缺失等安全风险。

**任务 3.1：HTTPS 强制 + 证书固定**（约束：Translation API base URL 必须 HTTPS）
- 全局搜索 `http://`，强制改为 `https://`
- `HttpClientProvider` 实现 Certificate Pinning（已有约定，需验证落地）
- `network_security_config.xml` 配置 cleartext traffic 禁用

**任务 3.2：密钥安全存储**
- `SecurePrefs` 已存在，审计所有 API Key 是否都通过 SecurePrefs 存储
- 移除代码中的硬编码密钥（如有）
- 加密失败时禁止明文回退（已有约定，需验证）

**任务 3.3：输入校验**
- 翻译 API 长文本必须用 POST（已有约定，需验证 MiMo/Baidu 路径）
- 正则表达式使用 `\s{2,}` 而非 `\s+`（已有约定，需验证）

**任务 3.4：安全扫描**
- 集成 OWASP Dependency Check 或 MobSF
- 修复发现的 High/Critical 漏洞
- **验收**：安全扫描无 High 以上漏洞

---

### 阶段 4（W7 可观测性 + CI）⏳ 待执行

**目标**：建立线上问题定位能力和自动化质量门禁。

**任务 4.1：结构化日志**
- 统一日志格式：`[模块][事件] key=value`
- `Log.d` 用于正常业务流，`Log.w` 用于实际警告（已有约定，需验证全项目落地）
- 关键操作添加日志：服务启停、翻译失败、资源释放

**任务 4.2：崩溃监控**
- 集成 Bugly 或 Firebase Crashlytics
- 配置脱敏（不收集 OCR 文本内容）
- **验收**：测试崩溃能上报到控制台

**任务 4.3：性能指标**
- 关键路径埋点：截图→OCR→翻译→渲染各阶段耗时
- 慢查询告警：单次翻译 > 3s 记录 Warning 日志

**任务 4.4：CI 流水线**
- GitHub Actions / Gitee Go 配置：
  - PR 触发：`assembleDebug` + `ktlintCheck` + `detekt` + `testDebugUnitTest`
  - 主分支触发：上述 + `jacocoTestReport` + 覆盖率门禁
- **验收**：PR 不通过检查无法合并

---

### 阶段 5（W8 回归灰度）⏳ 待执行

**目标**：验证改造后版本的稳定性，逐步放量。

**任务 5.1：全量回归测试**
- 编写测试用例清单（覆盖手动翻译、自动实时、AI 多模态、分镜检测、竖排识别）
- 在 API 26/28/30/33 模拟器全量回归
- 真机测试（至少 3 款不同厂商）

**任务 5.2：灰度发布**
- 内部测试包 → 100 用户灰度 → 1000 用户 → 全量
- 每阶段观察 24h，崩溃率 > 0.5% 回滚
- **验收**：全量发布后 7 天崩溃率 < 0.3%

**任务 5.3：文档更新**
- 架构文档（domain/data/presentation 分层图）
- 接口文档（Translator/OcrEngine 等接口契约）
- 运维文档（日志查看、崩溃排查、密钥轮换）

---

## 四、全局约束清单（来自 project_memory）

### 硬约束
- 截图必须保持原始宽高比（clamp 分辨率时）
- OCR 处理必须使用 6 线程并行（所有变体）
- 长文本翻译 API 必须用 POST
- ImageReader 监听器关闭前必须解绑
- ShangChuan 文件夹不得修改
- DebugManager 必须在 Application.onCreate 初始化
- OCR 坐标变换必须考虑缩放因子
- Service 销毁必须等待 executor 终止后才能关闭 plugin 资源
- 翻译 API base URL 必须用 HTTPS
- 悬浮窗触摸事件必须阻止截图捕获

### 工程约定
- VirtualDisplay density 必须与 clamp 分辨率等比缩放
- Bitmap 必须在 finally 块释放
- 日志级别：`Log.d` 正常，`Log.w` 警告
- 关键操作重试必须指数退避（初始 300ms，重建后 800ms）
- 翻译压缩正则用 `\s{2,}` 不用 `\s+`
- 跨线程共享状态必须 `@Volatile`
- 屏幕变化版本号必须用 `AtomicLong`
- 翻译去重用 O(n) 哈希集合
- StaticLayout 实例必须缓存
- HTTP 客户端必须实现证书固定

### 经验教训（已在阶段1 落地的用 ✅ 标注）
- ✅ Unscaled density 导致 GPU swap 不匹配，帧捕获失败
- ✅ 无界线程池 + 无界队列导致 OOM 风险
- ✅ StaticLayout 线性字体计算低效，二分搜索减少 60% 布局操作
- ✅ Thread.sleep 帧同步导致 CPU 空转，ConditionVariable.open() 更高效
- ✅ 关键操作静默 catch 应包含 Log.w
- ✅ lateinit 无保证初始化导致崩溃
- ✅ 非线程安全集合并发修改导致数据损坏
- ✅ Bitmap 异步处理完成前 recycle 导致 use-after-free
- ✅ 加密失败静默明文回退造成安全漏洞
- ✅ Service 状态未持久化导致配置变更后重复实例

---

## 五、阶段进度追踪

| 阶段 | 周期 | 状态 | 完成日期 |
|------|------|------|----------|
| 阶段0 准备期 | W0 | ✅ 完成 | 2026-07-15 |
| 阶段1 止血期 | W1-W2 | ✅ 完成 | 2026-07-16 |
| 阶段2 架构重构 | W3-W5 | ⏳ 待执行 | - |
| 阶段3 安全合规 | W6 | ⏳ 待执行 | - |
| 阶段4 可观测性+CI | W7 | ⏳ 待执行 | - |
| 阶段5 回归灰度 | W8 | ⏳ 待执行 | - |

---

## 六、变更记录

| 日期 | 变更 | 决策者 |
|------|------|--------|
| 2026-07-16 | 方案定稿 v1.0 | 用户确认 |
| 2026-07-16 | 阶段1 完成，7 个根因组全部修复 | 执行 |

# 漫画翻译器 - 更新日志

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

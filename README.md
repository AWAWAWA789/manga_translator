# 漫画翻译器

Android 漫画屏幕翻译工具，目标是实现：

- 屏幕 OCR 识别日文漫画
- 翻译成中文
- 翻译结果覆盖显示在原文位置附近
- 尽量减少干扰，保留原图阅读体验

当前版本：`4.20`

---

## 整体架构

```
MainActivity
  → 启动 ScreenCaptureService（截屏+翻译引擎）
  → 启动 FloatingWindowService（悬浮球+菜单+覆盖层）

ScreenCaptureService
  → 持续截屏 / 一次性截屏
  → 调用 PluginManager.translateImage()
  → 把结果交给 FloatingWindowService 显示

FloatingWindowService
  → 悬浮球（拖拽+点击+长按）
  → 菜单（实时/手动/横竖切换/暂停）
  → TranslationOverlayView（渲染翻译框）
```

---

## 两条处理路径

程序有两条路径，根据条件自动选择：

### 路径A：AI 多模态识别

**触发条件**：手动翻译 + AI开关打开 + MiMo API已配置

```
截图 → 裁剪
→ AiVisionPipeline.analyzeImage()
  → 一次 MiMo Vision API 调用
  → AI 同时完成：气泡检测 + 文字识别 + 翻译 + 输出定位
→ 直接返回 TranslationCard 列表
→ 显示覆盖层
```

**优势**：一次 API 调用完成所有工作，AI 能理解漫画布局，识别不规则气泡。

### 路径B：现有本地流程

**触发条件**：实时翻译 / AI关闭 / MiMo未配置

```
截图 → 裁剪
→ PanelDetector（OpenCV 分镜检测）
→ BubbleDetector（OpenCV 气泡检测）
→ OCR 识别（ML Kit，3~6遍）
→ TextFilter 文本过滤
→ SentenceAssembler 句子组装
→ 翻译（MiMo批量 / 百度逐条）
→ 定位输出框
→ 显示覆盖层
```

**优势**：无需网络，速度快，适合实时翻译。

---

## 完整数据流（路径B 详解）

```
1. 触发翻译
   用户点击悬浮球 / 实时模式画面静止

2. 隐藏UI + 等待 400ms
   避免截屏时截到自己的翻译框

3. 截屏
   imageReader.acquireLatestImage() → imageToBitmap()
   [MediaProjection]

4. 画面变化检测（仅实时模式）
   computePixelHashes() → isFrameChanged()
   [每20像素采样蓝通道，>1.2%变化=画面移动]
   等画面静止 2 帧后才翻译

5. 裁剪漫画区域
   ComicImageCropper.cropComicArea()
   [默认：去掉顶部15%，底部10%]

6. PanelDetector（OpenCV）
   灰度 → 二值化 → 形态学闭合 → 查找轮廓
   → 输出：分镜列表

7. BubbleDetector（OpenCV）
   灰度 → 高斯模糊 → Canny边缘 → 膨胀 → 查找轮廓 → 筛选
   → 输出：气泡列表
   [气泡用于辅助定位，findTrustedBubbleForSentence]

8. OCR 识别
   根据 verticalOnly 参数：
   - 竖向模式：只跑 3 遍（enhanced/raw/binary × 90°旋转）
   - 横向模式：跑全部 6 遍（3版本 × 2方向）

   每遍：
   → ensureMinSize(bitmap, 1100)
   → enhanceForOcr(bitmap, 1.6f)  [灰度+对比度增强]
   → binarizeForOcr(bitmap)  [二值化]
   → ML Kit JapaneseTextRecognizer 识别
   → mapRectBack() 坐标还原
   → deduplicateResults() 去重

   OcrPlugin 层：
   → 过滤空白、低置信度
   → 按宽高比判断 isVertical（height > width * 1.5）

9. TextFilter 文本过滤
   → 空白、长度<2、纯标点/字母
   → UI文字黑名单（暂停翻译、实时翻译等）
   → 已翻译中文检测（纯汉字>16字可能是上一轮污染）

10. 覆盖层过滤
    跳过与上次翻译框重叠 >25% 的块

11. SentenceAssembler 句子组装
    → 过滤不可靠块
    → 分离竖向/横向
    → 竖向：按X坐标从右到左分列 → 同列按Y排序 → 按间距/语法合并
    → 横向：按Y坐标分行 → 同行按X排序 → 按间距/语法合并
    → 合并判断：间距、句号结束、日文助词连续、长度限制

12. 区域构建（buildSentenceFirstRegions）
    每个句子组：
    → dedupeBlocksInBubble() 去重
    → 找可信气泡（trustedBubbleScore ≥ 0.45）
    → 选锚点块（最居中的 OCR 块）
    → 以锚点为中心估算输出框
    → 约束到气泡/边距范围内

13. 重叠抑制（suppressOverlappingRegions）
    输出框重叠 >55% → 保留质量更高的

14. 翻译
    TranslationPlugin.translateBatch()
    → MiMo 批量翻译（一次API调用多条）
    → 如果失败 → 百度逐条翻译
    → 质量验证 + 重复模式检测 + 缓存

15. 坐标缩放
    从裁剪坐标 → 全屏坐标（scaleFactor）

16. 显示
    FloatingWindowService.showTranslationsWithDebug()
    → 过滤：空白、过短、纯标点、翻译失败
    → 去重：Levenshtein文本相似度 + IoU空间重叠
    → 限制最多 16 个
    → 创建 TranslationOverlayView（全屏半透明层）
      → 按行聚类，行内从右到左排序
      → 逐个放置，跟踪已占用空间
      → 计算字体大小（13sp→6.5sp逐步缩小）
      → 竖向：逐字绘制，自动换列
      → 横向：StaticLayout 自动换行
      → 重叠时轻推避让（竖向左右推，横向上下推）
      → 绘制：白色遮罩 + 圆角卡片 + 文字
    → 15秒后自动移除

17. 恢复UI
```

---

## AI 多模态识别（路径A 详解）

### 工作原理

新建 `AiVisionPipeline.kt`，一次 MiMo Vision API 调用同时完成：

1. **气泡检测** - 返回像素坐标
2. **文字识别** - 返回日文原文
3. **翻译** - 返回中文译文
4. **竖横判断** - 返回 `is_vertical`
5. **阅读顺序** - 返回 `reading_order`

### Prompt

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

坐标基于图片尺寸 ${width}x${height}。
如果图片中没有对话气泡，返回 {"bubbles": []}。
仔细识别所有气泡，包括小的、不规则形状的。
翻译要自然流畅，保留漫画对白的语气。
```

### AI 返回示例

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

### 容错设计

- MiMo API 未配置 → 走现有流程
- AI 调用失败（网络超时、JSON解析错误）→ 捕获异常，回退现有流程
- AI 返回空气泡 → 返回空列表
- 坐标超出图片范围 → clamp 到合法范围

---

## 文件结构

```
app/src/main/java/com/manga/translator/
├── MainActivity.kt                    # 主入口，权限请求，服务启动
├── SettingsActivity.kt                # 设置页面，API 配置，AI 开关
├── debug/
│   ├── DebugOverlayConfig.kt          # 调试覆盖层配置
│   └── DebugOverlayData.kt            # 调试覆盖层数据
├── model/
│   ├── OcrBlock.kt                    # OCR 文本块（text, rect, confidence, isVertical）
│   └── TranslationCard.kt            # 翻译卡片（originalText, translatedText, sourceRect, isVertical）
├── ocr/
│   └── OcrProcessor.kt               # ML Kit OCR，多版本识别+去重+坐标修正
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

## 关键类职责

### PluginManager.kt（533行）- 主流程编排

```
translateImage(bitmap, lastTranslationRects, verticalOnly, isManual)
  ├─ AI路径：AiVisionPipeline.analyzeImage() → 直接返回
  └─ 现有路径：
      ├─ PanelDetector.detectPanels()
      ├─ BubbleDetector.detectBubbles()
      ├─ OcrPlugin.recognize()
      ├─ TextFilter 过滤
      ├─ translateCore()
      │   ├─ buildSentenceFirstRegions()
      │   │   ├─ dedupeBlocksInBubble()
      │   │   ├─ SentenceAssembler.assemble()
      │   │   ├─ findTrustedBubbleForSentence()
      │   │   ├─ pickAnchorBlock()
      │   │   ├─ estimateRectByAnchor()
      │   │   └─ constrainRectToAnchor()
      │   ├─ suppressOverlappingRegions()
      │   └─ limitRegions()
      └─ TranslationPlugin.translateBatch()
```

### AiVisionPipeline.kt（248行）- AI 多模态

```
analyzeImage(bitmap, width, height)
  ├─ bitmapToBase64()  [JPEG 85%]
  ├─ buildPrompt()  [漫画分析 Prompt]
  ├─ callMimoVisionApi()  [HTTP POST]
  └─ parseResponse()  [JSON解析 + 坐标clamp]

toTranslationCards(result, lastTranslationRects)
  ├─ 按 reading_order 排序
  ├─ 过滤无效结果
  ├─ 过滤重叠
  └─ 转为 TranslationCard
```

### OcrProcessor.kt（390行）- OCR 识别

```
recognizeText(bitmap, verticalOnly)
  ├─ 裁剪（CROP_TOP/BOTTOM_RATIO）
  ├─ 缩放（ensureMinSize 1100）
  ├─ 图像增强（enhanceForOcr 1.6f）
  ├─ 二值化（binarizeForOcr）
  ├─ 根据 verticalOnly 选择跑几遍：
  │   ├─ verticalOnly=true：3遍（90°旋转）
  │   └─ verticalOnly=false：6遍（0°+90°）
  ├─ 每遍：recognizeWithRotation()
  │   ├─ 旋转图片
  │   ├─ ML Kit JapaneseTextRecognizer
  │   ├─ 同步等待（CountDownLatch 15s）
  │   ├─ 遍历 TextBlock → Line → Element
  │   ├─ 合并同一 Block 的 Lines
  │   ├─ 过滤 UI 文字
  │   └─ mapRectBack() 坐标还原
  ├─ deduplicateResults() 逐层去重
  └─ 坐标缩放回原图
```

### SentenceAssembler.kt（175行）- 句子组装

```
assemble(blocks)
  ├─ 过滤不可靠块
  ├─ 分离竖向/横向
  ├─ assembleVertical()
  │   ├─ 按X坐标从右到左分列
  │   └─ splitVerticalColumn()：按间距/语法合并
  ├─ assembleHorizontal()
  │   ├─ 按Y坐标分行
  │   └─ splitHorizontalRow()：按间距/语法合并
  └─ 过滤非句子（isSentenceLike）

合并判断（canJoinVertical/Horizontal）：
  ├─ 前一句是否以句号结束
  ├─ 间距是否在合理范围
  ├─ X/Y 对齐是否足够
  ├─ 组高度/宽度是否超限
  ├─ 合并后长度是否超限（竖46/横42）
  └─ 日文助词连续判断（shouldContinueJapanese）
```

### TranslationOverlayView.kt（520行）- 覆盖层渲染

```
setTranslations(items) → onDraw()

buildLayout()
  ├─ sortByReadingOrder()：按行聚类，行内从右到左
  ├─ 逐个放置：
  │   ├─ normalizeSourceRect()：扩展边距
  │   ├─ constrainDisplayRect()：限制屏幕比例
  │   ├─ placeHorizontalText() / placeVerticalText()
  │   │   ├─ 计算字体大小（13sp→6.5sp逐步缩小）
  │   │   ├─ 竖向：逐字绘制，自动换列
  │   │   └─ 横向：StaticLayout 自动换行
  │   └─ nudgeAwayFromOccupied()：重叠时轻推避让
  └─ 记录已占用空间

onDraw()
  ├─ 绘制调试层（气泡框、OCR框、阅读顺序）
  └─ 逐个绘制翻译卡片
      ├─ 白色遮罩
      ├─ 圆角卡片背景
      ├─ 边框
      └─ 文字
```

---

## 当前参数

| 参数 | 值 | 说明 |
|------|-----|------|
| 版本号 | `4.10` | versionName |
| versionCode | `410` | Android 构建版本 |
| 默认识别方向 | 竖向 | `RecognitionDirection.VERTICAL` |
| 最大翻译区域 | 18 | `MAX_TRANSLATION_REGIONS` |
| OCR最小尺寸 | 1100 | 小图放大 |
| OCR最大尺寸 | 1600 | 预处理尺寸上限 |
| OCR增强对比度 | 1.6 | 灰度+对比度增强 |
| 覆盖层自动消失 | 15s | `AUTO_REMOVE_MS` |
| 截屏前隐藏等待 | 400ms | 避免识别旧翻译框 |
| 画面变化阈值 | 1.2% | 像素哈希变化比例 |
| 静止帧数要求 | 2帧 | 连续静止才翻译 |
| 最大合并字数 | 竖46/横42 | SentenceAssembler |
| 锚点评分阈值 | 0.45 | 可信气泡最低分 |
| 重叠抑制阈值 | 0.55 | 输出框IoU |
| AI max_tokens | 4096 | MiMo Vision API |
| AI temperature | 0.1 | 低随机性 |

---

## 设置说明

### 基本配置

- **默认翻译器**：MiMo AI（推荐）/ 百度翻译
- **百度翻译**：需要 APP ID + 密钥
- **MiMo AI**：需要 API Key，可选 Base URL 和 Model

### AI 多模态识别

开关在设置页面 "AI多模态识别" 卡片：

- **开启条件**：MiMo API 已配置
- **工作方式**：手动翻译时调用 AI Vision API，一次完成识别+翻译+定位
- **实时翻译**：仍走本地流程（OCR+组句+翻译），保证速度
- **注意**：每次手动翻译都会调用 AI API，速度取决于网络和模型

---

## 日志查看

建议使用 Android Studio Logcat 搜索：

```text
PluginManager
```

重点日志：

```text
=== PluginManager v3 已加载 ===
OCR识别: N 个块
文本过滤后: N 个块
覆盖层过滤后: N 个块
句子优先组装: N块→去重N块→M句
=== 句子优先: 输入N块, 句子M个, 去重K, 最终R区域 ===
```

AI 路径日志：

```text
使用AI多模态识别
AI多模态: N 个翻译结果
```

AI 失败日志：

```text
AI多模态失败，回退现有流程: 错误信息
```

---

## 已知限制

1. OCR 本身仍可能识别错误，如错字、乱码、注音混入
2. `SentenceAssembler` 仍需针对不同漫画版式继续调阈值
3. 气泡检测当前只起辅助定位作用，输出框贴近原文文字而不是严格贴合气泡
4. 翻译框允许轻微重叠，当前策略是重叠时轻推避让
5. 对大跨页、多分镜、复杂背景的稳定性仍需优化
6. AI 多模态每次调用需要 2-5 秒，不适合实时模式
7. AI 返回的坐标精度取决于模型质量

---

## 安装与使用

### 1. 用 Android Studio 打开项目

```text
mobile/android-native
```

### 2. 准备权限

需要开启：

- 悬浮窗权限
- 屏幕录制/截图权限
- 后台运行权限
- 网络权限
- MIUI/国产系统需允许后台运行、电池不限制

### 3. 启动应用

1. 编译运行 Android App
2. 授权相关权限
3. （可选）在设置中配置 MiMo API Key + 开启 AI 多模态识别
4. 点击悬浮球选择翻译模式
5. 打开漫画应用，等待自动识别和翻译

### 4. 悬浮球操作

- **短按**：触发翻译（手动模式）
- **长按**：打开菜单
- **拖拽**：移动悬浮球位置

### 5. 菜单功能

```
┌─────────────────┐
│ 实时翻译 │ 手动翻译 │
├─────────────────┤
│ 横向识别 │ 竖向识别 │
├─────────────────┤
│ 暂停翻译 │ 关闭菜单 │
└─────────────────┘
```

- **实时翻译**：画面静止后自动翻译
- **手动翻译**：点击后立即翻译一次
- **横向识别**：跑全部 6 遍 OCR
- **竖向识别**：只跑 3 遍竖向 OCR
- **暂停翻译**：暂停所有翻译

### 6. 悬浮球颜色

| 颜色 | 模式 |
|------|------|
| 绿色渐变 | 实时翻译 |
| 紫色渐变 | 手动翻译 |
| 灰色渐变 | 暂停模式 |

---

## 版本历史

| 版本 | 主要变化 |
|------|----------|
| v4.10 | AI 多模态全流程识别 + 大规模代码清理（删除7个文件/1000+行死代码）+ 逻辑修复 |
| v4.00 | 句子优先流程 + 坐标修正（mapRectBack 90°/270°）+ 覆盖层轻推避让 |
| v3.00 | 悬浮球菜单重设计 + 菜单文本修复 |
| v2.90 | 基础功能：截屏+OCR+翻译+覆盖层+悬浮球 |

---

## OpenCV 集成说明

如果需要集成 OpenCV Android SDK，可参考：

```text
mobile/android-native/OPENCV_SETUP.md
```

核心步骤：

1. 下载 OpenCV Android SDK
2. 拷贝 `jni/`、`libs/`、`java/` 到项目 `opencv/`
3. 按需在 `build.gradle` 中配置依赖
4. 执行构建验证

---

## 许可证

本项目仅供学习和个人使用。

## 致谢

- Google ML Kit
- MiMo AI
- 百度翻译
- OpenCV

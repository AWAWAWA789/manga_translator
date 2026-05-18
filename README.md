# 漫画翻译器

Android 漫画屏幕翻译工具，目标是实现：

- 屏幕 OCR 识别
- 日文漫画翻译
- 翻译结果覆盖显示在原文位置附近
- 尽量减少干扰，保留原图阅读体验

当前版本：`4.00`

## 当前状态

- 默认识别方向：竖向
- 截图方式：`MediaProjection`
- OCR：ML Kit Japanese OCR
- 翻译：MiMo 后端 + 百度翻译
- 显示：悬浮窗 + 全局覆盖
- 数据检测：临时关闭定位，仅保留为后续功能/调试用途

## 当前流程

```text
屏幕截图
→ 裁剪有效区域（顶部15%，底部10%）
→ OCR 识别模块
  → raw / enhanced / binary 三种版本
  → 原图 + 90° 旋转 OCR
  → 坐标反转 mapRectBack(90°/270°)
  → OCR 去重
→ TextFilter 文本过滤
→ SentenceAssembler 句子合并
  → 方向推断
  → 按阅读顺序排序
  → 基于空白、标点、语法判断是否合并
→ 翻译模块
→ TranslationOverlayView 按原 textRect / anchorBlock 显示
```

## 核心改动点

### 1. 以识别准确率为先

早期使用 OpenCV 进行复杂裁剪/定位时，容易因为边缘、背景、边框干扰导致误检：

- 真实 3 张图可能识别出 20+ 个碎片
- 底部边框、对白框、背景线条容易被误识别
- 复杂规则会让整体稳定性变差

当前版本优先采用：

- OCR 结果去重
- SentenceAssembler 合并句子
- 数据检测时临时关闭定位
- 翻译框尽量跟随原始 `textRect / anchorBlock`

### 2. 坐标修正

- `OcrProcessor.mapRectBack(90°/270°)` 用于修正旋转 OCR 后的坐标
- `SentenceAssembler` 负责把零碎识别结果拼成句子
- 识别时临时关闭某些定位逻辑，避免坐标偏移
- 通过原始 `textRect / anchorBlock` 做结果对齐

### 3. 显示策略

- 句子级显示，避免逐字碎片化
- `suppressOverlappingRegions` 基于 `outputRect` 做去重，不直接依赖 `textRect`
- 翻译文本尽量贴近原文区域
- 横向模式和竖向模式分别处理阅读顺序

## 主要文件

```text
mobile/android-native/app/src/main/java/com/manga/translator/
├── MainActivity.kt
├── service/
│   ├── FloatingWindowService.kt        # 悬浮球、菜单、默认识别模式
│   ├── ScreenCaptureService.kt         # MediaProjection 截图与处理管线
│   └── TranslationOverlayView.kt       # 翻译窗口显示/覆盖
├── ocr/
│   └── OcrProcessor.kt                 # ML Kit OCR、预处理、旋转、坐标修正
├── plugin/
│   ├── PluginManager.kt                # 插件管理
│   ├── SentenceAssembler.kt            # OCR 结果句子合并
│   ├── OcrPlugin.kt
│   ├── BubbleDetector.kt               # 气泡/区域检测
│   └── PanelDetector.kt
├── translation/
│   ├── TranslationPlugin.kt
│   ├── MimoTranslator.kt
│   └── BaiduTranslator.kt
└── util/
    ├── TextFilter.kt
    └── ComicImageCropper.kt
```

## 当前参数

| 项目 | 值 | 说明 |
|---|---:|---|
| 版本号 | `4.00` | `versionName` |
| versionCode | `400` | Android 构建版本 |
| 默认识别方向 | 竖向 | `RecognitionDirection.VERTICAL` |
| 最大翻译区域数 | 18 | `MAX_TRANSLATION_REGIONS` |
| OCR 最小尺寸 | 1100 | 小图放大 |
| OCR 最大尺寸 | 1600 | 预处理尺寸上限 |
| OCR 对比度增强 | 1.6 | 灰度 + 对比度增强 |
| 自动移除时间 | 15s | `AUTO_REMOVE_MS` |
| 翻译框等待时间 | 400ms | 等待识别完成后再显示 |

## 日志查看

建议使用 Android Studio Logcat 搜索：

```text
PluginManager
```

重点关注：

```text
=== PluginManager v2 已加载 ===
OCR识别: N 条
文本过滤后: N 条
气泡检测后: N 条
翻译结果安装: N个去重N个关联M个
=== 处理完成: 输入N条, 数据定位已关闭, 输出M条, 去重K, 合并R句 ===
```

定位相关日志示例：

```text
锚点选择: textRect=... anchor='...' rect=... conf=...
合并句子: text='...' anchor=[x,y] textRect=... out=... blocks=...
```

## 当前已知限制

1. OCR 本身仍可能识别错误，如错字、乱码、注音混入。
2. `SentenceAssembler` 仍需要针对不同漫画版式继续调阈值。
3. 气泡检测当前不参与定位，因此输出框贴近原文文字，而不是完全填满原文气泡。
4. 翻译框允许轻微重叠；当前策略是重叠时轻推，避免完全挡住文字。
5. 对大跨页、多分镜、复杂背景的稳定性仍需继续优化。

## 下一步优化方向

- 改进 `SentenceAssembler`：减少跨句合并和碎句。
- 增加“可信气泡”辅助定位：只在气泡数量合理且与 OCR 高度重叠时启用。
- 优化覆盖层布局：在不远离原文的前提下减少遮挡。
- 加强 OCR 质量过滤：过滤注音、小字、乱码、背景误识别。

## 安装与使用

### 1. 用 Android Studio 打开项目

打开：

```text
mobile/android-native
```

### 2. 准备权限

需要开启：

- 悬浮窗权限
- 屏幕录制/截图权限
- 后台运行权限
- 网络权限

### 3. 启动应用

- 编译运行 Android App
- 授权相关权限
- 点击悬浮球选择翻译模式
- 打开漫画应用，等待自动识别和翻译

## Android 原生版功能

### 功能特点

- 实时屏幕翻译
- 每句话单独识别，不合并
- 翻译文本覆盖在原文位置
- 只显示中文翻译，不显示日文原文

### 翻译模式

- 实时翻译：画面静止后自动翻译
- 手动翻译：最高优先级，立即执行
- 暂停模式：暂停所有翻译

### 显示模式

- 横向模式：适合横排文本
- 竖向模式：适合竖排漫画

### 悬浮球菜单

- 实时翻译
- 手动翻译
- 暂停翻译
- 横向模式 / 竖向模式切换

### 悬浮球颜色

| 颜色 | 模式 |
|---|---|
| 绿色渐变 | 实时翻译 |
| 紫色渐变 | 手动翻译 |
| 灰色渐变 | 暂停模式 |

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

## OCR 优化方向

可参考：

```text
mobile/android-native/OCR_OPTIMIZATION.md
```

其中提到的方案：

- 优化当前 ML Kit 识别
- 使用 manga-ocr 作为后端服务
- 使用 manga-image-translator 作为完整方案

## 目录结构

```text
android-native/
├── app/
│   ├── src/main/
│   │   ├── java/com/manga/translator/
│   │   ├── res/
│   │   └── AndroidManifest.xml
│   └── build.gradle
├── build.gradle
├── settings.gradle
└── README.md
```

## 版本历史

### v4.00
- 当前主版本
- 重点优化 OCR 稳定性、句子合并与显示逻辑

### v2.3
- 改进 OCR 识别：按气泡整体识别，合并同一气泡中的文本
- 改进翻译框位置：定位在气泡正上方
- 实现竖向模式从右到左、从上到下的读取顺序
- 优化手动/实时翻译模式

### v2.2
- 修复翻译框位置不准确问题
- 修复文字显示不全问题
- 修复翻译框重叠问题
- 优化手动模式
- 优化实时翻译灵敏度

### v2.1
- 接入后端 API 翻译
- 支持配置后端地址

### v2.0
- 每句话单独识别、单独翻译
- 精准覆盖在原文位置
- 只输出中文翻译
- OCR 区域限制
- UI 文本过滤
- 横竖模式切换
- 双击关闭翻译窗口

### v1.5
- 气泡级 OCR 识别
- 智能悬浮窗
- 手动翻译最高优先级
- 画面变化检测优化

### v1.0
- 基础屏幕截图功能
- OCR 日文识别
- 百度翻译 API 集成
- 悬浮窗显示

## 后端说明

历史后端实现位于：

```text
backup/old_backend
```

主要能力包括：

- FastAPI 接口
- 图片 OCR / 翻译
- 百度翻译文本翻译
- 调用 manga-image-translator 服务

依赖文件：

```text
backup/old_backend/requirements.txt
```

## 许可证

本项目仅供学习和个人使用。

## 致谢

- Google ML Kit
- 百度翻译
- manga-image-translator

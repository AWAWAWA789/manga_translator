# 漫画翻译器 - Android 原生版

当前版本：`4.20`

手机端独立运行的漫画翻译工具。支持两种模式：AI 多模态识别（一次 API 调用完成所有工作）和本地 OCR 流程（无需网络）。

## 功能特点

- 实时翻译：画面静止后自动翻译
- 手动翻译：点击后立即翻译一次
- AI 多模态识别：MiMo Vision API 一次性完成气泡检测+文字识别+翻译+定位
- 本地 OCR 流程：ML Kit 日文识别 + 句子组装 + 翻译
- 支持横向/竖向识别切换
- 翻译框重叠时轻推避让
- 悬浮球拖拽交互

## 两条处理路径

### 路径A：AI 多模态（手动翻译 + AI开启 + MiMo已配置）

```
截图 → 裁剪 → AiVisionPipeline.analyzeImage()
→ 一次 MiMo Vision API 调用
→ 直接返回：气泡位置 + 日文原文 + 中文翻译 + 竖横判断 + 阅读顺序
→ 显示覆盖层
```

### 路径B：现有本地流程（实时翻译 / AI关闭 / MiMo未配置）

```
截图 → 裁剪 → PanelDetector(OpenCV) → BubbleDetector(OpenCV)
→ OCR识别(ML Kit, 3~6遍) → TextFilter → SentenceAssembler组句
→ 翻译(MiMo批量/百度逐条) → 定位输出框 → 显示覆盖层
```

## 主要文件

```text
app/src/main/java/com/manga/translator/
├── MainActivity.kt                    # 主入口，权限请求
├── SettingsActivity.kt                # 设置页面，API 配置，AI 开关
├── model/
│   ├── OcrBlock.kt                    # OCR 文本块
│   └── TranslationCard.kt            # 翻译卡片
├── ocr/
│   └── OcrProcessor.kt               # ML Kit OCR，多版本识别+去重+坐标修正
├── plugin/
│   ├── AiVisionPipeline.kt           # AI 多模态全流程（新增）
│   ├── BubbleDetector.kt             # OpenCV 气泡检测
│   ├── OcrPlugin.kt                  # OCR 适配层
│   ├── PanelDetector.kt              # OpenCV 分镜检测
│   ├── PluginManager.kt              # 主流程编排（核心，533行）
│   └── SentenceAssembler.kt          # OCR块→句子组装
├── service/
│   ├── FloatingWindowService.kt      # 悬浮球、菜单、覆盖层渲染
│   ├── ScreenCaptureService.kt       # 截屏、画面变化检测、流程编排
│   └── TranslationOverlayView.kt     # 翻译框布局、渲染、避让
├── translation/
│   ├── BaiduTranslator.kt            # 百度翻译 API
│   ├── MimoTranslator.kt             # MiMo 翻译 API
│   └── TranslationPlugin.kt          # 翻译编排
└── util/
    ├── ComicImageCropper.kt           # 图片裁剪
    ├── OpenCVHelper.kt               # OpenCV 初始化
    └── TextFilter.kt                 # 文本过滤工具
```

## 调试日志

```text
PluginManager  # 主流程日志
AiVisionPipeline  # AI 多模态日志
```

重点日志：

```text
=== PluginManager v3 已加载 ===
使用AI多模态识别
AI多模态: N 个翻译结果
句子优先组装: N块→去重N块→M句
=== 句子优先: 输入N块, 句子M个, 去重K, 最终R区域 ===
```

## 权限

- 悬浮窗权限
- 屏幕录制/截屏授权
- 后台运行权限
- 网络权限
- MIUI/国产系统需允许后台运行、电池不限制

## 已知限制

- AI 多模态每次调用需要 2-5 秒，不适合实时模式
- OCR 仍可能受背景、字形、注音影响
- 句子组装仍需根据不同漫画版式继续调阈值
- 翻译框是"尽量靠近原文"，不是严格贴合气泡

## v4.20 更新

- 手动翻译修复：后台线程化，不再阻塞主线程
- 实时翻译修复：覆盖层更新不替换，失败不清除旧翻译
- 自动移除延长：15秒 → 30秒
- 动画系统：翻译框淡入淡出、菜单滑入滑出
- UI美化：悬浮球胶囊形状、翻译框阴影+蓝色竖条
- 性能优化：线程池、OCR并行化、Bitmap采样优化
- 状态文字：翻译/实时/暂停/识别中/完成/无结果/失败

## v4.10 更新

- AI 多模态全流程识别
- 大规模代码清理（删除7个文件/1000+行死代码）
- 识别方向修复：竖向模式只跑3遍，不再强制标记
- 气泡定位修复：不再写死空列表
- 黑名单统一
- 默认模型修复

---

## v2.9 旧版说明

以下保留原来的旧版说明作为历史参考。

### 核心功能
- 实时屏幕翻译
- 每句话单独识别、单独翻译
- 翻译文本覆盖在原文位置
- 只输出中文翻译

### 翻译模式
- 实时翻译：画面静止后自动翻译
- 手动翻译：最高优先级，立即执行
- 暂停模式：暂停所有翻译

### 显示模式
- 横向模式：适合横排文本
- 竖向模式：适合竖排漫画

### 悬浮球菜单
```
┌─────────────────┐
│ 实时翻译 │ 手动翻译 │
├─────────────────┤
│ 横向识别 │ 竖向识别 │
├─────────────────┤
│ 暂停翻译 │ 关闭菜单 │
└─────────────────┘
```

### 悬浮球颜色
| 颜色 | 模式 |
|------|------|
| 绿色渐变 | 实时翻译 |
| 紫色渐变 | 手动翻译 |
| 灰色渐变 | 暂停模式 |

### 技术架构
```
MainActivity → 权限请求与服务控制
ScreenCaptureService → 截屏 + 画面变化检测 + OCR + 翻译
FloatingWindowService → 悬浮球 + 菜单 + 翻译窗口
OcrProcessor → ML Kit OCR + 预处理 + 坐标修正
TranslationPlugin → MiMo/百度翻译 + 缓存
```

### OCR 优化
- 多角度识别：原图(横向) + 90°旋转(竖向)
- 智能去重：重叠>70% 或文本相似>55%
- 区域限制：顶部15%、底部10%
- UI文本过滤

### 翻译效果

横排文本：
```
原文：こんにちは世界 → 翻译：你好世界（在气泡正上方）
```

竖排文本：
```
原文：こ│ん│ば│ん → 翻译：你│好│世│界（从右到左）
```

### 使用步骤
1. Android Studio 打开 `mobile/android-native`
2. 编译运行
3. 授权权限
4. 点击悬浮球选择翻译模式
5. 打开漫画应用

### 故障排除
- 翻译不显示：检查权限、后端配置
- 悬浮窗不显示：允许悬浮窗权限
- 服务被杀：关闭电池优化、锁定最近任务
- 位置不准：确保文字清晰、避免干扰

### 版本历史
- v2.3：改进OCR识别、翻译框位置
- v2.2：修复位置/显示/重叠问题
- v2.1：接入后端API
- v2.0：单独识别+翻译+覆盖显示
- v1.5：气泡级OCR+智能悬浮窗
- v1.0：基础截屏+OCR+翻译

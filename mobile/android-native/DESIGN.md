# 漫画屏幕翻译器 - Android原生版

## 需求分析
实时读取屏幕中的漫画日文，翻译后在原文旁边显示翻译内容。

## 技术方案

### 1. 屏幕截图
- 使用 **MediaProjection API**（Android 5.0+）
- 需要用户授权屏幕录制权限
- 定时截图或手动触发

### 2. OCR识别
- 使用 **Google ML Kit Text Recognition**（推荐）
- 支持日文识别
- 支持竖排文本
- 本地处理，无需网络

### 3. 翻译功能
- 调用 **小米MiMo API**（国内可访问）
- 支持批量翻译
- 缓存翻译结果

### 4. 悬浮窗显示
- 使用 **WindowManager** 添加悬浮窗
- 半透明背景，不遮挡原内容
- 可拖动、可关闭
- 显示翻译结果在原文旁边

## 权限要求
1. `SYSTEM_ALERT_WINDOW` - 悬浮窗权限
2. `FOREGROUND_SERVICE` - 前台服务
3. `MEDIA_PROJECTION` - 屏幕录制
4. `INTERNET` - 网络请求
5. `READ_EXTERNAL_STORAGE` - 存储读取

## 核心组件
1. **ScreenCaptureService** - 前台服务，负责屏幕截图
2. **OcrProcessor** - OCR识别处理
3. **TranslationManager** - 翻译管理
4. **FloatingWindowService** - 悬浮窗服务
5. **MainActivity** - 主界面，权限申请

## 工作流程
1. 用户启动应用，申请权限
2. 启动屏幕截图服务
3. 定时截图（如每2秒）
4. 对截图进行OCR识别
5. 识别日文文本
6. 调用翻译API
7. 在悬浮窗显示翻译结果
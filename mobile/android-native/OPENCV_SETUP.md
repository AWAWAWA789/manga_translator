# OpenCV Android SDK 集成说明

## 下载步骤

1. 访问 OpenCV 官方发布页面：
   https://github.com/opencv/opencv/releases

2. 下载 OpenCV 4.8.0 Android SDK：
   https://github.com/opencv/opencv/releases/download/4.8.0/opencv-4.8.0-android-sdk.zip

3. 解压下载的文件

4. 将解压后的 `OpenCV-android-sdk/sdk/native/` 目录下的内容复制到项目的 `opencv/` 目录：
   - `jni/` → `opencv/jni/`
   - `libs/` → `opencv/libs/`
   - `java/` → `opencv/java/`

5. 确保目录结构如下：
   ```
   opencv/
   ├── build.gradle
   ├── jni/
   │   ├── arm64-v8a/
   │   ├── armeabi-v7a/
   │   ├── x86/
   │   └── x86_64/
   ├── libs/
   └── java/
   ```

## 备选方案：使用 OpenCV Maven 依赖

如果上述方法太复杂，可以尝试使用以下 Maven 依赖：

```gradle
// 在 build.gradle 中添加
implementation 'org.opencv:opencv-android:4.8.0-0'
```

## 验证集成

集成后，运行以下命令验证：

```bash
./gradlew :app:assembleDebug
```

如果成功，说明 OpenCV 已正确集成。

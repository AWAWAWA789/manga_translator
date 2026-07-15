# 漫画OCR识别优化方案

## 当前问题

Google ML Kit对漫画日文识别存在以下问题：
1. **竖排文本**识别差
2. **气泡内文字**识别困难
3. **特殊字体**识别不好
4. **背景干扰**影响识别

## 优化方案

### 方案1：优化当前ML Kit（简单）

调整识别参数和预处理：

```kotlin
// 提高分辨率
screenWidth = minOf(metrics.widthPixels, 1080)
screenHeight = minOf(metrics.heightPixels, 1920)

// 图片预处理：增强对比度
val processedBitmap = enhanceContrast(bitmap)
```

### 方案2：使用Manga-OCR（推荐）

GitHub上有一个专门针对漫画的OCR项目：

**manga-ocr** - https://github.com/kha-white/manga-ocr
- 专门针对日语漫画优化
- 支持竖排文本
- 星标：2.6k+

**特点：**
- 基于Vision Transformer
- 专门训练日语漫画数据集
- 支持竖排文本识别
- 准确率高

**使用方式：**
需要部署为API服务，Android通过HTTP调用。

### 方案3：使用manga-image-translator

**manga-image-translator** - https://github.com/zyddnys/manga-image-translator
- 完整的漫画翻译解决方案
- 包含文本检测、OCR、翻译、文本渲染
- 星标：9.8k+

**特点：**
- 一站式解决方案
- 支持多种OCR引擎
- 支持文本检测和擦除
- 支持多种翻译API

**使用方式：**
部署为API服务，Android通过HTTP调用。

## 推荐实现

### 使用manga-ocr作为后端

1. **部署manga-ocr服务**

```python
# manga_ocr_server.py
from manga_ocr import MangaOcr
from flask import Flask, request, jsonify
from PIL import Image
import io

app = Flask(__name__)
mocr = MangaOcr()

@app.route('/ocr', methods=['POST'])
def ocr():
    if 'image' not in request.files:
        return jsonify({'error': 'No image'}), 400
    
    image_file = request.files['image']
    image = Image.open(io.BytesIO(image_file.read()))
    
    # 识别文本
    text = mocr(image)
    
    return jsonify({'text': text})

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000)
```

2. **Android调用**

```kotlin
private fun callMangaOcrApi(bitmap: Bitmap): String {
    val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    val outputStream = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
    val imageBytes = outputStream.toByteArray()
    
    val requestBody = MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .addFormDataPart(
            "image", 
            "image.jpg",
            imageBytes.toRequestBody("image/jpeg".toMediaType())
        )
        .build()
    
    val request = Request.Builder()
        .url("http://your-server:5000/ocr")
        .post(requestBody)
        .build()
    
    val response = client.newCall(request).execute()
    val responseBody = response.body?.string() ?: ""
    
    return JSONObject(responseBody).getString("text")
}
```

## 简单优化（无需额外服务）

如果不想部署额外服务，可以优化当前ML Kit：

1. **提高截图分辨率**
2. **图片预处理**（增强对比度、去噪）
3. **调整识别参数**
4. **添加竖排文本检测**

## 建议

1. **短期**：优化当前ML Kit参数
2. **中期**：部署manga-ocr服务
3. **长期**：使用manga-image-translator完整方案

需要我帮你实现哪个方案？

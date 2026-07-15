# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# 保留调试堆栈行号
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ==================== Gson ====================
# Gson 使用反射，需要保留数据类
-keep class com.manga.translator.translation.BaiduTranslator$* { *; }
-keep class com.manga.translator.translation.MimoTranslator$* { *; }
-keep class com.manga.translator.plugin.AiVisionPipeline$* { *; }
-keep class com.manga.translator.model.** { *; }
-keep class com.google.gson.annotations.SerializedName { *; }

# Gson 通用规则
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn com.google.gson.**

# ==================== ML Kit ====================
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# ==================== OpenCV ====================
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**
-keep class com.quickbirdstudios.** { *; }
-dontwarn com.quickbirdstudios.**

# ==================== AndroidX Security (EncryptedSharedPreferences) ====================
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# ==================== OkHttp ====================
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ==================== Retrofit ====================
-keep class retrofit2.** { *; }
-dontwarn retrofit2.**

# ==================== Kotlin 协程 ====================
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

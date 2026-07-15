package com.manga.translator.util

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 共享 OkHttpClient 工厂
 * OkHttp 官方建议共享单一 Client 实例以复用连接池/线程池
 * 翻译器按用途创建两个实例：文本翻译（短超时）和 Vision API（长超时）
 */
object HttpClientProvider {

    /** 文本翻译用 Client（百度/MiMo 文本翻译） */
    val textClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** MiMo 文本翻译专用 Client */
    val mimoTextClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** Vision API 专用 Client（需传图，超时较长） */
    val visionClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

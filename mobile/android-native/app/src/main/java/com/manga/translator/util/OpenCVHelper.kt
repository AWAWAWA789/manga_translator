package com.manga.translator.util

import android.content.Context
import android.util.Log
import org.opencv.android.OpenCVLoader

object OpenCVHelper {

    private const val TAG = "OpenCVHelper"

    @Volatile private var isInitialized = false
    private val initLock = Any()

    fun initialize(context: Context): Boolean {
        if (isInitialized) {
            Log.d(TAG, "OpenCV已初始化")
            return true
        }

        synchronized(initLock) {
            if (isInitialized) return true
            try {
                isInitialized = OpenCVLoader.initDebug()
                if (isInitialized) {
                    Log.d(TAG, "OpenCV初始化成功")
                } else {
                    Log.e(TAG, "OpenCV初始化失败")
                }
            } catch (e: Exception) {
                Log.e(TAG, "OpenCV初始化异常: ${e.message}")
                isInitialized = false
            }
            return isInitialized
        }
    }

    fun isInitialized(): Boolean {
        return isInitialized
    }
}

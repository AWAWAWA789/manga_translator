package com.manga.translator.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 安全 SharedPreferences 封装
 * 使用 EncryptedSharedPreferences 加密存储 API Key 等敏感信息
 * 自动从旧版明文 SharedPreferences 迁移数据
 */
object SecurePrefs {

    private const val TAG = "SecurePrefs"
    private const val SECURE_FILE = "secure_config"
    private const val LEGACY_FILE = "translation_config"

    // 敏感 key 列表，需从明文迁移到加密存储
    private val SENSITIVE_KEYS = listOf("baidu_app_id", "baidu_secret_key", "mimo_api_key")

    @Volatile
    private var instance: SharedPreferences? = null

    fun get(context: Context): SharedPreferences {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            val appContext = context.applicationContext
            val prefs = createEncryptedPrefs(appContext)
            migrateFromLegacy(appContext, prefs)
            instance = prefs
            return prefs
        }
    }

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                SECURE_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "加密SharedPreferences创建失败，回退明文: ${e.message}")
            // 回退到普通 SharedPreferences，保证可用性
            context.getSharedPreferences(SECURE_FILE, Context.MODE_PRIVATE)
        }
    }

    /**
     * 从旧版明文 SharedPreferences 迁移敏感数据
     * 迁移成功后从明文文件中删除
     */
    private fun migrateFromLegacy(context: Context, securePrefs: SharedPreferences) {
        try {
            val legacyPrefs = context.getSharedPreferences(LEGACY_FILE, Context.MODE_PRIVATE)
            val editor = securePrefs.edit()
            var migrated = false

            for (key in SENSITIVE_KEYS) {
                val legacyValue = legacyPrefs.getString(key, null)
                if (legacyValue != null && !securePrefs.contains(key)) {
                    editor.putString(key, legacyValue)
                    migrated = true
                    Log.d(TAG, "迁移敏感数据: $key")
                }
            }

            if (migrated) {
                editor.apply()

                // 从明文文件中删除已迁移的敏感数据
                val legacyEditor = legacyPrefs.edit()
                for (key in SENSITIVE_KEYS) {
                    if (legacyPrefs.contains(key)) {
                        legacyEditor.remove(key)
                    }
                }
                legacyEditor.apply()
                Log.d(TAG, "敏感数据迁移完成，已从明文文件删除")
            }
        } catch (e: Exception) {
            Log.e(TAG, "敏感数据迁移失败: ${e.message}")
        }
    }
}

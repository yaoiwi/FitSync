package com.fitsync.data.network.personal

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Personal API 的凭据存储。
 *
 * - API Key 等价于密码，用 EncryptedSharedPreferences 存（AES256-GCM）；
 * - access token 24 小时过期，缓存到剩余不足 1 小时再换，避免每次同步都换一次。
 */
@Singleton
class PersonalApiStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private companion object {
        const val FILE = "fitsync_personal_api"
        const val K_API_KEY = "api_key"
        const val K_TOKEN = "access_token"
        const val K_TOKEN_EXP = "token_exp"
        const val K_ACCOUNT_ID = "local_account_id"
    }

    private val prefs: SharedPreferences by lazy {
        runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrElse {
            // 个别机型 Keystore 异常时退回普通存储，至少功能可用
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
    }

    fun apiKey(): String? = prefs.getString(K_API_KEY, null)?.takeIf { it.isNotBlank() }

    fun saveApiKey(key: String) {
        prefs.edit().putString(K_API_KEY, key.trim()).apply()
    }

    fun token(): String? = prefs.getString(K_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun tokenExpiresAt(): Long = prefs.getLong(K_TOKEN_EXP, 0L)

    fun saveToken(token: String, expiresAtSeconds: Long) {
        prefs.edit()
            .putString(K_TOKEN, token)
            .putLong(K_TOKEN_EXP, expiresAtSeconds)
            .apply()
    }

    /** Personal API 数据挂在本地一个固定账号下，第一次同步时创建并记住 id */
    fun accountId(): Long = prefs.getLong(K_ACCOUNT_ID, 0L)

    fun saveAccountId(id: Long) {
        prefs.edit().putLong(K_ACCOUNT_ID, id).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}

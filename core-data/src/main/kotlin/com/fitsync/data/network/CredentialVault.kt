package com.fitsync.data.network

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 凭据本地加密仓。
 *
 * - 凭据**只落本机**，不出设备；
 * - 使用 `androidx.security:security-crypto` 的 `EncryptedFile`，底层走 Android Keystore 托管的 AES-GCM；
 * - JSON 文件，每个账号一条记录，key 为 `sourceType|region|externalUserId`；
 * - security-crypto 1.1.0-alpha06 的 Builder 必须传 `(Context, File, MasterKey, Scheme)`，
 *   读写只能走 `openFileInput/Output`。
 */
@Singleton
class CredentialVault @Inject constructor(
    @ApplicationContext private val context: Context,
) : CredentialStore {

    private lateinit var vaultFile: EncryptedFile

    private val vaultPath: File get() = File(context.filesDir, "credentials.json")

    private fun ensureReady() {
        if (::vaultFile.isInitialized) return
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        vaultFile = EncryptedFile.Builder(
            context,
            vaultPath,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()
    }

    private fun key(sourceType: String, region: String, externalUserId: String) =
        "$sourceType|$region|$externalUserId"

    private fun backupFile() = File(context.filesDir, "credentials.json.bak")

    private fun readRaw(): String {
        ensureReady()
        // 上次写入中途被杀：从备份恢复
        if (!vaultPath.exists() && backupFile().exists()) {
            backupFile().renameTo(vaultPath)
        }
        if (!vaultPath.exists()) return ""
        return runCatching {
            vaultFile.openFileInput().bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrDefault("")
    }

    /**
     * EncryptedFile.openFileOutput() **不允许覆盖已存在的文件**（会抛
     * "output file already exists, please use a new file"），所以必须先移走旧文件。
     * 这里用「备份 → 写新 → 成功删备份 / 失败回滚」保证不丢已有账号。
     */
    private fun writeRaw(text: String) {
        ensureReady()
        val backup = backupFile()
        if (backup.exists()) backup.delete()

        val hadOld = vaultPath.exists()
        if (hadOld && !vaultPath.renameTo(backup)) {
            vaultPath.delete() // 极端情况：无法重命名，只能直接删
        }

        try {
            vaultFile.openFileOutput().use { it.write(text.toByteArray(Charsets.UTF_8)) }
            backup.delete()
        } catch (e: Exception) {
            if (hadOld && backup.exists()) {
                vaultPath.delete()
                backup.renameTo(vaultPath)
            }
            throw e
        }
    }

    @Synchronized
    override fun load(sourceType: String, region: String, externalUserId: String): StorableCredential? {
        val raw = readRaw()
        if (raw.isBlank()) return null
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val record = root.optJSONObject(key(sourceType, region, externalUserId)) ?: return null
        val password = record.optString("password", "")
        val accessToken = record.optString("accessToken", "")
        val refreshToken = record.optString("refreshToken", "")
        val oauth1Token = record.optString("oauth1Token", "")
        val oauth1Secret = record.optString("oauth1Secret", "")
        // 网页登录不保存密码，只有 token；这类记录同样要能读出来
        val hasSecret = password.isNotBlank() || accessToken.isNotBlank() ||
            refreshToken.isNotBlank() || oauth1Token.isNotBlank()
        return if (!hasSecret) null else StorableCredential(
            sourceType = sourceType,
            region = region,
            externalUserId = externalUserId,
            email = record.optString("email", externalUserId),
            password = password,
            csrfToken = record.optString("csrfToken", ""),
            ssoToken = record.optString("ssoToken", ""),
            accessToken = accessToken,
            refreshToken = refreshToken,
            oauth1Token = oauth1Token,
            oauth1Secret = oauth1Secret,
            sessionKey = record.optString("sessionKey", ""),
            tokenExpiresAtMs = record.optLong("tokenExpiresAtMs", 0L),
        )
    }

    @Synchronized
    override fun save(credential: StorableCredential) {
        val raw = readRaw()
        val root = if (raw.isBlank()) JSONObject() else runCatching { JSONObject(raw) }.getOrNull() ?: JSONObject()

        val record = root.optJSONObject(key(credential.sourceType, credential.region, credential.externalUserId))
            ?: JSONObject()
        record.put("email", credential.email)
        record.put("password", credential.password)
        record.put("csrfToken", credential.csrfToken)
        record.put("ssoToken", credential.ssoToken)
        record.put("accessToken", credential.accessToken)
        record.put("refreshToken", credential.refreshToken)
        record.put("sessionKey", credential.sessionKey)
        record.put("tokenExpiresAtMs", credential.tokenExpiresAtMs)
        record.put("oauth1Token", credential.oauth1Token)
        record.put("oauth1Secret", credential.oauth1Secret)
        root.put(key(credential.sourceType, credential.region, credential.externalUserId), record)

        writeRaw(root.toString())
    }

    @Synchronized
    override fun delete(sourceType: String, region: String, externalUserId: String) {
        val raw = readRaw()
        if (raw.isBlank()) return
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return
        if (root.remove(key(sourceType, region, externalUserId)) != null) {
            writeRaw(root.toString())
        }
    }
}

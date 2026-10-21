package com.fitgarminsync.data.network

import com.fitgarminsync.data.db.AccountRegion
import com.fitgarminsync.data.db.SourceType

/**
 * 凭据存取抽象。
 *
 * 抽出接口有两个目的：
 * 1. 网络层单测不需要 Android Keystore / EncryptedFile，用内存实现即可；
 * 2. 以后换存储后端（比如换成 DataStore + Keystore 直连）不影响调用方。
 */
interface CredentialStore {
    fun load(sourceType: String, region: String, externalUserId: String): StorableCredential?
    fun save(credential: StorableCredential)
    fun delete(sourceType: String, region: String, externalUserId: String)
}

/**
 * 一个账号的本地凭据。
 *
 * [password] 只在登录/重登时使用；[accessToken] / [refreshToken] 才是后续拉数据要用的东西。
 * 全部只落本机，经 [CredentialVault] 的 AES-GCM 加密文件保存。
 */
data class StorableCredential(
    val sourceType: String = SourceType.GARMIN.code,
    val region: String = AccountRegion.CN.code,
    val externalUserId: String,
    val email: String = "",
    /** 只用于兼容旧数据；网页登录不再保存密码 */
    val password: String = "",
    val csrfToken: String = "",
    /** SSO ticket（一次性，换 token 用） */
    val ssoToken: String = "",
    /** Connect API 的 OAuth access token */
    val accessToken: String = "",
    val refreshToken: String = "",
    val sessionKey: String = "",
    val tokenExpiresAtMs: Long = 0L,
    /** OAuth1 token：刷新 access token 用，必须保存 */
    val oauth1Token: String = "",
    val oauth1Secret: String = "",
)

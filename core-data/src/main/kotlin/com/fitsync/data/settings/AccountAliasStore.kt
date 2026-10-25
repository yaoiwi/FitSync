package com.fitsync.data.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 账号显示昵称（用户自定义）。
 *
 * **为什么不改 `accounts.display_name`**：那个字段是 Garmin 的真实
 * `displayName`，被当作 Connect API 的路径参数
 * （`/usersummary-service/usersummary/daily/{displayName}`）。
 * 直接改会让同步 404。所以昵称单独存在 SharedPreferences 里，
 * 只影响界面显示，不影响任何网络请求。
 *
 * 存 SharedPreferences 而不是加表字段，也避免了 Room 迁移
 * （本工程当前是 destructive migration，加字段会清掉用户数据）。
 */
@Singleton
class AccountAliasStore @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _aliases = MutableStateFlow(readAll())
    val aliases: StateFlow<Map<Long, String>> = _aliases.asStateFlow()

    fun setAlias(accountId: Long, alias: String) {
        val trimmed = alias.trim()
        if (trimmed.isEmpty()) {
            prefs.edit().remove(key(accountId)).apply()
        } else {
            prefs.edit().putString(key(accountId), trimmed).apply()
        }
        _aliases.value = readAll()
    }

    fun displayName(accountId: Long, fallback: String): String =
        _aliases.value[accountId] ?: fallback

    private fun readAll(): Map<Long, String> =
        prefs.all.mapNotNull { (k, v) ->
            val id = k.removePrefix(PREFIX).toLongOrNull() ?: return@mapNotNull null
            val name = (v as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            id to name
        }.toMap()

    private fun key(accountId: Long) = "$PREFIX$accountId"

    private companion object {
        const val PREFS = "fitsync_aliases"
        const val PREFIX = "alias_"
    }
}

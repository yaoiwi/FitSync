package com.fitgarminsync.data.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 主账号设置。
 *
 * 中国区和国际区是两个独立账号，各自有自己的数据。首页展示的是**合并后**的档案：
 * 主账号优先，主账号当天缺失的字段用另一区补齐。
 *
 * 存在 SharedPreferences 而不是 accounts 表：避免为此加一个字段、触发 Room 迁移
 * （本工程当前用 destructive migration，加字段会清掉用户已有数据）。
 */
@Singleton
class PrimaryAccountStore @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _primaryAccountId = MutableStateFlow(
        prefs.getLong(KEY_PRIMARY_ID, -1L).takeIf { it > 0 }
    )
    val primaryAccountId: StateFlow<Long?> = _primaryAccountId.asStateFlow()

    fun setPrimary(accountId: Long) {
        prefs.edit().putLong(KEY_PRIMARY_ID, accountId).apply()
        _primaryAccountId.value = accountId
    }

    fun clear() {
        prefs.edit().remove(KEY_PRIMARY_ID).apply()
        _primaryAccountId.value = null
    }

    private companion object {
        const val PREFS = "fgs_settings"
        const val KEY_PRIMARY_ID = "primary_account_id"
    }
}

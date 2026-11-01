package com.fitsync.data.settings

import android.content.Context

/**
 * 同步策略的持久化。
 *
 * 用同一个 SharedPreferences 文件（`fitsync_sync`），
 * `SyncCoordinator`、`SyncScheduler` 和设置页读的是同一份值，避免两处各存一套。
 */
object SyncSettings {

    private const val FILE = "fitsync_sync"

    /** 打开 App 时自动同步 */
    const val KEY_AUTO_ON_OPEN = "auto_sync_on_open"

    /** 后台同步间隔（小时），0 表示关闭后台同步、只手动 */
    const val KEY_INTERVAL_HOURS = "sync_interval_hours"

    /** 只在 Wi-Fi 下自动同步 */
    const val KEY_WIFI_ONLY = "sync_wifi_only"

    /** 「回填历史」默认补多少天 */
    const val KEY_BACKFILL_DAYS = "backfill_days"

    /** 「搬运健康数据」默认搬多少天 */
    const val KEY_COPY_HEALTH_DAYS = "copy_health_days"

    /** 搬运风险说明是否已勾选「不再提示」（长跨度仍会提醒耗时） */
    const val KEY_COPY_RISK_ACK = "copy_risk_ack"

    /** 上次自动同步时间（节流用） */
    const val KEY_LAST_AUTO_SYNC = "last_auto_sync_ms"

    const val INTERVAL_MANUAL = 0L

    fun autoSyncOnOpen(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_ON_OPEN, true)

    fun setAutoSyncOnOpen(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_ON_OPEN, enabled).apply()
    }

    fun intervalHours(context: Context): Long =
        prefs(context).getLong(KEY_INTERVAL_HOURS, 6L)

    fun setIntervalHours(context: Context, hours: Long) {
        prefs(context).edit().putLong(KEY_INTERVAL_HOURS, hours).apply()
    }

    fun wifiOnly(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WIFI_ONLY, false)

    fun setWifiOnly(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WIFI_ONLY, enabled).apply()
    }

    fun backfillDays(context: Context): Int =
        prefs(context).getInt(KEY_BACKFILL_DAYS, 365)

    fun setBackfillDays(context: Context, days: Int) {
        prefs(context).edit().putInt(KEY_BACKFILL_DAYS, days).apply()
    }

    fun copyHealthDays(context: Context): Int =
        prefs(context).getInt(KEY_COPY_HEALTH_DAYS, 7)

    fun setCopyHealthDays(context: Context, days: Int) {
        prefs(context).edit().putInt(KEY_COPY_HEALTH_DAYS, days).apply()
    }

    fun copyRiskAcknowledged(context: Context): Boolean =
        prefs(context).getBoolean(KEY_COPY_RISK_ACK, false)

    fun setCopyRiskAcknowledged(context: Context, acknowledged: Boolean) {
        prefs(context).edit().putBoolean(KEY_COPY_RISK_ACK, acknowledged).apply()
    }

    fun lastAutoSyncMs(context: Context): Long =
        prefs(context).getLong(KEY_LAST_AUTO_SYNC, 0L)

    fun markAutoSync(context: Context, ts: Long) {
        prefs(context).edit().putLong(KEY_LAST_AUTO_SYNC, ts).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}

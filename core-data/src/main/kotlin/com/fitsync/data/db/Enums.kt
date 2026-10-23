package com.fitsync.data.db

/**
 * 数据来源类型（accounts.sourceType）
 */
enum class SourceType(val code: String) {
    GARMIN("garmin"),
    FITBIT("fitbit"),
    FILE("file");

    companion object {
        fun from(code: String) = entries.firstOrNull { it.code == code } ?: GARMIN
    }
}

/**
 * 账号区域（Garmin 双区并存的核心维度）
 * 与 6.3 `GarminRegion` 对齐：决定 sso / connectapi host 拼接。
 */
enum class AccountRegion(val code: String, val ssoHost: String, val apiHost: String) {
    CN("cn", "sso.garmin.cn", "connectapi.garmin.cn"),
    INTERNATIONAL("com", "sso.garmin.com", "connectapi.garmin.com"),
    UNKNOWN("unknown", "", "");

    companion object {
        fun from(code: String?) = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/**
 * 账号卡 4 状态（原型 Screen E 的左色条；驱动 UI 与同步闸门）
 * 与 13.x / 6.3 状态机对齐。
 */
enum class AccountStatus(val code: String) {
    NORMAL("normal"),       // 正常
    SYNCING("syncing"),    // 同步中（进度可见）
    MFA_REQUIRED("mfa"),   // 需 MFA 二次验证
    EXPIRED("expired");    // 凭据过期（历史数据保留）

    companion object {
        fun from(code: String?) = entries.firstOrNull { it.code == code } ?: NORMAL
    }
}

/**
 * 回补分片状态（5.11.3 Garmin 历史回补按月分片）
 */
enum class BackfillShardStatus(val code: String) {
    PENDING("pending"), RUNNING("running"), DONE("done"),
    PARTIAL("partial"), FAILED("failed");

    companion object {
        fun from(code: String?) = entries.firstOrNull { it.code == code } ?: PENDING
    }
}

/**
 * 同步任务状态
 */
enum class SyncTaskStatus(val code: String) {
    ENABLED("enabled"), PAUSED("paused"), RUNNING("running"), ERROR("error");

    companion object {
        fun from(code: String?) = entries.firstOrNull { it.code == code } ?: ENABLED
    }
}
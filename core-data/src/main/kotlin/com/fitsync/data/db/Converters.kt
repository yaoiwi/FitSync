package com.fitsync.data.db

import androidx.room.TypeConverter
import java.util.Date
import java.util.UUID

/**
 * Room 类型转换器（第 4 章要求保留，v1 为预留 + 少量常用扩展）。
 *
 * 19 张表全部使用基本类型（Long / Int / String / Double / Boolean）落库，
 * 因此主实体层暂时不需要把 enum 转库。
 *
 * 注意：转换器类必须被 `@TypeConverters(...)` 直接引用，且转换方法本身带 `@TypeConverter`。
 * 不要在容器类上再叠加 `@TypeConverters`——KAPT 会生成非法注解值导致 kapt 失败。
 */
class Converters {

    class StringTypeConverter {
        @TypeConverter
        fun fromString(uuid: UUID?): String? = uuid?.toString()

        @TypeConverter
        fun toUUID(value: String?): UUID? = value?.let { UUID.fromString(it) }
    }

    class LongStringConverter {
        @TypeConverter
        fun fromDate(date: Date?): Long? = date?.time

        @TypeConverter
        fun toDate(millis: Long?): Date? = millis?.let { Date(it) }
    }
}

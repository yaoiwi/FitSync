# FitSync — R8 规则
#
# 原则：能被 R8 安全裁掉的一律不 keep；只有"运行时靠反射找名字"的库才整体保留。
# Compose / Hilt / Room / OkHttp 的 consumer rules 已经覆盖各自需要，不在这里重复。

-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*

# Miuix（Compose Multiplatform）：内部有 expect/actual 与运行时组合查找，
# 整体保留最稳。它的体积占比不大，裁错导致的崩溃代价更高。
-keep class top.yukonga.miuix.** { *; }
-dontwarn top.yukonga.miuix.**

# Garmin FIT SDK：按 mesg num 反射构造 RecordMesg/SessionMesg，
# 字段被裁掉不会报错，只会解析出 0——这种问题最难查，直接整包保留。
-keep class com.garmin.fit.** { *; }
-dontwarn com.garmin.fit.**

# Hilt / Dagger 生成的组件由反射实例化
-keep class dagger.hilt.** { *; }
-dontwarn dagger.hilt.**

# Tink 的注解依赖（编译期 only）
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# Room：实现类按注解生成，保留入口即可（实体字段名由 @ColumnInfo 固定，混淆无影响）
-keep class * extends androidx.room.RoomDatabase

# FitGarminSync app ProGuard/R8 rules
# 当前阶段先保证 Release 构建可通过；后续接入网络层与序列化库时再补充库规则。

# 保留 Compose/AndroidX 常见运行时反射依赖
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }
-keep class androidx.compose.material3.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep class androidx.hilt.** { *; }

# Hilt
-keep class dagger.hilt.** { *; }
-dontwarn dagger.hilt.**

# kotlinx.coroutines
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

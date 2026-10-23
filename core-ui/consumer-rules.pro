# FitSync core-ui consumer rules
# 保留 core-ui 组件与主题常量，避免下游模块在 R8 下误裁。

-keep class com.fitsync.ui.theme.** { *; }
-keep class com.fitsync.ui.components.** { *; }

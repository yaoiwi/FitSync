package com.fitsync.ui.about

import androidx.compose.runtime.Immutable

/**
 * 「关于」页的数据源。
 *
 * **只保留最新一版**：发版时直接替换 [AboutContent.changeLogs] 里的那一条，
 * 不累积历史，仓库里也不再维护 CHANGELOG.md。
 */

@Immutable
data class ChangeLogEntry(
    val version: String,
    val date: String,
    val highlights: List<String>,
)

@Immutable
data class OpenSourceItem(
    val name: String,
    val license: String,
    val url: String,
    val role: String,
)

@Immutable
data class OpenSourceGroup(
    val title: String,
    val items: List<OpenSourceItem>,
)

object AboutContent {

    // ============================ 更新日志 ============================

    /**
     * 只展示最新一版：发版时整体替换这一条，不累积历史。
     * 仓库里不再维护 CHANGELOG.md。
     */
    val changeLogs: List<ChangeLogEntry> = listOf(
        ChangeLogEntry(
            version = "1.9.0",
            date = "2026-11-07",
            highlights = listOf(
                "Material 3 风格新增「莫奈取色」：设置 → 外观里打开后，主色、卡片、背景、" +
                    "分组标题和按钮都由系统壁纸配色生成，换壁纸颜色跟着变",
                "只作用于 Material 3。HyperOS 那套是固定蓝灰 + 液态玻璃，不掺壁纸色，所以开关也只在 MD3 下出现",
                "需要 Android 12（API 31）及以上：动态色板读的是系统资源，低版本没有，" +
                    "这类设备上开关直接不显示，不会出现「开了没反应」",
                "数据语义色保持固定：步数绿、心率红、告警黄不跟着壁纸漂移，" +
                    "否则换个紫色壁纸就把心率画成了紫色，图表反而看不懂",
                "和深浅色、界面风格互不干扰：关掉就回到默认蓝色，切到 HyperOS 也不受影响；" +
                    "开关存在同一份本地设置里，备份恢复后仍记得",
            ),
        ),
    )

    // ============================ 开源致谢 ============================

    val acknowledgements: List<OpenSourceGroup> = listOf(
        OpenSourceGroup(
            title = "UI 与设计体系",
            items = listOf(
                OpenSourceItem(
                    name = "Miuix（miuix-ui / miuix-icons / miuix-blur）",
                    license = "Apache-2.0",
                    url = "https://github.com/YuKongA/miuix",
                    role = "HyperOS 风格组件库、图标与液态玻璃（RuntimeShader 背景采样）",
                ),
                OpenSourceItem(
                    name = "Jetpack Compose",
                    license = "Apache-2.0",
                    url = "https://developer.android.com/jetpack/compose",
                    role = "声明式 UI 框架，全站界面基础",
                ),
                OpenSourceItem(
                    name = "Material Design 3 / Material Components",
                    license = "Apache-2.0",
                    url = "https://github.com/material-components/material-components-android",
                    role = "基础控件、矢量图标与排版",
                ),
                OpenSourceItem(
                    name = "HyperIsland",
                    license = "MIT",
                    url = "https://github.com/1812z/HyperIsland",
                    role = "液态玻璃导航栏、页面骨架与滚动动画的参考实现",
                ),
                OpenSourceItem(
                    name = "Miuix 官方示例（IosLiquidGlassNavigationBar）",
                    license = "Apache-2.0",
                    url = "https://github.com/YuKongA/miuix",
                    role = "折射着色器与玻璃高光的上游实现",
                ),
            ),
        ),
        OpenSourceGroup(
            title = "Android 平台组件",
            items = listOf(
                OpenSourceItem(
                    name = "AndroidX Core / Activity / Lifecycle",
                    license = "Apache-2.0",
                    url = "https://developer.android.com/jetpack/androidx",
                    role = "兼容层、Activity 与生命周期",
                ),
                OpenSourceItem(
                    name = "AndroidX Navigation",
                    license = "Apache-2.0",
                    url = "https://developer.android.com/jetpack/androidx/releases/navigation",
                    role = "页面路由",
                ),
                OpenSourceItem(
                    name = "AndroidX WorkManager",
                    license = "Apache-2.0",
                    url = "https://developer.android.com/topic/libraries/architecture/workmanager",
                    role = "后台定时同步",
                ),
                OpenSourceItem(
                    name = "AndroidX Security Crypto",
                    license = "Apache-2.0",
                    url = "https://developer.android.com/jetpack/androidx/releases/security",
                    role = "EncryptedFile，本地凭据加密",
                ),
                OpenSourceItem(
                    name = "Room",
                    license = "Apache-2.0",
                    url = "https://developer.android.com/jetpack/androidx/releases/room",
                    role = "本地数据库（账号、活动、健康数据）",
                ),
                OpenSourceItem(
                    name = "Hilt / Dagger",
                    license = "Apache-2.0",
                    url = "https://dagger.dev/hilt/",
                    role = "依赖注入",
                ),
            ),
        ),
        OpenSourceGroup(
            title = "网络与数据",
            items = listOf(
                OpenSourceItem(
                    name = "OkHttp",
                    license = "Apache-2.0",
                    url = "https://square.github.io/okhttp/",
                    role = "HTTP 客户端，Garmin SSO 与数据接口",
                ),
                OpenSourceItem(
                    name = "Kotlin Coroutines",
                    license = "Apache-2.0",
                    url = "https://github.com/Kotlin/kotlinx.coroutines",
                    role = "异步任务与并发",
                ),
                OpenSourceItem(
                    name = "Garmin FIT SDK",
                    license = "Garmin FIT Protocol License",
                    url = "https://developer.garmin.com/fit/",
                    role = "生成合规 monitoring FIT，用于健康数据搬运",
                ),
                OpenSourceItem(
                    name = "Tink",
                    license = "Apache-2.0",
                    url = "https://github.com/tink-crypto/tink-java",
                    role = "加密原语",
                ),
                OpenSourceItem(
                    name = "material-color-utilities",
                    license = "Apache-2.0",
                    url = "https://github.com/material-foundation/material-color-utilities",
                    role = "Miuix 动态取色依赖（本项目使用固定色板）",
                ),
            ),
        ),
        OpenSourceGroup(
            title = "思路与接口参考（仅参考做法，未使用其代码）",
            items = listOf(
                OpenSourceItem(
                    name = "DailySync（dailysync-rev）",
                    license = "GPL-3.0 · 未包含其代码",
                    url = "https://github.com/gooin/dailysync-rev",
                    role = "健康数据搬运的接口与流程参考：下载当天 wellness 原始包、以 userfile 上传、用 409 判定重复。本项目为 Kotlin 独立实现，未复制或翻译其源码，整体仍按 Apache-2.0 发布",
                ),
            ),
        ),
        OpenSourceGroup(
            title = "开发与测试",
            items = listOf(
                OpenSourceItem(
                    name = "Kotlin",
                    license = "Apache-2.0",
                    url = "https://kotlinlang.org/",
                    role = "开发语言",
                ),
                OpenSourceItem(
                    name = "Gradle",
                    license = "Apache-2.0",
                    url = "https://gradle.org/",
                    role = "构建系统",
                ),
                OpenSourceItem(
                    name = "JUnit 4",
                    license = "Eclipse Public License 1.0",
                    url = "https://junit.org/junit4/",
                    role = "单元测试",
                ),
                OpenSourceItem(
                    name = "Robolectric",
                    license = "MIT",
                    url = "https://robolectric.org/",
                    role = "JVM 上运行 Android 测试",
                ),
                OpenSourceItem(
                    name = "JSON-java（org.json）",
                    license = "Public Domain",
                    url = "https://github.com/stleary/JSON-java",
                    role = "测试环境的 JSON 实现",
                ),
            ),
        ),
    )

    const val THANKS = "感谢以上项目及其贡献者。没有这些开源工作，这个应用不会存在。" +
        "其中 DailySync 以 GPL-3.0 发布，本项目只参考其接口做法，未使用其代码。"
}

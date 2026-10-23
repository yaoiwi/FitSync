package com.fitsync.ui.about

import androidx.compose.runtime.Immutable

/**
 * 「关于」页的数据源。
 *
 * **从 0.10.10 起记录**：每次发版在 [AboutContent.changeLogs] 顶部加一条。
 * 项目根目录的 CHANGELOG.md 同步加同一节，两处内容保持一致。
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
     * 从 0.10.10 起开始记录：每次发版在顶部加一条，应用内展示完整列表。
     * 项目根目录的 CHANGELOG.md 同步加同一节。
     */
    val changeLogs: List<ChangeLogEntry> = listOf(
        ChangeLogEntry(
            version = "0.10.10",
            date = "2026-10-24",
            highlights = listOf(
                "设置页新增「项目主页」「问题反馈」入口",
                "更新日志从本版本开始记录",
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

    const val THANKS = "感谢以上项目及其贡献者。没有这些开源工作，这个应用不会存在。"
}

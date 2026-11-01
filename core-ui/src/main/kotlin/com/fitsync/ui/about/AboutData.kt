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
            version = "1.14.14",
            date = "2026-11-01",
            highlights = listOf(
                "同步时列表可以正常滑动了：刷新头不再常驻顶部，改成标题栏下沿一条静止细线。之前那圈一直转的动画会占住拖动手势，还让整页玻璃每帧重新采样，越滑越卡",
                "同步记录页改用 HyperOS 的列表行：时间、账号、条数、耗时、结果对齐成一行，不再是一个小圆点加两行字",
                "搬运前的确认弹窗换成 Miuix 样式：26dp 圆角、系统字号、底部文字按钮，「以后不再提示」改成带说明的开关行",
                "后台搬运提速：长任务期间持 Wi-Fi 高性能锁，锁屏后不再掉进省电模式（往返延迟能从几百毫秒降回几十毫秒）",
                "设置 → 同步新增「后台加速」，一键把本应用加入系统「不限制电池」名单；国产 ROM 不加这个，锁屏后可能直接被冻结",
                "通知进度改成每 5% 或每 3 秒推送一次，不再逐天刷新——跨进程调用太密会被系统限流，看着反而一顿一顿",
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

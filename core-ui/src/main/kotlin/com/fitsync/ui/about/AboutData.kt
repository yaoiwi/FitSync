package com.fitsync.ui.about

import androidx.compose.runtime.Immutable

/**
 * 「关于」页的数据源。
 *
 * **每次发版必须在这里追加一条 [AboutContent.changeLogs]（最新在最前）**，
 * 同时同步更新项目根目录的 CHANGELOG.md，并在 HANDOFF.md 的发版清单里打勾。
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
    // 版本号倒序，最新的放最上面。发版时只加不删。

    val changeLogs: List<ChangeLogEntry> = listOf(
        ChangeLogEntry(
            version = "0.10.5",
            date = "2026-10-24",
            highlights = listOf(
                "活动列表只显示主账号的活动，两个区同步到同一场运动不再成对出现",
                "活动数统计改为只算主账号，与列表条数一致",
                "活动页最多展示 50 条（原来只取 5 条）",
                "未设主账号时退回按第一个账号取数，不再把所有账号的活动混在一起",
            ),
        ),
        ChangeLogEntry(
            version = "0.10.4",
            date = "2026-10-24",
            highlights = listOf(
                "修复启动瞬间闪出「还没有账号」页：等数据库返回首帧再决定显示空状态还是主页",
                "修复活动列表显示英文类型码（running）而详情页显示中文（跑步）的不一致",
                "运动类型中文映射扩充到 20 余种：越野跑、跑步机、场地跑、山地骑行、室内骑行、泳池游泳、公开水域游泳、散步、快走、登山、室内有氧、HIIT、瑜伽、普拉提等",
                "活动详情里的原始类型码只在和中文名不同时显示，标签改为「原始类型」",
                "空状态的「添加数据源」按钮换成 Miuix 风格",
            ),
        ),
        ChangeLogEntry(
            version = "0.10.3",
            date = "2026-10-24",
            highlights = listOf(
                "账号详情「修改昵称」和「移除账号」修好，之前点了没反应",
                "移除账号会级联清理活动、采样、日汇总与同步游标，并在需要时清空主账号",
                "新增触感反馈：列表行、按钮、开关、分段控件、页签切换、同步完成都有震动",
                "设置 → 外观 新增「触感反馈」开关，可随时关掉",
                "对话框统一换成 HyperOS 风格，替换原来的 Material3 弹窗",
            ),
        ),
        ChangeLogEntry(
            version = "0.10.2",
            date = "2026-10-24",
            highlights = listOf(
                "修复登录成功后白屏：返回首页时误把首页一起弹出，回退栈为空",
                "修复 Garmin 网页登录页：顶部返回栏挡住登录表单，改为占位式布局",
                "登录向导顶部新增返回按钮，底部留出空间避免被按钮条压住",
            ),
        ),
        ChangeLogEntry(
            version = "0.10.1",
            date = "2026-10-23",
            highlights = listOf(
                "接入 Garmin 官方 Personal API（只读），用 API Key 连接，不需要账号密码",
                "同步每日汇总与 HRV（含基线），首次同步自动设为主账号",
                "下载并解析活动完整 FIT 文件：逐秒采样与 GPS 轨迹",
                "活动详情新增轨迹图与心率 / 速度 / 海拔曲线",
            ),
        ),
        ChangeLogEntry(
            version = "0.10.0",
            date = "2026-10-23",
            highlights = listOf(
                "项目更名为 FitSync，应用名不再包含 Garmin 商标",
                "包名改为 com.fitsync.app，代码包改为 com.fitsync",
                "本地数据库与偏好设置文件同步改名",
                "本次升级需要卸载重装并重新登录，旧数据不会迁移",
            ),
        ),
        ChangeLogEntry(
            version = "0.9.15",
            date = "2026-10-21",
            highlights = listOf(
                "更新日志与开源致谢拆成两个独立页面",
                "新增深色模式开关：跟随系统 / 浅色 / 深色，切换立即生效",
                "账号可点开：新增账号列表页与账号详情页",
                "活动可点开：新增活动详情页（配速、心率等完整数据）",
                "今日数据可切换：步数 / 距离 / 活动热量 / 楼层",
                "导入、复制活动、搬运健康数据改为 HyperIsland 风格全屏页",
            ),
        ),
        ChangeLogEntry(
            version = "0.9.14",
            date = "2026-10-21",
            highlights = listOf(
                "新增「关于」页：版本信息、完整更新日志、开源致谢",
                "致谢按类别分组，点任意项目可直接打开其主页",
                "建立 CHANGELOG 与发版清单，之后每个版本都会同步更新",
            ),
        ),
        ChangeLogEntry(
            version = "0.9.13",
            date = "2026-10-21",
            highlights = listOf(
                "UI 全面换成 HyperIsland 3.2.1 同款官方 Miuix 0.9.4 组件库",
                "液态玻璃导航栏接上真实动画：弹性指示器滑动、拖动跟手、按下放大、折射与双光源高光",
                "新增上滑动画：大标题折叠、列表两端弹性回弹、顶栏渐进模糊",
                "列表、卡片、开关、行内容全部换成官方实现，下拉刷新与折叠共用滚动行为",
                "工具链升级：AGP 9.3.1 / Kotlin 2.4.10 / Compose 1.12.1 / Gradle 9.6.1",
                "移除 Haze 依赖（自绘玻璃无法真实采样背景）",
            ),
        ),
        ChangeLogEntry(
            version = "0.9.12",
            date = "2026-10-20",
            highlights = listOf(
                "新增「活动」页签，底部导航改为四页：概览 / 活动 / 账号 / 设置",
                "加液体玻璃浮层：导航栏与登录页底条改为半透明 + 高光描边",
            ),
        ),
        ChangeLogEntry(
            version = "0.9.11",
            date = "2026-10-20",
            highlights = listOf(
                "全站版式改为 HyperOS 骨架：左对齐大标题 + 分组标题 + 零间隙卡片",
                "底部导航改为悬浮胶囊样式，行高 64dp、左右 18dp 对齐",
            ),
        ),
        ChangeLogEntry(
            version = "0.9.10",
            date = "2026-10-19",
            highlights = listOf(
                "支持深色模式：颜色 token 改为双色板，跟随系统自动切换",
                "补充 values-night 主题，修正状态栏与系统栏配色",
            ),
        ),
        ChangeLogEntry(
            version = "0.9.9",
            date = "2026-10-19",
            highlights = listOf(
                "修复同步 401：token 提前 5 分钟续期，401 时自动换牌重试",
                "账号过期状态在列表中标红，避免静默失败",
            ),
        ),
        ChangeLogEntry(
            version = "0.9.8",
            date = "2026-10-19",
            highlights = listOf(
                "启动器图标定稿：外圈 9 个波浪 + 中间心电波形，适配自适应图标与 5 档 mipmap",
            ),
        ),
        ChangeLogEntry(
            version = "0.9.3",
            date = "2026-10-18",
            highlights = listOf(
                "首个可安装版本：Garmin 登录、账号管理、本地数据聚合",
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

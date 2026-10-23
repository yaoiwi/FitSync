# FitSync

把 Garmin 中国区和国际区的账号数据聚合到一个 App 里：步数、睡眠、心率、运动记录统一查看，两个区之间还能互相同步和搬运数据。

> 非官方客户端。与 Garmin Ltd. 无隶属关系，Garmin 是 Garmin Ltd. 的注册商标。

## 它解决什么问题

Garmin 的中国区（`sso.garmin.cn`）和国际区（`sso.garmin.com`）是**两套独立账号体系**，数据互不相通。手表换区或者双区都有账号的人，只能在两个 App 之间来回切换。

这个 App 的做法：

- 两个区可以同时登录，数据在本地合并展示
- 主账号决定首页「今日」以谁为准
- 支持把活动、健康数据从一个账号搬到另一个账号（Garmin 没开放接口，走的是 monitoring FIT 上传）
- 数据只存本地，token 用 EncryptedFile + Tink 加密

## 界面

- 完全基于 [Miuix](https://github.com/YuKongA/miuix) 0.9.4（HyperOS 官方风格组件库）
- 液态玻璃底部导航栏：真实背景模糊 + 折射 + 双光源高光，指示器弹簧滑动、拖动跟手
- 上滑时大标题折叠、列表两端弹性回弹、顶栏渐进模糊
- 深色模式三档：跟随系统 / 浅色 / 深色
- 四个页签：概览 / 活动 / 账号 / 设置

> 液态玻璃依赖 Android 13（API 33）的 RuntimeShader；更低版本自动降级为半透明 + 高光描边。

## 功能

| 功能 | 状态 |
|---|---|
| Garmin 中国区 / 国际区登录（含 MFA） | ✅ |
| 网页登录（WebView 取 ticket） | ✅ |
| 多账号管理、昵称、主账号 | ✅ |
| 步数 / 距离 / 热量 / 楼层 / 睡眠 / 心率 | ✅ |
| 活动列表与详情（配速、心率、爬升） | ✅ |
| 活动复制到另一账号 | ✅ |
| 健康数据搬运（monitoring FIT） | ✅ 实验性 |
| 本地文件导入（ZIP / JSON / CSV） | ✅ |
| 打开即同步（前台自动，5 分钟节流） | ✅ |
| 定时后台同步（WorkManager） | ✅ |

## 构建

**环境要求**（缺一不可）：

- JDK **21**（Miuix 的 class 文件版本是 65）
- Android SDK Platform **37** + Build-Tools 36+
- Gradle **9.6.1**（wrapper 已指向）

```bash
# 1. 指向本机 SDK
echo "sdk.dir=/path/to/Android/sdk" > local.properties

# 2. 构建
./gradlew :app:assembleDebug

# 3. 产物
# app/build/outputs/apk/debug/app-debug.apk
```

跑测试：

```bash
./gradlew :app:testDebugUnitTest
```

> 工具链说明：AGP 9 默认启用内置 Kotlin，但与本项目的 kapt（Hilt/Room）不兼容，
> 所以在 `gradle.properties` 里关掉了 `android.builtInKotlin`，继续用经典 Kotlin 插件。

## 项目结构

```
app/          应用层：导航、登录向导、首页、详情页、数据操作页
core-data/    数据层：Room、Garmin SSO/同步、加密存储、DI
core-ui/      UI 层：Miuix 主题、液态玻璃、页面骨架、通用组件
```

技术栈与版本、发版流程见 [HANDOFF.md](HANDOFF.md)，每个版本的变更见 [CHANGELOG.md](CHANGELOG.md)。

## 免责声明

- 本项目使用 Garmin 的非公开接口，**随时可能失效**，账号也存在被风控的可能
- 仅建议用于同步**你自己**的账号数据
- 请自行评估风险，作者不对账号异常、数据丢失负责

## 致谢

完整列表在 App 内「设置 → 关于 → 开源致谢」，主要依赖：

[Miuix](https://github.com/YuKongA/miuix) · [Jetpack Compose](https://developer.android.com/jetpack/compose) · [HyperIsland](https://github.com/1812z/HyperIsland)（液态玻璃与动画参考）· [Hilt](https://dagger.dev/hilt/) · [Room](https://developer.android.com/jetpack/androidx/releases/room) · [OkHttp](https://square.github.io/okhttp/) · [Garmin FIT SDK](https://developer.garmin.com/fit/)

## 许可证

[Apache-2.0](LICENSE)。可以自由使用、修改、分发，**包括商业用途和闭源衍生**，只需保留版权声明与许可证副本；同时包含明确的专利授权（谁起诉专利侵权，授权自动终止）。

第三方依赖的许可证、以及液态玻璃相关代码的上游来源，见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

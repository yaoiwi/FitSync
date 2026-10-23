# FitSync — 项目交接文档（HANDOFF）

> 本文件用于跨工具/跨 AI 交接。接手方读完这一份即可继续开发，无需翻聊天记录。

## 〇、发版清单（每次发版必须全做）

1. `app/build.gradle.kts`：`versionCode` +1、`versionName` 递进。
2. `CHANGELOG.md`：顶部新增一节（版本号 + 日期 + 变更要点），保留完整历史。
3. `core-ui/src/main/kotlin/com/fitsync/ui/about/AboutData.kt`：在 `changeLogs` **顶部加一条**
   （应用内「更新日志」展示完整历史）。
4. 若新增/移除开源依赖，同步更新 `AboutData.kt` 的 `acknowledgements` 分组。
5. 构建 + 跑 `:app:testDebugUnitTest`，确认全绿再打包。

> 第 2、3 步内容一致：都保留完整历史，发版时各加一条。

## 一、项目是什么

Android 应用 **FitSync**：多平台健康数据聚合器，是 DailySync 移动端的原生复刻。

核心定位：
- 通过**账号登录**（非 Health Connect）获取数据
- 聚合三类数据源：**Garmin（中国区 + 国际区）**、**Fitbit**、**本地文件导入**
- **Local-First**：数据只存本地，token 加密存储，不上传任何服务器
- 明确接受 Garmin / Fitbit 非官方接口的灰色地带风险（UI 中已有风险知情步骤）

## 二、工程位置与结构

```
/storage/emulated/0/Download/FitSync-Project/
├── .github/workflows/android.yml   # GitHub Actions 在线构建 APK
├── README-BUILD.md                 # 在线构建操作说明
├── HANDOFF.md                      # 本文件
├── scripts/push-to-github.sh       # 一键推送脚本（Termux 用）
├── build.gradle.kts                # 根构建脚本
├── settings.gradle.kts             # 三模块：:app :core-data :core-ui
├── gradle.properties
├── gradlew / gradlew.bat
├── gradle/
│   ├── libs.versions.toml          # 版本目录
│   └── wrapper/{gradle-wrapper.properties, gradle-wrapper.jar}
├── app/                            # 应用模块：导航、登录向导、首页
├── core-data/                      # 数据层：Room、网络、DI
└── core-ui/                        # UI 层：主题、可复用组件
```

规模：**22 个 Kotlin 文件 / 3035 行**。

## 三、技术栈与版本（已固定）

| 项 | 版本 |
|---|---|
| Gradle | 9.6.1 |
| AGP | 9.3.1（关闭 builtInKotlin，走经典 Kotlin 插件 + kapt） |
| Kotlin | 2.4.10（AGP 9 要求 ≥ 2.4） |
| Compose | BOM 2026.09.00 = Compose 1.12.1 |
| compileSdk / targetSdk / minSdk | 37 / 35 / 26 |
| JDK | 21（miuix 的 class 版本 65，JDK 17/20 跑不了单测） |
| DI | Hilt 2.60.1（kapt） |
| 数据库 | Room 2.8.5 |
| 网络 | OkHttp 4.12.0 |
| 加密 | Tink 1.6.1 + androidx.security-crypto 1.1.0-alpha06 |
| UI | **官方 Miuix 0.9.4**（miuix-ui / miuix-icons / miuix-blur）+ Material3 + Navigation 2.7.7 |

> 构建前必须装：**JDK 21**、**Android SDK Platform 37**（`miuix-ui` 的 AAR 元数据要求 compileSdk ≥ 37），
> 以及 Gradle 9.6.1（wrapper 已指向）。`miuix-blur` 的 minSdk 是 33，manifest 用
> `tools:overrideLibrary` 强制合并，运行时靠 `isRuntimeShaderSupported()` 降级。

## 三之二、HyperIsland UI 对齐（v0.9.13）

UI 完全参照 HyperIsland 3.2.1（同版本 Miuix 0.9.4），关键实现都在 `core-ui`：

| 能力 | 实现位置 | 说明 |
|---|---|---|
| 液态玻璃采样宿主 | `core-ui/.../components/LiquidGlass.kt` | `GlassHost` 双 layer：content 录进 `rememberLayerBackdrop`，overlay 不采样（避免自反馈） |
| 玻璃修饰符 | 同上 `Modifier.liquidGlass` | `textureBlur` + `BloomStroke` 高光；无 RuntimeShader 时降级半透明 |
| 液态玻璃导航栏 | `core-ui/.../components/LiquidGlassNavigationBar.kt` | 直接移植 HyperIsland：弹簧指示器、拖动跟手、松手按速度形变、按下放大 78/56、lens 折射 |
| 折射 shader | `core-ui/.../components/liquid/Lens.kt` | RuntimeShader 圆角矩形 SDF 折射 + 色散 |
| 玻璃饱和度 | `liquid/Vibrancy.kt` | `colorControls(saturation = 1.5f)` |
| 组合 backdrop | `liquid/CombinedBackdrop.kt` | 导航栏 + 指示器两层采样合并 |
| 上滑折叠 | `core-ui/.../components/HyperPage.kt` | 官方 `Scaffold + TopAppBar(largeTitle) + MiuixScrollBehavior`，滚动时大标题收起 |
| 弹性回弹 | 同上 | `Modifier.overScrollVertical()` |
| 官方下拉刷新 | 同上 | `PullToRefresh(topAppBarScrollBehavior = scrollBehavior)`，与折叠共用滚动行为 |
| 官方组件 | `MiuixSkeleton.kt` | `Card` / `BasicComponent` / `Switch` / `Text` / `Icon` 全部换成 Miuix 官方实现 |
| 主题 | `core-ui/.../theme/FitTheme.kt` | 外层包 `MiuixTheme(lightColorScheme/darkColorScheme, defaultTextStyles)` |

已彻底删除 Haze 依赖（自研玻璃没有真实模糊，是这次返工的原因）。

## 四、已经完成的部分

| 模块 | 文件 | 行数 | 说明 |
|---|---|---|---|
| Garmin SSO | `core-data/.../network/GarminSsoClient.kt` | 402 | 真实协议：登录、MFA、区域选择、session 提取 |
| 凭据加密 | `core-data/.../network/CredentialVault.kt` | 105 | EncryptedFile + AES256_GCM，存 token |
| SSO 模型 | `core-data/.../network/GarminSsoModels.kt` | 88 | 请求/响应/异常模型 |
| 数据模型 | `core-data/.../db/Entities.kt` | 303 | Room 实体，已预留 Fitbit 官方导出 12.5 字段（Active Zone Minutes、Daily Readiness） |
| Dao | `core-data/.../db/Dao.kt` | 311 | 各表读写 |
| 仓储实现 | `core-data/.../db/FitDataRepositoryImpl.kt` | 194 | Room 实现 |
| DI | `core-data/.../di/*.kt` | 57 | OkHttp、Database、Repository 的 Provide |
| 登录向导 | `app/.../feature/login/` | 516 | 4 步向导 UI + 状态机 |
| 首页 | `app/.../feature/home/` | 168 | 账号列表 + 空态 + 4 状态色条 |
| 导航 | `app/.../navigation/` | 42 | NavHost |
| UI 组件 | `core-ui/.../` | 589 | 数据源选项卡、MFA、区域选择、主题 |

## 五、关键缺口（接手必读）

1. **同步引擎完全不存在**
   没有任何 Worker / Service / Sync 实现。登录成功后不会拉取任何健康数据。数据库有表有 Dao，但没有代码写入真实数据。

2. **`fetchProfile` 是占位 fallback**
   `GarminSsoClient.kt:305` 注释明写："profile 拉取失败不应阻断登录主流程；当前采用最小 fallback"。
   登录后拿到的用户名/头像可能是假数据，需替换为真实 profile 接口。

3. **Fitbit 只有 3 处字符串**
   `Enums.kt:8` 枚举值、`LoginWizardScreen.kt` 一个可点选项卡、`HomeScreen.kt` 一句文案。
   **完全没有 OAuth、API 客户端、数据解析。** 点 Fitbit 卡片后面是空的。

4. **本地文件导入完全不存在**
   没有 importer、没有文件选择、没有解析。

5. **零测试**
   没有 `src/test`、没有 `src/androidTest`。

6. **从未真实构建过** → 已于 2026-10-18 首次真实构建通过（JDK 20 + Gradle 8.7 + SDK 35），见第十二节。仍需补测试。

## 六、本轮已修复的构建阻断问题

这三处原本会导致 100% 构建失败，已修复，不要回退：

1. 三个模块都开了 `buildFeatures.compose = true` 但缺 `composeOptions`
   → 已补 `kotlinCompilerExtensionVersion = "1.5.14"`（匹配 Kotlin 1.9.24）
   涉及：`app/build.gradle.kts`、`core-data/build.gradle.kts`、`core-ui/build.gradle.kts`

2. `core-data` 引用 `consumerProguardFiles("consumer-rules.pro")` 但文件不存在
   → 已创建

3. `core-ui` 同样问题
   → 文件已存在（已确认）

## 七、静态体检结论（已做，4 项通过）

| 检查 | 结果 |
|---|---|
| 版本目录引用一致性（`libs.*` vs `libs.versions.toml`） | 0 处未定义 |
| Hilt 依赖图闭合 | `GarminSsoClient` / `CredentialVault` / `FitDataRepositoryImpl` 均有 `@Inject constructor`；OkHttp / Database / Repository 均有 Provide |
| Room `@Database` entities | 7 个 entity 已注册 |
| 跨模块 import 悬空 | 0 处（扫 321 个声明） |

**静态检查抓不到的 3 类错误**（必须真编译才能暴露）：
Kotlin 语法/类型不匹配、Dagger 注解处理器错误、Room SQL 校验。

## 八、如何构建

### 方式 A：GitHub Actions（推荐，手机可完成）

```bash
# 手机 Termux
pkg install git -y
bash scripts/push-to-github.sh   # 先改脚本里的 GIT_USER
```

然后打开 `https://github.com/<用户名>/FitSync/actions` → `Build Debug APK` → `Run workflow`。
产物在运行的 Artifacts 里，下载 `app-debug.zip` 解压即得 APK。

### 方式 B：电脑本地

```bash
./gradlew :app:assembleDebug --stacktrace
# Windows: gradlew.bat :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

## 九、下一步优先级（建议顺序）

不要跳步，原因：现在全是未验证代码，一次引入太多新代码会让报错排查成本翻几倍。

1. **先让构建跑绿**（最高优先）
   推 CI，拿到第一个绿勾，确立基线。

2. **`fetchProfile` 换真接口**
   让登录拿到真实账号信息，工作量小、验证快。

3. **Garmin 数据同步引擎**
   主体工作量所在。需要：WorkManager 定时任务、分页拉取、增量游标（`SyncCursorEntity` 已预留）、失败重试与风控退避。

4. **Fitbit OAuth + 同步**
   需要注册开发者应用拿 client_id / redirect_uri。

5. **本地文件导入**
   最简单，CSV/JSON 解析即可，能最快看到"数据进库"的闭环效果。

## 十、避坑清单

- **手机端不要尝试本地编译**：多模块 + Hilt/kapt + Room + Compose，手机 JDK/SDK 环境不可靠。MT 管理器只能看文件。
- **不要用 GitHub 网页 "Upload files" 上传**：手机浏览器无法保留目录结构，42 个文件会被压平到根目录，`settings.gradle.kts` 找不到模块必挂。用 git 推送。
- **推送时密码填 Personal Access Token**，不是 GitHub 登录密码（Settings → Developer settings → Tokens (classic) → 勾 `repo`）。
- **`local.properties` 不要提交**（已在 `.gitignore`），CI 环境自带 `ANDROID_HOME`。
- **`fallbackToDestructiveMigration()`** 目前是 v1 阶段用的，上线前必须替换为显式 Migration，否则升级会清库。
- **凭据安全**：`CredentialVault` 用 EncryptedFile，不要改成明文 SharedPreferences。

## 十一、产品要求的 4 状态账号卡

账号卡有 4 种状态（`Enums.kt` + `FitTheme.kt` 的 `statusBarColor` 已实现配色）：
正常 / 需重新登录 / 同步失败 / 已暂停。首页 `AccountRow` 已按此渲染色条。

---

*文档生成时间：随工程打包时点。接手方若改动关键结构，请同步更新本文件。*

---

## 十二、构建验证记录（2026-10-18，首次跑通）

环境：Ubuntu 22.04 / OpenJDK 20 / Gradle 8.7 / Android SDK 35 + build-tools 35.0.0。
命令：`:app:assembleDebug` → **BUILD SUCCESSFUL**，产物 `app/build/outputs/apk/debug/app-debug.apk`（约 11 MB）。
`:app:lintDebug` → **0 errors / 6 warnings**（仅版本升级、allowBackup 弃用、TOML 建议，均非阻断）。

本轮真实编译暴露并修复的 11 类问题（静态体检没抓到，均已验证）：

| # | 问题 | 位置 | 修复 |
|---|---|---|---|
| 1 | `@Index([...])` 位置参数被 KAPT 生成为 `{{"a"}}` 非法注解值，全部 Entity 编译失败 | `Entities.kt` | 改为 `@Index(value = [...])` |
| 2 | `accounts` 索引用了 Kotlin 属性名（camelCase），与 `@ColumnInfo` 的 snake_case 列名不一致 | `Entities.kt` | 改为 `external_user_id` / `source_type` |
| 3 | `@TypeConverters` 打在容器类 `Converters` 上，且 DB 引用无转换方法的容器类 | `Converters.kt` / `FitSyncDatabase.kt` | DB 直接引用 `StringTypeConverter`、`LongStringConverter` |
| 4 | `exportSchema = true` 但未给 `room.schemaLocation` | `core-data/build.gradle.kts` | 补 kapt 参数，schema 输出到 `core-data/schemas/` |
| 5 | `EncryptedFile.Builder(File, MasterKey, scheme)` 签名不存在；`AES256_GCM` 枚举不存在；对 EncryptedFile 调 `readBytes/writeBytes` | `CredentialVault.kt` | 改 4 参构造 + `AES256_GCM_HKDF_4KB`，读写改 `openFileInput/Output`，方法加 `@Synchronized` |
| 6 | 4 个块体函数缺少 `return`；`substringAfter(limit=)` 无此重载 | `GarminSsoClient.kt` | 补 `return` / 去掉非法参数 |
| 7 | 对 `val` 形参 `result` 重新赋值 | `LoginWizardViewModel.kt` | 形参改 `ssoResult`，函数内 `var result` |
| 8 | 缺 `sp` 导入 | `LoginWizardComponents.kt` | 补 `androidx.compose.ui.unit.sp` |
| 9 | `TextRange` 包名错；`rememberFocusRequester()` 在 Compose 1.6.8 不存在；`showVerticalCursor` 非 KeyboardOptions 参数；`Pair` 传 3 参；`FontWeight.Extrabold` 拼写错 | `MfaAndRegionComponents.kt` / `LoginWizardComponents.kt` | 改 `androidx.compose.ui.text.TextRange`、`remember { FocusRequester() }`、`Triple`、`ExtraBold` |
| 10 | `import androidx.navigation.compose.Composable` 不存在 | `FitSyncNavHost.kt` | 删除 |
| 11 | 冷 Flow 调 `collectAsStateWithLifecycle()` 缺 `initialValue` | `HomeScreen.kt` | 补 `initialValue = emptyList()` |

**真机验证（2026-10-18）**：中国区、国际区两个账号均已登录成功，`access_token` / `refresh_token`
已写入加密凭据仓，账号以真实 `displayName` 入库。登录链路到此闭环。

关键修复路径（按发现顺序）：CookieJar 缺失 → 网页流程被 WAF 拦 → 移动端 JSON 接口 →
URL 多了一层 `/sso` → `EncryptedFile` 无法覆盖写 → `PHONE_REGISTRATION_REQUIRED` 属于账号状态。

## 十三、下一轮必须先修的运行时问题（比补功能更优先）

1. **OkHttp 没有 CookieJar**：bootstrap 拿到的 `JSESSIONID` / `XSRF-TOKEN` 不会带到登录 POST 与 MFA POST，真实 SSO 握手必失败。需在 `NetworkModule` 挂 `CookieJar`（内存实现即可，按 host 隔离）。
2. **ticket 解析取错对象**：`extractTicketFromBody(location)` 传的是 Location 头而不是响应 HTML；Garmin 的 ticket 通常在登录响应页的跳转 URL 里。
3. **缓存命中直接放行**：`startSsoLogin` 只要本地有凭据就 `cachedHit()`，不校验密码、不校验 `tokenExpiresAtMs`；应改为过期才 refresh、失败回落密码登录。
4. **profile 是占位**：`fetchProfile` 请求 `/session` 并把 body 前 24 字符当昵称，需换真实 profile 端点。
5. **REPLACE 型 upsert 会换主键**：唯一键冲突时 Room `REPLACE` 会删旧行插新行，`activity_samples.activityId` 等子表引用会变成孤儿；建议改 `@Upsert` 或先查 id 再更新。

## 十四、闪退排查工具（2026-10-18 追加）

真机上一打开就闪退，但沙箱没有可用模拟器（x86 镜像需要 KVM，ARM64 镜像不支持 x86 主机），
所以先补齐了两样基础设施，让崩溃可定位、回归可验证：

1. **崩溃栈落盘 + 上报页**（`FitSyncApp.kt` / `CrashReportScreen.kt`）
   - 全局 `UncaughtExceptionHandler` 把栈**写进内部存储 `crash-last.txt`**
     （崩溃后进程立刻被杀，只放内存里必然丢失）；
   - 下次冷启动先展示崩溃页，提供 **复制 / 分享 / 忽略进入** 三个按钮，
     可以直接用系统分享把栈发到微信或邮件，不需要连电脑开 logcat。

2. **JVM 冒烟测试**（`app/src/test/java/com/fitsync/HomeSmokeTest.kt`，Robolectric）
   - 建内存库写入账号并读回流（验证 19 张表 DDL 真实可执行）；
   - 渲染首页空状态 / 有账号状态。
   - CI 已在 `assembleDebug` 之前执行 `:app:testDebugUnitTest`。
   - 注意：Robolectric 4.12.2 只支持到 SDK 34，测试用 `@Config(sdk = [34])`；
     测试里显式提供的是**旧**的 `androidx.compose.ui.platform.LocalLifecycleOwner`，
     用来对齐 compose-ui 1.6.8 的真实行为（详见第十五节）。

**已排除的闪退原因**：Room 建表 SQL（19 张表 DDL 全部执行成功）、依赖缺失（Tink/Room/Hilt/Compose 均已在 APK 内）、
Lint 缺失类检查（0 error）、首页 Compose 与数据层链路（JVM 实测通过）。

**结果**：真机栈已拿到，根因与修复见第十五节；v0.1.1 在 Xiaomi 24031PN0DC / API 36 上
实测「冷启动进首页」「进登录向导」均正常。

## 十五、闪退根因与修复（2026-10-18，已解决）

真机栈（Xiaomi 24031PN0DC / API 36）：

```
java.lang.IllegalStateException: CompositionLocal LocalLifecycleOwner not present
  at androidx.lifecycle.compose.FlowExtKt.collectAsStateWithLifecycle(FlowExt.kt:183)
  at com.fitsync.feature.home.HomeScreenKt.HomeScreen(HomeScreen.kt:42)
```

**根因：`lifecycle-runtime-compose 2.8.1` 与 `compose-ui 1.6.8`（BOM 2024.06.00）不是一套。**

| 组件 | 提供的 CompositionLocal |
|---|---|
| compose-ui 1.6.8 | `androidx.compose.ui.platform.LocalLifecycleOwner`（旧） |
| lifecycle-runtime-compose 2.8.1 | `androidx.lifecycle.compose.LocalLifecycleOwner`（新，默认实现直接抛异常） |

`collectAsStateWithLifecycle()` 读新的那个，Compose 1.6.8 只提供旧的那个，两个是**不同的
CompositionLocal**（不是别名）。于是首帧必然抛 `IllegalStateException`——**所有设备都崩，与机型无关**。
编译、Lint、打包全绿是因为 CompositionLocal 属于运行时解析，编译期查不出来。

**修复（v0.1.1）**：

1. `HomeViewModel.accounts`：裸 `Flow` → `StateFlow`（`stateIn(WhileSubscribed(5_000), emptyList())`）；
2. `HomeScreen` / `LoginWizardScreen`：`collectAsStateWithLifecycle()` → `collectAsState()`；
3. 移除 `lifecycle-runtime-compose` 依赖，构建脚本里写明"Compose 升到 1.7+ 之前不要加回来"；
4. 回归测试对齐真实环境：Robolectric 用例只提供**旧**的
   `androidx.compose.ui.platform.LocalLifecycleOwner`，谁把 `collectAsStateWithLifecycle` 加回来，
   测试会像真机一样失败。

**以后要恢复 lifecycle 感知收集**：需要 Compose 升到 1.7.x（连带 Kotlin 升 2.0 + compose 编译器插件），
是独立的一轮升级，不要和功能开发混在一起。
## 十六、Garmin 登录链路（v0.2.0，已实现，待真账号验证）

### 改了什么

| 文件 | 内容 |
|---|---|
| `network/MemoryCookieJar.kt` | 新增。Garmin SSO 是 Cookie 会话制，原先 OkHttp 没有 CookieJar，bootstrap 的 `Set-Cookie` 带不到登录 POST，**必然拿不到 ticket**。现已在 `NetworkModule` 挂上。 |
| `network/CredentialStore.kt` | 新增接口 + `StorableCredential`（含 `accessToken` / `refreshToken` / `tokenExpiresAtMs`）。抽接口是为了让网络层能在 JVM 单测里跑，不依赖 Keystore。 |
| `network/GarminEndpoints.kt` | 新增。CN / 国际区三套域名（SSO、Connect API、Web），测试可整体指向 MockWebServer。 |
| `network/GarminSsoClient.kt` | 重写。真实流程：`GET /sso/signin` 取 `_csrf` → `POST /sso/signin`（**不跟随重定向**，从 302 Location 读 ticket）→ MFA 分支 → `preauthorized` 换 token → `socialProfile` 取真实昵称/头像。 |
| `LoginWizardViewModel` | 去掉"本地有凭据就免密放行"的捷径（安全问题 + 掩盖过期）；账号以真实 `displayName` 为主键维度；成功后保存 token 与过期时间。 |

### 协议细节（照着实现，别改错）

```
GET  {sso}/signin?id=gauth-widget&embedWidget=false&gauthHost=...&service=.../modern
POST {sso}/signin          username / password / embed=false / _csrf
POST {sso}/verifyMFA/loginEnterMfaCode   mfa-code / _csrf / fromPage=setupEnterMfaCode / embed=false
GET  {api}/oauth-service/oauth/preauthorized?ticket=...&clientId=GARMIN_CONNECT_MOBILE_ANDROID_DI&accepts-mfa-tokens=true
GET  {api}/userprofile-service/socialProfile      Authorization: Bearer <access_token>
POST {api}/oauth-service/oauth/token               grant_type=refresh_token
```

CN：`sso.garmin.cn / connectapi.garmin.cn / connect.garmin.cn`
国际：`sso.garmin.com / connectapi.garmin.com / connect.garmin.com`

### 验证情况

- **JVM 单测 5 条全过**（`core-data/src/test/.../GarminSsoClientTest.kt`，MockWebServer）：
  登录成功链路、密码错误映射、MFA 分支、缺 csrf 报错、CookieJar 过滤。
- **生产页面实测**：CN 登录页 HTTP 200，`_csrf` 正则命中，`login-form` / `username` 字段存在。
- **未验证**：真实账号的 `POST /sso/signin` 返回值（ticket 在 Location 还是 HTML）、`preauthorized` 的
  响应结构。这两处若与假设不一致，`GarminSsoClient` 会抛出带 `status code` 的明确异常，
  改 `extractTicket` / `exchangeTicket` 即可，不用动上层。

### 已知限制

- 若 Garmin 触发验证码 / 风控，登录会抛 `RiskBlocked`，需要用户换网络或稍后重试；
- 密码仍然保存在加密仓里（用于 token 失效后重登），这是产品取舍，不是疏漏；
- 尚未做"登录后立即拉数据"——那是同步引擎（下一步）。

### v0.2.1 追加（排障便利）

- 首页与登录页直接显示 `v版本号 (versionCode)`：真机排障时先看版本，避免"装了旧包却按新包排查"。
  背景：v0.2.0 交付后收到"无法从登录页提取 csrfToken"，该文案只存在于 0.1.x，
  0.2.0 的 dex 里不存在——实际是旧包未升级。
- `_csrf` 缺失时的异常补充了 `HTTP 状态 / 页面字节数 / 是否被 Cloudflare 拦截`，
  下次遇到同类问题可以直接判断是风控还是改版。

### v0.2.2（403 处理）

真机 v0.2.1 报「未找到 ticket（status 403）」。用**同样的请求序列 + 假账号**从沙箱复现得到 **401**，
说明 CSRF/Cookie 流程本身正确（401 = 服务端走到了账号校验）；403 意味着请求在账号校验前被拒，
典型原因是会话 Cookie 未带上或 SESSION 与 `_csrf` 不配对。

改动：

1. **403 自动重试一次**：清空 `MemoryCookieJar` → 重新 bootstrap 拿新 SESSION + 新 `_csrf` → 再 POST；
2. **403 / 缺 ticket 的异常带上诊断**：`HTTP 状态 + 实际带上的 Cookie 名 + 响应片段 120 字`；
   若 `Cookie[无]` 说明 CookieJar 没生效，若片段是 Cloudflare 页面则说明是 WAF 而不是代码；
3. 请求头补 `Accept-Language` / `Sec-Fetch-*` / `Upgrade-Insecure-Requests`，减少被 WAF 误判；
4. 测试新增 `403后清空会话重试一次能成功`（6 条全过）。

若重试后仍是 403 且片段是 Cloudflare：说明网页 SSO 这条路被 WAF 拦，应改用 Garmin 移动端
`/mobile/api/login`（JSON，无 CSRF、无 HTML 解析），那是下一步的备选方案。

### v0.2.3：改用移动端 JSON 接口（网页 SSO 被 WAF 拦）

真机 v0.2.2 仍失败，且 UI 只显示了 `RiskBlocked` 的固定文案——**诊断信息在 cause 里被 ViewModel 吞了**。
已修：`describeError()` 会把 cause.message 一起显示。

同时定位到网页 SSO 不可用的原因：GET 登录页能过，POST 被前置拒绝（403）。对照实验（假账号）显示
网页 POST 从普通网络能走到账号校验（401），说明是设备网络侧的 WAF/风控，不是代码。

因此改用**官方 App 的移动端 JSON 接口**，已用假账号在生产环境验证契约（CN + COM 均返回
`401 + INVALID_USERNAME_PASSWORD`，说明参数正确、接口正常处理）：

```
POST {sso}/mobile/api/login?clientId=GCM_ANDROID_DARK&service={mobileService}
  body: {"username","password","rememberMe":true,"captchaToken":""}
  → 200 {"serviceTicketId":"ST-...","responseStatus":{"type":"SUCCESSFUL"}}
  → 401 {"responseStatus":{"type":"INVALID_USERNAME_PASSWORD"}}      // 账号密码错
  → 200 {"responseStatus":{"type":"MFA_REQUIRED"},"customerMfaInfo":{...}}
  → 428/429 限流（Garmin 官方说明：可能锁 24 小时）

POST {sso}/mobile/api/mfa/verifyCode?clientId=...&service=...
  body: {"mfaMethod","mfaVerificationCode","rememberMyBrowser":true,"reconsentList":[],"mfaSetup":false}

mobileService：CN = https://mobile.integration.garmin.cn/gcm/android
               国际 = https://mobile.integration.garmin.com/gcm/android
```

策略：**移动端优先**；只有 400/404/405/5xx（接口不可用）才退回网页表单，
账号密码错误 / 限流 / MFA 一律不重试，避免重复提交凭据。

ticket → token 的 DI clientId 改为按 `2025Q2 → 2024Q4 → DI` 依次尝试并缓存命中的那个。

### v0.2.4：WebView 登录（绕过边缘防护）

真机 v0.2.3 的诊断终于完整了：

```
HTTP 403，Cookie[__cf_bm,__cflb,__cfuid]，响应片段：Access is Denied.
```

两个结论：
1. **没有 SESSION Cookie**，只有之前网页流程留下的 Cloudflare Cookie；
2. `Access is Denied.` 是边缘防护（Cloudflare/Akamai）的拒绝页，不是 Garmin 业务返回。

用「浏览器 UA + OkHttp 的 TLS 指纹 + 残留 __cf_bm」打移动接口，正是被判为伪装客户端的典型组合。

改动：

1. **移动端调用前清空 CookieJar**（移动接口本就不需要 Cookie）；
2. **移动端改用原生 App UA**：`GCM-Android-5.23` + `x-garmin-user-agent`；
3. **新增 WebView 登录**（`GarminWebLoginScreen`）：在真实浏览器引擎里打开
   `{ssoBase}/mobile/sso/en_US/sign-in?clientId=GCM_ANDROID_DARK&service={mobileService}`，
   JS 挑战、TLS、Cookie 全由 WebView 处理；登录成功后从两条路拿 ticket：
   - 重定向 URL 上的 `?ticket=ST-...`（`shouldOverrideUrlLoading`）
   - 页面内 fetch/XHR 响应 JSON 的 `serviceTicketId`（注入 JS 钩子）
   再交给 `GarminSsoClient.completeWithTicket()` 换 token、拉 profile、落库。
4. 网页登录**不保存密码**（只有 refresh token），`CredentialStore.load` 已支持"只有 token 无密码"的记录；
   `refreshSession` 在无密码且 refresh 失败时给出"登录已过期，请重新登录"。

入口：登录向导第 3 步的「改用网页登录（推荐，可过风控）」按钮。

### v0.2.5：修正 URL 构造（真凶）

v0.2.4 的 WebView 截图暴露了根因：地址是 `https://sso.garmin.cn/sso/mobile/sso/...`，**多了一层 `/sso`**。

两个 bug：

1. `ssoBase` 已经是 `https://sso.garmin.cn/sso`，移动端接口却在**域名根**下：
   `https://sso.garmin.cn/mobile/api/login`。直接用 `ssoBase + "/mobile/api/login"` 会拼成
   `/sso/mobile/api/login` → 404（真机上是 403 Access is Denied）。
2. `originOf()` 用 `substringBefore("/sso")` 求站点根，但 `https://` 里就含 `//sso`，
   结果得到 `https:/` —— Origin 头非法，进一步触发风控。

修复：`GarminEndpoints` 增加 `ssoOrigin`（用 `HttpUrl` 正确解析 scheme/host/port），
移动端接口、WebView 登录页、Referer、Origin 全部改用 `ssoOrigin`。

验证（生产环境实测）：

```
https://sso.garmin.cn/mobile/api/login                     → 401 INVALID_USERNAME_PASSWORD ✅
https://sso.garmin.cn/sso/mobile/api/login                 → 404 ❌（修复前）
https://sso.garmin.cn/mobile/sso/en_US/sign-in?clientId=…  → 200 / 30 KB ✅
https://sso.garmin.cn/sso/mobile/sso/en_US/sign-in?…       → 404 ❌（修复前）
```

测试补了两条回归断言：移动端路径必须是 `/mobile/api/login`（不带 `/sso`）、
`ssoOrigin` 必须等于 `https://sso.garmin.cn`。core-data 共 10 条全过。

### v0.2.6：区域选择器根本点不动

真机反馈"国际区登不上"，追问后确认是**点不了**，不是登录失败。

根因：`RegionSegmentedControl` 接收了 `onRegionSelected` 却从未调用——`Column` 上少了
`.clickable { onRegionSelected(code) }`。编译期只有一条 `Parameter 'onRegionSelected' is never used`
的警告，很容易漏掉。

修复：给两个选项补 `clip + clickable`；新增 Robolectric 回归测试
`RegionSelectorTest.点击国际区会回调com`（点击后断言回调值为 `com`），
以后这个参数再"接了不用"就会直接红。

**教训**：编译器对"未使用参数"的警告要当成缺陷处理，尤其是回调类参数。

### v0.2.7：第二个账号存不进去（EncryptedFile 不能覆盖）

真机加国际区账号时报：

```
登录失败：output file already exists, please use a new file: credentials.json
```

根因：`EncryptedFile.openFileOutput()` **拒绝覆盖已存在文件**。中国区第一次登录时文件还不存在所以成功，
加第二个账号时 `save()` 直接抛异常——注意这意味着**国际区其实已经认证成功、token 也拿到了**，只是落盘失败。

修复（`CredentialVault.writeRaw`）：

1. 先把旧文件重命名为 `credentials.json.bak`；
2. 写新文件；成功则删备份，失败则回滚备份；
3. `readRaw` 检测到"主文件缺失但备份存在"时自动恢复（覆盖写入途中被杀的情况）。

顺带补了 WebView 的 `onReceivedError`（主框架加载失败会显示错误码与描述），
之前加载失败只有一个转圈，看不出原因。

**教训**：`EncryptedFile` 是"一次性写入"语义，不能当普通 FileOutputStream 用；
涉及多账号追加写时，必须整文件重写 + 备份。

### v0.2.8：账号状态映射（PHONE_REGISTRATION_REQUIRED 等）

真机国际区返回：

```
移动端登录失败：PHONE_REGISTRATION_REQUIRED（HTTP 200）
```

这是 Garmin 服务端的**账号状态**，不是代码问题：该账号尚未完成手机号验证，
官方 App 登录时同样会先要求绑定/验证手机号。

处理方式（二选一）：

1. 先在 connect.garmin.com（或官方 App）用该账号登录一次，完成手机号验证后回本 App 重试；
2. 在本 App 点「改用网页登录」，在 WebView 里走完 Garmin 自己的验证流程。

同时补齐了常见状态映射：

| status | 处理 |
|---|---|
| `PHONE_REGISTRATION_REQUIRED` | 提示去官网完成手机号验证，或改用网页登录 |
| `ACCOUNT_LOCKED` / `ACCOUNT_DISABLED` | 归为风控，提示去官网处理 |
| `CAPTCHA_REQUIRED` | 归为风控，提示改用网页登录 |
| 其它 | 显示原始 status + 尝试网页登录 |

## 十七、同步引擎（v0.3.0）

### 结构

| 文件 | 职责 |
|---|---|
| `core-data/.../network/GarminConnectClient.kt` | 调 Connect API：日汇总、睡眠、活动列表；字段缺失一律降级为默认值 |
| `core-data/.../sync/GarminSyncEngine.kt` | 编排：按日增量、token 失效自动刷新、写库、推游标、记 `sync_logs` |
| `app/.../sync/SyncWorker.kt` | WorkManager：每 6 小时一次（联网约束、指数退避）+ `SyncScheduler` |
| `HomeViewModel` / `HomeScreen` | 「立即同步」按钮、同步结果文案、账号卡显示上次同步时间 |

### 端点

```
GET {api}/usersummary-service/usersummary/daily/{displayName}?calendarDate=yyyy-MM-dd
GET {api}/wellness-service/wellness/dailySleepData/{displayName}?date=yyyy-MM-dd&nonSleepBufferMinutes=60
GET {api}/activitylist-service/activities/search/activities?limit=20&start=0
Authorization: Bearer <access_token>
```

### 策略

- 首次无游标：回填 7 天；有游标：从「上次同步日 - 1」续上，避免跨天缺口；
- 活动取最近 20 条，靠 `UNIQUE(account_id, external_id)` 幂等去重；
- `access_token` 401 → 用 refresh_token 换新再重试一次；refresh 失败且无密码 → 提示重新登录；
- 失败只记日志、**不更新 `last_sync_at_ms`**（避免把失败伪装成"刚同步过"）；
- 游标缺失时插入而不是 UPDATE（`advance()` 是 UPDATE，行不存在等于没推进）。

### 测试（core-data 16 条 + app 4 条，共 20 条全过）

- `GarminConnectClientTest`：日汇总/睡眠/活动解析、空数据、401；
- `GarminSyncEngineTest`：真实 Room 内存库 + MockWebServer，验证三张表落库、游标推进、日志、账号同步时间。

### 已知限制

- 只同步「日汇总 / 睡眠 / 活动」三类，心率样本、GPS 轨迹、身体指标尚未接；
- `sync_logs.trigger` 固定写 `manual`，定时任务应区分 `cron`；
- 失败时账号状态没有单独的「同步失败」态（`AccountStatus` 缺该枚举），首页仍显示正常；
- WorkManager 登记用 `runCatching` 兜底：初始化失败不能让 App 启动崩溃（测试环境就踩到了）。

## 十八、首页仪表盘（v0.4.0，Apple HIG 风格）

结构（从上到下）：日期副标题 + 大标题「今天」+ 右上角「同步」→ 步数大卡 → 睡眠/静息心率 双列小卡 →
最近活动 → 账号列表 → 添加数据源。空态是居中引导。

规范：`core-ui/theme/FitTheme.kt` 新增 Apple 色板（AppleBg/AppleCard/AppleLabel/AppleSecondary +
6 个强调色）；卡片统一 18dp 圆角、16dp 内边距；列表项间距 14dp、内容边距 20dp；
步数 48sp / 次级指标 24sp / 标签 13sp。

数据来自 Room 新增的三个聚合查询（跨账号）：
`daily_summaries` 最新一天、`sleep_sessions` 最新一次、`activities` 最近 5 条 + 总数。

测试：LazyColumn 的账号区不在首屏，测试用 `performScrollToIndex` 滚到可见再断言。

## 十九、同步失败的定位（v0.4.0）

真机报错「该账号还没完成手机号验证」其实是**同步**时冒出来的：`finishLogin` 用
`runCatching { exchangeTicket() }.getOrNull()` 吞掉了「ticket 换 token」的异常，
导致账号登录成功但 `accessToken` 为空；同步时 `ensureToken` 只能走"用密码重新登录"，
于是撞上国际区账号的 PHONE_REGISTRATION_REQUIRED。

已修：

1. `GarminSsoResult` 增加 `tokenError`，登录成功页直接显示换 token 失败的原因；
2. `DI_CLIENT_IDS` 增加 `GCM_ANDROID_DARK` 候选；
3. `GarminSyncEngine.ensureToken` 失败时提示「本地没有访问令牌，请到登录页重新登录一次」。

下一步拿到 tokenError 的原文（HTTP 码 + 响应片段）就能定位是 clientId 不对还是端点变了。

## 二十、首页改为 Miuix / HyperOS 设计语言（v0.4.1）

按 `miuix-skill`（0.9.4）的规范重做首页，语义 token 取自其 `Colors.kt` 浅色值：

| 角色 | token | 值 |
|---|---|---|
| 主色 | primary | `#3482FF` |
| 画布 | surface | `#F7F7F7` |
| 卡片 | surfaceContainer | `#FFFFFF` |
| 摘要文字 | onSurfaceVariantSummary | `#99000000` |
| 次要操作 | onSurfaceVariantActions | `#66000000` |
| 分割线 | dividerLine | `#E0E0E0` |
| 错误 | error | `#E94634` |

结构遵循其分组范式：**`SmallTitle` + `Card`**，内容边距 12dp、卡片圆角 20dp、
卡片内行高 13dp 上下内边距、行间用 0.7dp 分割线而不是间距；主色只用于「同步」与「添加数据源」。
Miuix 没有 `success` 语义色，账号「正常」的状态绿 `#34C759` 由应用自定义（代码已注明）。

**为什么不直接依赖 miuix-ui**：`top.yukonga.miuix.kmp:miuix-ui-android:0.9.4` 依赖
Kotlin **2.4.20** 与 Compose Multiplatform **1.12.0**；本工程是 Kotlin 1.9.24 + Compose 1.6.8。
引入需要连带升级 Kotlin 2.4 / AGP / Gradle / KSP（kapt 迁移），属于独立的一轮工具链升级，
不能和 UI 改版混在一起。当前按 token 与组件解剖手工实现，视觉与交互对齐；升级后可直接替换为真组件。

技能已安装到 `~/.codebuddy/skills/miuix`，后续可直接调用。

## 二十一、v0.5.0：登录只走网页 + 本地导入

### 1. ticket 换 token 一直 400 的真因：缺 OAuth1 签名

真机日志 `ticket 换 token 失败：clientId=GCM_ANDROID_DARK HTTP 400`。用 garth 的实现对照后确认，
原实现三处都不对：

| 原实现 | 正确做法 |
|---|---|
| `preauthorized?ticket=..&clientId=..` | `preauthorized?ticket=..&service={mobileService}&accepts-mfa-tokens=true` |
| 无签名 | **OAuth1 HMAC-SHA1 签名**（consumer key/secret 为 Garmin 移动端公开凭据） |
| 把响应当 JSON 解析 | 响应是 **form-urlencoded**：`oauth_token=..&oauth_token_secret=..` |
| 直接拿 access_token | 还要 `POST /oauth-service/oauth/exchange/user/2.0`（OAuth1 签名，`audience=GARMIN_CONNECT_MOBILE_ANDROID_DI`）才拿到 access_token |

实测证据（生产端点，假 ticket）：

```
无签名                → 400 Bad Request（不带原因）
有签名 + login-url    → 401 "'service' and 'ticket' parameters are both required"
有签名 + service      → 401 "Expected parameters oauth token or SSO ticket were not provided"
```

401 说明签名已被接受、进入参数校验；最后一条是因为假 ticket 在 SSO 侧不存在。
即：签名与参数形状正确，只差一个真实 ticket。

新增 `network/OAuth1Signer.kt`（RFC3986 编码 + HMAC-SHA1 + Authorization 头）。

### 2. 登录后从未保存凭据（重大缺陷）

全工程搜索 `credentialStore.save` 为 **0 处调用**。所以即使登录成功，
同步时 `credentialStore.load()` 永远返回 null → 只能退回"用密码重新登录" → 撞各种账号状态。
已在 `LoginWizardViewModel.persistAccountAfterLogin` 落库时保存 access_token / refresh_token /
**oauth1_token + oauth1_secret**（刷新要用），并让 `GarminSyncEngine.ensureToken` 刷新后写回。

### 3. 登录入口只剩网页

- 删掉账号密码登录、MFA 页、移动端 JSON 登录尝试；
- 向导第 3 步只有「风险知情 + 打开 Garmin 登录页」；
- 账号密码只输入在 Garmin 自己的页面里，本应用**不保存密码**。

### 4. 本地导入（不依赖账号/网络）

新增 `data/importer/LocalDataImporter.kt`：

- 支持 **Garmin 官方导出 zip**（自动遍历包内 JSON，不依赖目录名）；
- 支持单个 **JSON**（UDS / summarizedActivities / sleepData 结构）；
- 支持 **CSV**（中英文表头，见 `CSV_ALIASES`）；
- 解析用「字段嗅探 + 递归扫描」，认不出就跳过，不中断整次导入；
- 落库到 `daily_summaries` / `sleep_sessions` / `activities`，并建一个 `sourceType=file` 的本地账号；
- 本地账号不参与云端同步（`syncAll` 已过滤），同步按钮会给"无需同步"的提示。

入口：首页底部「导入本地数据（zip / JSON / CSV）」，空态也有入口。

### 5. 同步账号匹配修正

`GarminSyncEngine` 原来用 `account.displayName` 去查凭据，但凭据 key 是 `externalUserId`；
没有 profile 时两者不同 → 凭据永远查不到。已改为 `externalUserId`。

### 测试

21 条全过：SSO 6（含 OAuth1 头断言、form 响应解析、刷新）、Connect 解析 5、
同步引擎集成 1、**本地导入 5**（zip/CSV/未知文件/重复导入/本地账号）、首页 3、区域点击 1。

### 仍未验证

真实 ticket 走完 `preauthorized → exchange → profile` 全链路。若失败，异常会带 HTTP 状态与响应片段。

### v0.5.1：网页登录页白屏

真机截图：`/mobile/sso/en_US/sign-in` 一直转圈。

原因：那个地址是 **React SPA**（响应里是 `/mobile/assets/index-d10b89ff39a6804af60e44186d1f4230.js` +
17 处 MFA 字样），页面要等 JS 包和外部字体（fonts.garmin.com）全部加载完才渲染，
WebView 里很容易长时间白屏。

改用**服务端渲染**的 `/sso/signin`，实测响应 12.6 KB，直接含
`login-form` / `name="username"` / `name="password"` / `name="_csrf"`，不需要等 JS：

```
{ssoBase}/signin?id=gauth-widget&embedWidget=true&clientId=GCM_ANDROID_DARK
  &gauthHost={ssoOrigin}/sso
  &service={mobileService}&source={mobileService}
  &redirectAfterAccountLoginUrl={mobileService}
  &redirectAfterAccountCreationUrl={mobileService}
```

`service` 用 `mobileService` 是为了**与后续 `preauthorized?service=` 一致**——CAS 要求 ticket 的
受众与校验时的 service 匹配，否则 ticket 不被接受。

另外：
- WebView 的 UA 显式设为标准 Chrome 移动 UA（默认 UA 带 `; wv`，容易被边缘防护判成非浏览器）；
- 15 秒未加载完给出提示 + 「重新加载」按钮；
- `onPageStarted` 也检查 URL 里的 ticket（302 重定向时 `shouldOverrideUrlLoading` 不一定触发），
  命中就 `stopLoading()`，避免继续加载不可达的 service 域名。

### v0.5.2：真实 ticket 仍 401 —— 网页登录与移动端是两套认证体系

真机（v0.5.1）拿到真实 ticket 后仍报：

```
HTTP 401 Unauthorized: Expected parameters oauth token or SSO ticket were not provided.
```

定位过程：

1. 用真实 ticket 与假 ticket 的错误**完全一致**，说明不是 ticket 真伪，而是**参数体系不对**；
2. 补测了加签名后的 `clientId` 组合（此前只测过 service / login-url），全部同样 401；
3. 查开源实现（`dotnet.garmin.connect`）发现：**网页登录**流程里，
   `service` / `gauthHost` / `source` / `redirectAfter*` 全部是 `https://sso.garmin.com/sso/embed`，
   换 token 时用 `login-url=https://sso.garmin.com/sso/embed`；
   而 `login-url=https://mobile.integration.../gcm/android` 是**移动端登录**流程用的。
4. 另有开源实践明确写到：「Web 登录和移动端 API 是两套独立认证体系，不互通」。

结论：v0.5.1 用 `/sso/signin`（Web）登录，却拿 `service=mobileService`（移动端）去换 token，
ticket 的受众与校验方不匹配 → 服务端认为"没有有效的 SSO ticket"。

修复：

| 位置 | 改动 |
|---|---|
| `GarminWebLoginScreen.webLoginUrl` | `service` / `gauthHost` / `source` / `redirectAfter*` 全部改为 `{ssoOrigin}/sso/embed` |
| `GarminSsoClient.exchangeTicket` | 依次尝试三组参数：`login-url=sso/embed`（首选）、`service=mobileService`、`clientId=GCM_ANDROID_DARK`；全失败时把每组的状态码 + Garmin 原话一起抛出 |
| `MemoryCookieJar` | 新增 `importFromHeader()`，把 WebView 的 SSO Cookie 灌进 OkHttp（ticket 与会话绑定） |
| `GarminWebLoginScreen` | 截获 ticket 时一并取出 `CookieManager.getCookie(ssoHost)` 传给网络层 |

登录成功后 CAS 跳 `{ssoOrigin}/sso/embed?ticket=ST-...`，由 `onPageStarted` / `shouldOverrideUrlLoading` 截获。

验证：登录页 `service=sso/embed` 实测 HTTP 200 / 12 KB，含 `login-form` / `username` / `password` / `_csrf`。
测试 21 条全过（SSO 用例把断言改为"首选 login-url=sso/embed"）。

### v0.5.2 真机验证：网页登录全链路打通 ✅

2026-10-18，Xiaomi 24031PN0DC / Android 16，实测通过：

```
WebView 打开 /sso/signin?service=sso/embed
  → 输入账号密码（Garmin 官方页面）
  → CAS 跳转 /sso/embed?ticket=ST-...
  → onPageStarted 截获 ticket + 同步 SSO Cookie
  → GET preauthorized?ticket=..&login-url=.../sso/embed  [OAuth1 签名]  ✅
  → POST oauth-service/oauth/exchange/user/2.0          [OAuth1 签名]  ✅
  → GET userprofile-service/socialProfile               [Bearer]      ✅
  → 账号 + access_token + oauth1_token/secret 落加密仓
```

**登录方式到此定型：只用网页登录，不保存密码。**

关键结论（写死在这里，避免以后再走弯路）：

1. **Web 登录与移动端 API 是两套认证体系**。网页登录的 ticket 只能配
   `login-url={ssoOrigin}/sso/embed`；移动端 ticket 才配
   `login-url=https://mobile.integration.{domain}/gcm/android`。混用必 401。
2. `preauthorized` **必须** OAuth1 签名（HMAC-SHA1），否则 400 且无错误说明。
3. `preauthorized` 响应是 **form-urlencoded**，不是 JSON；access_token 还要再走
   `exchange/user/2.0`。
4. ticket 与 SSO 会话绑定，换 token 时要带上 WebView 的 Cookie。
5. 登录完成后**必须** `credentialStore.save()`（此前整个工程 0 处调用，
   导致登录成功也同步不了）。

### 待验证：同步是否真的能拉到数据

登录已拿到 access_token，下一步点首页「同步」验证：

- 成功：显示"已同步 N 条记录"，账号卡出现"上次同步"时间；
- 401：`ensureToken` 会用 oauth1 重新 exchange（不是用 refresh_token），
  失败信息会写"本地没有访问令牌，重新获取也失败（...）"。

### v0.5.3：同步完成后"数据消失"

现象：点同步 → 提示"已同步 N 条记录" → 首页数字变 0 / 数据像没了。

根因：`fetchDailySummary` 对**没有数据的日期**（通常是今天）也会返回 HTTP 200 + 全 0 字段，
旧实现照单写库；首页查询是 `ORDER BY date DESC LIMIT 1`，于是今天的 0 值记录把真实数据顶掉。

修复三处：

1. `GarminConnectClient.fetchDailySummary`：steps / distance / activeCalories / restingHr /
   stress / spo2 **全为 0 时返回 null，不落库**；
2. `Dao.observeLatest()`：日汇总加 `WHERE steps>0 OR distance_m>0 OR calories_active>0 OR resting_hr>0`，
   睡眠加 `WHERE total_sleep_min>0`——**历史上已经写进去的 0 值行也不会再顶掉展示**；
3. `HomeViewModel.dashboard`：加 `.catch {}`，任何一条查询出错都不会让 combine 流终止
   （否则首页会永远停在初始的全 0 状态，表现同样是"数据消失"）。

另外首页标题下新增一行：`库里已有 N 天汇总 · M 条活动`，用于一眼确认数据是否真的在库里。
新增回归测试 `全0的日汇总视为无数据`，core-data + app 共 22 条全过。

## 二十二、双区互相同步 + 主账号（v0.6.0）

### 先说限制

Garmin **没有**把健康数据写入另一个账号的接口（日汇总 / 睡眠 / 心率都只读）。
唯一可跨账号写入的是**活动**（FIT/TCX 上传）。所以"中国区同步到国际区"在本工程里的实现是：

> 两个账号各自从云端拉取 → 本地按日期对齐 → **字段级合并** → 首页展示一份完整档案。

数据不会出现在对方 Garmin 账号的云端页面上。如果需要"活动真的进到另一个账号"，
下一步可以走 `POST /upload-service/upload`（FIT），但仅限活动，且要先把活动导出成 FIT。

### 实现

| 文件 | 内容 |
|---|---|
| `data/settings/PrimaryAccountStore.kt`（新增） | 主账号存 SharedPreferences（不加表字段，避免 destructive migration 清库） |
| `FitDataRepository.observeMergedLatestDaily/Sleep/Activities` | 跨账号合并：按日期（睡眠按结束时间 12 小时内）对齐，主账号有值用主账号，为 0 的字段用另一区补 |
| `HomeViewModel.dashboard` | `primaryAccountId.flatMapLatest { combine(merged... ) }`，切换主账号立即重新合并，不需要重新同步 |
| `HomeScreen` | 账号卡右侧「设为主账号 / 主账号」；标题下显示 `主账号 xxx · 另一区补齐 · N 天 / M 条活动` |
| `GarminSyncEngine.syncAll(primaryFirstId)` | 主账号排在最前，先拿到它的最新数据 |

### 合并规则（写死，避免歧义）

- **日汇总**：取最新有数据的一天；同一天两个账号都有记录时，主账号的
  steps / 距离 / 楼层 / 热量 / 静息心率 / HRV / 呼吸 / 压力 / Body Battery / 血氧
  **逐字段判空**，主账号为 0 才用另一区补。
- **睡眠**：结束时间相差 12 小时内视为同一晚，字段级互补。
- **活动**：按 `externalId` 去重，主账号优先，按开始时间倒序取前 5。

### 测试

新增 `MergedDataTest` 4 条：同日主账号优先 + 字段补齐、换主账号后基准变化、
主账号缺当天数据时回退、活动去重。全工程 **26 条全过**。

### v0.7.0：活动跨账号复制（真正写入另一个 Garmin 账号）

需求：把主账号的运动数据 + 健康数据同步到另一个账号。

**能力边界（Garmin 侧硬限制，不是本工程的取舍）**：

| 数据 | 能否写入另一个账号 | 依据 |
|---|---|---|
| 活动（跑步 / 骑行 / 健身 / 游泳…） | **能** | `POST /upload-service/upload`（multipart，FIT/TCX/GPX/zip），即网页版「导入数据」 |
| 心率 / 睡眠 / 喝水 / 步数 / 日汇总 | **不能** | Garmin 无写入 API，只能由设备同步 |
| 活动详情里的 GPS / 心率曲线 | 能 | 随 FIT 文件一起上传 |

实现：

- `GarminConnectClient.downloadActivityFile()`：
  `GET {api}/download-service/files/activity/{activityId}`（Bearer），
  响应是 zip，解压出第一个 `.fit/.tcx/.gpx`；
- `GarminConnectClient.uploadActivityFile()`：
  `POST {api}/upload-service/upload`，multipart 字段 `file`；
- `ActivityCopyService`：
  源账号本地活动（最近 N 条）→ 下载 → 上传到目标账号；
  目标账号已有的活动按**开始时间**比对并跳过，避免重复上传；
  凭据缺失时用 oauth1 刷新；
- 首页新增「复制活动到另一账号」（仅两个 Garmin 账号时出现），
  弹确认框明确告知"健康数据无法复制"。

健康数据仍然只能**在本应用内合并展示**（v0.6.0 的 `observeMerged*`）。

未验证：真实账号的复制效果（沙箱无账号）。若上传失败，`uploadActivityFile` 返回 null，
UI 会显示"上传失败"，此时需要看 Garmin 返回体（下一步可把响应体带进错误信息）。

### v0.8.0：健康数据跨区搬运（按 DailySync 的方式）

查了 dailysync.cn 的文档，它的机制不是"调用写接口"，而是：

> 前提：中国区、国际区都必须有「**主要可穿戴设备**」标签，**必须是同一块手表且昵称相同**。
> 可搬运：睡眠、HRV（**只能最近一个月**）、体重、步数、静息心率、压力等。
> 缺失设备标签的补救：用 Garmin Express 切换验证域，分别登录双区账号插线同步一次。

这印证了原理：**Garmin 只接受「已绑定设备」上传的 monitoring 数据**。
所以做法是生成 **monitoring FIT**，以目标账号已绑定设备的身份（serial number / product）上传。

实现：

| 文件 | 内容 |
|---|---|
| `data/health/MonitoringFitBuilder.kt`（新增） | 用官方 `com.garmin:fit:21.176.0` 生成 monitoring FIT：FileId(MONITORING_B) + DeviceInfo + Monitoring(每 15 分钟步数) + SleepLevel(每 5 分钟阶段) + StressLevel(每小时) |
| `GarminConnectClient.fetchDevices()` | `GET {api}/device-service/deviceregistration/devices`，宽松解析出 serialNumber / productId / primary |
| `data/health/HealthSyncService.kt`（新增） | 源账号最近 7 天日汇总 + 睡眠 → 生成 FIT → 上传到目标账号；目标账号无设备时直接给出可执行提示 |
| 首页 | 「搬运健康数据到另一账号（实验）」，确认框写明前提与"睡眠/HRV 仅 1 个月"的限制 |

**依赖**：`com.garmin:fit:21.176.0`（Maven Central 官方发布，无 AWT 依赖，APK 增大 ~1 MB）。

### 尚未验证（重要）

沙箱没有真实账号，以下三点只能真机确认：

1. `device-service/deviceregistration/devices` 的响应结构与解析是否匹配（代码是宽松递归解析，字段名猜了 3 种）；
2. Garmin 是否接受这种 monitoring FIT（可能要求更多消息：MonitoringInfo / DeviceSettings / 正确的 productId 对应表）；
3. 若接受，日汇总会以"设备上传"的形式出现，还是被当成导入文件忽略。

失败时的表现：UI 显示"已搬运 0 天 / 失败 N 天"或"目标账号没有绑定设备"。
下一步若上传被拒，需要把 Garmin 的响应体带回提示（当前 `uploadActivityFile` 只返回 null）。

### v0.9.0：打开 App 自动同步

同步逻辑从 `HomeViewModel` 抽到 `SyncCoordinator`（Application 级作用域）：

- `MainActivity.onStart()` → `autoSyncOnOpen()`：冷启动与回到前台都会触发；
- 节流 5 分钟（SharedPreferences `last_auto_sync_ms`），避免切前后台反复拉数据；
- 自动同步是**静默**的：没有账号/失败不弹提示，成功也不打扰（手动同步才显示结果）；
- 手动「同步」、本地导入、活动复制、健康搬运全部走同一个协调器，UI 状态统一来自
  `SyncCoordinator.state`，所以自动同步的结果也能在首页看到；
- WorkManager 的 6 小时周期任务保留，两者不冲突（Worker 只跑一次，协调器只管前台）。

为什么必须抽出来：ViewModel 只在首页可见时存在，App 启动那一刻可能还没创建，
放在 ViewModel 里做不到"打开就同步"。

### v0.9.1：移除 Fitbit + 首页下拉同步

- 登录向导第 1 步删掉 Fitbit 选项（只保留 Garmin 与本地文件导入）；
- 首页支持下拉刷新：`androidx.compose.material.pullrefresh`（Material2），
  下拉即调用 `SyncCoordinator.syncNow()`，指示器颜色跟随 Miuix token。

为什么用 Material2 的下拉刷新而不是 material3：
当前 material3 是 1.2.1（BOM 2024.06.00），`PullToRefreshBox` 要到 1.3.0 才有；
1.2.1 只有实验性的 `PullToRefreshContainer` + `PullToRefreshState`，`isRefreshing`
不是 State，无法可靠驱动 UI。Material2 的 `pullRefresh` 是成熟 API，与 material3 可共存，
代价是 APK 略增（~0.5 MB）。

### v0.9.2：账号昵称可改 + 同步动画去重

**昵称**：点账号名（带 ✎ 提示）→ 弹框修改，可「恢复默认」。
关键约束：**不能改 `accounts.display_name`**——它是 Garmin 的真实用户名，
被当作 Connect API 的路径参数（`/usersummary-service/usersummary/daily/{displayName}`），
改了同步会 404。因此昵称存在 `AccountAliasStore`（SharedPreferences `fitsync_aliases`），
只影响显示；有自定义昵称时，第二行会附加「原名 xxx」便于对照。

**同步动画**：原来同时出现两个（下拉指示器 + 右上角转圈）。现在：

| 触发方式 | 指示器 |
|---|---|
| 用户下拉 | 顶部下拉指示器 |
| 点「同步」/ 自动同步 | 只有右上角文字变「同步中…」，不转圈、不显示下拉指示器 |

实现：`rememberPullRefreshState(refreshing = syncing && pulled)`，
`pulled` 只在下拉回调里置 true，`syncing` 结束后复位。

### v0.9.3：下拉指示器避让挖孔屏 + 图标方案

**指示器被摄像头挡住**：edge-to-edge 下 `PullRefreshIndicator` 贴在内容顶部（y=0），
正好落在状态栏/挖孔区域。改为 `statusBarsPadding() + padding(top = 10.dp)`，
跟随系统状态栏高度下移。右上角「同步」文字不受影响。

**图标**：原来 manifest 用的是 `@android:drawable/ic_menu_agenda`（系统菜单图标），
所以看起来非常粗糙。已准备 4 个原创几何方案（无第三方素材、可自由商用）：

| 方案 | 主题 | 配色 |
|---|---|---|
| A 双环同步 | 两个开口环 + 中心心跳，表达"循环/同步" | 蓝紫 #4C8DFF→#7B5BFF |
| B 山与心跳 | 山峰 + 心率线，最直观的运动健康 | 青绿 #12B5A5→#3ECF5B |
| C 心跳线 | 极简单条心率线 | 深蓝 #1B3C6E→#3482FF |
| D 双点互联 | 两个端点 + 双向弧，表达"两区互传" | 橙红 #FF8A3D→#FF3B30 |

预览见 `/workspace/icon-options.png`。选定后接入方式：
SVG 白色图形 → `drawable/ic_launcher_foreground.xml`（VectorDrawable），
背景渐变 → `drawable/ic_launcher_bg.xml`（shape gradient），
`mipmap-anydpi-v26/ic_launcher.xml` 组合（minSdk 26，无需低版本 PNG 兜底）。

<div align="center">
  <img src="docs/icon.png" width="120" alt="FitSync" />

# FitSync

把 Garmin 中国区与国际区的数据聚合到一个 App：步数、睡眠、心率、运动记录统一查看。

![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white)
![Compose](https://img.shields.io/badge/Compose-BOM%202026.09-4285F4?logo=jetpackcompose&logoColor=white)
![Miuix](https://img.shields.io/badge/Miuix-0.9.4-FF6900)
![License](https://img.shields.io/badge/License-Apache--2.0-blue)
![Release](https://img.shields.io/github/v/release/yaoiwi/FitSync?color=green)

**简体中文** | [English](README_EN.md)
</div>

## ✨ 功能特色

| | | |
|:---:|:---:|:---:|
| **🧩 双区账号**<br>中国区与国际区同时登录，本地合并展示，主账号决定首页口径 | **🔁 活动互传**<br>把运动记录复制到另一个账号，换区不用重练 | **🏃 运动详情**<br>配速、心率、海拔曲线，一键生成成绩卡片分享 |
| **📊 趋势与周报**<br>近 7 / 30 天变化，以及本周和上周的直接对比 | **🛌 睡眠分期**<br>深睡 / 浅睡 / REM / 清醒，一条彩带看清整晚 | **⏳ 历史回填**<br>活动翻页拉到最早，健康数据补齐一年 |
| **📑 分段与区间**<br>每公里配速、步频功率曲线、心率区间占比 | **🎛️ 同步策略**<br>间隔 6/12/24 小时或仅手动，可限定仅 Wi-Fi | **🩺 健康搬运**<br>步数、睡眠、压力打包成 monitoring FIT 上传 |
| **🔐 本地优先**<br>数据只存手机，令牌用 Tink 加密 | **📤 数据导出**<br>JSON 可再导入，CSV 三张表直接进表格软件 | **💾 备份恢复**<br>整个数据库一键备份，换机直接还原 |
| **🌗 深色模式**<br>跟随系统 / 浅色 / 深色，全站即时生效 | **🔍 筛选搜索**<br>按运动类型、月份过滤，也能搜名称 | **🧾 同步记录**<br>每次同步的时间、条数、失败原因都留痕 |

## 📱 界面

<p align="center">
  <img src="docs/screenshots/accounts.jpg" width="300" alt="账号管理">
  &nbsp;&nbsp;&nbsp;
  <img src="docs/screenshots/settings.jpg" width="300" alt="设置">
</p>

- 基于 [Miuix](https://github.com/YuKongA/miuix) 0.9.4（主题、卡片、开关、列表项、浮动导航），
  在其上补了自研组件：页面骨架 `HyperPage`、液态玻璃导航栏、分段控件、输入 / 确认对话框
- 液态玻璃底部导航栏：真实背景模糊 + 折射 + 双光源高光，指示器弹簧滑动、拖动跟手
- 上滑时大标题折叠、列表两端弹性回弹、顶栏渐进模糊
- 四个页签：概览 / 活动 / 账号 / 设置；触感反馈可关闭

> 液态玻璃依赖 Android 13（API 33）的 RuntimeShader；更低版本自动降级为半透明 + 高光描边。

## 📖 使用文档

到 [Releases](https://github.com/yaoiwi/FitSync/releases/latest) 下载 APK 直接安装。每个版本同时附带 `apk-sha256`，下载后可核对：

```bash
sha256sum -c apk-sha256
```

App 内「设置 → 关于」也能看到当前安装包的 SHA-256 与签名证书指纹，点一下即可复制。

1. 打开 App，点右上角「添加账号」，登录 Garmin 中国区或国际区（支持 MFA）
2. 首页下拉同步，第一次回填最近 7 天；之后打开即同步，后台每 6 小时一次
3. 两个区都登录后，可在「账号 → 数据」里复制活动、搬运健康数据、导出本地记录

- 系统要求 **Android 8.0（API 26）** 及以上
- 液态玻璃效果需要 **Android 13（API 33）** 及以上，低版本自动降级
- 遇到问题去 [Issues](https://github.com/yaoiwi/FitSync/issues)；构建与签名见下文

## 🔨 构建

需要 **JDK 21**、Android SDK Platform **37**（Build-Tools 36+），Gradle 由 wrapper 提供（9.6.1）。

```bash
echo "sdk.dir=/path/to/Android/sdk" > local.properties
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

跑单元测试：

```bash
./gradlew :app:testDebugUnitTest
```

Release 包默认开启 R8 与资源压缩（19 MB → 约 3.6 MB），规则在 `app/proguard-rules.pro`。

<details>
<summary>签名配置（开发 / 发布两条路径）</summary>

由环境变量自动切换，两种都不会出现「每次构建签名随机变化」。

**默认：仓库内的固定 debug keystore**

`signing/debug.keystore` 提交在仓库里，密码是 Android 公开值（`android` / `androiddebugkey`）。CI 没配 Secrets、本地没导出环境变量时都用它。⚠️ 不能上架应用商店。

**发布：自己的 release keystore**

```bash
# 1. 生成（Termux 或电脑，需 JDK）
keytool -genkeypair -v -keystore fitsync-release.jks -alias fitsync \
  -keyalg RSA -keysize 2048 -validity 10000 -storetype JKS \
  -storepass 你的密码 -dname "CN=FitSync, OU=Dev, O=yaoiwi, C=CN"

# 2. 转单行 Base64
base64 -w 0 fitsync-release.jks
```

在 `Settings → Secrets and variables → Actions` 建四个 Secret：`KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。CI 的 `Prepare signing` 步骤会解码使用；本地则导出 `KEYSTORE_PATH` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` 四个环境变量。

> 从 debug 切到 release 后，手机上已安装的旧版**必须先卸载**才能装新版。

```bash
# 核对签名：两处应输出同一串指纹
keytool -list -v -keystore fitsync-release.jks -alias fitsync -storepass 你的密码 | grep SHA256
apksigner verify --print-certs app-debug.apk | grep SHA-256
```

</details>

## ⚠️ 免责声明

- 本项目使用 Garmin 的非公开接口，**随时可能失效**，账号也存在被风控的可能
- 仅建议用于同步**你自己**的账号数据
- 非官方客户端，与 Garmin Ltd. 无隶属关系；Garmin 是 Garmin Ltd. 的注册商标
- 请自行评估风险，作者不对账号异常、数据丢失负责

## ⭐ Star History

[![Star History Chart](https://api.star-history.com/svg?repos=yaoiwi/FitSync&type=Date)](https://star-history.com/#yaoiwi/FitSync&Date)

## 📄 许可证

[Apache-2.0](LICENSE)。可自由使用、修改、分发，**包括商业用途和闭源衍生**，只需保留版权声明与许可证副本；同时包含明确的专利授权（谁起诉专利侵权，授权自动终止）。

第三方依赖的许可证与液态玻璃相关代码的上游来源见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

<div align="center">

Made with ❤️ for Garmin users

![Stars](https://img.shields.io/github/stars/yaoiwi/FitSync?style=social)

</div>

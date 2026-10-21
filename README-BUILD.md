# FitGarminSync — 在线构建（GitHub Actions）说明

本工程已内置 GitHub Actions 工作流：
`.github/workflows/android.yml`

推送代码后，GitHub 会在云端 Linux 机器上自动装 JDK 17 + Android SDK，
执行 `./gradlew :app:assembleDebug`，并把生成的 `app-debug.apk` 作为 Artifact 提供下载。

---

## 一、手机端操作（全流程不需要电脑）

### 1. 建仓库
1. 手机浏览器打开 https://github.com/new
2. 仓库名填 `FitGarminSync`，选 **Private**（私有，代码不外泄）
3. 不要勾选 "Add a README file"，直接 Create

### 2. 上传工程
手机上最简单的方式有两种：

**方式 A：网页上传（推荐，零安装）**
1. 进仓库 → `Add file` → `Upload files`
2. 用系统文件选择器进入 `/storage/emulated/0/Download/FitGarminSync-Project`
3. 全选上传（Web 端一次最多 100 个文件，若超限就分批传）
4. Commit changes

**方式 B：MT 管理器 + Git 客户端**
- MT 管理器本身不带 Git 推送，建议配 Termux：
```bash
pkg install git
cd /sdcard/Download/FitGarminSync-Project
git init
git add .
git commit -m "init"
git remote add origin https://github.com/<你的用户名>/FitGarminSync.git
git push -u origin main
```

### 3. 看构建结果
1. 仓库页 → 顶部 `Actions` 标签
2. 左侧选 `Build Debug APK`
3. 若没自动跑，点 `Run workflow` 手动触发
4. 绿色 √ 后点进那次运行 → 页面底部 `Artifacts` → 下载 `app-debug.zip`
5. 解压得到 `app-debug.apk`，直接安装

---

## 二、关键点说明

| 项目 | 说明 |
|---|---|
| 免费额度 | 私有仓库 2000 分钟/月；公开仓库完全免费不限量 |
| 构建耗时 | 首次约 6–10 分钟（要下 Gradle 8.7 + 依赖），之后有缓存约 2–4 分钟 |
| 签名 | 生成的是 **debug 签名** APK，可直接安装；发布版需自己在仓库 Secrets 里配 keystore |
| `local.properties` | 不需要上传（已在 .gitignore 中），云端 runner 自带 `ANDROID_HOME` |
| 失败排查 | 工作流失败时会额外上传 `build-reports`，里面是 Gradle 的 HTML 报告，可直接看报错 |

---

## 三、其他在线构建方案（备选）

| 平台 | 是否开源/免费 | 适配度 |
|---|---|---|
| **GitHub Actions** | 免费（公开仓库不限量） | ★★★★★ 最推荐，配置已内置 |
| **GitLab CI** | 免费 400 分钟/月 | ★★★★ 需自建 `.gitlab-ci.yml` |
| **Codemagic** | 免费 500 分钟/月，专为移动端 | ★★★★ 有 Android 专用模板，私有仓库友好 |
| **Cirrus CI** | 公开仓库免费 | ★★★ 配置略简 |
| **Bitrise / Appcircle** | 有免费额度，超出收费 | ★★★ 图形化，但免费额度少 |
| **Replit / 在线 IDE** | — | ★ 跑不动 Android SDK，不适用 |

> 注意：这类"在线编译"本质都是**云端 Linux 机器跑 Gradle**，不是手机端本地编译。
> 所以结论不变：手机上只要能推代码，就能拿到 APK；但编译本身发生在云上。

---

## 四、本工程已确认的构建前置条件

- Gradle Wrapper：`8.7`（`gradle/wrapper/gradle-wrapper.properties`）
- `gradle-wrapper.jar`：已存在（43453 bytes）
- AGP `8.5.2` / Kotlin `1.9.24` / Compose 编译器 `1.5.14`
- JDK 17（工作流已固定 temurin 17）
- 模块：`:app`、`:core-data`、`:core-ui`
- `compileSdk 35` / `minSdk 26` / `targetSdk 35`

---

## 五、本地或云端的最低验证命令

```bash
./gradlew :app:assembleDebug --stacktrace
```

产物路径：

```
app/build/outputs/apk/debug/app-debug.apk
```

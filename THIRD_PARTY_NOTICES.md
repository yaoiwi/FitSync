# 第三方组件与许可声明

本项目以 Apache-2.0 发布（见 `LICENSE`）。以下是运行时依赖、构建依赖，以及参考/移植代码的来源与许可。

## 一、直接依赖（随 APK 分发）

| 组件 | 许可证 | 版权 / 来源 |
|---|---|---|
| Miuix（miuix-ui / miuix-icons / miuix-blur） | Apache-2.0 | © YuKongA — https://github.com/YuKongA/miuix |
| Jetpack Compose（ui / foundation / material3 / material-icons） | Apache-2.0 | © The Android Open Source Project |
| AndroidX（core / activity / lifecycle / navigation / work / security-crypto） | Apache-2.0 | © The Android Open Source Project |
| Room | Apache-2.0 | © The Android Open Source Project |
| Hilt / Dagger | Apache-2.0 | © Google LLC |
| OkHttp | Apache-2.0 | © Square, Inc. |
| Kotlin / kotlinx.coroutines | Apache-2.0 | © JetBrains s.r.o. |
| Tink | Apache-2.0 | © Google LLC |
| material-color-utilities | Apache-2.0 | © Google LLC（经 Miuix 传递引入，仅用于动态取色） |
| **Garmin FIT SDK** | FIT Protocol License Agreement | © Garmin Ltd. — https://developer.garmin.com/fit/ |

### 关于 Garmin FIT SDK

本项目**不复制、不再分发** FIT SDK 的源码或二进制，仅通过 Gradle 从 Maven Central 声明依赖
（`com.garmin:fit`），由使用者在构建时自行获取，遵循其 FIT Protocol License Agreement。
该 SDK 仅用于生成 monitoring FIT 文件（「搬运健康数据」功能）。

## 二、参考与移植代码

液态玻璃导航栏与折射效果**不是原创实现**，而是从下列 Apache-2.0 代码改写而来，
文件头保留了来源注释，并已针对本项目做了包名、颜色体系与降级逻辑的修改：

| 文件 | 上游来源 | 许可证 |
|---|---|---|
| `core-ui/.../components/LiquidGlassNavigationBar.kt` | SukiSU-Ultra 的 `FloatingBottomBar` + Miuix 官方示例 `IosLiquidGlassNavigationBar` | Apache-2.0 |
| `core-ui/.../components/liquid/Lens.kt` | Miuix 官方示例 `IosLiquidGlassNavigationBar` | Apache-2.0 |
| `core-ui/.../components/liquid/Vibrancy.kt` | 同上 | Apache-2.0 |
| `core-ui/.../components/liquid/CombinedBackdrop.kt` | 同上 | Apache-2.0 |

同时参考了 [HyperIsland](https://github.com/1812z/HyperIsland)（MIT，© 2026 1812z）的页面骨架
与滚动动画组织方式；该项目的这些部分同样源自上述 Apache-2.0 示例。

依 Apache-2.0 第 4 条，上述文件保留原始版权与许可声明，修改处已在代码注释中标注。

## 三、仅用于测试（不随 APK 分发）

| 组件 | 许可证 |
|---|---|
| JUnit 4 | Eclipse Public License 1.0 |
| Robolectric | MIT |
| org.json (JSON-java) | Public Domain |
| OkHttp MockWebServer | Apache-2.0 |

## 四、许可全文

- Apache-2.0：https://www.apache.org/licenses/LICENSE-2.0
- MIT：见下方
- Apache-2.0（本项目本体）：见 `LICENSE`
- FIT Protocol License：https://developer.garmin.com/fit/

```
MIT License

Copyright (c) 2026 1812z (HyperIsland)
Copyright (c) Robolectric contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## 五、与 Garmin 的关系

本项目为非官方第三方客户端，与 Garmin Ltd. 无任何隶属、授权或背书关系。
「Garmin」「Garmin Connect」是 Garmin Ltd. 的商标，仅用于说明兼容对象。

<div align="center">
  <img src="docs/icon.png" width="120" alt="FitSync" />

# FitSync

One app for both of your Garmin accounts: steps, sleep, heart rate and workouts in one place.

![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white)
![Compose](https://img.shields.io/badge/Compose-BOM%202026.09-4285F4?logo=jetpackcompose&logoColor=white)
![Miuix](https://img.shields.io/badge/Miuix-0.9.4-FF6900)
![License](https://img.shields.io/badge/License-Apache--2.0-blue)
![Release](https://img.shields.io/github/v/release/yaoiwi/FitSync?color=green)

[简体中文](README.md) | **English**
</div>

## ✨ Features

| | | |
|:---:|:---:|:---:|
| **🧩 Two regions**<br>Mainland China and International accounts side by side, merged locally | **🔁 Workout transfer**<br>Copy activities to the other account — no re-recording after switching regions | **🏃 Workout detail**<br>Pace, heart rate and elevation curves, plus a shareable summary card |
| **📊 Trends & weekly**<br>7 / 30-day changes, plus this week vs last week | **🛌 Sleep stages**<br>Deep, light, REM and awake in one colour band | **⏳ Backfill**<br>Page through every activity; a year of daily health data |
| **📑 Laps & zones**<br>Per-km pace, cadence and power curves, heart-rate zone split | **🎛️ Sync policy**<br>Every 6/12/24 hours or manual only; Wi-Fi-only option | **🩺 Health migration**<br>Steps, sleep and stress uploaded as monitoring FIT |
| **🔐 Local first**<br>Data stays on the phone; tokens encrypted with Tink | **📤 Export**<br>Re-importable JSON, or three CSV tables | **💾 Backup**<br>One-tap database backup, restore on a new phone |
| **🌗 Dark mode**<br>Follow system / light / dark, applied instantly | **🔍 Filter & search**<br>By sport, month, or name | **🧾 Sync log**<br>Time, record count and failure reason for every run |

## 📱 Screenshots

<p align="center">
  <img src="docs/screenshots/accounts.jpg" width="300" alt="Accounts">
  &nbsp;&nbsp;&nbsp;
  <img src="docs/screenshots/settings.jpg" width="300" alt="Settings">
</p>

- Built on [Miuix](https://github.com/YuKongA/miuix) 0.9.4 (theme, cards, switches, list rows, floating navigation),
  plus in-house components: `HyperPage`, the liquid-glass navigation bar, segmented control, input and confirm dialogs
- Liquid-glass bar with real backdrop blur, refraction and dual-light highlights; spring-driven indicator that follows your drag
- Collapsing large title, rubber-band list edges, progressive top-bar blur
- Four tabs: Overview / Activities / Accounts / Settings; haptic feedback can be turned off

> The glass effect needs Android 13 (API 33) RuntimeShader; older versions fall back to translucency with a highlight stroke.

## 📖 Getting started

Download the APK from [Releases](https://github.com/yaoiwi/FitSync/releases/latest) and install it. Each release ships a `apk-sha256`:

```bash
sha256sum -c apk-sha256
```

The app itself shows the installed APK's SHA-256 and signing certificate under Settings → About; tap to copy.

1. Open the app, tap "Add account" and sign in to Garmin China or Garmin International (MFA supported)
2. Pull to refresh — the first sync backfills 7 days; afterwards it syncs on open and every 6 hours in the background
3. With both accounts added, use "Accounts → Data" to copy activities, migrate health data, or export your local records

- Requires **Android 8.0 (API 26)** or newer
- The liquid-glass effect requires **Android 13 (API 33)**; older versions degrade gracefully
- Found a problem? Open an [issue](https://github.com/yaoiwi/FitSync/issues). Build and signing notes are below

## 🔨 Build

You need **JDK 21** and Android SDK Platform **37** (Build-Tools 36+). Gradle comes from the wrapper (9.6.1).

```bash
echo "sdk.dir=/path/to/Android/sdk" > local.properties
./gradlew :app:assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

Run the unit tests:

```bash
./gradlew :app:testDebugUnitTest
```

Release builds run R8 with resource shrinking (19 MB → about 3.6 MB). Rules live in `app/proguard-rules.pro`.

<details>
<summary>Signing (development / release)</summary>

Signing switches on environment variables, so builds never end up with a random signature.

**Default: the checked-in debug keystore**

`signing/debug.keystore` is committed with Android's public credentials (`android` / `androiddebugkey`). Used when no secrets or environment variables are configured. ⚠️ It cannot be published to an app store.

**Release: your own keystore**

```bash
# 1. Generate (Termux or a desktop with a JDK)
keytool -genkeypair -v -keystore fitsync-release.jks -alias fitsync \
  -keyalg RSA -keysize 2048 -validity 10000 -storetype JKS \
  -storepass YOUR_PASSWORD -dname "CN=FitSync, OU=Dev, O=yaoiwi, C=CN"

# 2. Base64, single line
base64 -w 0 fitsync-release.jks
```

Add four secrets under `Settings → Secrets and variables → Actions`: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. The `Prepare signing` CI step decodes them; locally, export `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`.

> Once you switch from debug to release, uninstall the old build before installing the new one.

```bash
# Verify: both commands should print the same fingerprint
keytool -list -v -keystore fitsync-release.jks -alias fitsync -storepass YOUR_PASSWORD | grep SHA256
apksigner verify --print-certs app-debug.apk | grep SHA-256
```

</details>

## ⚠️ Disclaimer

- This project uses Garmin's non-public endpoints. They **can break at any time**, and accounts may be rate-limited or flagged
- Only use it to sync **your own** accounts
- Not an official client; not affiliated with Garmin Ltd. Garmin is a trademark of Garmin Ltd.
- Use at your own risk. The author is not responsible for account restrictions or data loss

## ⭐ Star History

[![Star History Chart](https://api.star-history.com/svg?repos=yaoiwi/FitSync&type=Date)](https://star-history.com/#yaoiwi/FitSync&Date)

## 📄 License

[Apache-2.0](LICENSE). Free to use, modify and distribute, **including commercial and closed-source derivatives**, as long as the copyright notice and license text are kept. Includes an express patent grant (terminated if you sue over patents).

Third-party licenses and the upstream source of the liquid-glass code are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

<div align="center">

Made with ❤️ for Garmin users

![Stars](https://img.shields.io/github/stars/yaoiwi/FitSync?style=social)

</div>

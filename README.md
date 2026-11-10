# VoiceShelf

English · [简体中文](README.zh-CN.md)

VoiceShelf is an Android player and library manager for local audio works. It organizes audio folders by RJ catalog ID and can share the phone's library with an optional Windows player on the same Wi-Fi network.

## Features

- Browse audio folders, search and filter works, save favorites, and revisit recently and frequently played tracks.
- Play whole works or custom queues, resume from the last position, and use LRC lyrics, playback speed, a sleep timer, and USB exclusive output on supported devices.
- Fetch covers and work details by RJ ID. With a DeepSeek API key, the same update can translate titles into the current app language. The original titles are retained.
- Manage files and folders from the app. The Android interface supports Simplified Chinese, English, and Japanese and follows the system language by default.

## Build for Android

Use Windows with PowerShell, JDK 21, Gradle 9.1.0, and Android SDK Platform 36. Set `JAVA_HOME` and `ANDROID_SDK_ROOT` to your local installations. The app supports Android 8.0 and later.

For a local release build, generate a development signing key once. Use the same key for subsequent builds that should update that installation.

```powershell
$signingDir = Join-Path $env:LOCALAPPDATA 'DLVoiceBuild'
New-Item -ItemType Directory -Force $signingDir | Out-Null
$env:YINQI_SIGNING_KEY = Join-Path $signingDir 'debug.jks'
if (!(Test-Path -LiteralPath $env:YINQI_SIGNING_KEY)) {
    & "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -keystore $env:YINQI_SIGNING_KEY `
        -alias androiddebugkey -storepass android -keypass android `
        -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=VoiceShelf Local Build'
}
.\build.ps1 -Gradle 'C:\path\to\gradle-9.1.0\bin\gradle.bat'
```

The build script downloads the required USB audio dependencies and verifies their checksums before producing the APK in the project root.

## Windows companion

In the `desktop` directory, run `npm ci` and `npm start` with Node.js 24 or later. On the phone, enable sharing under **Settings → PC connection**, then enter the displayed address and pairing code in the Windows app. See the [Windows guide](desktop/README.md) for packaging and usage details.

## Credits and license

- **GPT** — 99% of the development contribution.
- **[misaka356](https://github.com/misaka356)** — 1% of the development contribution and project maintainer.

VoiceShelf source code is released under the [MIT License](LICENSE). Third-party components retain their own licenses. See the [Decent Player library release](https://github.com/Ma145/decent-player/releases/tag/v0.1.0-libs) and the [bundled license notices](app/src/main/assets/licenses/).

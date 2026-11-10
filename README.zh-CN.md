# VoiceShelf

简体中文 · [English](README.md)

VoiceShelf 是一款 Android 本地音声管理播放器。它按 RJ 编号整理音声目录，也可通过同一 Wi-Fi 将手机作品库共享给 Windows 播放器。

## 主要功能

- 浏览音声目录、搜索和筛选作品、收藏，以及查看最近播放和最常播放。
- 支持作品队列、自定义队列、进度续听、LRC 字幕、倍速、睡眠定时，以及兼容设备上的 USB 独占输出。
- 按 RJ 编号获取封面和作品资料。配置 DeepSeek API Key 后，更新时可一并将标题译为当前界面语言，并保留原名。
- 在应用内管理文件和文件夹。Android 界面支持简体中文、英语和日语，默认跟随系统语言。

## 构建 Android 版

在 Windows 上准备 PowerShell、JDK 21、Gradle 9.1.0 和 Android SDK Platform 36，并将 `JAVA_HOME`、`ANDROID_SDK_ROOT` 指向本机安装目录。应用支持 Android 8.0 及以上版本。

首次构建 Release 时，生成一把本地开发签名密钥。后续需要覆盖安装时继续使用同一把密钥。

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

构建脚本会下载并校验 USB 音频依赖，将 APK 输出到项目根目录。

## Windows 播放器

使用 Node.js 24 或更高版本，在 `desktop` 目录运行 `npm ci` 和 `npm start`。在手机端「设置 → 电脑连接」开启共享，再在 Windows 播放器中输入地址和配对码。打包和使用说明见 [Windows 指南](desktop/README.md)。

## 许可证

VoiceShelf 源码采用 [MIT 许可证](LICENSE)。第三方组件沿用各自的许可证，详见 [Decent Player 库发布页](https://github.com/Ma145/decent-player/releases/tag/v0.1.0-libs)和[项目内的许可声明](app/src/main/assets/licenses/)。

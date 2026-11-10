param(
    [string]$Sdk = $(if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { "$env:LOCALAPPDATA\Android\Sdk" }),
    [string]$Jdk = $env:JAVA_HOME,
    [string]$Gradle = "$env:LOCALAPPDATA\DLVoiceBuild\gradle-9.1.0\bin\gradle.bat"
)
$ErrorActionPreference = 'Stop'
$env:ANDROID_HOME = $Sdk
if ($Jdk) { $env:JAVA_HOME = $Jdk }

$depot = if ($env:YINQI_USB_LIBS) { $env:YINQI_USB_LIBS } else { "$env:LOCALAPPDATA\DLVoiceBuild\usb" }
New-Item -ItemType Directory -Force $depot | Out-Null
$release = 'https://github.com/Ma145/decent-player/releases/download/v0.1.0-libs'
$artifacts = @{
    'decent-usb-audio-driver-release.aar' = 'C92C75BE2C454F028075FC92299C19E22F6D1DC1F6C3E6E2008CDA4AA67E265F'
    'decent-usb-audio-wrapper-media3-release.aar' = '5BCA0523AD4BEB68D9248D3D849C0BABF1272AD6FB9E267EE09B573225686B8F'
    'decent-media3-decoder-flac-release.aar' = 'B2D8A92235F2F2273C2579C7E9F31F7074BBDC6E5B70C3E3CD5ECF3FC864711B'
}
foreach ($name in $artifacts.Keys) {
    $file = Join-Path $depot $name
    if (!(Test-Path -LiteralPath $file)) {
        $partial = "$file.download"
        try {
            curl.exe --fail --location --retry 2 --max-time 90 "$release/$name" --output $partial
            if ($LASTEXITCODE -ne 0 -or (Get-FileHash -LiteralPath $partial).Hash -ne $artifacts[$name]) {
                throw "Unable to obtain verified Decent artifact: $name"
            }
            Move-Item -LiteralPath $partial -Destination $file
        } finally {
            if (Test-Path -LiteralPath $partial) { Remove-Item -LiteralPath $partial }
        }
    }
    if ((Get-FileHash -LiteralPath $file).Hash -ne $artifacts[$name]) { throw "Unexpected checksum for $file" }
}
$env:YINQI_USB_LIBS = $depot

$temp = Join-Path ([System.IO.Path]::GetTempPath()) ('yinqi-build-' + [guid]::NewGuid())
try {
    & $Gradle -p $PSScriptRoot --no-daemon --project-cache-dir (Join-Path $temp 'cache') "-PyinqiBuildDir=$temp" :app:assembleRelease
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with code $LASTEXITCODE" }
    $artifact = Join-Path $temp 'app\outputs\apk\release\app-release.apk'
    $apk = Join-Path $PSScriptRoot 'VoiceShelf-v0.15.9.apk'
    Copy-Item -LiteralPath $artifact -Destination $apk -Force
    Write-Output $apk
} finally {
    if (Test-Path -LiteralPath $temp) {
        $resolved = (Resolve-Path -LiteralPath $temp).Path
        if ($resolved -eq $temp -and (Split-Path $resolved -Leaf) -match '^yinqi-build-[0-9a-f-]{36}$') {
            Remove-Item -LiteralPath $resolved -Recurse -Force
        }
    }
}


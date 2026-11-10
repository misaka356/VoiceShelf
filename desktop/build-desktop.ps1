param([string]$ElectronCache="$env:LOCALAPPDATA\DLVoiceBuild\electron")
$ErrorActionPreference='Stop'
Push-Location $PSScriptRoot
try {
    $archive=Join-Path $ElectronCache 'electron-v45.2.2-win32-x64.zip'
    if(!(Test-Path -LiteralPath $archive)){throw 'Missing Electron ZIP cache'}
    $checks=Get-Content 'node_modules/electron/checksums.json' -Raw | ConvertFrom-Json
    if((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $checks.'electron-v45.2.2-win32-x64.zip'){throw 'Electron archive checksum mismatch'}
    & node build-desktop.mjs $ElectronCache
    if($LASTEXITCODE -ne 0){throw 'Desktop build failed'}
    Copy-Item -LiteralPath README.md -Destination '../releases/Yinqi-win32-x64/使用说明.md' -Force
} finally {Pop-Location}

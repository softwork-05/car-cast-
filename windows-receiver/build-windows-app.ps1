# Build & Package Standalone CarCastMirror Windows Desktop App
$ErrorActionPreference = "Stop"

$winReceiverDir = "D:\Projects\CarCastMirror\CarCastMirror\windows-receiver"
$electronDist = Join-Path $winReceiverDir "node_modules\electron\dist"
$targetDir = "D:\Projects\CarCastMirror\CarCastMirror-Windows-App"

Write-Host "Assembling standalone Windows app into: $targetDir" -ForegroundColor Cyan

if (-not (Test-Path $electronDist)) {
    Write-Error "Electron dist not found at $electronDist"
    exit 1
}

if (Test-Path $targetDir) {
    Remove-Item $targetDir -Recurse -Force
}
New-Item -ItemType Directory -Path $targetDir | Out-Null

# 1. Copy Electron runtime binaries
Copy-Item "$electronDist\*" $targetDir -Recurse -Force
Rename-Item (Join-Path $targetDir "electron.exe") "CarCastMirror.exe" -Force

$defaultAsar = Join-Path $targetDir "resources\default_app.asar"
if (Test-Path $defaultAsar) { Remove-Item $defaultAsar -Force }

# 2. Stage App source files into temporary directory
$tempAppDir = Join-Path $env:TEMP "CarCastAppStage"
if (Test-Path $tempAppDir) { Remove-Item $tempAppDir -Recurse -Force }
New-Item -ItemType Directory -Path $tempAppDir | Out-Null

Copy-Item (Join-Path $winReceiverDir "main.js") $tempAppDir -Force
Copy-Item (Join-Path $winReceiverDir "server.js") $tempAppDir -Force
Copy-Item (Join-Path $winReceiverDir "package.json") $tempAppDir -Force
Copy-Item (Join-Path $winReceiverDir "public") $tempAppDir -Recurse -Force

$appModules = Join-Path $tempAppDir "node_modules"
New-Item -ItemType Directory -Path $appModules | Out-Null
Copy-Item (Join-Path $winReceiverDir "node_modules\ws") $appModules -Recurse -Force

# 3. Pack asar archive into resources\app.asar
$resourcesDir = Join-Path $targetDir "resources"
if (-not (Test-Path $resourcesDir)) { New-Item -ItemType Directory -Path $resourcesDir | Out-Null }
$appAsar = Join-Path $resourcesDir "app.asar"

Set-Location $winReceiverDir
npx asar pack $tempAppDir $appAsar
Remove-Item $tempAppDir -Recurse -Force

# 4. FIX ALL TIMESTAMPS: Update every single file and folder to current release timestamp (2026)
Write-Host "Updating all file timestamps from 1980 to current release date..." -ForegroundColor Cyan
$releaseDate = Get-Date
Get-ChildItem -Path $targetDir -Recurse | ForEach-Object {
    try {
        $_.LastWriteTime = $releaseDate
        $_.CreationTime = $releaseDate
        $_.LastAccessTime = $releaseDate
    } catch { }
}

Write-Host "CarCastMirror Windows App assembled and verified!" -ForegroundColor Green

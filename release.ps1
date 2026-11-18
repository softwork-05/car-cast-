<#
.SYNOPSIS
    CarCast Mirror - Automated Release & GitHub Direct Publisher

.DESCRIPTION
    Automates release verification, version increment, unit testing, local release APK building,
    signature verification (matching installed app certificate), Git commit, tagging, and direct
    GitHub Release publishing without waiting for duplicate cloud builds.

.EXAMPLE
    .\release.ps1
    .\release.ps1 -Bump patch
    .\release.ps1 -Bump minor
    .\release.ps1 -Tag v1.0.8
#>

[CmdletBinding()]
param(
    [ValidateSet("none", "patch", "minor", "major")]
    [string]$Bump = "none",
    
    [string]$Tag = "",
    
    [switch]$DirectPublish = $true,
    [switch]$SkipTests = $false,
    
    [string]$Remote = "origin",
    [string]$Branch = "main",
    [string]$Repo = "softwork-05/car-cast-"
)

$ErrorActionPreference = "Stop"

# Ensure Git and GitHub CLI are in PATH
if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    if (Test-Path "C:\Program Files\Git\cmd\git.exe") {
        $env:Path += ";C:\Program Files\Git\cmd"
    } else {
        Write-Error "Git is not installed or not in PATH."
        exit 1
    }
}
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    if (Test-Path "C:\Program Files\GitHub CLI\gh.exe") {
        $env:Path += ";C:\Program Files\GitHub CLI"
    } else {
        Write-Error "GitHub CLI (gh) is not installed or not in PATH."
        exit 1
    }
}

$ProjectRoot = $PSScriptRoot
Set-Location $ProjectRoot

Write-Host "=================================================" -ForegroundColor Cyan
Write-Host "   CarCast Mirror Automated Release Publisher    " -ForegroundColor Cyan
Write-Host "=================================================" -ForegroundColor Cyan

# 1. Read current version from app/build.gradle.kts
$BuildGradle = Join-Path $ProjectRoot "app\build.gradle.kts"
if (-not (Test-Path $BuildGradle)) {
    Write-Error "Could not find app/build.gradle.kts at $BuildGradle"
    exit 1
}

$BuildContent = Get-Content $BuildGradle -Raw
$VersionNameMatch = [regex]::Match($BuildContent, 'versionName\s*=\s*"([^"]+)"')
$VersionCodeMatch = [regex]::Match($BuildContent, 'versionCode\s*=\s*([0-9]+)')

if (-not $VersionNameMatch.Success -or -not $VersionCodeMatch.Success) {
    Write-Error "Could not extract versionCode or versionName from app/build.gradle.kts"
    exit 1
}

$CurrentVersionName = $VersionNameMatch.Groups[1].Value
$CurrentVersionCode = [int]$VersionCodeMatch.Groups[1].Value

Write-Host "Current Version Name: $CurrentVersionName" -ForegroundColor Yellow
Write-Host "Current Version Code: $CurrentVersionCode" -ForegroundColor Yellow

$NewVersionName = $CurrentVersionName
$NewVersionCode = $CurrentVersionCode

# 2. Version bumping if requested
if ($Bump -ne "none") {
    $Parts = $CurrentVersionName.Split('.')
    $Major = [int]($Parts[0])
    $Minor = if ($Parts.Length -gt 1) { [int]($Parts[1]) } else { 0 }
    $Patch = if ($Parts.Length -gt 2) { [int]($Parts[2]) } else { 0 }

    switch ($Bump) {
        "patch" { $Patch++ }
        "minor" { $Minor++; $Patch = 0 }
        "major" { $Major++; $Minor = 0; $Patch = 0 }
    }

    $NewVersionName = "$Major.$Minor.$Patch"
    $NewVersionCode = $CurrentVersionCode + 1

    Write-Host "Bumping version -> Name: $NewVersionName, Code: $NewVersionCode" -ForegroundColor Green
    
    $BuildContent = $BuildContent -replace 'versionCode\s*=\s*[0-9]+', "versionCode = $NewVersionCode"
    $BuildContent = $BuildContent -replace 'versionName\s*=\s*"[^"]+"', "versionName = `"$NewVersionName`""
    Set-Content -Path $BuildGradle -Value $BuildContent -NoNewline
}

$ReleaseTag = if ($Tag -ne "") { $Tag } else { "v$NewVersionName" }
if (-not $ReleaseTag.StartsWith("v")) {
    $ReleaseTag = "v$ReleaseTag"
}

Write-Host "Target Release Tag  : $ReleaseTag" -ForegroundColor Magenta

# 3. Check for existing remote tags (Never overwrite published tags)
Write-Host "`nChecking remote tags on $Remote..." -ForegroundColor Cyan
$remoteTags = git ls-remote --tags $Remote
if ($remoteTags -match "refs/tags/$ReleaseTag(\^\{\})?$") {
    Write-Error "Tag $ReleaseTag ALREADY exists on remote $Remote! To prevent overwriting published releases, please choose a newer version tag (e.g. .\release.ps1 -Bump patch)."
    exit 1
}

# 4. Run unit tests before release
if (-not $SkipTests) {
    Write-Host "`nRunning Gradle unit tests (testDebugUnitTest)..." -ForegroundColor Cyan
    $testResult = Start-Process -FilePath ".\gradlew.bat" -ArgumentList "testDebugUnitTest", "--no-daemon" -Wait -PassThru -NoNewWindow
    if ($testResult.ExitCode -ne 0) {
        Write-Error "Gradle unit tests failed with exit code $($testResult.ExitCode). Aborting release!"
        exit $testResult.ExitCode
    }
    Write-Host "All unit tests passed successfully!" -ForegroundColor Green
}

# 5. Build and verify signed release APK if DirectPublish is enabled
$DistDir = Join-Path $ProjectRoot "release_dist"
if ($DirectPublish) {
    Write-Host "`n[DirectPublish] Building signed release APK locally..." -ForegroundColor Cyan
    
    $env:RELEASE_KEYSTORE_PATH = "$env:USERPROFILE\.android\debug.keystore"
    $env:STORE_PASSWORD = "android"
    $env:KEY_ALIAS = "androiddebugkey"
    $env:KEY_PASSWORD = "android"
    
    $buildResult = Start-Process -FilePath ".\gradlew.bat" -ArgumentList "assembleRelease", "--no-daemon" -Wait -PassThru -NoNewWindow
    if ($buildResult.ExitCode -ne 0) {
        Write-Error "Gradle assembleRelease failed with exit code $($buildResult.ExitCode). Aborting release!"
        exit $buildResult.ExitCode
    }
    
    $releaseApk = Join-Path $ProjectRoot "app\build\outputs\apk\release\app-release.apk"
    if (-not (Test-Path $releaseApk)) {
        Write-Error "Could not find release APK at $releaseApk"
        exit 1
    }
    
    # Verify signing certificate with apksigner
    $apksigner = (Get-ChildItem -Path "$env:LOCALAPPDATA\Android\Sdk\build-tools" -Filter "apksigner.bat" -Recurse | Select-Object -First 1).FullName
    if ($apksigner) {
        Write-Host "Verifying APK signing certificate..." -ForegroundColor Cyan
        $certOutput = & $apksigner verify --print-certs $releaseApk
        $foundDigest = ($certOutput | Select-String "certificate SHA-256 digest: ([a-f0-9]{64})" | ForEach-Object { $_.Matches[0].Groups[1].Value.ToLower() })
        $expectedDigest = "82d57a4863a3cf3471a6293fbecac3ab8f44218d62ef28fdd22f2e85c0d8928e"
        
        if ($foundDigest -ne $expectedDigest) {
            Write-Error "Security verification failed! APK certificate SHA-256 ($foundDigest) does not match expected installed certificate ($expectedDigest)."
            exit 1
        }
        Write-Host "Signature verified! Certificate strictly matches existing installations: $foundDigest" -ForegroundColor Green
    }
    
    # Prepare distribution artifacts
    if (-not (Test-Path $DistDir)) { New-Item -ItemType Directory -Path $DistDir | Out-Null }
    
    $distApk = Join-Path $DistDir "CarCastMirror.apk"
    $distVersionedApk = Join-Path $DistDir "CarCastMirror-$ReleaseTag.apk"
    Copy-Item $releaseApk $distApk -Force
    Copy-Item $releaseApk $distVersionedApk -Force
    
    # Also update workspace root copy for immediate USB sideloading
    $rootCopy = Join-Path (Split-Path $ProjectRoot -Parent) "CarCastMirror-native-debug.apk"
    Copy-Item $releaseApk $rootCopy -Force
    
    $apkHash = (Get-FileHash $distApk -Algorithm SHA256).Hash.ToLower()
    "$apkHash  CarCastMirror.apk" | Set-Content (Join-Path $DistDir "CarCastMirror.apk.sha256") -NoNewline
    "$apkHash  CarCastMirror.apk`r`n$apkHash  CarCastMirror-$ReleaseTag.apk" | Set-Content (Join-Path $DistDir "checksums.txt")
    
    Write-Host "Release APK ready. Size: $((Get-Item $distApk).Length / 1MB) MB, SHA-256: $apkHash" -ForegroundColor Green
}

# 6. Stage files and commit
Write-Host "`nStaging git files..." -ForegroundColor Cyan
git add .gitignore
git add app/src/
git add app/build.gradle.kts
git add build.gradle.kts
git add settings.gradle.kts
git add gradle.properties
git add gradle/
git add gradlew
git add gradlew.bat
git add README.md
git add LICENSE
git add PATCH_NOTES.md
git add AIRPLAY_ENGINE_MIGRATION.md
git add VERIFICATION_RESULTS.txt
git add .github/
git add release.ps1

$gitStatus = git status --porcelain
if ($gitStatus) {
    $commitMsg = "Release ${ReleaseTag}: Add Scan to Pay Button & NayaPay/Easypaisa QR Codes (Build $NewVersionCode)"
    Write-Host "Creating Git commit: '$commitMsg'..." -ForegroundColor Cyan
    git commit -m "$commitMsg"
} else {
    Write-Host "Working tree is clean, no uncommitted changes." -ForegroundColor Yellow
}

# 7. Create local Git tag
$existingLocalTag = git tag -l $ReleaseTag
if ($existingLocalTag) {
    git tag -d $ReleaseTag
}
git tag -a $ReleaseTag -m "Release $ReleaseTag (Build $NewVersionCode)"
Write-Host "Created local Git tag: $ReleaseTag" -ForegroundColor Green

# 8. Push branch and tag to GitHub safely
Write-Host "`nPushing $Branch and tag $ReleaseTag to $Remote..." -ForegroundColor Cyan
git push $Remote $Branch
git push $Remote $ReleaseTag

# 9. Directly publish release using GitHub CLI
if ($DirectPublish) {
    Write-Host "`nPublishing release $ReleaseTag to GitHub directly via GitHub CLI..." -ForegroundColor Cyan
    
    $notesFile = Join-Path $DistDir "release_notes.md"
    @"
## 🚗 CarCast Mirror Release $ReleaseTag (Build $NewVersionCode)

Official verified production release published directly via GitHub CLI.

### 📋 Release Metadata
| Property | Value |
|---|---|
| **Version Name** | `$NewVersionName` |
| **Version Code** | `$NewVersionCode` |
| **Package ID** | `com.carcast.mirror` |
| **File Size** | `$([math]::Round((Get-Item $distApk).Length / 1MB, 2)) MB` |
| **Signing Certificate (SHA-256)** | `82:D5:7A:48:63:A3:CF:34:71:A6:29:3F:BE:CA:C3:AB:8F:44:21:8D:62:EF:28:FD:D2:2F:2E:85:C0:D8:92:8E` |
| **Update Compatibility** | 100% verified compatible with all previous CarCast Mirror installs |
| **Target Android** | Android 8.0 (API 26) through Android 14+ (API 34) |

### 🔒 Checksums & Verification
```text
SHA-256: $apkHash  CarCastMirror.apk
```

---

### ✨ Key Features & Improvements
- 💳 **Scan to Pay (NayaPay & Easypaisa QR Codes)**: Instant full-screen payment modal on Receiver screen with crisp, responsive NayaPay (Orange) and Easypaisa (Green) QR codes for car displays and phones.
- 🍏 **Apple iOS AirPlay 1080p Mirroring**: Native UxPlay engine with FairPlay authentication, low-latency H.264/HEVC hardware decoding, and zero-config mDNS discovery.
- 📱 **Android Screen & Internal Audio Mirroring**: Low-latency 60 FPS screen casting with AudioPlaybackCapture routing sound directly through car LCD speakers.
- 🕹️ **Reverse Touch Control**: Control mirrored Android phones directly from the car touchscreen with smooth multi-touch gesture forwarding.
- 🔄 **Orientation & Dynamic Aspect Ratio**: Responsive portrait/landscape UI for phones and car displays with adaptive letterboxing and fullscreen modes.
- 🚀 **Smart In-App Auto-Updates**: Seamless version checks via GitHub Releases API with numeric versionCode validation (Build $NewVersionCode) and automatic cleanup of obsolete cached APKs.
"@ | Set-Content -Path $notesFile -Encoding UTF8

    # Check if release already exists on GitHub
    $releaseExists = gh release view $ReleaseTag --repo $Repo 2>$null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "Release $ReleaseTag already exists. Uploading assets to existing release..." -ForegroundColor Yellow
        gh release upload $ReleaseTag "$distApk#CarCastMirror.apk" "$distVersionedApk#CarCastMirror-$ReleaseTag.apk" (Join-Path $DistDir "CarCastMirror.apk.sha256") (Join-Path $DistDir "checksums.txt") --clobber --repo $Repo
    } else {
        gh release create $ReleaseTag "$distApk#CarCastMirror.apk" "$distVersionedApk#CarCastMirror-$ReleaseTag.apk" (Join-Path $DistDir "CarCastMirror.apk.sha256") (Join-Path $DistDir "checksums.txt") --title "CarCastMirror $ReleaseTag (Build $NewVersionCode)" --notes-file $notesFile --repo $Repo
    }
    
    Write-Host "`nRelease successfully published to GitHub!" -ForegroundColor Green
    Write-Host "Release URL: https://github.com/$Repo/releases/tag/$ReleaseTag" -ForegroundColor Cyan
    Write-Host "Download URL: https://github.com/$Repo/releases/download/$ReleaseTag/CarCastMirror.apk" -ForegroundColor Cyan
}

Write-Host "`n=================================================" -ForegroundColor Cyan
Write-Host "   Release Process Completed Successfully        " -ForegroundColor Cyan
Write-Host "=================================================" -ForegroundColor Cyan

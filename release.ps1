<#
.SYNOPSIS
    CarCast Mirror - Automated Release & GitHub Push Script

.DESCRIPTION
    Automates the release verification, version increment, unit testing, Git commit, 
    tagging, and push to GitHub (which triggers the GitHub Actions APK release workflow).

.EXAMPLE
    .\release.ps1
    .\release.ps1 -Bump patch
    .\release.ps1 -Tag v1.0.5
#>

[CmdletBinding()]
param(
    [ValidateSet("none", "patch", "minor", "major")]
    [string]$Bump = "none",
    
    [string]$Tag = "",
    
    [switch]$SkipTests = $false,
    [switch]$Force = $false,
    
    [string]$Remote = "origin",
    [string]$Branch = "main"
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

# 3. Run unit tests before release
if (-not $SkipTests) {
    Write-Host "`nRunning Gradle unit tests (testDebugUnitTest)..." -ForegroundColor Cyan
    $testResult = Start-Process -FilePath ".\gradlew.bat" -ArgumentList "testDebugUnitTest", "--no-daemon" -Wait -PassThru -NoNewWindow
    if ($testResult.ExitCode -ne 0) {
        Write-Error "Gradle unit tests failed with exit code $($testResult.ExitCode). Aborting release!"
        exit $testResult.ExitCode
    }
    Write-Host "Unit tests passed successfully!" -ForegroundColor Green
}

# 4. Check Git repo and remotes
if (-not (Test-Path (Join-Path $ProjectRoot ".git"))) {
    Write-Host "Initializing Git repository..." -ForegroundColor Yellow
    git init -b $Branch
    git remote add origin https://github.com/softwork-05/car-cast-.git
}

# Ensure remote origin is configured
$remotes = git remote
if ($remotes -notcontains $Remote) {
    git remote add $Remote https://github.com/softwork-05/car-cast-.git
}

# 5. Stage files complying with .gitignore
Write-Host "`nStaging files for commit..." -ForegroundColor Cyan
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

# Check if anything changed to commit
$gitStatus = git status --porcelain
if ($gitStatus) {
    $commitMsg = "Release $ReleaseTag: Android Cast & UxPlay AirPlay Receiver (Build $NewVersionCode)"
    Write-Host "Creating Git commit: '$commitMsg'..." -ForegroundColor Cyan
    git commit -m "$commitMsg"
} else {
    Write-Host "Working tree is clean, no new changes to commit." -ForegroundColor Yellow
}

# 6. Create Git Tag
$existingTags = git tag -l $ReleaseTag
if ($existingTags) {
    Write-Host "Tag $ReleaseTag already exists locally. Updating tag..." -ForegroundColor Yellow
    git tag -d $ReleaseTag
}
git tag -a $ReleaseTag -m "Release $ReleaseTag (Build $NewVersionCode)"
Write-Host "Created Git tag: $ReleaseTag" -ForegroundColor Green

# 7. Push to GitHub
Write-Host "`nPushing $Branch and tag $ReleaseTag to $Remote..." -ForegroundColor Cyan
Write-Host "Command: git push -u $Remote $Branch --tags" -ForegroundColor DarkGray

try {
    $forceParam = if ($Force) { @("--force") } else { @() }
    git push -u $Remote $Branch @forceParam
    git push $Remote $ReleaseTag @forceParam
    Write-Host "`nSuccessfully pushed to GitHub!" -ForegroundColor Green
    Write-Host "GitHub Actions workflow triggered at: https://github.com/softwork-05/car-cast-/actions" -ForegroundColor Cyan
    Write-Host "Releases available at: https://github.com/softwork-05/car-cast-/releases" -ForegroundColor Cyan
} catch {
    Write-Warning "Could not push automatically. This may require authenticating with GitHub."
    Write-Warning "To authenticate, run: gh auth login"
    Write-Warning "Or run: git push -u $Remote $Branch --tags"
}

Write-Host "=================================================" -ForegroundColor Cyan
Write-Host "   Release Process Completed Successfully        " -ForegroundColor Cyan
Write-Host "=================================================" -ForegroundColor Cyan

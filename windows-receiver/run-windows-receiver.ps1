# CarCast Mirror — Windows PC Receiver Launcher
$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ScriptDir

Write-Host "========================================================" -ForegroundColor Cyan
Write-Host "      CarCast Mirror — Windows PC Screen Receiver       " -ForegroundColor Cyan
Write-Host "========================================================" -ForegroundColor Cyan

# 1. Check Node.js
if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    if (Test-Path "C:\Program Files\nodejs\node.exe") {
        $env:Path = "C:\Program Files\nodejs;" + $env:Path
    } else {
        Write-Error "Node.js is not installed or not in PATH. Please install Node.js (https://nodejs.org)."
        exit 1
    }
}

# 2. Check dependencies
if (-not (Test-Path "node_modules\ws")) {
    Write-Host "[SETUP] Installing streaming dependencies..." -ForegroundColor Yellow
    npm install ws --no-audit --no-fund
}

Write-Host "[START] Launching CarCast Windows Streaming Server..." -ForegroundColor Green
$serverProcess = Start-Process node -ArgumentList "server.js" -PassThru -NoNewWindow

Start-Sleep -Seconds 2

Write-Host "[DISPLAY] Opening CarCast Receiver App Window..." -ForegroundColor Cyan
if (Get-Command msedge -ErrorAction SilentlyContinue) {
    Start-Process msedge -ArgumentList "--app=http://localhost:8088", "--window-size=1280,720"
} else {
    Start-Process "http://localhost:8088"
}

Write-Host "`n========================================================" -ForegroundColor Green
Write-Host "  CarCast Windows Receiver is ACTIVE!" -ForegroundColor Green
Write-Host "  Stream Endpoint : ws://0.0.0.0:8080" -ForegroundColor White
Write-Host "  Web Portal      : http://localhost:8088" -ForegroundColor White
Write-Host "========================================================" -ForegroundColor Green
Write-Host "To stop the server, press Enter."
Read-Host
Stop-Process -Id $serverProcess.Id -Force -ErrorAction SilentlyContinue

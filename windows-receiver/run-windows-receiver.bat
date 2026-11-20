@echo off
title CarCast Mirror — Windows PC Receiver
cls

echo ========================================================
echo       CarCast Mirror — Windows PC Screen Receiver
echo ========================================================
echo.

cd /d "%~dp0"

:: 1. Check for Node.js
where node >nul 2>nul
if %ERRORLEVEL% neq 0 (
    if exist "C:\Program Files\nodejs\node.exe" (
        set "PATH=C:\Program Files\nodejs;%PATH%"
    ) else (
        echo [ERROR] Node.js is not found in PATH.
        echo Please install Node.js from https://nodejs.org or install via winget:
        echo winget install OpenJS.NodeJS
        pause
        exit /b 1
    )
)

:: 2. Check for dependencies
if not exist "node_modules\ws" (
    echo [SETUP] Installing required streaming dependencies...
    call npm install ws --no-audit --no-fund
)

echo [START] Starting CarCast Windows Streaming Server...
echo [INFO]  WebSocket Streaming Port : 8080
echo [INFO]  Web Receiver Portal Port : 8088
echo.

:: 3. Launch server in background and open dedicated app window in Edge or Chrome
start /b "" node server.js

:: Brief pause for server socket initialization
timeout /t 2 /nobreak >nul

echo [DISPLAY] Opening CarCast Windows Receiver Window...
:: Attempt to open in frameless application mode using Edge or Chrome
where msedge >nul 2>nul
if %ERRORLEVEL% equ 0 (
    start msedge --app=http://localhost:8088 --window-size=1280,720
) else (
    where chrome >nul 2>nul
    if %ERRORLEVEL% equ 0 (
        start chrome --app=http://localhost:8088 --window-size=1280,720
    ) else (
        start http://localhost:8088
    )
)

echo.
echo ========================================================
echo  Receiver is RUNNING!
echo  On your Android Phone:
echo    1. Open CarCast Mirror -> Sender Mode
echo    2. Under "Nearby CarCast Receivers", tap Connect on this PC
echo ========================================================
echo Press Ctrl+C in this terminal window to stop the receiver.
echo.
pause

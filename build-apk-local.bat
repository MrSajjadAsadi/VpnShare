@echo off
setlocal enabledelayedexpansion
title VpnShare - Local Android APK Builder

echo ===================================================================
echo             VpnShare - Android APK Builder (Local)
echo ===================================================================
echo.

:: 1. Check if Java is installed
where java >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    echo [*] Java is not installed. Installing OpenJDK 17 via winget ...
    winget install Microsoft.OpenJDK.17 --accept-package-agreements --accept-source-agreements
    if %ERRORLEVEL% NEQ 0 (
        echo.
        echo [ERROR] Winget failed to install Java automatically.
        echo Please download and install JDK 17 manually from:
        echo https://learn.microsoft.com/en-us/java/openjdk/download
        pause
        exit /b 1
    )
    echo.
    echo [*] Java installed successfully!
    echo [!] Please close and re-open this script so Windows loads the new Java PATH.
    pause
    exit /b 0
)

echo [*] Java is detected.

:: 2. Check Android SDK
if "%ANDROID_HOME%"=="" (
    if exist "%LOCALAPPDATA%\Android\Sdk" (
        set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
        echo [*] Found Android SDK at: %LOCALAPPDATA%\Android\Sdk
    )
)

cd /d "%~dp0android"

echo.
echo [*] Building APK using Gradle wrapper ...
echo [*] Note: If downloading Gradle/SDK dependencies fails, please ensure your VPN is active (Google Maven restricts direct access from Iran).
echo.

call gradlew.bat assembleDebug

if %ERRORLEVEL% EQU 0 (
    echo.
    echo ===================================================================
    echo [SUCCESS] APK built successfully!
    echo Output file:
    echo %~dp0android\app\build\outputs\apk\debug\app-debug.apk
    echo ===================================================================
    explorer "%~dp0android\app\build\outputs\apk\debug"
) else (
    echo.
    echo ===================================================================
    echo [!] Note: Android requires the Android SDK for local compilation.
    echo If you do not have Android SDK installed on this PC:
    echo.
    echo The FASTEST and ZERO-SETUP way is GitHub Actions:
    echo Push this project to a free GitHub repository, and GitHub
    echo will build the APK in 90 seconds with zero local prerequisites!
    echo ===================================================================
)

pause

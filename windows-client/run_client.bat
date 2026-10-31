@echo off
title VPN Bridge Windows Client
cd /d "%~dp0"
python vpn_bridge.py
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo Running python failed. Press any key to exit...
    pause >nul
)

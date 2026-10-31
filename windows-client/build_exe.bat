@echo off
title Build Standalone VpnBridge.exe
cd /d "%~dp0"
echo ========================================================
echo Installing PyInstaller and compiling VpnBridge.exe ...
echo ========================================================
python -m pip install --upgrade pyinstaller
pyinstaller --noconsole --onefile --name "VpnBridge" vpn_bridge.py

echo.
echo ========================================================
echo Build Finished! 
echo The standalone EXE is located in: windows-client\dist\VpnBridge.exe
echo ========================================================
pause

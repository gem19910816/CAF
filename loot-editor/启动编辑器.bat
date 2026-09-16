@echo off
title CAF Loot Pool Editor
cd /d "%~dp0"

echo ============================================
echo    CAF Loot Pool Visual Editor
echo ============================================
echo.
echo    Starting local server, browser will open...
echo    Close this window to stop the editor.
echo.

where python >nul 2>nul
if %errorlevel%==0 (
  python server.py
) else (
  echo python not found, trying py launcher...
  py -3 server.py
)

echo.
echo Server stopped. Press any key to close.
pause >nul

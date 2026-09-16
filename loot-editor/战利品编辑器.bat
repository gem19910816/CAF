@echo off
rem CAF 战利品池编辑器 —— 桌面程序启动器
rem 双击即弹出原生窗口（不是浏览器网页）
rem 注意：需要在真实 Windows 桌面上双击，远程桌面/沙箱可能起不了 WebView2
title CAF 战利品池编辑器
cd /d "%~dp0"

set "VENV_PY=C:\Users\79662\.workbuddy-ai\binaries\python\envs\default\Scripts\python.exe"

if exist "%VENV_PY%" (
  rem 用 python.exe 起（有控制台窗口，但稳定）
  rem 想隐藏控制台就把 python.exe 改成 pythonw.exe，但 pythonw 下 print 会崩
  start "" "%VENV_PY%" app.py
  exit /b 0
)

rem 环境没找到就退回浏览器网页版
echo 桌面运行环境没找到，退回浏览器网页版...
where python >nul 2>nul
if %errorlevel%==0 (
  python server.py
) else (
  py -3 server.py
)

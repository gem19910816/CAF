# -*- mode: python ; coding: utf-8 -*-
# PyInstaller 打包配置：CAF 战利品池编辑器 → 单个 exe（无控制台窗口）
# 数据文件（web/ icons/ data/ 脚本）不打进 exe —— 放在 exe 旁边，随时可编辑可更新

import os

block_cipher = None

a = Analysis(
    ['app.py'],
    pathex=[],
    binaries=[],
    datas=[
        # 运行时必需且「不属于可编辑资源」的才考虑内嵌；这里全部外置，保持灵活
    ],
    hiddenimports=['webview', 'webview.platforms.winforms', 'clr'],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    win_no_prefer_redirects=False,
    win_private_assemblies=False,
    cipher=block_cipher,
    noarchive=False,
)
pyz = PYZ(a.pure, a.zipped_data, cipher=block_cipher)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.zipfiles,
    a.datas,
    [],
    name='CAF战利品编辑器',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=False,          # 无黑窗口，纯 GUI
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    icon=None,
)

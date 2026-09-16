#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
CAF 战利品池编辑器 —— 桌面程序入口（原生窗口，不是网页）

原理：本地 HTTP 服务跑在后台线程，原生窗口（Windows 自带 WebView2）加载它。
界面、后端、校验、AI 全部复用 server.py，一份代码两用。

用法：双击 战利品编辑器.bat，或
    python app.py            # 默认编辑 chaoszpack_lc_loot
    python app.py --root "D:/other/loot_tables"
    python app.py --mock-llm # 离线模拟 AI
"""
import argparse
import json
import socket
import sys
import threading
import time
import traceback
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path

import runpy

# 冻结 exe 的子进程模式：`CAF战利品编辑器.exe --run-script build_registry ...`
# 让「重建物品库」在没有 Python 的机器上也能跑 —— 脚本已内嵌进 exe。
if len(sys.argv) >= 3 and sys.argv[1] == "--run-script":
    name = sys.argv[2]
    sys.argv = [name + ".py"] + sys.argv[3:]
    try:
        runpy.run_module(name, run_name="__main__")
    except SystemExit as e:
        sys.exit(e.code or 0)
    sys.exit(0)

import server

# 无控制台（pythonw）启动时，出错要留痕——写进 app_error.log
_HERE = Path(sys.executable).resolve().parent if getattr(sys, "frozen", False)     else Path(__file__).resolve().parent
_ERROR_LOG = _HERE / "app_error.log"
# 记住上次的窗口大小 / 编辑目录
_UI_STATE = _HERE / ".ui_state.json"


def _log_err(exc):
    try:
        _ERROR_LOG.write_text(
            time.strftime("[%Y-%m-%d %H:%M:%S] ") + traceback.format_exc(),
            encoding="utf-8")
    except Exception:
        pass


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


def wait_ready(port, timeout=10):
    """等 HTTP 服务就绪。用 socket 直连，不走 urllib（pythonw 下代理可能拦 localhost）。"""
    t0 = time.time()
    while time.time() - t0 < timeout:
        try:
            s = socket.create_connection(("127.0.0.1", port), timeout=2)
            s.close()
            return True
        except Exception:
            time.sleep(0.2)
    return False


def _log(msg):
    """pythonw 无控制台，print 会崩。写进日志文件。"""
    try:
        with open(_ERROR_LOG, "a", encoding="utf-8") as f:
            f.write(time.strftime("[%Y-%m-%d %H:%M:%S] ") + str(msg) + "\n")
    except Exception:
        pass


def load_ui():
    """记住上次的窗口大小 / 编辑目录，下次打开还原。"""
    try:
        return json.loads(_UI_STATE.read_text(encoding="utf-8"))
    except Exception:
        return {}


def save_ui(d):
    try:
        _UI_STATE.write_text(json.dumps(d, ensure_ascii=False), encoding="utf-8")
    except Exception:
        pass


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=None)
    ap.add_argument("--mock-llm", action="store_true")
    ap.add_argument("--width", type=int, default=None)
    ap.add_argument("--height", type=int, default=None)
    args = ap.parse_args()

    ui = load_ui()
    root = args.root or ui.get("root") or str(server.DEFAULT_ROOT)
    # 分发版：默认目录不存在（比如发给别人、路径不同）时自动探测整合包，
    # 探测不到就给一个空目录让前端弹「切换战利品表目录」引导
    if not Path(root).is_dir():
        inst = server.detect_instance(server.HERE)
        if inst:
            dirs = server.find_loot_dirs(inst)
            if dirs:
                root = dirs[0]["path"] if isinstance(dirs[0], dict) else str(dirs[0])
    server.ROOT = Path(root)
    server.MOCK_LLM = args.mock_llm
    server.PORT = free_port()
    win_w = args.width or int(ui.get("w") or 1480)
    win_h = args.height or int(ui.get("h") or 920)

    httpd = ThreadingHTTPServer(("127.0.0.1", server.PORT), server.Handler)
    httpd.daemon_threads = True
    t = threading.Thread(target=httpd.serve_forever, daemon=True)
    t.start()

    if not wait_ready(server.PORT):
        _log("服务没起来")
        sys.exit(1)

    url = f"http://127.0.0.1:{server.PORT}"
    _log(f"服务已就绪: {url}  (编辑目录: {server.ROOT})")
    # 把端口写到文件，方便外部确认（窗口式启动时 stdout 不可见）
    try:
        (_HERE / ".runtime_port").write_text(str(server.PORT), encoding="utf-8")
    except Exception:
        pass

    try:
        import webview
    except ImportError:
        _log("缺少 pywebview，退回浏览器打开")
        import webbrowser
        webbrowser.open(url)
        input("按回车停止…")
        httpd.shutdown()
        return

    win = webview.create_window(
        "CAF 战利品池编辑器",
        url,
        width=win_w,
        height=win_h,
        min_size=(1100, 700),
        text_select=True,
        js_api=_DesktopApi(),
    )

    def _remember():
        """关窗前记住窗口大小和当前编辑目录，下次原样打开。"""
        try:
            save_ui({"w": win.width, "h": win.height, "root": str(server.ROOT)})
        except Exception:
            pass

    try:
        win.events.closed += _remember
    except Exception:
        pass

    _log("调用 webview.start()")
    try:
        webview.start()          # 阻塞，直到窗口关闭
    except Exception as e:
        _log(f"webview.start() 崩了: {e}")
        _log(traceback.format_exc())
        # 退回浏览器
        import webbrowser
        webbrowser.open(url)
        _log("已退回浏览器打开")
        return
    _remember()
    _log("webview.start() 返回（窗口已关闭）")
    httpd.shutdown()


class _DesktopApi:
    """暴露给前端 JS 的原生能力（window.pywebview.api.*）"""

    def copy_text(self, text):
        """把文本写进系统剪贴板（网页版的 navigator.clipboard 在 pywebview 里常被拦）"""
        try:
            import webview
            win = webview.windows[0] if webview.windows else None
            if win:
                # pywebview 没有直接的剪贴板 API，用 Windows 自带的
                import subprocess
                subprocess.run("clip", input=str(text).encode("utf-16-le"),
                               shell=True, check=False)
                return True
        except Exception:
            pass
        return False

    def pick_directory(self):
        """弹系统原生「选择文件夹」对话框，返回选中的路径"""
        import webview
        win = webview.windows[0] if webview.windows else None
        if not win:
            return None
        res = win.create_file_dialog(webview.FOLDER_DIALOG)
        return res[0] if res else None


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        _log_err(e)
        # 也尽量弹个提示（有窗口环境时）
        try:
            import webview
            webview.create_window("启动失败", f"data:text/plain,{e}", width=520, height=200)
            webview.start()
        except Exception:
            pass
        sys.exit(1)

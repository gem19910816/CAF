# CAF 战利品池可视化编辑器（Loot Editor）

**本地工具（不是游戏模组）—— 用桌面窗口可视化编辑整合包的战利品池。**

编辑对象：`kubejs/data/chaoszpack_lc_loot/loot_tables/` 下的战利品表（当前约 41 个文件 / 48 个池 / 8000+ 条目）。

## 技术组成

| 部分 | 文件 | 说明 |
| --- | --- | --- |
| 后端 | `server.py` | **零依赖纯标准库** HTTP 服务；保存自动备份、删除进暂存、AI 操作校验后才落盘 |
| 桌面壳 | `app.py` | pywebview 桌面窗口（pythonw 启动，不弹黑框） |
| 前端 | `web/`（index.html / app.js / style.css） | 纯静态前端 |
| 工具脚本 | `build_registry.py` / `scan_items.py` / `scan_mod_names.py` / `clean_dead.py` / `strip_pool_chance.py` / `verify_vs_log.py` / `_analyze_log.py` | 物品库/注册表扫描生成、废表清理、概率调整、日志校验等 |
| 打包 | `打包.spec` | PyInstaller 绿色版打包配置，产物在 `dist/` |

## 启动

```bash
# 方式一：桌面窗口（推荐）
双击 战利品编辑器.bat        # venv pythonw + pywebview 桌面窗口

# 方式二：纯浏览器
python server.py            # 零依赖标准库，默认端口 8787
```

## 功能

- 战利品表文件 / 池 / 条目的可视化增删改，开箱预览、批量操作
- 搜索（中文名 / 物品名 / 注册名 / 模组），按模组过滤（`auth_ns` 规则）
- AI 面板：OpenAI 兼容接口，LLM 只输出操作 JSON，后端 `apply_ops` 校验后才落盘；
  密钥存 `data/ai_config.json`（本地私有，**不入库**）；`--mock-llm` 可离线模拟
- 保存自动备份到 `_backup/`，删除暂存到 `_trash/`，可恢复

## ⚠️ 关键约定（改之前先看）

1. **保存必须用后端的 `dumps_loot()`，禁止用 `json.dumps(indent=2)`** —— 前者保证格式与字段顺序兼容游戏读取
2. 改前端后靠 `index.html` 的 `?v=` 版本号刷缓存
3. 改任何吸顶元素前先看 `.file-head`（自己就是 sticky，高约 116px）与 CSS 变量 `--filebar-h`
   （由 `app.js` 的 `syncStickyOffsets()` 动态测量），否则会被文件工具栏盖住
4. 打包绿色版：PyInstaller 跑 `打包.spec`，产物在 `dist/`

## 哪些内容不入库（本仓库已排除）

| 路径 | 原因 |
| --- | --- |
| `data/` | 运行数据/扫描产物（`registry.json`、`items.json` 等，可重新生成），且含私有的 `ai_config.json`（API Key） |
| `icons/` | 物品图标库（万级小文件，由脚本生成） |
| `dist/` / `build/` | 打包产物 / PyInstaller 中间目录 |
| `_backup/` / `_trash/` / `__pycache__/` / 日志 | 运行时产物 |

克隆后首次使用：先跑 `build_registry.py`、`scan_items.py`、`scan_mod_names.py` 生成数据，
或从原环境拷贝 `data/` 目录（**不要拷 `ai_config.json`**）。

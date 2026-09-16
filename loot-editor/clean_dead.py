# -*- coding: utf-8 -*-
"""清理废弃条目 —— 删掉「游戏里不存在」的引用，让整张表能在游戏里加载

为什么必须清
------------
Minecraft 1.20.1 解析战利品表时，`minecraft:item` 的 name 会查 ITEM 注册表；
**只要有一条查不到，立刻抛 JsonSyntaxException，整张表作废** ——
表现就是「建筑里的箱子全空 / 用不了」，而工具自己的预览照样能刷出东西。

判定用的是 data/registry.json（build_registry.py 生成 + 游戏日志校准），
和「校验」按钮、开箱预览、物品选择器完全同一套规则，不存在两套标准。

用法:
    python clean_dead.py                 # 演练：只列出会被删的条目
    python clean_dead.py --apply         # 真删（每个文件先自动备份到 _backup/）
    python clean_dead.py --apply --root "D:/别的/loot_tables"
"""
import argparse
import importlib.util
import json
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.stdout.reconfigure(encoding="utf-8")

_spec = importlib.util.spec_from_file_location("srv", str(HERE / "server.py"))
srv = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(srv)

TRASH_DIR = HERE / "_trash"


def walk_entries(entries, path=()):
    """深度遍历，产出 (索引路径, 条目)；children 里的也算"""
    for i, e in enumerate(entries or []):
        if not isinstance(e, dict):
            continue
        yield path + (i,), e
        for sub in ("children", "entries"):
            if isinstance(e.get(sub), list):
                yield from walk_entries(e[sub], path + (i, sub))


def get_entry(root_entries, idx_path):
    cur = root_entries
    for k in idx_path:
        cur = cur[k]
    return cur


def dead_of_entry(e):
    """这条条目该不该删？返回原因或 None（与前端「清理废弃」同一套判定）"""
    if not isinstance(e, dict):
        return None
    t = srv._norm(e.get("type"))
    if t == "minecraft:item":
        nm = e.get("name")
        if isinstance(nm, str) and nm and not nm.startswith("#"):
            return srv.item_problem(nm)
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=None)
    ap.add_argument("--apply", action="store_true", help="真的写入（默认只演练）")
    args = ap.parse_args()

    root = Path(args.root).resolve() if args.root else srv.ROOT
    if not root.is_dir():
        print("!! 目录不存在：%s" % root)
        return 1

    rep = srv.registry_meta()
    print("=" * 70)
    print("  目录   : %s" % root)
    print("  注册表 : 生成于 %s（%s）" % (rep.get("generated", "?"), rep.get("rule", "?")[:60]))
    print("  模式   : %s" % ("**真删（先备份）**" if args.apply else "演练（不写文件）"))
    print("=" * 70)

    report = []
    stats = {"files": 0, "dead": 0, "changed": 0, "emptiedPools": 0}
    for f in sorted(root.rglob("*.json")):
        rel = str(f.relative_to(root)).replace("\\", "/")
        try:
            obj = json.loads(f.read_text(encoding="utf-8"))
        except Exception as ex:
            print("  !! 跳过（JSON 读不了）：%s — %s" % (rel, ex))
            continue
        stats["files"] += 1
        found = []
        for pi, pool in enumerate(obj.get("pools") or []):
            if not isinstance(pool, dict):
                continue
            for idx_path, e in walk_entries(pool.get("entries") or []):
                why = dead_of_entry(e)
                if why:
                    found.append((pi, idx_path, e.get("name"), why))
        if not found:
            continue
        stats["dead"] += len(found)
        report.append("### %s（%d 条）" % (rel, len(found)))
        for pi, idx_path, nm, why in found:
            report.append("    池%d %s  %-44s %s"
                          % (pi + 1, ".".join(str(x) for x in idx_path), nm, why.split("。")[0][:96]))

        # 从后往前删，索引不会乱；先收集要删的父路径
        by_container = {}
        for pi, idx_path, nm, why in found:
            container = (pi,) + idx_path[:-1]
            by_container.setdefault(container, []).append(idx_path[-1])

        for (pi, *rest), idxs in by_container.items():
            entries = (obj.get("pools") or [])[pi].get("entries") or []
            # rest 是 children/entries/索引 交替的路径
            cur = entries
            ok = True
            for step in rest[:-1]:
                try:
                    cur = cur[step]
                except Exception:
                    ok = False
                    break
            if not ok or not isinstance(cur, list):
                continue
            for i in sorted(set(idxs), reverse=True):
                if 0 <= i < len(cur):
                    cur.pop(i)
        # 删完后检查空池
        for pi, pool in enumerate(obj.get("pools") or []):
            if isinstance(pool, dict) and not (pool.get("entries") or []):
                stats["emptiedPools"] += 1
                report.append("    ⚠ 池%d 清空后没有条目了 —— 这个池永远不会产出（需要补内容）" % (pi + 1))

        if args.apply:
            srv.make_backup(rel, f)
            srv.atomic_write(f, srv.dumps_loot(obj) + "\n")
            stats["changed"] += 1
            print("  已清理 %-52s -%d 条" % (rel, len(found)))
        else:
            print("  待清理 %-52s -%d 条" % (rel, len(found)))

    print()
    print("=== 结果 ===")
    print("  扫描文件   : %d" % stats["files"])
    print("  废弃条目   : %d" % stats["dead"])
    print("  写入文件   : %d" % stats["changed"])
    if stats["emptiedPools"]:
        print("  ⚠ 被清空的池: %d 个（这些池需要补内容）" % stats["emptiedPools"])
    if not stats["dead"]:
        print("  ✓ 没有废弃条目，全库都引用了游戏里真实存在的物品")

    out = HERE / ("清废报告_%s.txt" % time.strftime("%Y%m%d-%H%M%S"))
    out.write_text("\n".join(report) or "（无废弃条目）", encoding="utf-8")
    print("  明细报告   : %s" % out)
    if not args.apply and stats["dead"]:
        print()
        print("  演练结束 —— 确认无误后加 --apply 真删（每步都会先备份）")
    return 0


if __name__ == "__main__":
    sys.exit(main())

# -*- coding: utf-8 -*-
"""把「池级 random_chance」去掉 —— 让箱子恢复成「一开就有东西」

背景
----
`random_chance` 挂在**池**上时，官方语义是「这个池只以该概率参与抽取」：
池不触发，整个池一件都不出。所以池触发率 0.3 = 70% 的箱子是空的。
这完全符合 1.20.1 规范（校验不会、也不该报错），但玩家看到的就是「刷不出东西」。

模组 jar 里的原始副本**没有**这个条件 —— 是后来编辑时批量加上去的
（全库 29 张表 / 30 个池都是同一个 0.3），所以有了这个脚本。

⚠ 只删「池」这一层的 random_chance：
  条目级（entries[].conditions）的 random_chance 是你用来做稀有度的，一律不动。

用法:
    python strip_pool_chance.py                     # 演练：只列出会被改的池
    python strip_pool_chance.py --apply             # 真改（每个文件先备份）
    python strip_pool_chance.py --apply --only shop,restaurant   # 只改指定表
"""
import argparse
import importlib.util
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.stdout.reconfigure(encoding="utf-8")

_spec = importlib.util.spec_from_file_location("srv", str(HERE / "server.py"))
srv = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(srv)

POOL_CHANCE = ("minecraft:random_chance", "minecraft:random_chance_with_looting")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=None)
    ap.add_argument("--apply", action="store_true", help="真的写入（默认只演练）")
    ap.add_argument("--only", default="", help="只处理这些表（逗号分隔的文件名，不含 .json）")
    args = ap.parse_args()

    root = Path(args.root).resolve() if args.root else srv.ROOT
    if not root.is_dir():
        print("!! 目录不存在：%s" % root)
        return 1
    only = {x.strip() for x in args.only.split(",") if x.strip()}

    print("=" * 72)
    print("  目录 : %s" % root)
    print("  模式 : %s" % ("**真改（先备份）**" if args.apply else "演练（不写文件）"))
    if only:
        print("  范围 : %s" % ", ".join(sorted(only)))
    print("=" * 72)

    changed = stripped = 0
    for f in sorted(root.rglob("*.json")):
        if only and f.stem not in only:
            continue
        rel = str(f.relative_to(root)).replace("\\", "/")
        try:
            obj = json.loads(f.read_text(encoding="utf-8"))
        except Exception:
            continue
        before = srv.output_report(obj)
        hits = []
        for i, pool in enumerate(obj.get("pools") or []):
            if not isinstance(pool, dict):
                continue
            conds = pool.get("conditions")
            if not isinstance(conds, list):
                continue
            keep = [c for c in conds
                    if not (isinstance(c, dict) and srv._norm(c.get("condition")) in POOL_CHANCE)]
            if len(keep) != len(conds):
                removed = [c for c in conds if c not in keep]
                chs = [c.get("chance") for c in removed if isinstance(c, dict)]
                hits.append((i, chs))
                if keep:
                    pool["conditions"] = keep
                else:
                    pool.pop("conditions", None)      # 只剩触发条件时整块删掉，保持干净
        if not hits:
            continue
        after = srv.output_report(obj)
        changed += 1
        stripped += len(hits)
        pb = before["emptyChance"] * 100 if before else 0
        pa = after["emptyChance"] * 100 if after else 0
        print("  %-46s 去掉 %d 个池级条件   空箱率 %.0f%% -> %.0f%%"
              % (rel, len(hits), pb, pa))
        if args.apply:
            srv.make_backup(rel, f)
            srv.atomic_write(f, srv.dumps_loot(obj) + "\n")

    print()
    print("=== 结果 ===")
    print("  涉及文件 : %d" % changed)
    print("  去掉条件 : %d 个" % stripped)
    if not args.apply and stripped:
        print()
        print("  演练结束 —— 确认没问题后加 --apply（每个文件都会先备份到 _backup/）")
    if not stripped:
        print("  ✓ 没有池级 random_chance（箱子本来就不会整箱落空）")
    return 0


if __name__ == "__main__":
    sys.exit(main())

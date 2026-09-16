# -*- coding: utf-8 -*-
"""对着游戏日志验工具 —— 「工具说没问题」和「游戏真的没问题」是不是同一件事

判据来自 Minecraft 自己的日志（logs/latest.log）：
    加载失败  : [ForgeHooks/ERROR] Couldn't parse element loot_tables:<表ID>
                下一行   com.google.gson.JsonSyntaxException: Expected name to be an item,
                         was unknown string '<物品ID>'
    加载成功  : 没出现在上面的表，就是游戏确认能用的表（它引用的物品都是真的）

于是可以双向对拍：
    1) 游戏说崩、工具说没问题的表  → 工具的漏判（必须为 0）
    2) 游戏崩掉的表引用的物品 ID   → 工具必须判「不存在」
    3) 游戏成功加载的表引用的物品  → 工具必须判「存在」（误报会导致误删真物品）

用法:
    python verify_vs_log.py                 # 用当前实例的最新日志对拍
    python verify_vs_log.py --log "D:/.../logs/latest.log"
"""
import argparse
import importlib.util
import json
import re
import sys
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.stdout.reconfigure(encoding="utf-8")

_spec = importlib.util.spec_from_file_location("srv", str(HERE / "server.py"))
srv = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(srv)


def parse_log(path: Path):
    """返回 (游戏判定不存在的物品 ID 集合, 加载失败的表 ID 集合, 日志时间)"""
    import time
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    bad, failed = set(), set()
    for i, ln in enumerate(lines):
        m = re.search(r"Couldn't parse element loot_tables:(\S+)", ln)
        if not m:
            continue
        failed.add(m.group(1))
        if i + 1 < len(lines):
            m2 = re.search(r"unknown string '([^']+)'", lines[i + 1])
            if m2:
                bad.add(m2.group(1))
    stamp = time.strftime("%Y-%m-%d %H:%M", time.localtime(path.stat().st_mtime))
    return bad, failed, stamp


def collect_refs(instance: Path, failed_tables):
    """从「游戏成功加载过」的战利品表里收集物品引用 = 真实物品真值集"""
    out = set()

    def walk(node):
        if isinstance(node, dict):
            if node.get("type") == "minecraft:item" and isinstance(node.get("name"), str):
                out.add(node["name"])
            for v in node.values():
                if isinstance(v, (dict, list)):
                    walk(v)
        elif isinstance(node, list):
            for v in node:
                walk(v)

    def harvest(data, rid):
        if rid in failed_tables:
            return
        for p in data.get("pools") or []:
            if isinstance(p, dict):
                walk(p.get("entries") or [])

    for jar in sorted((instance / "mods").glob("*.jar")):
        try:
            z = zipfile.ZipFile(jar)
        except Exception:
            continue
        for n in z.namelist():
            if "/loot_tables/" not in n or not n.endswith(".json"):
                continue
            rid = n.split("data/", 1)[-1].replace("/loot_tables/", ":").rsplit(".json", 1)[0]
            try:
                harvest(json.loads(z.read(n).decode("utf-8", "replace")), rid)
            except Exception:
                pass
        z.close()
    for f in (instance / "kubejs" / "data").rglob("loot_tables/*.json"):
        rel = str(f).replace("\\", "/")
        rid = rel.split("data/", 1)[-1].replace("/loot_tables/", ":").rsplit(".json", 1)[0]
        try:
            harvest(json.loads(f.read_text(encoding="utf-8")), rid)
        except Exception:
            pass
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--log", default=None)
    ap.add_argument("--instance", default=None)
    args = ap.parse_args()

    inst = Path(args.instance).resolve() if args.instance else srv.detect_instance(HERE)
    if not inst:
        print("!! 找不到整合包实例")
        return 1
    log = Path(args.log) if args.log else (inst / "logs" / "latest.log")
    if not log.is_file():
        print("!! 找不到日志：%s（先启动一次游戏）" % log)
        return 1

    bad_items, failed_tables, stamp = parse_log(log)
    reg = srv.registry()
    valid, invalid = reg["valid"], reg["invalid"]
    print("=" * 70)
    print("  日志   : %s（%s）" % (log, stamp))
    print("  注册表 : %s  |  真实物品 %d 个，判定不存在 %d 个"
          % (reg["meta"].get("generated", "?"), len(valid or ()), len(invalid)))
    print("=" * 70)

    problems = 0

    # 1) 游戏说不存在的物品，工具必须也说不存在
    wrong = sorted(x for x in bad_items if valid is None or x in valid)
    print("[1] 游戏判定「不存在的物品」：%d 个；工具漏判 %d 个" % (len(bad_items), len(wrong)))
    for x in wrong:
        print("    !! 漏判：%s" % x)
    problems += len(wrong)

    # 2) 本目录的表：游戏崩过的，现在工具还认为有问题的有几个
    #    注意日志是「上一轮启动」的快照：表在日志之后被改好过，就不该再算漏判
    import time as _t
    log_mtime = log.stat().st_mtime
    mine = "chaoszpack_lc_loot:"
    tbl = sorted(x for x in failed_tables if x.startswith(mine))
    caught, fixed_after, missed = [], [], []
    for rid in tbl:
        rel = rid[len(mine):] + ".json"
        p = srv.ROOT / rel
        if not p.is_file():
            continue
        try:
            obj = json.loads(p.read_text(encoding="utf-8"))
        except Exception:
            missed.append(rid + "（JSON 读不了）")
            continue
        if srv.validate(obj)["errors"]:
            caught.append(rid)
        elif p.stat().st_mtime > log_mtime:
            fixed_after.append(rid)
        else:
            missed.append(rid)
    print("[2] 本目录（%s）日志里崩过的表：%d 个" % (srv.ROOT.name, len(tbl)))
    print("    工具抓到问题     : %d 个" % len(caught))
    print("    日志之后已改好   : %d 个（不算漏判，等下次启动日志刷新）" % len(fixed_after))
    print("    真漏判           : %d 个" % len(missed))
    for x in missed:
        print("    !! 漏判：%s" % x)
    problems += len(missed)

    # 3) 游戏成功加载的表引用的物品，工具不该判成不存在（否则会误删真物品）
    refs = collect_refs(inst, failed_tables)
    false_alarm = sorted(x for x in refs if valid is not None and x not in valid)
    print("[3] 游戏成功加载的表引用的物品：%d 个；被工具误判为不存在 %d 个"
          % (len(refs), len(false_alarm)))
    for x in false_alarm[:20]:
        print("    !! 误判：%-44s %s" % (x, invalid.get(x, "?")[:70]))
    problems += len(false_alarm)

    print()
    if problems == 0:
        print("✓ 对拍通过：工具的判断和游戏日志完全一致")
        return 0
    print("✗ 有 %d 处不一致 —— 工具还需要修" % problems)
    return 2


if __name__ == "__main__":
    sys.exit(main())

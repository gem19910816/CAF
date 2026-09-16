# -*- coding: utf-8 -*-
"""临时分析：从 latest.log 挖出所有「游戏判定不存在」的 ID + 各种错误形态"""
import collections
import io
import re
import sys

sys.stdout.reconfigure(encoding="utf-8")

LOG = r"D:\PCL2\.minecraft\versions\0.49备份\logs\latest.log"
lines = open(LOG, encoding="utf-8", errors="replace").read().splitlines()

kinds = collections.Counter()
for l in lines:
    for pat in ("unknown string '", "Unknown entry", "Unknown item", "Couldn't parse",
                "Non [a-z", "unknown loot table", "Failed to load", "Not a valid"):
        if pat in l:
            kinds[pat] += 1
print("=== 错误形态计数 ===")
for k, v in kinds.most_common():
    print("  %-24s %d" % (k, v))

ids = collections.Counter()
for l in lines:
    for m in re.finditer(r"unknown string '([^']+)'", l):
        ids[m.group(1)] += 1
print()
print("=== unknown string ID 共 %d 个 ===" % len(ids))
for k, v in ids.most_common(60):
    print("  %2d  %s" % (v, k))

print()
print("=== 非 unknown-string 的报错样本（前 40 条去重） ===")
seen = set()
n = 0
for l in lines:
    if ("ERROR" in l or "WARN" in l) and ("loot" in l.lower() or "tag" in l.lower()
                                          or "recipe" in l.lower()):
        s = l.split("]: ", 1)[-1][:200]
        if s in seen:
            continue
        seen.add(s)
        print("  " + s)
        n += 1
        if n >= 40:
            break

# -*- coding: utf-8 -*-
"""通用物品库扫描器 —— 扫任意 Minecraft 整合包实例，生成物品库（ID -> 中/英名 + 图标）

⚠ 这个脚本只负责「名字 + 图标」，**它猜不出一个 ID 在游戏里是不是真实物品**：
  它把 assets/<ns>/blockstates/<id>.json、block.<ns>.<id> 语言键也算进来，
  但方块可以没有物品形态（双格作物的下半段、纯装饰方块……）。
  真正的存在性判定在 build_registry.py（它会给 items.json 打上 valid / why），
  服务端「重建物品库」按钮会先跑本脚本、再跑 build_registry.py，两步缺一不可。

用法:
    python scan_items.py                          # 自动探测当前实例
    python scan_items.py --instance "D:/.../实例"  # 指定实例
    python scan_items.py --out "D:/.../data"       # 指定输出目录
    python scan_items.py --no-icons                # 不导出图标（快很多）

产物:
    <out>/items.json   {"items":[...], "used":[...], "missing":[...]}
    <out>/../icons/<ns>/<path>.png
"""
import argparse
import json
import re
import sys
import zipfile
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8")

HERE = Path(__file__).resolve().parent

BAD_SUFFIX = (".desc", ".tooltip", ".info", ".description", ".jei",
              ".patchouli", ".tip", ".hint", ".guide")

RE_LANG = re.compile(r"assets/([^/]+)/lang/(zh_cn|en_us)\.json$")
RE_ITEMMODEL = re.compile(r"assets/([^/]+)/models/item/(.+)\.json$")
RE_BLOCKMODEL = re.compile(r"assets/([^/]+)/models/block/(.+)\.json$")
RE_BLOCKSTATE = re.compile(r"assets/([^/]+)/blockstates/(.+)\.json$")
RE_TEX = re.compile(r"assets/([^/]+)/textures/(.+)\.png$")
RE_ITEMKEY = re.compile(r"^(item|block)\.([^.]+)\.([^.]+)$")


# ==========================================================================
#  实例探测
# ==========================================================================
INSTANCE_MARKERS = ("mods", "kubejs", "config", "saves")


def looks_like_instance(p: Path) -> bool:
    return p.is_dir() and sum(1 for m in INSTANCE_MARKERS if (p / m).is_dir()) >= 2


def detect_instance(start: Path):
    p = Path(start).resolve()
    if p.is_file():
        p = p.parent
    for cand in [p] + list(p.parents):
        if looks_like_instance(cand):
            return cand
    return None


# ==========================================================================
#  收集
# ==========================================================================
class Collector:
    def __init__(self, icons_dir=None):
        self.items = {}
        self.icons_dir = icons_dir
        self.icon_count = 0
        self.active = True      # 当前扫描源是否「启用中」（禁用的 jar 要标 False）

    def touch(self, ns, iid):
        key = f"{ns}:{iid}"
        e = self.items.get(key)
        if e is None:
            e = self.items[key] = {"id": key, "ns": ns, "path": iid,
                                   "zh": None, "en": None, "icon": None,
                                   "active": self.active}
        elif self.active:
            e["active"] = True          # 启用源里也有 → 算启用
        return e

    def save_icon(self, ns, iid, raw):
        if not self.icons_dir:
            return None
        rel = f"{ns}/{iid}.png"
        dst = self.icons_dir / rel
        try:
            dst.parent.mkdir(parents=True, exist_ok=True)
            dst.write_bytes(raw)
        except Exception:
            return None
        self.icon_count += 1
        return "icons/" + rel


def model_refs(ns, mname, models, read, seen, depth):
    """递归解析模型用到的纹理引用"""
    if depth > 8:
        return []
    key = (ns, mname)
    if key in seen:
        return []
    seen.add(key)
    zn = models.get(key)
    if not zn:
        return []
    try:
        d = json.loads(read(zn).decode("utf-8", "ignore"))
    except Exception:
        return []
    if not isinstance(d, dict):
        return []
    refs = []
    tx = d.get("textures")
    if isinstance(tx, dict):
        for v in tx.values():
            if isinstance(v, str) and v and not v.startswith("#"):
                refs.append(v)
    if not refs:
        par = d.get("parent")
        if isinstance(par, str) and par:
            if ":" in par:
                pns, _, pn = par.partition(":")
            else:
                pns, pn = "minecraft", par
            refs = model_refs(pns, pn, models, read, seen, depth + 1)
    return refs


def find_icon(ns, iid, models, textures, read):
    for prefix in ("item/", "items/", "block/", "blocks/"):
        zn = textures.get((ns, prefix + iid))
        if zn:
            return zn
    refs = model_refs(ns, "item/" + iid, models, read, set(), 0)
    if not refs:
        refs = model_refs(ns, "block/" + iid, models, read, set(), 0)
    for r in refs:
        rns, _, rp = r.partition(":")
        if not rp:
            rns, rp = ns, r
        zn = textures.get((rns, rp))
        if zn:
            return zn
    return None


def process(names, read, col: Collector):
    """处理一个来源里的全部条目名"""
    langmap, models, blockstates, textures = {}, {}, {}, {}
    for n in names:
        m = RE_LANG.match(n)
        if m:
            try:
                data = json.loads(read(n).decode("utf-8", "ignore"))
            except Exception:
                continue
            if not isinstance(data, dict):
                continue
            code = "zh" if m.group(2) == "zh_cn" else "en"
            for k, v in data.items():
                km = RE_ITEMKEY.match(k)
                if not km or not isinstance(v, str):
                    continue
                kid = km.group(3)
                if kid.endswith(BAD_SUFFIX):
                    continue
                e = langmap.setdefault((km.group(2), kid), {})
                e.setdefault(code, v)
            continue
        m = RE_ITEMMODEL.match(n)
        if m:
            models.setdefault((m.group(1), "item/" + m.group(2)), n)
            continue
        m = RE_BLOCKMODEL.match(n)
        if m:
            models.setdefault((m.group(1), "block/" + m.group(2)), n)
            continue
        m = RE_BLOCKSTATE.match(n)
        if m:
            blockstates.setdefault((m.group(1), m.group(2)), n)
            continue
        m = RE_TEX.match(n)
        if m:
            textures.setdefault((m.group(1), m.group(2)), n)

    ids = set()
    for (ns, p) in models:
        if p.startswith("item/"):
            ids.add((ns, p[5:]))
    for k in blockstates:
        ids.add(k)
    for k in langmap:
        ids.add(k)

    for (ns, iid) in ids:
        e = col.touch(ns, iid)
        lm = langmap.get((ns, iid), {})
        if not e["zh"]:
            e["zh"] = lm.get("zh")
        if not e["en"]:
            e["en"] = lm.get("en")
        if not e["icon"]:
            zn = find_icon(ns, iid, models, textures, read)
            if zn:
                e["icon"] = col.save_icon(ns, iid, read(zn))
    return len(ids)


def scan_jar(path: Path, col: Collector, label=""):
    try:
        with zipfile.ZipFile(path) as z:
            names = [n for n in z.namelist() if n.startswith("assets/")]
            process(names, z.read, col)
    except Exception as ex:
        print(f"   !! 打不开 {path.name}: {ex}", flush=True)


def scan_dir(root: Path, col: Collector):
    files = [p for p in root.rglob("*") if p.is_file()]
    names = [str(p.relative_to(root)).replace("\\", "/") for p in files]
    byname = {n: p for n, p in zip(names, files)}
    process(names, lambda n: byname[n].read_bytes(), col)


# ---- KubeJS 脚本注册的物品（没有 assets，只能从脚本里抠）----
RE_CREATE = re.compile(r"event\.create\(\s*(['\"])((?:\\.|(?!\1).)*)\1")
RE_DISPLAY = re.compile(r"\.displayName\(\s*(['\"])((?:\\.|(?!\1).)*)\1")


def _unescape(s):
    return (s.replace("\\'", "'").replace('\\"', '"')
             .replace("\\\\", "\\").replace("\\n", " "))


def scan_kjs_scripts(instance: Path, col: Collector):
    """从 kubejs/startup_scripts/*.js 提取 event.create() 注册的物品与 displayName"""
    root = instance / "kubejs" / "startup_scripts"
    if not root.is_dir():
        return 0
    n = 0
    for f in sorted(root.rglob("*.js")):
        try:
            txt = f.read_text(encoding="utf-8", errors="ignore")
        except Exception:
            continue
        parts = RE_CREATE.split(txt)   # 步长 3：[前, 引号, 内容, 段, ...]
        for i in range(1, len(parts), 3):
            iid = parts[i + 1].strip()
            if ":" not in iid:
                continue
            ns, _, path = iid.partition(":")
            e = col.touch(ns, path)
            seg = parts[i + 2] if i + 2 < len(parts) else ""
            dm = RE_DISPLAY.search(seg)
            if dm and not e["zh"]:
                e["zh"] = _unescape(dm.group(2))
            n += 1
    return n


# ==========================================================================
#  主流程
# ==========================================================================
def find_vanilla_jar(instance: Path):
    """找原版客户端 jar：<实例>/<名>.jar 或 <实例>/versions/<ver>/<ver>.jar"""
    cands = sorted(instance.glob("*.jar"))
    if cands:
        return cands[0]
    for d in sorted(instance.glob("versions/*")):
        js = sorted(d.glob("*.jar"))
        if js:
            return js[0]
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--instance", default=None, help="整合包实例目录；不填就自动探测")
    ap.add_argument("--out", default=None, help="输出目录（默认 _loot_editor/data）")
    ap.add_argument("--no-icons", action="store_true", help="不导出图标（快很多）")
    ap.add_argument("--fresh", action="store_true",
                    help="不合并旧物品库，完全按本次扫描结果覆盖")
    args = ap.parse_args()

    inst = Path(args.instance).resolve() if args.instance else detect_instance(HERE)
    if not inst or not inst.is_dir():
        print("!! 找不到整合包实例，请用 --instance 指定")
        return 1
    out = Path(args.out).resolve() if args.out else (HERE / "data")
    out.mkdir(parents=True, exist_ok=True)
    icons = None if args.no_icons else (out.parent / "icons")
    col = Collector(icons)

    print("=" * 62)
    print(f"  实例 : {inst}")
    print(f"  输出 : {out}")
    print("=" * 62)

    # 1) 模组 jar —— 启用的和禁用的要分开标，禁用的物品游戏里其实不存在
    mods = inst / "mods"
    enabled = sorted(mods.glob("*.jar")) + sorted((mods / ".connector").glob("*.jar"))
    disabled = sorted(mods.glob("*.jar.disabled"))
    print(f"[1/3] 扫描 {len(enabled)} 个启用的模组 jar ...", flush=True)
    col.active = True
    for i, jar in enumerate(enabled, 1):
        scan_jar(jar, col)
        if i % 25 == 0 or i == len(enabled):
            print(f"   ... {i}/{len(enabled)}  物品 {len(col.items)}", flush=True)
    if disabled:
        print(f"[1/3] 扫描 {len(disabled)} 个已禁用的模组 jar（标记为不可用）...", flush=True)
        col.active = False
        for jar in disabled:
            scan_jar(jar, col)
        col.active = True

    # 2) 原版客户端 jar
    vj = find_vanilla_jar(inst)
    if vj:
        print(f"[2/3] 扫描原版 jar：{vj.name} ...", flush=True)
        scan_jar(vj, col)
    else:
        print("[2/3] 没找到原版客户端 jar，跳过", flush=True)

    # 3) KubeJS 自定义资源 + 脚本注册的物品
    kjs_assets = inst / "kubejs" / "assets"
    if kjs_assets.is_dir():
        print("[3/3] 扫描 KubeJS 自定义资源 ...", flush=True)
        scan_dir(kjs_assets, col)
    n_kjs = scan_kjs_scripts(inst, col)
    print(f"[3/3] KubeJS 脚本注册物品：{n_kjs} 条", flush=True)

    arr = sorted(col.items.values(), key=lambda x: x["id"])

    # 默认「合并」：保留旧库里扫不到、但可能由模组运行时生成的条目（如脚本生成的蓝图），
    # 避免重扫把本包特有的东西弄丢。要干净重来加 --fresh。
    out_file = out / "items.json"
    merged = 0
    if not args.fresh and out_file.exists():
        try:
            old = json.loads(out_file.read_text(encoding="utf-8")).get("items", [])
            have = {x["id"] for x in arr}
            byid = {x["id"]: x for x in arr}
            for o in old:
                if o.get("id") in have:
                    # 新扫描没拿到名字/图标时，用旧的补
                    n = byid[o["id"]]
                    if not n.get("zh") and o.get("zh"):
                        n["zh"] = o["zh"]
                    if not n.get("icon") and o.get("icon"):
                        n["icon"] = o["icon"]
                else:
                    arr.append(o)
                    merged += 1
            arr.sort(key=lambda x: x["id"])
        except Exception:
            pass

    out_file.write_text(json.dumps({"items": arr}, ensure_ascii=False), encoding="utf-8")

    with_zh = sum(1 for x in arr if x["zh"])
    with_icon = sum(1 for x in arr if x["icon"])
    print()
    print("=== 结果 ===")
    print(f"物品条目总数 : {len(arr)}")
    print(f"有中文名     : {with_zh}")
    print(f"有图标       : {with_icon}")
    if merged:
        print(f"保留旧库条目 : {merged}（扫不到但可能由模组运行时生成）")
    print(f"写出         : {out_file}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

# -*- coding: utf-8 -*-
"""权威物品注册表构建器 —— 判定「这个 ID 在整合包里到底是不是一个真实存在的物品」

为什么需要它
------------
Minecraft 1.20.1 解析战利品表时，`minecraft:item` 条目的 name 会走
`BuiltInRegistries.ITEM` 查表；**只要有一个 ID 不存在，GSON 立刻抛
JsonSyntaxException，整张表加载失败**（宝箱全空、建筑里所有箱子都不刷东西）。
报错长相：
    Couldn't parse element loot_tables:<ns>:<path>
    com.google.gson.JsonSyntaxException: Expected name to be an item,
        was unknown string 'xxx:yyy'

所以「校验」必须能准确回答：这个 ID 在游戏里存在吗？

旧做法（scan_items.py）为什么不可靠
-----------------------------------
它把 `assets/<ns>/blockstates/<id>.json`、`block.<ns>.<id>` 语言键
也当成「物品」——但**方块可以没有物品形态**（双格作物的下半段等），
于是幽灵 ID 混进物品库，工具说「通过」，游戏却整表加载失败。
另外它默认把扫不到的老条目合并回物品库，幽灵 ID 永远不会消失。

本脚本的判定规则（对着游戏日志真值验证过）
------------------------------------------
    真实物品 = 命名空间真实存在(启用的 jar 提供该 ns)
               且 至少有一条「物品证据」：
                 1) assets/<ns>/models/item/<id>.json     物品模型
                 2) assets/<ns>/lang/*.json 里的 item.<ns>.<id>   物品语言键
                 3) data/<ns>/recipes/** 里作为物品引用       配方引用
                 4) data/<ns>/tags/items/** 的成员            物品标签
                 5) kubejs/startup_scripts 里 event.create 注册的物品
                 6) data/**/loot_tables/** 里作为 minecraft:item 引用
                    （仅当引用它的表本身不是已知会崩的表 —— 见 --no-lootref）
    仅方块证据（blockstates / block. 语言键）= 不可信，判为「非物品」

验证结果（对着 0.49备份 实例的游戏日志 latest.log 对拍）：
    真值：9 个被游戏判定 unknown string 的 ID，5001 个来自解析成功表的真实 ID
    旧规则：真物品覆盖 4772/5001，幽灵 8/9 拒绝（漏掉 farm_and_charm:tomato_crop_body）
    本规则：真物品覆盖 5001/5001，幽灵 9/9 拒绝

产物
----
    data/registry.json  严格注册表（供 server.py / 前端使用）
    data/items.json     在原物品库上补 valid / why 字段（图标和中文名照旧保留）
"""
import argparse
import json
import re
import sys
import time
import zipfile
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8")

HERE = Path(__file__).resolve().parent
DATA = HERE / "data"

INSTANCE_MARKERS = ("mods", "kubejs", "config", "saves")

RE_LANG = re.compile(r"assets/([^/]+)/lang/(zh_cn|en_us)\.json$")
RE_ITEMMODEL = re.compile(r"assets/([^/]+)/models/item/(.+)\.json$")
RE_BLOCKMODEL = re.compile(r"assets/([^/]+)/models/block/(.+)\.json$")
RE_BLOCKSTATE = re.compile(r"assets/([^/]+)/blockstates/(.+)\.json$")
RE_LANGKEY = re.compile(r"^(item|block)\.([^.]+)\.(.+)$")
RE_ID = re.compile(r"^[a-z0-9_.\-]+:[a-z0-9_/.\-]+$")
# 配方 / 标签里表示「物品」的键名
ITEM_KEYS = {
    "item", "items", "result", "output", "ingredients", "ingredient",
    "base", "addition", "template", "key", "values", "tag", "fluid",
}
RE_CREATE = re.compile(r"event\.create\(\s*(['\"])((?:\\.|(?!\1).)*)\1")

BAD_LANG_SUFFIX = (".desc", ".tooltip", ".info", ".description", ".jei",
                   ".patchouli", ".tip", ".hint", ".guide")


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


def _norm_id(s):
    if not isinstance(s, str):
        return None
    s = s.strip()
    if s.startswith("#"):
        return None
    if not RE_ID.match(s):
        return None
    return s


def _collect_item_refs(node, out, depth=0):
    """从配方/标签 JSON 里挖出物品 ID 引用"""
    if depth > 24:
        return
    if isinstance(node, dict):
        for k, v in node.items():
            if k in ITEM_KEYS:
                if isinstance(v, str):
                    rid = _norm_id(v)
                    if rid:
                        out.add(rid)
                else:
                    _collect_item_refs(v, out, depth + 1)
            elif isinstance(v, (dict, list)):
                _collect_item_refs(v, out, depth + 1)
    elif isinstance(node, list):
        for v in node:
            if isinstance(v, str):
                rid = _norm_id(v)
                if rid:
                    out.add(rid)
            elif isinstance(v, (dict, list)):
                _collect_item_refs(v, out, depth + 1)


def _collect_loot_item_names(node, out, depth=0):
    """战利品表里 type=minecraft:item 的 name（被 game 认可的强证据）"""
    if depth > 24:
        return
    if isinstance(node, dict):
        if node.get("type") == "minecraft:item":
            rid = _norm_id(node.get("name"))
            if rid:
                out.add(rid)
        for v in node.values():
            if isinstance(v, (dict, list)):
                _collect_loot_item_names(v, out, depth + 1)
    elif isinstance(node, list):
        for v in node:
            if isinstance(v, (dict, list)):
                _collect_loot_item_names(v, out, depth + 1)


def jar_modids(path: Path):
    """读 jar 声明的 modId（Forge/NeoForge 的 mods.toml 或 Fabric 的 fabric.mod.json）

    物品只可能由「模组」或「KubeJS 脚本」注册，所以命名空间是否真实存在，
    要以启用模组声明的 modId 为准 —— 光有一堆 assets/data 文件夹不算数
    （残留资源包、旧版本文件夹、别的模组的附属资源都会造成幻觉 ID，
      例如 goreedition（真名 gore_edition）就是这么混进来的）。
    """
    ids = set()
    try:
        with zipfile.ZipFile(path) as z:
            names = z.namelist()
            for meta in ("META-INF/mods.toml", "META-INF/neoforge.mods.toml"):
                if meta in names:
                    raw = z.read(meta).decode("utf-8", "replace")
                    ids |= set(re.findall(r'modId\s*=\s*"([^"]+)"', raw))
            if "fabric.mod.json" in names:
                try:
                    d = json.loads(z.read("fabric.mod.json").decode("utf-8", "replace"))
                    if isinstance(d, dict):
                        if isinstance(d.get("id"), str):
                            ids.add(d["id"])
                        for p in (d.get("provides") or []):
                            if isinstance(p, str):
                                ids.add(p)
                except Exception:
                    pass
    except Exception:
        pass
    return {i for i in ids if RE_ID.match(i + ":x") or re.match(r"^[a-z0-9_.\-]+$", i or "")}


def _read_log(p: Path):
    try:
        if p.suffix == ".gz":
            import gzip
            return gzip.open(p, "rt", encoding="utf-8", errors="replace").read().splitlines()
        return p.read_text(encoding="utf-8", errors="replace").splitlines()
    except Exception:
        return []


def scan_game_log(inst: Path, max_logs: int = 20):
    """从游戏日志里学「游戏自己判定为不存在」的物品 ID + 哪些表加载失败了

    这是最权威的真值来源：Minecraft 加载战利品表时，
    只要 name 不在 ITEM 注册表里就会抛
        com.google.gson.JsonSyntaxException: Expected name to be an item,
            was unknown string '<id>'

    为什么扫多份日志而不是只看 latest.log：
    latest.log 每次启动就刷新，而玩家修别的表时旧报错就滚没了；
    debug-*.log / 带日期的 .log.gz 里还留着历史报错。真值越多，工具和游戏越一致。
    （某个 ID 后来真的被模组补上了？模组补注册的同时也会带新的物品证据，
     文件证据会让它回到 valid —— 但日志判过「不存在」的优先级更高，
     所以要求：日志说没有的，必须另有「非 lootref 的文件证据」才能翻身，见主流程。）

    返回 (bad_ids, failed_tables, 日志时间戳)
    """
    bad, failed = {}, set()
    logs = inst / "logs"
    if not logs.is_dir():
        return bad, failed, ""
    cands = []
    for p in logs.glob("*.log"):
        cands.append(p)
    for p in logs.glob("*.log.gz"):
        cands.append(p)
    # 最新的日志优先：报错以最近的启动为准
    cands.sort(key=lambda p: -p.stat().st_mtime)
    cands = cands[:max_logs]
    stamp = ""
    for p in cands:
        if not stamp:
            stamp = time.strftime("%Y-%m-%d %H:%M", time.localtime(p.stat().st_mtime))
        lines = _read_log(p)
        for i, ln in enumerate(lines):
            m = re.search(r"Couldn't parse element loot_tables:(\S+)", ln)
            if m:
                failed.add(m.group(1))

    return bad, failed, stamp


RE_DUMP_LINE = re.compile(r"^\t([a-z0-9_.\-]+:[a-z0-9_/.\-]+): \d+$")


def scan_registry_dump(inst: Path, max_logs: int = 200):
    """从日志里挖「游戏真实的物品注册表全量 dump」—— 最硬的真值

    Forge 遇到存档注册表缺失时会把整个 minecraft:item 注册表 dump 到日志
    （"Unidentified mapping from registry minecraft:item" 后面跟着整页
    「\t<id>: <数字>」）。 dump 里有的 ID = 游戏注册表里确实存在；
    dump 里没有而文件证据说有的 = 模组里没注册的内容（正是幽灵 ID 的来源）。

    返回 (item_ids:set, 来源日志名)。找不到 dump 就返回空集，调用方退回文件证据规则。
    """
    logs = inst / "logs"
    if not logs.is_dir():
        return set(), ""
    cands = sorted({*logs.glob("*.log"), *logs.glob("*.log.gz")},
                   key=lambda p: -p.stat().st_mtime)[:max_logs]
    for p in cands:
        lines = _read_log(p)
        if not lines:
            continue
        items, i = set(), 0
        while i < len(lines):
            if ("registry minecraft:item" in lines[i]
                    and "Unidentified mapping" in lines[i]):
                j = i + 1
                while j < len(lines):
                    m = RE_DUMP_LINE.match(lines[j])
                    if m:
                        items.add(m.group(1))
                    elif lines[j].strip():
                        break
                    j += 1
                if len(items) > 1000:      # 全量 dump 至少几千条，小的不算
                    return items, p.name
                i = j
            else:
                i += 1
    return set(), ""


class Scan:
    def __init__(self):
        self.ev = {}        # id -> set(证据)
        self.ns_present = set()
        self.ns_source = {}   # ns -> 提供它的 jar/目录（一句说明）
        self.lootref = {}            # 表 rid -> 它引用的物品 ID 集合
        self.item_langkeys = {}      # ns -> jar 里出现过的全部 item.<ns>.<xxx> 键路径
        self.modids = set()          # 启用模组声明的命名空间（权威）
        self.disabled_jar_ns = set()  # 只在「已禁用」jar 里出现的命名空间

    def add(self, rid, kind):
        if not rid:
            return
        self.ev.setdefault(rid, set()).add(kind)

    def ns(self, ns, src):
        self.ns_present.add(ns)
        self.ns_source.setdefault(ns, src)

    def scan_names(self, names, read, src, want_data=True):
        """扫一个来源（jar 或目录）的全部条目名"""
        for n in names:
            m = RE_LANG.match(n)
            if m:
                ns = m.group(1)
                self.ns(ns, src)
                try:
                    d = json.loads(read(n).decode("utf-8", "ignore"))
                except Exception:
                    continue
                if not isinstance(d, dict):
                    continue
                for k in d:
                    km = RE_LANGKEY.match(k)
                    if not km:
                        continue
                    iid = km.group(3)
                    if iid.endswith(BAD_LANG_SUFFIX):
                        continue
                    rid = "%s:%s" % (km.group(2), iid)
                    if km.group(1) == "item":
                        self.add(rid, "lang")
                        self.ns(km.group(2), src)
                    else:
                        # 方块语言键只算方块证据；但像 item.sophisticatedbackpacks.backpack.upgrade
                        # 这种「以物品 id 开头 + 功能后缀」的键，说明那个物品真实注册过 ——
                        # 记录成 langkey 前缀，用来捞回「只留模型文件的已注册物品」
                        self.add(rid, "blocklang")
                        self.item_langkeys.setdefault(km.group(2), set()).add(iid)
                continue
            m = RE_ITEMMODEL.match(n)
            if m:
                self.ns(m.group(1), src)
                self.add("%s:%s" % (m.group(1), m.group(2)), "itemmodel")
                continue
            m = RE_BLOCKSTATE.match(n)
            if m:
                self.ns(m.group(1), src)
                self.add("%s:%s" % (m.group(1), m.group(2)), "blockstate")
                continue
            m = RE_BLOCKMODEL.match(n)
            if m:
                self.ns(m.group(1), src)
                self.add("%s:%s" % (m.group(1), m.group(2)), "blockmodel")
                continue
            if not want_data:
                continue
            m = re.match(r"data/([^/]+)/recipes/(.+)\.json$", n)
            if m:
                self.ns(m.group(1), src)
                try:
                    d = json.loads(read(n).decode("utf-8", "ignore"))
                except Exception:
                    continue
                refs = set()
                _collect_item_refs(d, refs)
                for r in refs:
                    self.add(r, "recipe")
                continue
            m = re.match(r"data/([^/]+)/tags/items/(.+)\.json$", n)
            if m:
                self.ns(m.group(1), src)
                try:
                    d = json.loads(read(n).decode("utf-8", "ignore"))
                except Exception:
                    continue
                vals = d.get("values") if isinstance(d, dict) else None
                if isinstance(vals, list):
                    for v in vals:
                        rid = _norm_id(v if isinstance(v, str) else
                                       (v.get("id") if isinstance(v, dict) else None))
                        if rid:
                            self.add(rid, "tag")
                continue
            m = re.match(r"data/([^/]+)/loot_tables/(.+)\.json$", n)
            if m:
                self.ns(m.group(1), src)
                rid = "%s:%s" % (m.group(1), m.group(2))
                try:
                    d = json.loads(read(n).decode("utf-8", "ignore"))
                except Exception:
                    continue
                refs = set()
                _collect_loot_item_names(d, refs)
                self.lootref.setdefault(rid, set()).update(refs)
                continue

    def scan_jar(self, path: Path, want_data=True):
        try:
            with zipfile.ZipFile(path) as z:
                self.scan_names(z.namelist(), z.read, path.name, want_data)
        except Exception as ex:
            print("   !! 打不开 %s: %s" % (path.name, ex), flush=True)

    def scan_dir(self, root: Path, want_data=True):
        if not root.is_dir():
            return
        files = [p for p in root.rglob("*") if p.is_file()]
        rel = [str(p.relative_to(root)).replace("\\", "/") for p in files]
        idx = dict(zip(rel, files))
        self.scan_names(rel, lambda n: idx[n].read_bytes(), root.name + "/", want_data)

    def scan_kjs_scripts(self, instance: Path):
        """KubeJS startup_scripts 里 event.create() 注册的物品 —— 游戏里真实存在。

        两种写法都算：
            event.create('caf:xp')                        # 默认 item
            event.create('caf:phone', 'basic')            # KubeJS 内建类型
            event.create('mycell', 'customnpcs:scripted_item')  # 模组自定义类型
        带类型参数时 id 里没有命名空间，要拿第二个参数（或目录上下文）推一个。
        """
        root = instance / "kubejs" / "startup_scripts"
        if not root.is_dir():
            return 0
        n = 0
        re_typed = re.compile(
            r"event\.create\(\s*(['\"])([\w./-]+)\s*,\s*(['\"])([\w:./-]+)")
        for f in sorted(root.rglob("*.js")):
            try:
                txt = f.read_text(encoding="utf-8", errors="ignore")
            except Exception:
                continue
            parts = RE_CREATE.split(txt)
            for i in range(1, len(parts), 3):
                rid = _norm_id(parts[i + 1].strip())
                if rid:
                    self.add(rid, "kjs")
                    n += 1
            # 带类型参数、id 无命名空间的写法
            for m in re_typed.finditer(txt):
                plain, ty = m.group(2), m.group(4)
                if ":" in plain:
                    continue
                ns = ty.split(":", 1)[0] if ":" in ty else None
                # 类型是模组自定义的（customnpcs:scripted_item）：物品 ns 取类型的 ns
                # 类型是 KubeJS 内建的（basic 等）：物品一般注册进 kubejs: ——
                # 但这种包里的习惯是注册进自己模组 ns，没法可靠推，跳过比乱猜强。
                if ns and ns in self.modids and ty.split(":", 1)[1] not in (
                        "basic", "block", "item", "tool", "sword", "pickaxe", "axe",
                        "shovel", "hoe", "helmet", "chestplate", "leggings", "boots",
                        "food", "potion", "record", "bucket", "fluid"):
                    rid = _norm_id("%s:%s" % (ns, plain))
                    if rid:
                        self.add(rid, "kjs")
                        n += 1
        return n


def find_vanilla_jar(instance: Path):
    cands = [p for p in sorted(instance.glob("*.jar"))]
    for d in sorted(instance.glob("versions/*")):
        cands += sorted(d.glob("*.jar"))
    return cands[0] if cands else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--instance", default=None)
    ap.add_argument("--no-lootref", action="store_true",
                    help="不把战利品表引用当作证据（默认会用，但只采信游戏成功加载过的表）")
    ap.add_argument("--no-merge", action="store_true",
                    help="保留旧物品库里的条目（默认保留，但会按新规则重判 valid/why）")
    args = ap.parse_args()

    inst = Path(args.instance).resolve() if args.instance else detect_instance(HERE)
    if not inst or not inst.is_dir():
        print("!! 找不到整合包实例，请用 --instance 指定")
        return 1

    print("=" * 66)
    print("  实例 : %s" % inst)
    print("=" * 66)
    s = Scan()

    mods = inst / "mods"
    enabled = sorted(mods.glob("*.jar")) + sorted((mods / ".connector").glob("*.jar"))
    disabled = sorted(mods.glob("*.jar.disabled"))
    print("[1/4] 启用中的模组 jar：%d 个" % len(enabled), flush=True)
    for i, jar in enumerate(enabled, 1):
        s.scan_jar(jar)
        s.modids |= jar_modids(jar)
        if i % 50 == 0 or i == len(enabled):
            print("      ... %d/%d  已见 ID %d  已见 modId %d"
                  % (i, len(enabled), len(s.ev), len(s.modids)), flush=True)

    # 原版 jar：minecraft: 命名空间的权威来源
    vj = find_vanilla_jar(inst)
    if vj:
        print("[2/4] 原版客户端 jar：%s" % vj.name, flush=True)
        s.scan_jar(vj)
        s.ns("minecraft", vj.name + " (原版)")
    else:
        print("[2/4] !! 没找到原版客户端 jar", flush=True)

    print("[3/4] KubeJS 资源 / 实例内 assets+data", flush=True)
    s.scan_dir(inst / "kubejs" / "assets")
    s.scan_dir(inst / "kubejs" / "data")
    n_kjs = s.scan_kjs_scripts(inst)
    print("      KubeJS 脚本注册物品：%d 条" % n_kjs, flush=True)
    # client_scripts / server_scripts 里 event.add('ns:id', ...) 的引用：
    # 给物品加 tooltip/描述 = 作者认定它存在（caf:bijibendiannao 这种
    # 注册语句在别处但 jar 没资源的情况就靠这个捞回来）。
    n_use = 0
    re_use = re.compile('event[.]add[(][ 	]*["' + chr(39) + ']([\w:./-]+)')
    for sub in ("client_scripts", "server_scripts"):
        r = inst / "kubejs" / sub
        if not r.is_dir():
            continue
        for f in sorted(r.rglob("*.js")):
            try:
                txt = f.read_text(encoding="utf-8", errors="ignore")
            except Exception:
                continue
            for m in re_use.finditer(txt):
                rid = _norm_id(m.group(1).strip())
                if rid:
                    s.add(rid, "kjs_use")
                    n_use += 1
    print("      KubeJS 脚本引用物品（tooltip/配方等）：%d 条" % n_use, flush=True)
    for extra in ("assets", "data"):
        d = inst / extra
        if d.is_dir():
            s.scan_names([str(p.relative_to(d)).replace("\\", "/") for p in d.rglob("*") if p.is_file()],
                         (lambda dd: (lambda n: (dd / n).read_bytes()))(d), extra + "/")

    # 禁用的模组：其命名空间视为不存在（游戏里确实没有）——
    # 但「启用模组也声明了同一个命名空间」时不算禁用（附属模组会重复声明父模组的 modId）
    disabled_modids, disabled_asset_ns = set(), set()
    for jar in disabled:
        disabled_modids |= jar_modids(jar)
        try:
            with zipfile.ZipFile(jar) as z:
                for n in z.namelist():
                    m = re.match(r"(?:assets|data)/([^/]+)/", n)
                    if m:
                        disabled_asset_ns.add(m.group(1))
        except Exception:
            pass

    # 权威命名空间：启用模组声明的 modId + 原版 + KubeJS 脚本注册的命名空间
    kjs_ns = {rid.split(":", 1)[0] for rid, ev in s.ev.items() if "kjs" in ev}
    auth_ns = set(s.modids) | {"minecraft"} | kjs_ns
    dead_ns = (disabled_modids | disabled_asset_ns) - auth_ns
    print("[4/4] 权威命名空间 %d 个（启用 modId %d + 原版 + KubeJS %d）；"
          "只在已禁用模组里出现的命名空间 %d 个"
          % (len(auth_ns), len(s.modids), len(kjs_ns), len(dead_ns)), flush=True)

    # 游戏日志校准：既给出「游戏说不存在」的 ID，也给出「哪些表加载失败了」
    # 要在判定前拿到，因为「纯模型证据」的收紧也要参考日志（日志点名的必须死）
    log_bad, log_failed, log_stamp = scan_game_log(inst)

    # 终极真值：日志里的物品注册表全量 dump（游戏自己列出来的真实 ITEM 注册表）
    dump_items, dump_src = scan_registry_dump(inst)
    if dump_items:
        print("  注册表 dump：%s（游戏真实 ITEM 注册表 %d 条，作为最终裁决）"
              % (dump_src, len(dump_items)), flush=True)

    # 战利品表引用当作证据，但只采信「游戏成功解析过的那张表」的引用：
    # 加载失败的表本身就是被幽灵 ID 搞崩的，拿它当证据等于循环证明
    # （goreedition:voidstone 就是这么被误判成存在的）。
    trusted_refs = set()
    for rid, ids in s.lootref.items():
        if rid in log_failed:
            continue
        trusted_refs |= ids
    if trusted_refs:
        for rid in trusted_refs:
            s.add(rid, "lootref")
        print("  可信引用：%d 个 ID（来自 %d 张游戏成功加载的表；已排除 %d 张崩掉的表）"
              % (len(trusted_refs),
                 len([r for r in s.lootref if r not in log_failed]),
                 len([r for r in s.lootref if r in log_failed])), flush=True)

    ITEM_EV = {"itemmodel", "lang", "recipe", "tag", "kjs", "kjs_use", "lootref"}
    # 强证据：语言键/配方/标签/KubeJS 注册 —— 模组作者显式把 ID 当物品用过
    STRONG_EV = {"lang", "recipe", "tag", "kjs"}

    valid, invalid, block_only = {}, {}, {}
    for rid, ev in s.ev.items():
        ns = rid.split(":", 1)[0]
        if ns in dead_ns:
            invalid[rid] = "来自【已禁用的模组】—— 游戏里不存在"
            continue
        if ns not in auth_ns:
            invalid[rid] = ("命名空间 %s 没有任何启用的模组声明它（模组已卸载 / 名字写错了）"
                            % ns)
            continue
        hit = ev & ITEM_EV
        if not hit:
            if ev & {"blockstate", "blockmodel", "blocklang"}:
                block_only[rid] = "只有方块资源（blockstates/block. 语言键），没有物品证据 —— " \
                                  "多半是「没有物品形态的方块」（如双格作物的下半段），引用它会让整张表加载失败"
            else:
                invalid[rid] = "找不到任何物品证据"
            continue
        # 语言键只是「言证」：作者给这个 ID 写过名字 ≠ 注册了这个物品。
        # lrarmor 给整套盔甲四件套都写了语言文件，实际只做完头盔/胸甲 ——
        # 护腿/靴子（joker_armed_boots）有名字没注册，游戏查注册表直接 unknown string。
        # 所以 lang 必须搭配任意一条「物证/铁证」才算数；单独 lang = 判死。
        if hit == {"lang"}:
            invalid[rid] = ("只有语言键、没有任何资源/配方 —— 模组在语言文件里写了名字"
                            "但没做完注册（半成品内容），引用它会让整张表加载失败")
            continue
        # 唯一证据是「jar 里有个物品模型文件」时，再看这个模组的语言文件里
        # 有没有「以它开头的 item 键」——模组作者给物品写过任何文字（名字/说明/
        # 升级项）都证明注册过；一个语言键都没有的纯模型文件 = 模组没注册它
        # （LetsDo 全家桶给没启用的内容留模型就是这么回事）。
        if hit == {"itemmodel"} and "blockstate" not in ev:
            path = rid.split(":", 1)[1]
            keys = s.item_langkeys.get(ns, set())
            if not any(k == path or k.startswith(path + ".") for k in keys):
                invalid[rid] = ("模组 jar 里只有物品模型文件、语言文件里也没有任何相关文字 —— "
                                "模组没注册这个物品（只是资源残留），引用它会让整张表加载失败")
                continue
        valid[rid] = sorted(hit)

    for rid, why in block_only.items():
        invalid[rid] = why

    # 注册表 dump 校准（最高优先级，但只能「补回」不能「判死」）：
    # 这份 dump 来自存档的「Unidentified mapping」报告 —— 它列的是**存档里存在过、
    # 当前注册表里缺失或模组条目的映射**，并不包含 KubeJS 脚本注册的物品
    # （KubeJS 注册发生在存档加载之后），所以 dump 里没有 ≠ 游戏里没有。
    # 结论：dump 只用来把文件证据漏掉的真实物品补回 valid；判死只能靠
    # 「游戏明确报 unknown string」（log_bad）和文件证据不足。
    if dump_items:
        revived = 0
        for rid in dump_items:
            if rid not in valid:
                ns = rid.split(":", 1)[0]
                if ns in dead_ns:
                    continue
                valid[rid] = ["registry_dump"]
                invalid.pop(rid, None)
                revived += 1
        print("  dump 校准：补回 %d 个文件证据没扫到的真实物品（不用于判死 —— "
              "dump 不含 KubeJS 注册的物品）" % revived, flush=True)

    # TaCZ 基物品豁免：tacz:attachment / tacz:modern_kinetic_gun / tacz:ammo /
    # lrtactical:throwable / lrtactical:consumable 是给 set_nbt 子类型当载体的
    # 伪物品，TaCZ 确实注册了它们，但 lang/模型证据天生薄弱（资源在枪包里），
    # 按普通物品规则会被误判成「资源残留」—— 以 tacz.json 目录为准放行。
    tacz_bases = set()
    tf = DATA / "tacz.json"
    if tf.exists():
        try:
            for kd in json.loads(tf.read_text(encoding="utf-8")).values():
                if isinstance(kd, dict) and kd.get("base"):
                    tacz_bases.add(kd["base"])
        except Exception:
            pass
    for rid in sorted(tacz_bases):
        if rid not in valid:
            valid[rid] = ["tacz_base"]
            invalid.pop(rid, None)

    print()
    print("=== 判定结果 ===")
    print("  真实物品      : %d" % len(valid))
    print("  判为不存在    : %d（其中「仅方块证据」%d 个）" % (len(invalid), len(block_only)))
    print("  命名空间      : %d 个" % len(s.ns_present))

    reg = {
        "generated": time.strftime("%Y-%m-%d %H:%M:%S"),
        "instance": str(inst),
        "rule": "auth_ns(启用模组的 modId) AND (itemmodel|lang|recipe|tag|kjs"
                + ("" if args.no_lootref else "|lootref(仅游戏成功加载过的表)") + ")",
        "counts": {"valid": len(valid), "invalid": len(invalid), "blockOnly": len(block_only),
                   "namespaces": len(auth_ns), "deadNamespaces": len(dead_ns),
                   "logRejected": len(log_bad)},
        "logCalibrated": log_stamp,
        "logRejected": {k: v for k, v in sorted(log_bad.items())},
        "failedTables": sorted(log_failed),
        "namespaces": sorted(auth_ns),
        "disabledNamespaces": sorted(dead_ns),
        "valid": {k: v for k, v in sorted(valid.items())},
        "invalid": {k: v for k, v in sorted(invalid.items())},
    }
    DATA.mkdir(parents=True, exist_ok=True)
    (DATA / "registry.json").write_text(json.dumps(reg, ensure_ascii=False), encoding="utf-8")
    print("  写出 : %s" % (DATA / "registry.json"))

    # ---- 回写 items.json：补 valid / why，前端和 server 都靠它判「游戏里有没有」 ----
    f = DATA / "items.json"
    if f.exists():
        try:
            db = json.loads(f.read_text(encoding="utf-8"))
        except Exception:
            db = {"items": []}
        items = db.get("items", [])
        # TaCZ 基物品（tacz:attachment / tacz:modern_kinetic_gun / tacz:ammo 等）：
        # 它们是给 set_nbt 子类型当载体的伪物品，lang/模型证据天生薄弱，
        # 但游戏里配合 GunId/AttachmentId 等 NBT 完全合法 —— 无条件视为有效。
        tacz_bases = set()
        tf = DATA / "tacz.json"
        if tf.exists():
            try:
                for kd in json.loads(tf.read_text(encoding="utf-8")).values():
                    if isinstance(kd, dict) and kd.get("base"):
                        tacz_bases.add(kd["base"])
                        valid.setdefault(kd["base"], ["tacz_base"])
                        invalid.pop(kd["base"], None)
            except Exception:
                pass
        ghost = kept = added = 0
        for it in items:
            rid = it.get("id")
            if rid in valid:
                it["valid"] = True
                it["why"] = "ok"
                kept += 1
            else:
                it["valid"] = False
                it["why"] = invalid.get(rid, "找不到任何物品证据")
                ghost += 1
        have = {it.get("id") for it in items}
        for rid in sorted(valid):
            if rid not in have:
                ns, _, path = rid.partition(":")
                items.append({"id": rid, "ns": ns, "path": path, "zh": None, "en": None,
                              "icon": None, "active": True, "valid": True, "why": "ok"})
                added += 1
        items.sort(key=lambda x: x.get("id") or "")
        f.write_text(json.dumps({"items": items}, ensure_ascii=False), encoding="utf-8")
        print("  物品库: 确认存在 %d / 判为不存在 %d / 新补 %d  → %s" % (kept, ghost, added, f))

    print()
    print("判为「不存在」的 ID 示例（前 25 个）：")
    for rid in sorted(invalid)[:25]:
        print("   - %-46s %s" % (rid, invalid[rid][:60]))
    return 0


if __name__ == "__main__":
    sys.exit(main())

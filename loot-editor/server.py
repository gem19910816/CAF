#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
CAF 战利品池可视化编辑器 —— 本地服务（零第三方依赖，仅标准库）

用法:
    python server.py                    # 默认编辑 chaoszpack_lc_loot
    python server.py --port 8899
    python server.py --root "D:/some/other/loot_tables"
"""
import argparse
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import webbrowser
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from pathlib import Path
from urllib.parse import urlparse, parse_qs, unquote

# 冻结打包（PyInstaller exe）时 __file__ 指向临时解压目录，
# 资源（web/icons/data/脚本）放在 exe 旁边 —— 用 sys.executable 定位。
if getattr(sys, "frozen", False):
    HERE = Path(sys.executable).resolve().parent
else:
    HERE = Path(__file__).resolve().parent
WORK = HERE.parent
DEFAULT_ROOT = WORK / "kubejs" / "data" / "chaoszpack_lc_loot" / "loot_tables"
WEB = HERE / "web"
DATA = HERE / "data"
ICONS = HERE / "icons"
TRASH = HERE / "_trash"

ROOT = DEFAULT_ROOT
PORT = 8787

# ==========================================================================
#  校验器 —— 依据 Minecraft 1.20.1 的 loot table 规范
# ==========================================================================

ENTRY_TYPES = {
    "minecraft:item", "minecraft:tag", "minecraft:loot_table", "minecraft:dynamic",
    "minecraft:group", "minecraft:alternatives", "minecraft:sequence", "minecraft:empty",
}
ENTRY_TYPES_NEED_NAME = {"minecraft:item", "minecraft:tag", "minecraft:dynamic"}
ENTRY_TYPES_NEED_CHILDREN = {"minecraft:group", "minecraft:alternatives", "minecraft:sequence"}

FUNCTION_TYPES = {
    "minecraft:apply_bonus", "minecraft:copy_name", "minecraft:copy_nbt",
    "minecraft:copy_state", "minecraft:enchant_randomly", "minecraft:enchant_with_levels",
    "minecraft:exploration_map", "minecraft:explosion_decay", "minecraft:furnace_smelt",
    "minecraft:limit_count", "minecraft:looting_enchant", "minecraft:set_attributes",
    "minecraft:set_banner_pattern", "minecraft:set_book_cover", "minecraft:set_contents",
    "minecraft:set_count", "minecraft:set_damage", "minecraft:set_enchantments",
    "minecraft:set_firework_explosion", "minecraft:set_fireworks",
    "minecraft:set_instrument", "minecraft:set_loot_table", "minecraft:set_lore",
    "minecraft:set_name", "minecraft:set_nbt", "minecraft:set_potion",
    "minecraft:set_stew_effect", "minecraft:set_written_book_pages", "minecraft:toggle_tooltips",
}

CONDITION_TYPES = {
    "minecraft:alternative", "minecraft:block_state_property", "minecraft:damage_source_properties",
    "minecraft:entity_properties", "minecraft:entity_scores", "minecraft:inverted",
    "minecraft:killed_by_player", "minecraft:location_check", "minecraft:match_tool",
    "minecraft:random_chance", "minecraft:random_chance_with_looting", "minecraft:reference",
    "minecraft:survives_explosion", "minecraft:table_bonus", "minecraft:time_check",
    "minecraft:value_check", "minecraft:weather_check",
}

TABLE_TYPES = {
    "minecraft:generic", "minecraft:chest", "minecraft:block", "minecraft:entity",
    "minecraft:advancement_reward", "minecraft:advancement_entity", "minecraft:gift",
    "minecraft:barter", "minecraft:fishing", "minecraft:archaeology", "minecraft:selector",
    "minecraft:command", "minecraft:empty",
}


def _norm(rid):
    """把 'set_count' 规范成 'minecraft:set_count'"""
    if not isinstance(rid, str):
        return rid
    return rid if ":" in rid else "minecraft:" + rid


def _is_number(v):
    return isinstance(v, (int, float)) and not isinstance(v, bool)


# 资源位置（ResourceLocation）合法字符：namespace = [a-z0-9_.-]+，path = [a-z0-9_/.-]+
_RID_NS = re.compile(r"^[a-z0-9_.-]+$")
_RID_PATH = re.compile(r"^[a-z0-9_/.-]+$")


def rid_problem(v):
    """检查一个资源位置字符串是否会让 Minecraft 解析失败。
    返回 None（没问题）/ (级别, 说明)，级别 'error' 或 'warn'。
    大写、空格、中文等字符会让 ResourceLocation 解析抛异常 → 整张表加载失败。"""
    if not isinstance(v, str) or not v:
        return ("error", "必须是字符串")
    s = v[1:] if v.startswith("#") else v          # tag 条目以 # 开头
    if ":" in s:
        ns, _, path = s.partition(":")
        if not ns:
            return ("error", f"命名空间为空（{v}）")
        if not path:
            return ("error", f"冒号后面是空的（{v}）")
        if not _RID_NS.match(ns):
            return ("error", f"命名空间含非法字符（{ns}）—— 只能用小写字母、数字、_ . -")
        if not _RID_PATH.match(path):
            return ("error", f"路径含非法字符（{path}）—— 只能用小写字母、数字、_ / . -")
        return None
    # 没有命名空间：Minecraft 一般会补 minecraft:，但不保证，提示补上
    if not _RID_PATH.match(s):
        return ("error", f"含非法字符（{v}）—— 只能用小写字母、数字、_ / . -")
    return ("warn", f"{v} 没写命名空间，建议写成 minecraft:{v}")


# --------------------------------------------------------------------------
#  SNBT 校验 —— set_nbt 的 tag 由 Minecraft 的 TagParser 严格解析，
#  写错一个逗号/冒号/引号，游戏加载这张表时直接抛异常（整表失效）。
#  这里按 1.20.1 TagParser 的真实语法做词法检查，不做「大概齐」判断。
# --------------------------------------------------------------------------

_SNBT_BARE_KEY = re.compile(r"[A-Za-z0-9._+-]+$")
_SNBT_NUM = re.compile(r"[-+]?(?:[0-9]+\.?[0-9]*|\.[0-9]+)(?:[eE][-+]?[0-9]+)?[bBsSlLfFdD]?$")
# 官方 TagParser.isAllowedInUnquotedString：只有这些字符能出现在不带引号的词里
_SNBT_BARE_CHARS = set("abcdefghijklmnopqrstuvwxyz"
                       "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
                       "0123456789_-+.")


class _Snbt:
    def __init__(self, s):
        self.s = s
        self.i = 0

    def err(self, msg):
        raise ValueError("%s（第 %d 个字符附近：%s）" % (msg, self.i + 1, self.s[max(0, self.i - 12):self.i + 12]))

    def ws(self):
        while self.i < len(self.s) and self.s[self.i] in " \t\r\n":
            self.i += 1

    def peek(self):
        self.ws()
        return self.s[self.i] if self.i < len(self.s) else ""

    def value(self, depth=0):
        if depth > 32:
            self.err("嵌套过深")
        c = self.peek()
        if c == "":
            self.err("内容意外结束")
        if c == "{":
            return self.compound(depth)
        if c == "[":
            return self.list_(depth)
        if c == '"' or c == "'":
            return self.quoted()
        tok = self.bare()
        if tok == "":
            self.err("缺少值")
        if tok in ("true", "false") or _SNBT_NUM.match(tok):
            return
        # 官方 TagParser 的兜底：裸词不是数字/布尔时，整个词就是字符串
        # （所以 {Potion:water} 是合法的，别误报）
        return

    def delim_hint(self, want):
        c = self.s[self.i] if self.i < len(self.s) else ""
        if c and c not in " \t\r\n":
            self.err("期望 %s，但这里是 %r —— 不带引号的词只能含字母/数字/._-+，"
                     "含其它字符（: / # 空格 中文 等）必须写成字符串加引号" % (want, c))
        self.err("期望 " + want)

    def compound(self, depth):
        self.i += 1                       # {
        self.ws()
        if self.peek() == "}":
            self.i += 1
            return
        while True:
            self.ws()
            c = self.peek()
            if c == "":
                self.err("复合标签没有闭合的 }")
            if c == '"' or c == "'":
                self.quoted()
            else:
                k = self.bare()
                if k == "" or not _SNBT_BARE_KEY.match(k):
                    self.err("非法的键名 %r（含空格/特殊字符的键必须加引号）" % k)
            if self.peek() != ":":
                self.err("键后面缺少冒号")
            self.i += 1
            self.value(depth + 1)
            self.ws()
            c = self.peek()
            if c == ",":
                self.i += 1
                self.ws()
                if self.peek() == "}":
                    self.err("多了一个逗号（} 前面不能有逗号）")
                continue
            if c == "}":
                self.i += 1
                return
            self.delim_hint(", 或 }")

    def list_(self, depth):
        self.i += 1                       # [
        self.ws()
        # 类型前缀数组 [I;1,2] / [L;..] / [B;..]
        if self.i + 1 < len(self.s) and self.s[self.i] in "BbLlIi" and self.s[self.i + 1] == ";":
            tag = self.s[self.i].upper()
            self.i += 2
            first = True
            while True:
                self.ws()
                if self.peek() == "]":
                    self.i += 1
                    return
                tok = self.bare()
                if not re.match(r"[-+]?[0-9]+$", tok):
                    self.err("[%s;...] 里只能是整数（当前 %r）" % (tag, tok))
                first = False
                self.ws()
                c = self.peek()
                if c == ",":
                    self.i += 1
                    continue
                if c == "]":
                    self.i += 1
                    return
                self.delim_hint(", 或 ]")
        if self.peek() == "]":
            self.i += 1
            return
        while True:
            self.value(depth + 1)
            self.ws()
            c = self.peek()
            if c == ",":
                self.i += 1
                self.ws()
                if self.peek() == "]":
                    self.err("多了一个逗号（] 前面不能有逗号）")
                continue
            if c == "]":
                self.i += 1
                return
            self.delim_hint(", 或 ]")

    def quoted(self):
        q = self.s[self.i]
        self.i += 1
        buf = []
        while True:
            if self.i >= len(self.s):
                self.err("字符串没有闭合的 %s" % q)
            c = self.s[self.i]
            if c == "\\":
                self.i += 1
                if self.i >= len(self.s):
                    self.err("反斜杠后面没有内容")
                buf.append(self.s[self.i])      # 1.20.1 的 TagParser：\x 就是字面 x
                self.i += 1
                continue
            if c == q:
                self.i += 1
                return "".join(buf)
            buf.append(c)
            self.i += 1

    def bare(self):
        self.ws()
        st = self.i
        while self.i < len(self.s) and self.s[self.i] in _SNBT_BARE_CHARS:
            self.i += 1
        return self.s[st:self.i]

    def parse(self):
        self.value()
        self.ws()
        if self.i != len(self.s):
            self.err("末尾有多余内容")


def snbt_error(tag):
    """返回错误说明；None 表示这个 SNBT 能被游戏解析。"""
    if not isinstance(tag, str):
        return "必须是字符串"
    t = tag.strip()
    if not t:
        return "是空的"
    try:
        _Snbt(t).parse()
    except ValueError as e:
        return str(e)
    except RecursionError:
        return "嵌套过深"
    return None


def check_rolls(v, where, errors, warnings, allow_empty=False):
    """校验 rolls / bonus_rolls 结构"""
    if v is None:
        if allow_empty:
            return
        errors.append(f"{where}: 缺少 rolls 字段")
        return
    if isinstance(v, bool):
        errors.append(f"{where}: rolls 不能是布尔值")
    elif isinstance(v, int):
        if v < 0:
            errors.append(f"{where}: rolls 不能为负数（当前 {v}）")
        elif v == 0 and not allow_empty:
            # 1.20.1 里 rolls=0 完全合法（LootPool 只要求 >=0），但这样的池永远不产出
            warnings.append(f"{where}: rolls 为 0 —— 这个池永远不会产出任何物品"
                            f"（如果是有意做的「占位池」可以忽略）")
    elif isinstance(v, float):
        errors.append(f"{where}: rolls 必须是整数（当前 {v}）")
    elif isinstance(v, dict):
        t = v.get("type")
        if t is not None and _norm(t) not in ("minecraft:uniform", "minecraft:binomial",
                                              "minecraft:constant", "minecraft:score"):
            warnings.append(f"{where}: 未知的 rolls 类型 {t}")
        if _norm(t) == "minecraft:binomial":
            if not _is_number(v.get("n")):
                errors.append(f"{where}: binomial 需要数字字段 n")
            if not _is_number(v.get("p")):
                errors.append(f"{where}: binomial 需要数字字段 p")
        else:
            mn, mx = v.get("min"), v.get("max")
            if mn is None and mx is None and t is not None:
                errors.append(f"{where}: rolls 不支持 type={t} —— 1.20.1 只认整数、"
                              f"{{min,max}}（均匀分布）或 {{type:binomial,n,p}}")
            else:
                if not _is_number(mn):
                    errors.append(f"{where}: rolls 缺少数字字段 min")
                if not _is_number(mx):
                    errors.append(f"{where}: rolls 缺少数字字段 max")
                if _is_number(mn) and _is_number(mx) and mn > mx:
                    errors.append(f"{where}: rolls 的 min({mn}) 大于 max({mx})")
    else:
        errors.append(f"{where}: rolls 类型非法（应为整数或对象）")


def check_conditions(conds, where, errors, warnings, depth=0):
    if conds is None:
        return
    if not isinstance(conds, list):
        errors.append(f"{where}: conditions 必须是数组")
        return
    if depth > 8:
        errors.append(f"{where}: conditions 嵌套过深")
        return
    for i, c in enumerate(conds):
        p = f"{where}.conditions[{i}]"
        if not isinstance(c, dict):
            errors.append(f"{p}: 必须是对象")
            continue
        ct = c.get("condition")
        if not ct:
            errors.append(f"{p}: 缺少 condition 字段")
            continue
        if not isinstance(ct, str):
            errors.append(f"{p}: condition 必须是字符串")
            continue
        rp = rid_problem(ct)
        if rp and rp[0] == "error":
            errors.append(f"{p}: condition 名非法 —— {rp[1]}")
        nct = _norm(ct)
        if nct not in CONDITION_TYPES:
            warnings.append(f"{p}: 未知的条件类型 {ct}（游戏可能整张表都加载不了）")
        if nct in ("minecraft:random_chance", "minecraft:random_chance_with_looting"):
            ch = c.get("chance")
            if not _is_number(ch):
                errors.append(f"{p}: {ct.split(':')[1]} 需要数字字段 chance")
            elif not (0 <= ch <= 1):
                errors.append(f"{p}: chance 必须在 0~1 之间（当前 {ch}）")
            if nct == "minecraft:random_chance_with_looting" and not _is_number(c.get("looting_multiplier")):
                warnings.append(f"{p}: random_chance_with_looting 通常还要 looting_multiplier（默认 0）")
        # 嵌套条件：把里面的条件也校验一遍
        if nct == "minecraft:inverted":
            term = c.get("term")
            if not isinstance(term, dict):
                errors.append(f"{p}: inverted 需要 term 对象")
            else:
                check_conditions([term], p + ".term", errors, warnings, depth + 1)
        elif nct in ("minecraft:alternative", "minecraft:any_of", "minecraft:all_of"):
            terms = c.get("terms")
            if not isinstance(terms, list):
                errors.append(f"{p}: {ct.split(':')[1]} 需要 terms 数组")
            else:
                check_conditions(terms, p + ".terms", errors, warnings, depth + 1)


_TACZ = None
TACZ_NBT_KEYS = ("GunId", "AttachmentId", "AmmoId", "ThrowableId", "ConsumableId")


def tacz_catalog():
    """TaCZ 系子类型目录（惰性载入）"""
    global _TACZ
    if _TACZ is None:
        _TACZ = {}
        f = DATA / "tacz.json"
        if f.exists():
            try:
                raw = json.loads(f.read_text(encoding="utf-8"))
                for nbt, d in raw.items():
                    _TACZ[nbt] = {it["id"] for it in d.get("items", [])}
            except Exception:
                _TACZ = {}
    return _TACZ


def check_tacz_nbt(where, tag, warnings):
    """检查 set_nbt 里的 TaCZ 子类型是否真实存在（枪包可能已卸载）"""
    cat = tacz_catalog()
    if not cat:
        return
    for key in TACZ_NBT_KEYS:
        m = re.search(key + r':"([^"]+)"', tag)
        if not m:
            continue
        sid = m.group(1)
        known = cat.get(key)
        if known and sid not in known:
            warnings.append(f"{where}: {key} 引用的 {sid} 不在已安装的枪包中"
                            f"（枪包可能已卸载，游戏里会抽到一个空白物品）")


def check_functions(funcs, where, errors, warnings):
    if funcs is None:
        return
    if not isinstance(funcs, list):
        errors.append(f"{where}: functions 必须是数组")
        return
    for i, f in enumerate(funcs):
        p = f"{where}.functions[{i}]"
        if not isinstance(f, dict):
            errors.append(f"{p}: 必须是对象")
            continue
        ft = f.get("function")
        if not ft:
            errors.append(f"{p}: 缺少 function 字段")
            continue
        if not isinstance(ft, str):
            errors.append(f"{p}: function 必须是字符串")
            continue
        if ft != _norm(ft):
            warnings.append(f"{p}: function 缺少命名空间，建议写作 {_norm(ft)}")
        nft = _norm(ft)
        rp = rid_problem(ft)
        if rp and rp[0] == "error":
            errors.append(f"{p}: function 名非法 —— {rp[1]}")
        if nft not in FUNCTION_TYPES:
            warnings.append(f"{p}: 未知的函数类型 {ft}（游戏可能整张表都加载不了）")
        if nft == "minecraft:set_count":
            if "count" not in f:
                errors.append(f"{p}: set_count 缺少 count 字段")
            else:
                check_rolls(f["count"], p + ".count", errors, warnings)
        if nft == "minecraft:set_nbt":
            if not isinstance(f.get("tag"), str):
                errors.append(f"{p}: set_nbt 需要字符串字段 tag")
            else:
                tag = f["tag"]
                bad = snbt_error(tag)
                if bad:
                    errors.append(f"{p}: set_nbt 的 tag 不是合法 SNBT —— {bad}。"
                                  f"游戏解析失败会让整张表加载不了")
                else:
                    if not (tag.strip().startswith("{") and tag.strip().endswith("}")):
                        errors.append(f"{p}: set_nbt 的 tag 必须是复合标签（形如 {{Damage:0}}）")
                    check_tacz_nbt(p, tag, warnings)
        if nft == "minecraft:set_damage":
            d = f.get("damage")
            if d is None:
                errors.append(f"{p}: set_damage 缺少 damage 字段")
            elif _is_number(d) and not (0 <= d <= 1):
                errors.append(f"{p}: set_damage 的 damage 应在 0~1（当前 {d}）")
        if nft == "minecraft:set_name" and "name" not in f:
            errors.append(f"{p}: set_name 缺少 name 字段")
        if nft == "minecraft:looting_enchant" and "count" not in f:
            errors.append(f"{p}: looting_enchant 缺少 count 字段")
        if nft == "minecraft:limit_count" and "limit" not in f:
            errors.append(f"{p}: limit_count 缺少 limit 字段")


def _loot_table_file(rid):
    """loot_table 条目引用的表 ID -> 当前根目录下的文件路径（找不到返回 None）"""
    if not isinstance(rid, str) or ":" not in rid:
        return None
    ns, _, path = rid.partition(":")
    base = ROOT
    # 当前根目录本身就是某个命名空间的 loot_tables 目录（如 kubejs/data/<ns>/loot_tables），
    # 同命名空间的引用直接按相对路径找；跨命名空间就没办法了（不知道别的命名空间在哪），
    # 交给「未知」处理，不误报。
    if loot_namespace() and ns != loot_namespace():
        return "other_ns"
    p = (base / (path + ".json"))
    return p if p.is_file() else None


def _check_loot_table_ref(rid, where, errors, warnings, depth):
    """loot_table 引用的真实性 + 循环引用检测（游戏加载时递归 → StackOverflow / 整链失效）"""
    rp = rid_problem(rid)
    if rp:
        (errors if rp[0] == "error" else warnings).append(f"{where}: loot_table 引用 —— {rp[1]}")
        return
    loc = _loot_table_file(rid)
    if loc == "other_ns":
        return
    if loc is None:
        warnings.append(f"{where}: loot_table 引用的 {rid} 在当前目录里找不到对应文件"
                        f"（游戏里会嵌套解析失败，连带这张表也加载不了）")
        return
    if depth >= 12:
        return
    # 循环检测：沿着引用链往下走，回到任何祖先就是环
    chain = _REF_CHAIN
    if rid in chain:
        errors.append(f"{where}: loot_table 循环引用 {' → '.join(list(chain) + [rid])}"
                      f" —— 游戏加载时会无限递归，整条链的表全部失效")
        return
    try:
        obj = json.loads(loc.read_text(encoding="utf-8"))
    except Exception as ex:
        warnings.append(f"{where}: 引用的 {rid} 文件本身 JSON 解析失败（{ex}）")
        return
    chain.append(rid)
    try:
        for pi, pool in enumerate(obj.get("pools") or []):
            if isinstance(pool, dict):
                _walk_refs(pool.get("entries") or [], f"{rid}.pools[{pi}]", errors, warnings, depth + 1)
    finally:
        chain.pop()


_REF_CHAIN = []


def _walk_refs(entries, where, errors, warnings, depth):
    for i, e in enumerate(entries or []):
        if not isinstance(e, dict):
            continue
        if _norm(e.get("type")) == "minecraft:loot_table" and e.get("value"):
            _check_loot_table_ref(e["value"], f"{where}[{i}]", errors, warnings, depth)
        for sub in ("children", "entries"):
            if isinstance(e.get(sub), list):
                _walk_refs(e[sub], f"{where}[{i}].{sub}", errors, warnings, depth)


def check_entries(entries, where, errors, warnings, depth=0):
    if not isinstance(entries, list):
        errors.append(f"{where}: entries 必须是数组")
        return
    if depth > 12:
        errors.append(f"{where}: 嵌套层级过深（超过 12 层）")
        return
    for i, e in enumerate(entries):
        p = f"{where}[{i}]"
        if not isinstance(e, dict):
            errors.append(f"{p}: 条目必须是对象")
            continue
        et = e.get("type")
        if not et:
            errors.append(f"{p}: 缺少 type 字段")
            continue
        net = _norm(et)
        if net not in ENTRY_TYPES:
            warnings.append(f"{p}: 未知的条目类型 {et}（游戏可能整张表都加载不了）")
        # 条目类型本身也必须是合法资源位置
        rp = rid_problem(et)
        if rp and rp[0] == "error":
            errors.append(f"{p}: type 非法 —— {rp[1]}")
        if net in ENTRY_TYPES_NEED_NAME and not e.get("name"):
            errors.append(f"{p}: {et} 缺少 name 字段")
        if net == "minecraft:loot_table":
            if not e.get("value"):
                errors.append(f"{p}: loot_table 条目缺少 value 字段（要引用的战利品表 ID）")
            else:
                _check_loot_table_ref(e.get("value"), p, errors, warnings, depth)
        # 名字 / 引用值的格式：大写、空格、中文都会让游戏解析失败
        for key in ("name", "value"):
            val = e.get(key)
            if isinstance(val, str) and val:
                rp = rid_problem(val)
                if rp:
                    (errors if rp[0] == "error" else warnings).append(f"{p}: {key} —— {rp[1]}")
        if net == "minecraft:item":
            nm = e.get("name")
            if isinstance(nm, str) and nm and not nm.startswith("#"):
                why = item_problem(nm, e)
                if why:
                    errors.append(f"{p}: 物品 {nm} —— {why}")
        if net == "minecraft:tag":
            nm = e.get("name")
            # 1.20.1 的 TagEntry 直接把这个字段当 ResourceLocation 用：
            # 带 # 会解析失败（DataFixer 不会替你补），这是真的会让整表崩掉的写法
            if isinstance(nm, str):
                if nm.startswith("#"):
                    errors.append(f"{p}: tag 条目的 name 不能带 #（1.20.1 官方写法是 "
                                  f"「{nm.lstrip('#')}」）—— 带 # 会让整张表加载失败")
                else:
                    rp = rid_problem(nm)
                    if rp and rp[0] == "error":
                        errors.append(f"{p}: tag 名非法 —— {rp[1]}")
            if "expand" in e and not isinstance(e["expand"], bool):
                errors.append(f"{p}: tag 的 expand 必须是布尔值")
        if net in ENTRY_TYPES_NEED_CHILDREN:
            ch = e.get("children")
            if not isinstance(ch, list):
                errors.append(f"{p}: {et} 需要 children 数组")
            elif not ch:
                errors.append(f"{p}: {et} 的 children 不能为空")
            else:
                check_entries(ch, p + ".children", errors, warnings, depth + 1)
        if "weight" in e:
            w = e["weight"]
            if not isinstance(w, int) or isinstance(w, bool):
                errors.append(f"{p}: weight 必须是整数（当前 {w!r}）")
            elif w < 0:
                errors.append(f"{p}: weight 不能为负数（当前 {w}）")
            elif w == 0:
                warnings.append(f"{p}: weight 为 0，这条永远抽不到（如果是有意屏蔽可以忽略）")
        if "quality" in e and not isinstance(e["quality"], int):
            errors.append(f"{p}: quality 必须是整数")
        check_conditions(e.get("conditions"), p, errors, warnings)
        check_functions(e.get("functions"), p, errors, warnings)


def check_pools(pools, errors, warnings):
    if not isinstance(pools, list):
        errors.append("pools: 必须是数组")
        return
    for i, pool in enumerate(pools):
        p = f"pools[{i}]"
        if not isinstance(pool, dict):
            errors.append(f"{p}: 必须是对象")
            continue
        name = pool.get("name")
        if name is not None and not isinstance(name, str):
            errors.append(f"{p}: name 必须是字符串")
        elif name is not None:
            # 1.20.1 的 LootPool 没有 name 字段（那是 1.21 才加的），
            # 游戏会直接忽略它 —— 靠它区分池子只是工具内部的便利，别指望游戏认
            warnings.append(f"{p}: 池子名 \"{name}\" 不是 1.20.1 官方字段（游戏会忽略），"
                            f"只用来在工具里区分池子")
        check_rolls(pool.get("rolls"), p + ".rolls", errors, warnings)
        if "bonus_rolls" in pool:
            check_rolls(pool["bonus_rolls"], p + ".bonus_rolls", errors, warnings, allow_empty=True)
        check_conditions(pool.get("conditions"), p, errors, warnings)
        check_functions(pool.get("functions"), p, errors, warnings)
        entries = pool.get("entries")
        if entries is None:
            errors.append(f"{p}: 缺少 entries 字段")
        else:
            check_entries(entries, p + ".entries", errors, warnings)
            if not entries:
                # 官方允许空 entries，但这样的池子永远不会产出任何东西 —— 不是报错，是一定要看见
                warnings.append(f"{p}: entries 是空数组 —— 这个池永远不会产出任何物品"
                                f"（如果本来想留一个「稀有池」，得先把条目加进去）")
            else:
                tot = 0
                for e in entries:
                    if isinstance(e, dict):
                        w = e.get("weight", 1)
                        if isinstance(w, int) and not isinstance(w, bool):
                            tot += w
                if tot <= 0:
                    errors.append(f"{p}: 池内条目权重总和为 {tot}，游戏抽取时会抛异常"
                                  f"（至少要有一条权重 > 0）")


def _rolls_avg(v):
    """rolls 的平均值（和前端 rollsAvg 一致）"""
    if isinstance(v, bool) or v is None:
        return 1.0
    if _is_number(v):
        return float(v)
    if isinstance(v, dict):
        n, p = v.get("n"), v.get("p")
        if _is_number(n) and _is_number(p):
            return float(n) * float(p)
        a = float(v.get("min", 1) or 0)
        b = float(v.get("max", 1) or 0)
        return (a + b) / 2.0
    return 1.0


def pool_output(pool):
    """一个池的产出画像。

    为什么必须算这个：`random_chance` 挂在**池**上时，官方语义是
    「这个池只以该概率参与抽取」——池不触发时整个池一件都不出。
    箱子里只有一个池时，池触发率 0.3 就等于「70% 的箱子是空的」。
    这完全合法，但玩家看到的就是「箱子刷不出东西」，
    所以校验必须把它说出来，而不是一句「通过」了事。
    """
    if not isinstance(pool, dict):
        return None
    chance = 1.0
    for c in pool.get("conditions") or []:
        if not isinstance(c, dict):
            continue
        if _norm(c.get("condition")) in ("minecraft:random_chance",
                                        "minecraft:random_chance_with_looting"):
            ch = c.get("chance")
            if _is_number(ch):
                chance *= ch
    entries = pool.get("entries") or []
    total = 0.0
    empty_w = 0.0
    for e in entries:
        if not isinstance(e, dict):
            continue
        w = e.get("weight", 1)
        if isinstance(w, bool) or not _is_number(w):
            w = 1
        w = max(0.0, float(w))
        total += w
        if _norm(e.get("type")) == "minecraft:empty":
            empty_w += w
    rolls = max(0.0, _rolls_avg(pool.get("rolls"))) + max(0.0, _rolls_avg(pool.get("bonus_rolls")))
    if total <= 0:
        p_empty = 1.0                     # 没条目 / 权重全 0 → 一定不出东西
    else:
        p_empty = (empty_w / total) ** rolls if rolls > 0 else 1.0
    return {
        "triggerChance": round(chance, 6),
        "entries": len(entries),
        "rolls": round(rolls, 3),
        "emptyWeightRatio": round(empty_w / total, 6) if total > 0 else 1.0,
        "emptyChance": round((1 - chance) + chance * p_empty, 6),
        "gated": chance < 1.0,
        "noOutput": total <= 0,
    }


def output_report(obj):
    """整张表的产出画像：空箱概率 + 期望件数 + 每个池的原因"""
    pools = obj.get("pools") if isinstance(obj, dict) else None
    if not isinstance(pools, list) or not pools:
        return None
    infos = [pool_output(p) for p in pools]
    infos = [i for i in infos if i]
    if not infos:
        return None
    p_empty = 1.0
    expected = 0.0
    for i in infos:
        p_empty *= i["emptyChance"]
        if not i["noOutput"]:
            expected += i["triggerChance"] * i["rolls"] * (1 - i["emptyWeightRatio"])
    return {"emptyChance": round(p_empty, 6),
            "expectedItems": round(expected, 3),
            "pools": infos}


def check_output(obj, errors, warnings):
    """产出体检 —— 不报「格式错」，只报「游戏里会不会什么都不出」"""
    rep = output_report(obj)
    if not rep:
        return
    pct = rep["emptyChance"] * 100
    if pct < 30:
        return
    causes = []
    for idx, i in enumerate(rep["pools"]):
        if i["noOutput"]:
            causes.append(f"池{idx + 1} 没有可产出的条目")
        elif i["gated"]:
            causes.append(f"池{idx + 1} 只有 {i['triggerChance'] * 100:.0f}% 概率触发"
                          f"（random_chance）")
        elif i["emptyWeightRatio"] > 0.3:
            causes.append(f"池{idx + 1} 有 {i['emptyWeightRatio'] * 100:.0f}% 权重落在"
                          f"「minecraft:empty」上")
    why = "；".join(causes[:3]) or "条目权重/抽取次数配置"
    warnings.append(
        f"📉 产出体检：游戏里约 {pct:.0f}% 的开箱**什么都出不来**（{why}）。"
        f"这是官方允许的写法，所以不算错误，但玩家看到的就是「箱子刷不出东西」。"
        f"如果想每箱都有东西，把这几个池的触发概率去掉（删掉池里的 random_chance 条件）；"
        f"预期每次开箱产出约 {rep['expectedItems']:.2f} 件。")


def validate(obj):
    """返回 {'errors': [...], 'warnings': [...], 'output': {...}}"""
    errors, warnings = [], []
    if not isinstance(obj, dict):
        return {"errors": ["顶层必须是一个 JSON 对象"], "warnings": [], "output": None}
    t = obj.get("type")
    if t is not None:
        if not isinstance(t, str):
            errors.append("type: 必须是字符串")
        else:
            rp = rid_problem(t)
            if rp and rp[0] == "error":
                errors.append(f"type 非法 —— {rp[1]}")
            if _norm(t) not in TABLE_TYPES:
                warnings.append(f"type: 未知的战利品表类型 {t}（游戏可能整张表都加载不了）")
    if "random_sequence" in obj:
        rp = rid_problem(obj["random_sequence"])
        if rp:
            (errors if rp[0] == "error" else warnings).append(f"random_sequence —— {rp[1]}")
    if "pools" not in obj:
        errors.append("缺少 pools 字段（战利品表必须至少有一个池）")
    else:
        check_pools(obj["pools"], errors, warnings)
    if "functions" in obj:
        check_functions(obj["functions"], "(顶层)", errors, warnings)
    known = {"type", "pools", "functions", "random_sequence"}
    for k in obj:
        if k not in known:
            warnings.append(f"顶层出现非标准字段：{k}")
    check_output(obj, errors, warnings)
    return {"errors": errors, "warnings": warnings, "output": output_report(obj)}


# ==========================================================================
#  文件操作
# ==========================================================================

_ITEM_NAMES = None


def item_names():
    """id -> 中文名/英文名 映射（惰性载入并缓存）"""
    global _ITEM_NAMES
    if _ITEM_NAMES is None:
        _ITEM_NAMES = {}
        f = DATA / "items.json"
        if f.exists():
            try:
                for it in json.loads(f.read_text(encoding="utf-8")).get("items", []):
                    nm = it.get("zh") or it.get("en") or ""
                    if nm:
                        _ITEM_NAMES[it["id"]] = nm
            except Exception:
                pass
    return _ITEM_NAMES


_KNOWN_IDS = None
_INACTIVE_IDS = None


def _load_items_db():
    """载入物品库，缓存「已知 ID」和「来自已禁用模组的 ID」"""
    global _KNOWN_IDS, _INACTIVE_IDS
    if _KNOWN_IDS is not None:
        return
    _KNOWN_IDS = set()
    _INACTIVE_IDS = set()
    f = DATA / "items.json"
    if not f.exists():
        return
    try:
        for it in json.loads(f.read_text(encoding="utf-8")).get("items", []):
            iid = it.get("id")
            if not iid:
                continue
            _KNOWN_IDS.add(iid)
            # 新物品库带 valid/why（build_registry.py 判定），比只看 active 准得多
            if it.get("valid") is False or (it.get("valid") is None and it.get("active") is False):
                _INACTIVE_IDS.add(iid)
    except Exception:
        pass


def known_ids():
    """物品库里所有已知物品 ID 的集合（含无名字的）。"""
    _load_items_db()
    return _KNOWN_IDS


def inactive_ids():
    """来自「已禁用模组」的物品 ID —— 游戏里其实不存在，引用它会让整张表加载失败。"""
    _load_items_db()
    return _INACTIVE_IDS


# --------------------------------------------------------------------------
#  严格注册表（build_registry.py 生成）
#  规则：命名空间必须由「启用中的模组」声明，且至少有一条物品证据
#        （物品模型 / item. 语言键 / 配方引用 / 物品标签 / KubeJS 注册），
#        再用游戏日志做校准（游戏说 unknown string 的一律判不存在）。
#  对拍结果：幽灵 ID 9/9 拒绝，真实物品 5001/5001 覆盖。
# --------------------------------------------------------------------------

_REGISTRY = None


def registry():
    global _REGISTRY
    if _REGISTRY is None:
        _REGISTRY = {"valid": None, "invalid": {}, "meta": {}, "failedTables": set(),
                     "logRejected": {}}
        f = DATA / "registry.json"
        if f.exists():
            try:
                d = json.loads(f.read_text(encoding="utf-8"))
                _REGISTRY["valid"] = set(d.get("valid") or {})
                _REGISTRY["invalid"] = dict(d.get("invalid") or {})
                _REGISTRY["failedTables"] = set(d.get("failedTables") or [])
                _REGISTRY["logRejected"] = dict(d.get("logRejected") or {})
                _REGISTRY["meta"] = {k: d.get(k) for k in
                                     ("generated", "rule", "counts", "logCalibrated", "instance")}
            except Exception:
                _REGISTRY = {"valid": None, "invalid": {}, "meta": {}, "failedTables": set(),
                             "logRejected": {}}
    return _REGISTRY


def registry_meta():
    return registry()["meta"]


def item_problem(rid, entry=None):
    if entry is not None:
        # TaCZ 基物品 + set_nbt 子类型 = 游戏里的合法伪物品（tacz:attachment 这类
        # 基物品 lang/模型证据很弱，但配合 AttachmentId 子类型就能正常生成）。
        # 子类型本身是否存在由 check_tacz_nbt 负责，这里不按普通物品判死。
        for f in entry.get("functions") or []:
            if not isinstance(f, dict):
                continue
            if _norm(f.get("function")) != "minecraft:set_nbt":
                continue
            tag = f.get("tag") or ""
            for key in TACZ_NBT_KEYS:
                if re.search(key + r':"[^"]+"', tag):
                    try:
                        cat = json.loads((DATA / "tacz.json").read_text(encoding="utf-8"))
                    except Exception:
                        cat = {}
                    base = (cat.get(key) or {}).get("base")
                    if base and rid == base:
                        return None
    """这个物品 ID 在游戏里到底存不存在？不存在就返回原因，存在返回 None。

    这是整个工具最重要的一次判断：Minecraft 加载战利品表时，只要有一个
    `minecraft:item` 的 name 查不到，就会抛 JsonSyntaxException，
    **整张表加载失败** —— 建筑里所有箱子都不刷东西。
    """
    reg = registry()
    valid = reg["valid"]
    if valid is None:
        # 还没有注册表 → 退回物品库标记（老行为，会漏判）
        if rid in inactive_ids():
            return "来自【已禁用的模组】—— 游戏里不存在，引用它会让整张战利品表加载失败（宝箱变空）"
        if rid not in known_ids():
            return "不在物品库里 —— 游戏里多半不存在；引用它会让整张战利品表加载失败（宝箱变空）"
        return None
    if rid in valid:
        return None
    why = reg["invalid"].get(rid)
    if why:
        return why + "。引用它会让整张战利品表加载失败（宝箱全空）"
    return ("物品库里找不到它 —— 游戏里多半不存在。引用不存在的物品会让整张战利品表加载失败"
            "（宝箱全空）；如果是刚装的模组，先点「重建物品库」")


def safe_path(rel: str) -> Path:
    rel = unquote(rel).lstrip("/\\")
    p = (ROOT / rel).resolve()
    base = ROOT.resolve()
    if base != p and base not in p.parents:
        raise ValueError("路径越界，拒绝访问")
    return p


def set_root(path):
    """切换正在编辑的战利品表根目录（任意目录都能导进来）"""
    global ROOT
    p = Path(str(path)).resolve()
    if not p.is_dir():
        raise ValueError(f"目录不存在：{path}")
    ROOT = p
    return ROOT


# ==========================================================================
#  实例探测 —— 让工具能用于任意整合包，不用改代码
# ==========================================================================

INSTANCE_MARKERS = ("mods", "kubejs", "config", "saves")


def looks_like_instance(p: Path) -> bool:
    """目录里有没有整合包实例的特征（至少两样）"""
    if not p.is_dir():
        return False
    return sum(1 for m in INSTANCE_MARKERS if (p / m).is_dir()) >= 2


def detect_instance(start):
    """从给定路径往上找实例根目录；找不到返回 None"""
    try:
        p = Path(str(start)).resolve()
    except Exception:
        return None
    if p.is_file():
        p = p.parent
    for cand in [p] + list(p.parents):
        if looks_like_instance(cand):
            return cand
    return None


def find_loot_dirs(instance: Path):
    """在实例里找所有可能的战利品表目录（KubeJS / 数据包 / 存档数据包）"""
    out = []
    if not instance.is_dir():
        return out
    for d in sorted(instance.glob("kubejs/data/*/loot_tables")):
        if d.is_dir():
            out.append({"path": str(d), "label": f"KubeJS · {d.parent.name}", "kind": "kubejs"})
    for d in sorted(instance.glob("datapacks/*/data/*/loot_tables")):
        if d.is_dir():
            out.append({"path": str(d), "label": f"数据包 · {d.parent.parent.name}", "kind": "datapack"})
    for d in sorted(instance.glob("saves/*/datapacks/*/data/*/loot_tables")):
        if d.is_dir():
            out.append({"path": str(d),
                        "label": f"存档数据包 · {d.parents[1].name}/{d.parent.name}", "kind": "world"})
    return out


def pick_default_root():
    """没指定 --root 时，自动挑一个战利品表目录：
    先看实例里的 KubeJS，再退回到工具自己所在目录。"""
    inst = detect_instance(HERE)
    if inst:
        dirs = find_loot_dirs(inst)
        kjs = [d for d in dirs if d["kind"] == "kubejs"]
        if kjs:
            return Path(kjs[0]["path"])
        if dirs:
            return Path(dirs[0]["path"])
    return DEFAULT_ROOT


def loot_namespace():
    """当前战利品表目录对应的命名空间。
    优先沿路径找 `data/<ns>/loot_tables` 结构（标准数据包布局）；
    找不到就退回上一级目录名。"""
    try:
        parts = list(ROOT.resolve().parts)
    except Exception:
        return ""
    for i in range(len(parts) - 1, 0, -1):
        if parts[i] == "loot_tables" and i >= 2 and parts[i - 2] == "data":
            return parts[i - 1]
    # 非标准布局：目录本身叫 loot_tables 就取上一级，否则用目录名
    return (ROOT.parent.name if ROOT.name == "loot_tables" else ROOT.name) or ""


def loot_id(rel: str) -> str:
    """相对路径 -> 完整战利品表 ID，例如 chests/suburban.json -> ns:chests/suburban"""
    rid = rel.replace("\\\\", "/")
    if rid.endswith(".json"):
        rid = rid[:-5]
    ns = loot_namespace()
    return f"{ns}:{rid}" if ns else rid


def dumps_loot(obj, level=0, key=None):
    """输出格式：结构用缩进，entries / children 里的每个条目压成一行（贴近手写风格）"""
    ind = "  " * level
    ind2 = "  " * (level + 1)
    if isinstance(obj, dict):
        if not obj:
            return "{}"
        parts = [f'{json.dumps(k, ensure_ascii=False)}: {dumps_loot(v, level + 1, k)}'
                 for k, v in obj.items()]
        return "{\n" + ind2 + (",\n" + ind2).join(parts) + "\n" + ind + "}"
    if isinstance(obj, list):
        if not obj:
            return "[]"
        if key in ("entries", "children"):
            parts = [json.dumps(v, ensure_ascii=False, separators=(", ", ": ")) for v in obj]
        else:
            parts = [dumps_loot(v, level + 1) for v in obj]
        return "[\n" + ind2 + (",\n" + ind2).join(parts) + "\n" + ind + "]"
    return json.dumps(obj, ensure_ascii=False)


def atomic_write(p: Path, text: str):
    """原子写入：先写同目录临时文件，再 os.replace 覆盖。
    中途断电/崩溃也不会留下半截文件。newline='' 保证写 LF（Minecraft 认 LF）。"""
    tmp = p.with_name(p.name + ".tmp")
    try:
        tmp.write_text(text, encoding="utf-8", newline="")
        os.replace(tmp, p)          # 同分区上是原子操作
    finally:
        if tmp.exists():
            try:
                tmp.unlink()
            except Exception:
                pass


def make_backup(rel: str, p: Path):
    """保存前给原文件留一份备份，并按文件轮转，只保留最近 BACKUP_KEEP 份。"""
    if not p.exists():
        return
    bkdir = HERE / "_backup"
    bkdir.mkdir(parents=True, exist_ok=True)
    safe = rel.replace("/", "__")
    stamp = time.strftime("%Y%m%d-%H%M%S")
    dst = bkdir / (stamp + "__" + safe)
    n = 1
    while dst.exists():                     # 同一秒内多次保存也不互相覆盖
        dst = bkdir / (f"{stamp}-{n:02d}__{safe}")
        n += 1
    shutil.copy2(p, dst)
    # 轮转：同一文件的备份超过上限就删最旧的（按修改时间排，同秒也不乱）
    mine = sorted(bkdir.glob("*__" + safe), key=lambda f: f.stat().st_mtime)
    for old in mine[:-BACKUP_KEEP]:
        try:
            old.unlink()
        except Exception:
            pass


BACKUP_KEEP = 20       # 每个文件保留多少份历史备份


def prune_trash(keep: int = 60):
    """回收站只保留最近 keep 个文件，防止无限增长（测试残留也会堆在这）。"""
    if not TRASH.is_dir():
        return
    files = sorted([f for f in TRASH.iterdir() if f.is_file()])
    for old in files[:-keep]:
        try:
            old.unlink()
        except Exception:
            pass


def summarize(obj):
    """统计一个战利品表里的池数 / 条目数 / 物品数"""
    pools = obj.get("pools") or []
    n_entries = 0
    n_items = 0

    def walk(entries):
        nonlocal n_entries, n_items
        for e in entries:
            if not isinstance(e, dict):
                continue
            n_entries += 1
            if e.get("type") in ("minecraft:item", "minecraft:tag", "minecraft:dynamic"):
                n_items += 1
            for sub in ("children", "entries"):
                if isinstance(e.get(sub), list):
                    walk(e[sub])

    for p in pools:
        if isinstance(p, dict):
            walk(p.get("entries") or [])
    return {"pools": len(pools), "entries": n_entries, "items": n_items}


def list_files():
    out = []
    if not ROOT.is_dir():
        return out
    for f in sorted(ROOT.rglob("*.json")):
        rel = str(f.relative_to(ROOT)).replace("\\", "/")
        try:
            raw = f.read_text(encoding="utf-8")
            obj = json.loads(raw)
            info = summarize(obj)
            ok = True
        except Exception as ex:
            info = {"pools": 0, "entries": 0, "items": 0}
            ok = False
        out.append({
            "path": rel,
            "name": f.name,
            "dir": str(f.parent.relative_to(ROOT)).replace("\\", "/") if f.parent != ROOT else "",
            "size": f.stat().st_size,
            "mtime": f.stat().st_mtime,
            "valid": ok,
            **info,
        })
    return out


# ==========================================================================
#  AI 对话 —— 接 OpenAI 兼容接口，把自然语言变成战利品表操作
# ==========================================================================
#
# 设计要点：
#  - 模型只做「理解意图 → 输出操作 JSON」，不直接写 JSON 全文（容易出错）。
#    所有改动都在后端套用、校验，前端只负责展示预览。
#  - 系统提示里带：loot 格式简述、TaCZ 子类型目录（枪/配件/弹药/投掷物/消耗品，
#    因为这些东西 LLM 不可能知道）、操作集 schema。
#  - 普通物品 LLM 写中文名或 ID 都行，后端 resolve_item() 负责解析成真实 ID。
#  - 不配置 key 时 /api/chat 返回明确的引导信息。

AI_CFG_FILE = DATA / "ai_config.json"
MOCK_LLM = False          # --mock-llm 打开后走离线模拟，用于无 key 时验证链路

DEFAULT_AI_CFG = {
    "base_url": "https://api.deepseek.com/v1",
    "api_key": "",
    "model": "deepseek-chat",
    "use_proxy": False,
}


def load_ai_config():
    cfg = dict(DEFAULT_AI_CFG)
    if AI_CFG_FILE.exists():
        try:
            cfg.update(json.loads(AI_CFG_FILE.read_text(encoding="utf-8")))
        except Exception:
            pass
    return cfg


def save_ai_config(cfg):
    cur = load_ai_config()
    for k in DEFAULT_AI_CFG:
        if k in cfg:
            cur[k] = cfg[k]
    DATA.mkdir(parents=True, exist_ok=True)
    AI_CFG_FILE.write_text(json.dumps(cur, ensure_ascii=False, indent=2), encoding="utf-8")
    return cur


def masked_ai_config():
    cfg = load_ai_config()
    cfg = dict(cfg)
    k = cfg.get("api_key") or ""
    cfg["api_key"] = (k[:6] + "…" + k[-3:]) if len(k) > 9 else ("●" * len(k))
    cfg["configured"] = bool(load_ai_config().get("api_key"))
    return cfg


# ---------- 系统提示 ----------

OPS_DOC = """\
你可以输出两种东西，二选一：

A. 操作列表（**默认就用这个**，改动小、可审查）：
{"reply": "给用户的一句话总结", "ops": [ ...操作... ]}

B. 整表替换（**尽量别用**）：{"reply": "...", "replace_data": {完整的新战利品表}}
   只有用户明确要求「整张表重写/重新生成」时才用。整表要输出几百条，
   很容易超出输出上限被截断，导致整个请求失败——能拆成操作就拆成操作。

C. 只回答问题（用户没要求改动时）：{"reply": "你的回答"}
   例如用户问「这个表有几个池」「某物品权重多少」，只给 reply，不要给 ops。

操作集（pool 是池的序号，从 0 开始）：
  add_pool          {op, name?, rolls?}                 新建一个池
  del_pool          {op, pool}                          删除池
  rename_pool       {op, pool, name}                    改池名
  set_rolls         {op, pool, rolls}                   rolls 是数字或 {min,max}
  add_entry         {op, pool, item, weight?, count?, quality?}   加一个物品条目；item 写中文名或 ID
  add_tacz          {op, pool, kind, id, weight?, count?}         加枪械/配件/弹药等子类型；kind 见下，id 必须从目录里挑
  remove_entry      {op, pool, match}                   删除匹配条目；match 写中文名或 ID（模糊匹配）
  set_weight        {op, pool, match, weight}           改匹配条目的权重
  set_count         {op, pool, match, count:{min,max}}  改匹配条目的掉落数量
  （match 对 TaCZ 条目直接写子类型 id 或中文名就行，比如「pistol」「通用手枪子弹」——
    后端会按 GunId/AmmoId 等去匹配，不要因为是 NBT 条目就清空重建）
  clear_pool        {op, pool}                          清空一个池的所有条目
  set_type          {op, type}                          改整表类型（minecraft:chest 等）

TaCZ 子类型（kind 只能是这几个，id 必须从对应目录里挑，不要自己编）：
"""

ENTRY_DOC = """\
条目类型：minecraft:item（物品）、minecraft:tag（物品标签，name 以 # 开头）、minecraft:empty（空/占位）、
minecraft:loot_table（引用另一张表）、minecraft:group（全部掉）、minecraft:alternatives（择一）、
minecraft:sequence（顺序）、minecraft:dynamic。
常用函数：set_count（数量）、set_nbt（写 NBT）。常用条件：random_chance（chance 0~1）。
"""


def tacz_compact():
    """TaCZ 子类型目录的紧凑文本，塞进系统提示"""
    f = DATA / "tacz.json"
    if not f.exists():
        return "（无 TaCZ 目录）\n"
    try:
        cat = json.loads(f.read_text(encoding="utf-8"))
    except Exception:
        return "（TaCZ 目录读取失败）\n"
    lines = []
    for nbt, d in cat.items():
        label = d.get("label", nbt)
        items = d.get("items", [])
        lines.append(f"【{label}】kind={nbt} 基物品={d.get('base')} 共{len(items)}种：")
        for it in items:
            nm = it.get("name") or ""
            lines.append(f"  {it['id']} = {nm}" if nm else f"  {it['id']}")
    return "\n".join(lines) + "\n"


_TACZ_NAMES = None


def tacz_names():
    """TaCZ 子类型 id -> 名字（惰性载入）"""
    global _TACZ_NAMES
    if _TACZ_NAMES is None:
        _TACZ_NAMES = {}
        f = DATA / "tacz.json"
        if f.exists():
            try:
                cat = json.loads(f.read_text(encoding="utf-8"))
                for d in cat.values():
                    for it in d.get("items", []):
                        if it.get("name"):
                            _TACZ_NAMES[it["id"]] = it["name"]
            except Exception:
                pass
    return _TACZ_NAMES


def _entry_hint(e):
    """给压缩摘要用的单条描述：中文名(ID) + 权重 + 子类型/歌名"""
    nm = e.get("name") or e.get("value") or e.get("type") or "?"
    w = e.get("weight", 1)
    zh = item_names().get(nm, "")
    label = f"{zh}({nm})" if zh else nm
    extra = ""
    for fn in e.get("functions") or []:
        if _norm(fn.get("function")) != "minecraft:set_nbt":
            continue
        tag = fn.get("tag")
        if not isinstance(tag, str):
            continue
        if "NetMusicSongInfo" in tag:
            m = re.search(r'name:"([^"]*)"', tag)
            if m:
                extra = "「" + m.group(1) + "」"
            break
        for key in TACZ_NBT_KEYS:
            m = re.search(key + r':"([^"]+)"', tag)
            if m:
                extra = tacz_names().get(m.group(1), m.group(1))
                break
        if extra:
            break
    return f"{label} w{w}" + (f" [{extra}]" if extra else "")


def compact_file_summary(data):
    """大文件时给 LLM 的紧凑摘要：每个池列条目名+权重，不带函数/条件细节"""
    lines = []
    pools = data.get("pools") or []
    lines.append(f"整表类型 {data.get('type', '?')}，共 {len(pools)} 个池：")
    for i, p in enumerate(pools):
        rolls = p.get("rolls")
        entries = p.get("entries") or []
        lines.append(f"池{i}「{p.get('name', '')}」 rolls={rolls} 共{len(entries)}条：")
        for e in entries:
            lines.append("  " + _entry_hint(e))
    return "\n".join(lines)


# 文件 JSON 超过这个大小就用紧凑摘要，不带全文（用户用的是 1M 上下文模型，所以放得很宽；
# 摘要路径保留为兜底，防止有人接了小上下文模型）
FULL_PROMPT_LIMIT = 400000


def clear_all_caches():
    """清掉所有惰性加载的模块级缓存。重建物品库后必须调这个，
    否则后续请求还在用旧的物品名/ID 索引——表现就是「重建了没用，得重启程序」。"""
    global _TACZ, _ITEM_NAMES, _KNOWN_IDS, _INACTIVE_IDS, _TACZ_NAMES, _NAME_TO_ID, _REGISTRY
    _TACZ = None
    _ITEM_NAMES = None
    _KNOWN_IDS = None
    _INACTIVE_IDS = None
    _TACZ_NAMES = None
    _NAME_TO_ID = None
    _REGISTRY = None


def build_system_prompt(path, data):
    full = json.dumps(data, ensure_ascii=False)
    if len(full) <= FULL_PROMPT_LIMIT:
        file_part = "当前文件内容（完整 JSON）：\n```json\n" + full + "\n```\n\n"
    else:
        file_part = ("当前文件比较大，下面是紧凑摘要（每池的条目名+权重；"
                     "改条目时用 match 写中文名或 ID，不用记序号）：\n"
                     + compact_file_summary(data) + "\n\n")
    return (
        "你是一个 Minecraft 1.20.1 战利品表（loot table）编辑助手。"
        "用户用自然语言描述要怎么改，你只输出 JSON，不要输出任何别的文字。\n\n"
        + ENTRY_DOC + "\n"
        + OPS_DOC + "\n"
        + tacz_compact() + "\n"
        f"当前正在编辑的文件：{path}\n"
        + file_part +
        "规则：\n"
        "- 只输出一个 JSON 对象，不要解释文字，不要用 ``` 代码围栏。\n"
        "- JSON 必须严格合法：字符串用双引号、最后一个元素后面不要留逗号、字符串里不要有未转义的换行。\n"
        "- 优先用 ops 操作列表；不要用 replace_data 整表替换（输出太长容易被截断，会让整个请求失败）。\n"
        "- 用户只是提问（没要求改动）时，只给 reply，不要给 ops。\n"
        "- 物品一律写中文名或完整 ID（含命名空间），后端会负责解析。\n"
        "- TaCZ 子类型只能用目录里真实存在的 id，不能瞎编。\n"
        "- 拿不准用户要改哪个池时，默认改第 0 个池，并在 reply 里说明。\n"
        "- 用户说「不要」「删掉」「清空」时，生成对应的删除类操作。\n"
        "- 如果用户要求的目标在表里根本不存在，别硬造，reply 里说清楚没找到即可。\n"
    )


# ---------- LLM 调用 ----------

def call_llm(messages, cfg):
    """调 OpenAI 兼容接口，返回 content 字符串。

    健壮性处理：
    - 有的模型不支持 response_format=json_object（会直接 400），
      遇到就先带它试，报「不支持」就降级去掉再试一次。
    - 网络错误分类成「超时 / 连不上 / HTTP 错误」，各自给能看懂的提示。
    """
    if MOCK_LLM:
        return mock_llm_reply(messages)
    url = cfg.get("base_url", "").rstrip("/") + "/chat/completions"
    base_payload = {
        "model": cfg.get("model", ""),
        "messages": messages,
        "temperature": 0.2,
    }
    handlers = []
    if cfg.get("use_proxy"):
        proxy = os.environ.get("https_proxy") or os.environ.get("http_proxy") \
            or "http://127.0.0.1:63701"
        handlers.append(urllib.request.ProxyHandler({"http": proxy, "https": proxy}))
    opener = urllib.request.build_opener(*handlers)

    def do_post(payload):
        req = urllib.request.Request(
            url,
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            headers={
                "Content-Type": "application/json",
                "Authorization": "Bearer " + (cfg.get("api_key") or ""),
            },
            method="POST",
        )
        with opener.open(req, timeout=120) as r:
            resp = json.loads(r.read().decode("utf-8"))
        return resp["choices"][0]["message"]["content"]

    # 先试带 json_object（能约束输出）；不支持就去掉重试
    for payload in (dict(base_payload, response_format={"type": "json_object"}), base_payload):
        try:
            return do_post(payload)
        except urllib.error.HTTPError as e:
            body = e.read().decode("utf-8", errors="ignore")[:300]
            # 带 response_format 的调用一报错就先降级（中转站对不支持的参数报错码千奇百怪，
            # 有的拿 401 当「不支持」，有的拿 400）。降级后的第二次调用才走下面的细分。
            if payload.get("response_format"):
                continue
            # 认证错误单独说清楚——这是最常见的「用不起来」原因
            if e.code in (401, 403):
                raise RuntimeError(
                    f"认证失败（HTTP {e.code}）。通常是这几种：① API Key 填错或过期了 "
                    f"② 账户欠费/没额度了 ③ 接口地址填错（base_url 结尾别带 /chat/completions）"
                    f"④ 用的是中转站但没在该站开通这个模型。原始返回：{body}")
            # 模型名不存在
            if e.code == 404:
                raise RuntimeError(
                    f"模型不存在（HTTP 404）。检查模型名是不是写对了（比如 deepseek-chat / moonshot-v1-8k）。"
                    f"原始返回：{body}")
            raise RuntimeError(f"LLM 接口报错 HTTP {e.code}：{body}")
        except socket.timeout:
            raise RuntimeError("连接超时——模型可能还在算，或者被代理卡住了。换个快点的模型，或检查代理。")
        except urllib.error.URLError as e:
            reason = str(e.reason)
            if "timed out" in reason.lower():
                raise RuntimeError("连接超时——模型可能还在算，或者被代理卡住了。")
            raise RuntimeError(f"连不上接口：{reason}（境外服务记得在设置里开「走代理」）")
        except (KeyError, IndexError):
            raise RuntimeError("LLM 返回结构异常：" + json.dumps(resp, ensure_ascii=False)[:300])
    raise RuntimeError("LLM 调用失败")


# ---------- 离线模拟（--mock-llm，无 key 时验证链路） ----------

def mock_llm_reply(messages):
    user = ""
    for m in reversed(messages):
        if m.get("role") == "user":
            user = m.get("content", "")
            break
    # 找一个「当前文件」提示里的第一个池
    if "钻石" in user:
        ops = [{"op": "add_entry", "pool": 0, "item": "钻石", "weight": 1,
                "count": {"min": 1, "max": 2}}]
        reply = "已把「钻石」加进第 1 个池（权重 1，每次 1~2 个）"
    elif "弹药" in user:
        ops = [{"op": "set_weight", "pool": 0, "match": "弹药", "weight": 5}]
        reply = "已把第 1 个池里「弹药」的权重改成 5"
    elif "清空" in user:
        ops = [{"op": "clear_pool", "pool": 0}]
        reply = "已清空第 1 个池"
    elif "新池" in user or "新建池" in user:
        ops = [{"op": "add_pool", "name": "AI 新建的池", "rolls": {"min": 1, "max": 2}}]
        reply = "已新建一个池（抽 1~2 次）"
    else:
        ops = [{"op": "add_entry", "pool": 0, "item": "minecraft:stone", "weight": 1}]
        reply = f"[模拟] 收到了你的要求「{user[:24]}」，示例性地加了个石头。"
    return json.dumps({"reply": reply, "ops": ops}, ensure_ascii=False)


# ---------- 操作解析与套用 ----------

def _escape_control_in_strings(s):
    """把 JSON 字符串字面量里未转义的换行/制表符转义掉。
    LLM 写长字符串时偶尔直接把换行写进去，json.loads 会报 Invalid control character。"""
    out = []
    in_str = False
    esc = False
    for ch in s:
        if in_str:
            if esc:
                out.append(ch); esc = False
            elif ch == '\\':
                out.append(ch); esc = True
            elif ch == '"':
                out.append(ch); in_str = False
            elif ch == '\n':
                out.append('\\n')
            elif ch == '\r':
                out.append('\\r')
            elif ch == '\t':
                out.append('\\t')
            else:
                out.append(ch)
        else:
            out.append(ch)
            if ch == '"':
                in_str = True
    return ''.join(out)


def _close_truncated_json(s):
    """把被截断的 JSON 尽量补成合法：补上没收尾的字符串和括号。
    长输出撞到输出上限被截断时，这是最常见的畸形形态。"""
    stack = []
    in_str = False
    esc = False
    for ch in s:
        if in_str:
            if esc:
                esc = False
            elif ch == '\\':
                esc = True
            elif ch == '"':
                in_str = False
        else:
            if ch == '"':
                in_str = True
            elif ch in '{[':
                stack.append('}' if ch == '{' else ']')
            elif ch in '}]':
                if stack:
                    stack.pop()
    out = s
    if esc:                                        # 结尾是个孤立反斜杠
        out = out[:-1]
    if in_str:
        out += '"'
    out = re.sub(r'[,:]\s*$', '', out.rstrip())    # 结尾悬空的逗号/冒号
    out += ''.join(reversed(stack))
    return out


def parse_llm_json(text):
    """从 LLM 输出里抠出 JSON 对象。

    带容错：LLM（尤其是推理模型 + 长输出）经常吐出「差一点」的 JSON——
    尾随逗号、字符串里未转义的换行、被输出上限截断。先原样解析，
    不行就依次尝试常见修复，全都不行才报错。"""
    if not isinstance(text, str):
        raise RuntimeError("LLM 返回的不是文本")
    s = text.strip()
    # 去掉 ```json ... ``` 围栏
    m = re.search(r"```(?:json)?\s*([\s\S]*)\s*```", s)
    if m:
        s = m.group(1).strip()
    # 找第一个 { 到最后一个 }（被截断时可能没有收尾的 }）
    a = s.find("{")
    if a < 0:
        raise RuntimeError("LLM 输出里没有 JSON 对象")
    b = s.rfind("}")
    body = s[a:b + 1] if b > a else s[a:]

    no_trailing = re.sub(r',\s*([}\]])', r'\1', body)
    escaped = _escape_control_in_strings(no_trailing)
    attempts = [
        body,
        no_trailing,
        _escape_control_in_strings(body),
        escaped,
        _close_truncated_json(escaped),
    ]
    last = None
    for cand in attempts:
        try:
            obj = json.loads(cand)
        except json.JSONDecodeError as e:
            last = str(e)
            continue
        if isinstance(obj, dict):
            return obj
        last = f"顶层不是 JSON 对象（是 {type(obj).__name__}）"
    raise RuntimeError(f"LLM 输出的 JSON 不合法（已尝试自动修复）：{last}")


_NAME_TO_ID = None


def name_to_id():
    """中文名/英文名 -> id 反向索引（惰性载入）。同名取第一个。

    只收「注册表确认存在」的物品：以前把方块状态、旧版本残留都收进来，
    AI 按中文名选物品时就会挑到游戏里根本不存在的东西。"""
    global _NAME_TO_ID
    if _NAME_TO_ID is None:
        _NAME_TO_ID = {}
        f = DATA / "items.json"
        if f.exists():
            try:
                for it in json.loads(f.read_text(encoding="utf-8")).get("items", []):
                    iid = it.get("id")
                    if not iid:
                        continue
                    if it.get("valid") is False:
                        continue
                    if it.get("valid") is None and it.get("active") is False:
                        continue
                    for nm in (it.get("zh"), it.get("en")):
                        if nm and nm not in _NAME_TO_ID:
                            _NAME_TO_ID[nm] = iid
            except Exception:
                pass
    return _NAME_TO_ID


def resolve_item(ref):
    """把「中文名 / 英文名 / ID」解析成物品 ID。解析不到返回 None。

    只认「注册表里确认存在」的物品：AI 或手动填的 ID 只要游戏里没有，
    整张表在游戏里就会加载失败 —— 这里挡掉，别让它落到文件里。
    """
    if not isinstance(ref, str) or not ref.strip():
        return None
    ref = ref.strip()
    items = item_names()           # id -> name
    # 1) 看起来就是 ID：必须是确认存在的
    if ":" in ref and re.match(r"^[a-z0-9_\-\.]+:[a-z0-9_\-\./]+$", ref):
        return None if item_problem(ref) else ref
    # 2) 名字精确匹配 / 前缀 / 包含（只在确认存在的物品里找）
    n2i = name_to_id()
    if ref in n2i:
        return n2i[ref]
    cand = [(nm, iid) for nm, iid in n2i.items() if nm.startswith(ref)]
    if not cand:
        cand = [(nm, iid) for nm, iid in n2i.items() if ref in nm]
    if cand:
        # 名字最短的优先（最贴切的）
        cand.sort(key=lambda x: len(x[0]))
        return cand[0][1]
    # 3) 退一步：当作没写命名空间的 ID
    if ":" not in ref:
        for cand_id in (ref, "minecraft:" + ref):
            if cand_id in items:
                return None if item_problem(cand_id) else cand_id
    return None


def item_problem_msg(ref):
    """给 AI 的失败说明：为什么这个 ID 不能用"""
    if not isinstance(ref, str) or not ref.strip():
        return "物品是空的"
    rid = ref.strip()
    why = item_problem(rid) if ":" in rid else None
    if why:
        return f"「{rid}」在游戏里不存在：{why}"
    return f"认不出物品「{rid}」—— 请用物品库里确认存在的完整 ID（或准确的中文名）"


def _count_obj(c):
    if isinstance(c, (int, float)):
        return {"min": int(c), "max": int(c)}
    if isinstance(c, dict):
        return {"min": int(c.get("min", 1)), "max": int(c.get("max", 1))}
    return None


def apply_ops(data, ops):
    """把操作套到 data 的深拷贝上，返回 (新数据, 每一步的可读描述)。出错抛异常。"""
    d = json.loads(json.dumps(data))     # 深拷贝
    d.setdefault("pools", [])
    pools = d["pools"]
    notes = []

    def need_pool(i):
        if not isinstance(i, int) or i < 0 or i >= len(pools):
            raise RuntimeError(f"池序号 {i} 不存在（当前共 {len(pools)} 个池）")
        return pools[i]

    def entry_tacz_label(e):
        """条目的 TaCZ 子类型显示名（AmmoId/GunId 等 → 目录里的名字）"""
        for fn in e.get("functions") or []:
            if not isinstance(fn, dict):
                continue
            tag = fn.get("tag")
            if not isinstance(tag, str):
                continue
            for key in TACZ_NBT_KEYS:
                m = re.search(key + r':"([^"]+)"', tag)
                if m:
                    return m.group(1), tacz_names().get(m.group(1), "")
        return None, None

    def match_entries(pool, match):
        """按 name 模糊匹配池里的条目（先 ID 精确，再 ID 子串，再中文名，
        最后 TaCZ 子类型 id/中文名 —— AI 说「手枪子弹/pistol」时条目名是
        tacz:ammo，不 match 子类型就永远找不到，AI 只能清空重建）"""
        items = item_names()
        mid = resolve_item(match) or match
        mlow = str(match).strip().lower()
        hits = []
        for idx, e in enumerate(pool.get("entries") or []):
            nm = e.get("name") or ""
            if nm == mid:
                hits.append((idx, e))
        if not hits:
            for idx, e in enumerate(pool.get("entries") or []):
                nm = e.get("name") or ""
                if mid in nm:
                    hits.append((idx, e))
        if not hits:
            for idx, e in enumerate(pool.get("entries") or []):
                nm = items.get(e.get("name") or "", "")
                if match in nm:
                    hits.append((idx, e))
        if not hits:
            # TaCZ 子类型：id 子串 或 目录中文名包含
            for idx, e in enumerate(pool.get("entries") or []):
                sid, sname = entry_tacz_label(e)
                if sid and (mlow in sid.lower() or (sname and match in sname)):
                    hits.append((idx, e))
        return hits

    for o in ops:
        if not isinstance(o, dict):
            continue
        op = o.get("op")
        if op == "add_pool":
            pools.append({
                "name": o.get("name") or f"pool_{len(pools) + 1}",
                "rolls": o.get("rolls", 1),
                "entries": [],
            })
            notes.append(f"新建池「{pools[-1]['name']}」")
        elif op == "del_pool":
            i = o.get("pool")
            p = need_pool(i)
            notes.append(f"删除池「{p.get('name', i)}」")
            pools.pop(i)
        elif op == "rename_pool":
            need_pool(o["pool"])["name"] = o.get("name", "")
            notes.append(f"池{o['pool']}改名为「{o.get('name')}」")
        elif op == "set_rolls":
            r = o.get("rolls")
            if isinstance(r, (int, float)):
                r = int(r)
            elif isinstance(r, dict):
                r = {"min": int(r.get("min", 1)), "max": int(r.get("max", 1))}
            need_pool(o["pool"])["rolls"] = r
            notes.append(f"池{o['pool']}抽取次数改为 {r}")
        elif op == "add_entry":
            iid = resolve_item(o.get("item"))
            if not iid:
                raise RuntimeError(item_problem_msg(o.get("item")))
            e = {"type": "minecraft:item", "name": iid,
                 "weight": int(o.get("weight", 1))}
            c = _count_obj(o.get("count"))
            if c:
                e["functions"] = [{"function": "minecraft:set_count", "count": c}]
            if o.get("quality") is not None:
                e["quality"] = int(o["quality"])
            need_pool(o["pool"]).setdefault("entries", []).append(e)
            notes.append(f"加入「{iid}」权重{e['weight']}")
        elif op == "add_tacz":
            kind = o.get("kind")
            if kind not in TACZ_NBT_KEYS:
                raise RuntimeError(f"未知的 TaCZ 子类型 {kind}（可选：{', '.join(TACZ_NBT_KEYS)}）")
            base = {"GunId": "tacz:modern_kinetic_gun", "AttachmentId": "tacz:attachment",
                    "AmmoId": "tacz:ammo", "ThrowableId": "lrtactical:throwable",
                    "ConsumableId": "lrtactical:consumable"}[kind]
            sid = o.get("id")
            if not sid or ":" not in sid:
                raise RuntimeError(f"TaCZ 子类型 id 要完整（含命名空间），当前是 {sid!r}")
            e = {"type": "minecraft:item", "name": base,
                 "weight": int(o.get("weight", 1)),
                 "functions": [{"function": "minecraft:set_nbt",
                                "tag": "{%s:\"%s\"}" % (kind, sid)}]}
            c = _count_obj(o.get("count"))
            if c:
                e["functions"].append({"function": "minecraft:set_count", "count": c})
            need_pool(o["pool"]).setdefault("entries", []).append(e)
            notes.append(f"加入 {kind}「{sid}」")
        elif op == "remove_entry":
            p = need_pool(o["pool"])
            hits = match_entries(p, o.get("match", ""))
            if not hits:
                raise RuntimeError(f"池{o['pool']}里没找到「{o.get('match')}」")
            for idx, e in reversed(hits):
                p["entries"].pop(idx)
            notes.append(f"删除池{o['pool']}里 {len(hits)} 条「{o.get('match')}」")
        elif op == "set_weight":
            p = need_pool(o["pool"])
            hits = match_entries(p, o.get("match", ""))
            if not hits:
                raise RuntimeError(f"池{o['pool']}里没找到「{o.get('match')}」")
            for _, e in hits:
                e["weight"] = int(o.get("weight", 1))
            notes.append(f"池{o['pool']}里「{o.get('match')}」权重改为 {o.get('weight')}")
        elif op == "set_count":
            p = need_pool(o["pool"])
            hits = match_entries(p, o.get("match", ""))
            if not hits:
                raise RuntimeError(f"池{o['pool']}里没找到「{o.get('match')}」")
            c = _count_obj(o.get("count")) or {"min": 1, "max": 1}
            for _, e in hits:
                e["functions"] = [f for f in (e.get("functions") or [])
                                  if _norm(f.get("function")) != "minecraft:set_count"]
                e.setdefault("functions", []).append(
                    {"function": "minecraft:set_count", "count": c})
            notes.append(f"池{o['pool']}里「{o.get('match')}」数量改为 {c['min']}~{c['max']}")
        elif op == "clear_pool":
            p = need_pool(o["pool"])
            n = len(p.get("entries") or [])
            p["entries"] = []
            notes.append(f"清空池{o['pool']}（原 {n} 条）")
        elif op == "set_type":
            d["type"] = o.get("type", "minecraft:generic")
            notes.append(f"整表类型改为 {d['type']}")
        else:
            raise RuntimeError(f"不支持的操作 {op!r}")
    return d, notes


class Handler(BaseHTTPRequestHandler):
    server_version = "LootEditor/1.0"
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    # ---------- helpers ----------
    def _send(self, code, body: bytes, ctype="application/json; charset=utf-8", extra=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        # 缓存策略：
        #   - JSON / API：no-store（永远最新）
        #   - HTML / JS / CSS：no-cache（每次都重新校验，防止开发期改了看不到）
        #   - PNG 图标等二进制资源：可缓存一天（内容基本不变）
        if ctype.startswith("application/json"):
            cache = "no-store"
        elif ctype.startswith(("text/html", "application/javascript", "text/css", "text/javascript")):
            cache = "no-cache"
        else:
            cache = "public, max-age=86400"
        self.send_header("Cache-Control", cache)
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionAbortedError):
            pass

    def _json(self, data, code=200):
        self._send(code, json.dumps(data, ensure_ascii=False).encode("utf-8"))

    def _err(self, msg, code=400):
        self._json({"ok": False, "error": msg}, code)

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n <= 0:
            return {}
        raw = self.rfile.read(n)
        try:
            return json.loads(raw.decode("utf-8"))
        except Exception:
            return {}

    def _query(self):
        return parse_qs(urlparse(self.path).query)

    # ---------- GET ----------
    def do_GET(self):
        path = urlparse(self.path).path
        try:
            if path in ("/", "/index.html"):
                return self._static(WEB / "index.html")
            if path.startswith("/web/"):
                return self._static(WEB / path[5:])
            if path.startswith("/icons/"):
                return self._static(ICONS / path[7:])
            if path == "/api/meta":
                items_file = DATA / "items.json"
                n_items = 0
                if items_file.exists():
                    try:
                        n_items = len(json.loads(items_file.read_text(encoding="utf-8")).get("items", []))
                    except Exception:
                        pass
                return self._json({
                    "ok": True,
                    "root": str(ROOT),
                    "rootExists": ROOT.is_dir(),
                    "itemCount": n_items,
                    "files": len(list_files()),
                    "registry": registry_meta(),
                    "failedTables": sorted(registry()["failedTables"]),
                })
            if path == "/api/instances":
                # 探测当前整合包实例 + 列出所有候选战利品表目录
                inst = detect_instance(ROOT) or detect_instance(HERE)
                return self._json({
                    "ok": True,
                    "instance": str(inst) if inst else "",
                    "current": str(ROOT),
                    "dirs": find_loot_dirs(inst) if inst else [],
                    "hasMods": bool(inst and (inst / "mods").is_dir()),
                })
            if path == "/api/items":
                f = DATA / "items.json"
                if not f.exists():
                    return self._err("物品数据库尚未生成，请先点「重建物品库」", 503)
                try:
                    db = json.loads(f.read_text(encoding="utf-8"))
                except Exception:
                    db = {"items": []}
                # 附带上 modid -> 中文/英文模组名 的映射（scan_mod_names.py 生成），
                # 前端「全部模组」下拉靠它显示中文，不靠玩家背 modid
                mf = DATA / "mod_names.json"
                mods = {}
                if mf.exists():
                    try:
                        mods = json.loads(mf.read_text(encoding="utf-8"))
                    except Exception:
                        mods = {}
                db["modNames"] = mods
                return self._json(db)
            if path == "/api/tacz":
                f = DATA / "tacz.json"
                if not f.exists():
                    return self._json({"ok": True, "catalog": {}})
                return self._send(200, f.read_bytes())
            if path == "/api/registry":
                reg = registry()
                return self._json({"ok": True, "meta": reg["meta"],
                                   "validCount": len(reg["valid"] or ()),
                                   "invalidCount": len(reg["invalid"]),
                                   "invalid": dict(list(sorted(reg["invalid"].items()))[:400]),
                                   "failedTables": sorted(reg["failedTables"]),
                                   "logRejected": reg["logRejected"]})
            if path == "/api/list":
                return self._json({"ok": True, "root": str(ROOT), "files": list_files()})
            if path == "/api/file":
                rel = (self._query().get("path") or [""])[0]
                if not rel:
                    return self._err("缺少 path 参数")
                p = safe_path(rel)
                if not p.is_file():
                    return self._err("文件不存在", 404)
                raw = p.read_text(encoding="utf-8")
                # 检测 CRLF——Minecraft 的 JSON 解析器可能不认
                has_crlf = "\r\n" in raw
                try:
                    obj = json.loads(raw)
                except Exception as ex:
                    return self._json({"ok": False, "error": f"JSON 解析失败: {ex}", "raw": raw}, 200)
                rep = validate(obj)
                lid = loot_id(rel)
                failed_now = lid in registry()["failedTables"]
                if has_crlf:
                    rep["warnings"].insert(0, "⚠ 文件是 CRLF（\\r\\n）换行，Minecraft 可能解析失败。保存时会自动转成 LF。")
                return self._json({"ok": True, "path": rel, "data": obj,
                                   "report": rep, "raw": raw, "lootId": lid,
                                   "failedInGame": failed_now,
                                   "logCalibrated": registry_meta().get("logCalibrated", "")})
            if path == "/api/open":
                # 用系统默认编辑器打开这个源文件
                rel = (self._query().get("path") or [""])[0]
                p = safe_path(rel)
                if not p.is_file():
                    return self._err("文件不存在", 404)
                try:
                    if sys.platform == "win32":
                        os.startfile(str(p))
                    elif sys.platform == "darwin":
                        os.system(f'open "{p}"')
                    else:
                        os.system(f'xdg-open "{p}"')
                except Exception as ex:
                    return self._err(f"打开失败: {ex}")
                return self._json({"ok": True, "opened": str(p)})
            if path == "/api/audit":                return self._json({"ok": True, **self._audit()})
            if path == "/api/duplicates":
                return self._json({"ok": True, "groups": self._duplicates()})
            if path == "/api/find":
                q = (self._query().get("q") or [""])[0].strip().lower()
                if not q:
                    return self._err("缺少 q 参数")
                return self._json({"ok": True, "q": q, "hits": self._find(q)})
            if path == "/api/ai/config":
                return self._json({"ok": True, "config": masked_ai_config(), "mock": MOCK_LLM})
            return self._err("未知路径 " + path, 404)
        except ValueError as ex:
            return self._err(str(ex), 403)
        except Exception as ex:
            return self._err(f"服务端异常: {ex}", 500)

    # ---------- POST ----------
    def do_POST(self):
        path = urlparse(self.path).path
        try:
            if path == "/api/validate":
                body = self._body()
                return self._json({"ok": True, "report": validate(body.get("data"))})
            if path == "/api/file":
                rel = (self._query().get("path") or [""])[0]
                if not rel:
                    return self._err("缺少 path 参数")
                body = self._body()
                data = body.get("data")
                rep = validate(data)
                if rep["errors"] and not body.get("force"):
                    return self._json({"ok": False, "error": "校验未通过，已拒绝写入",
                                       "report": rep}, 200)
                p = safe_path(rel)
                p.parent.mkdir(parents=True, exist_ok=True)
                make_backup(rel, p)          # 保存前留备份（自动轮转，不会无限增长）
                atomic_write(p, dumps_loot(data) + "\n")
                return self._json({"ok": True, "path": rel, "report": rep})
            if path == "/api/new":
                body = self._body()
                rel = (body.get("path") or "").strip()
                if not rel:
                    return self._err("缺少 path")
                if not rel.endswith(".json"):
                    rel += ".json"
                p = safe_path(rel)
                if p.exists():
                    return self._err("文件已存在")
                p.parent.mkdir(parents=True, exist_ok=True)
                tpl = body.get("data") or {"type": "minecraft:chest",
                                           "pools": [{"rolls": 1, "entries": []}]}
                atomic_write(p, dumps_loot(tpl) + "\n")
                return self._json({"ok": True, "path": rel})
            if path == "/api/rename":
                body = self._body()
                src = (body.get("from") or "").strip()
                dst = (body.get("to") or "").strip()
                if not src or not dst:
                    return self._err("缺少 from / to")
                if not dst.endswith(".json"):
                    dst += ".json"
                sp, dp = safe_path(src), safe_path(dst)
                if not sp.is_file():
                    return self._err("源文件不存在", 404)
                if dp.exists():
                    return self._err("目标已存在")
                dp.parent.mkdir(parents=True, exist_ok=True)
                sp.rename(dp)
                return self._json({"ok": True, "path": str(dp.relative_to(ROOT)).replace("\\", "/")})
            if path == "/api/delete":
                body = self._body()
                rel = (body.get("path") or "").strip()
                p = safe_path(rel)
                if not p.is_file():
                    return self._err("文件不存在", 404)
                dst = TRASH / (time.strftime("%Y%m%d-%H%M%S") + "__" + rel.replace("/", "__"))
                dst.parent.mkdir(parents=True, exist_ok=True)
                shutil.move(str(p), str(dst))
                prune_trash()
                return self._json({"ok": True, "movedTo": str(dst)})

            if path == "/api/setroot":
                body = self._body()
                path_in = (body.get("path") or "").strip()
                if not path_in:
                    return self._err("缺少 path", 400)
                try:
                    newroot = set_root(path_in)
                except ValueError as e:
                    return self._err(str(e), 400)
                return self._json({"ok": True, "root": str(newroot), "files": len(list_files())})

            if path == "/api/scan":
                # 扫描整合包重建物品库（通用化：任意实例都能用）
                body = self._body()
                inst = (body.get("instance") or "").strip()
                # 冻结 exe：子脚本已内嵌，用 --run-script 参数调用自己；
                # 源码运行：照常 python scan_items.py
                if getattr(sys, "frozen", False):
                    cmd = [sys.executable, "--run-script", "scan_items"]
                else:
                    cmd = [sys.executable, str(HERE / "scan_items.py")]
                if inst:
                    cmd += ["--instance", inst]
                if body.get("fresh"):
                    cmd.append("--fresh")
                if body.get("noIcons"):
                    cmd.append("--no-icons")
                try:
                    p = subprocess.run(cmd, capture_output=True, text=True, timeout=1800,
                                       encoding="utf-8", errors="replace")
                except Exception as e:
                    return self._err(f"扫描失败：{e}", 500)
                # 扫完必须再跑一次严格注册表判定：光靠资产文件猜出来的「物品」
                # 会把不存在的 ID 也当成真的（这就是游戏里整表加载失败的根源）
                reg_ok, reg_log = True, ""
                try:
                    if getattr(sys, "frozen", False):
                        cmd2 = [sys.executable, "--run-script", "build_registry"]
                    else:
                        cmd2 = [sys.executable, str(HERE / "build_registry.py")]
                    if inst:
                        cmd2 += ["--instance", inst]
                    p2 = subprocess.run(cmd2, capture_output=True, text=True, timeout=1800,
                                        encoding="utf-8", errors="replace")
                    reg_ok = p2.returncode == 0
                    reg_log = (p2.stdout or "") + (("\n[stderr]\n" + p2.stderr) if p2.stderr else "")
                except Exception as e:
                    reg_ok, reg_log = False, f"注册表判定失败：{e}"
                # 清掉全部物品库相关缓存，让后续请求重新读（漏一个就会「重建了没用」）
                clear_all_caches()
                log = (p.stdout or "") + (("\n[stderr]\n" + p.stderr) if p.stderr else "")
                log += "\n\n===== 严格注册表判定 =====\n" + reg_log
                return self._json({"ok": p.returncode == 0 and reg_ok,
                                   "code": p.returncode,
                                   "log": log[-8000:]})

            if path == "/api/ai/config":
                body = self._body()
                cfg = save_ai_config(body)
                return self._json({"ok": True, "config": masked_ai_config()})
            if path == "/api/ai/test":
                body = self._body()
                cfg = {**load_ai_config(), **{k: v for k, v in body.items() if k in DEFAULT_AI_CFG}}
                if not cfg.get("api_key") and not MOCK_LLM:
                    return self._err("还没填 API Key", 400)
                try:
                    out = call_llm([{"role": "user", "content": "只回两个字：在线"}], cfg)
                except Exception as e:
                    return self._json({"ok": False, "error": str(e)}, 200)
                return self._json({"ok": True, "reply": out[:80]})
            if path == "/api/ai/models":
                # 拉取该接口可用的模型列表，用来验证 key 通不通、模型名对不对
                body = self._body()
                cfg = {**load_ai_config(), **{k: v for k, v in body.items() if k in DEFAULT_AI_CFG}}
                if MOCK_LLM:
                    return self._json({"ok": True, "models": ["mock-model"], "mock": True})
                if not cfg.get("api_key"):
                    return self._err("还没填 API Key", 400)
                url = cfg.get("base_url", "").rstrip("/") + "/models"
                handlers = []
                if cfg.get("use_proxy"):
                    proxy = os.environ.get("https_proxy") or os.environ.get("http_proxy") or "http://127.0.0.1:63701"
                    handlers.append(urllib.request.ProxyHandler({"http": proxy, "https": proxy}))
                opener = urllib.request.build_opener(*handlers)
                reqm = urllib.request.Request(url, headers={"Authorization": "Bearer " + cfg["api_key"]})
                try:
                    with opener.open(reqm, timeout=30) as r:
                        resp = json.loads(r.read().decode("utf-8"))
                    models = sorted([m.get("id") for m in resp.get("data", []) if m.get("id")])
                    return self._json({"ok": True, "models": models, "count": len(models)})
                except urllib.error.HTTPError as e:
                    b = e.read().decode("utf-8", errors="ignore")[:200]
                    if e.code in (401, 403):
                        return self._json({"ok": False, "error": f"认证失败（HTTP {e.code}）：Key 无效/过期/欠费，或接口地址填错。原始返回：{b}"}, 200)
                    return self._json({"ok": False, "error": f"HTTP {e.code}：{b}"}, 200)
                except Exception as e:
                    return self._json({"ok": False, "error": f"连不上接口：{e}"}, 200)
            if path == "/api/chat":
                body = self._body()
                rel = (body.get("path") or "").strip()
                msg = (body.get("message") or "").strip()
                if not msg:
                    return self._err("消息为空", 400)
                cfg = load_ai_config()
                if not cfg.get("api_key") and not MOCK_LLM:
                    return self._json({"ok": False, "need_setup": True,
                                       "error": "还没配置 AI。点右上角「AI 设置」填一个 OpenAI 兼容接口的地址、Key 和模型名。"}, 200)
                # 当前文件内容（可选；给了才能针对当前表编辑）
                cur_data = None
                if rel:
                    try:
                        cur_data = json.loads(safe_path(rel).read_text(encoding="utf-8"))
                    except Exception:
                        cur_data = None
                sysmsg = build_system_prompt(rel or "(未打开文件)", cur_data or {})
                # 把多轮历史接进去
                history = body.get("history") or []
                messages = [{"role": "system", "content": sysmsg}]
                for m in history[-12:]:
                    if isinstance(m, dict) and m.get("role") in ("user", "assistant"):
                        messages.append({"role": m["role"], "content": str(m.get("content", ""))[:4000]})
                messages.append({"role": "user", "content": msg})

                # 最多试 2 次：第一次失败就把错误塞回去让 AI 自己纠正
                last_err = None
                obj = None
                raw = None
                for attempt in range(2):
                    try:
                        raw = call_llm(messages, cfg)
                        obj = parse_llm_json(raw)
                    except RuntimeError as e:
                        # 网络/认证类错误重试没意义，直接报；解析类错误才值得重试
                        msg_err = str(e)
                        if not msg_err.startswith("LLM 输出的 JSON"):
                            return self._json({"ok": False, "error": msg_err}, 200)
                        last_err = msg_err
                        obj = None
                    except Exception as e:
                        return self._json({"ok": False, "error": f"服务端异常: {e}"}, 200)

                    if obj is None:
                        # JSON 没解析出来 → 让 AI 重做一次
                        if attempt == 0:
                            messages.append({"role": "assistant", "content": (raw or "")[:2000]})
                            messages.append({"role": "user", "content":
                                f"你刚才的输出不是合法 JSON：{last_err}。"
                                "重新只输出一个合法的 JSON 对象，不要用 ``` 围栏，"
                                "字符串用双引号，最后一个元素后面不要留逗号。"})
                            continue
                        break

                    reply = obj.get("reply") or "（无说明）"
                    # 整表替换
                    if isinstance(obj.get("replace_data"), dict):
                        new_data = obj["replace_data"]
                        rep = validate(new_data)
                        return self._json({"ok": True, "reply": reply, "mode": "replace",
                                           "preview": new_data, "report": rep,
                                           "ops": [{"op": "replace_data"}]})
                    ops = obj.get("ops")
                    if not isinstance(ops, list) or not ops:
                        # 没有操作 —— 这是一次纯问答（用户只是提问），直接把回答给他，
                        # 不要当成错误。前端按 mode='answer' 渲染，不给「应用」按钮。
                        if attempt == 0 and not reply:
                            last_err = "AI 既没给操作也没给回答"
                            messages.append({"role": "assistant", "content": (raw or "")[:2000]})
                            messages.append({"role": "user", "content":
                                "你既没有输出 ops 也没有输出 reply。请重新输出一个合法 JSON 对象。"})
                            continue
                        return self._json({"ok": True, "reply": reply, "mode": "answer",
                                           "ops": [], "report": {"errors": [], "warnings": []}})
                    if cur_data is None:
                        return self._json({"ok": False, "error": "先在左边打开一个池文件，AI 才能改它"}, 200)
                    try:
                        new_data, notes = apply_ops(cur_data, ops)
                        rep = validate(new_data)
                        return self._json({"ok": True, "reply": reply, "mode": "ops",
                                           "ops": ops, "notes": notes,
                                           "preview": new_data, "report": rep})
                    except Exception as e:
                        last_err = str(e)
                    # 操作落地失败 → 把原因告诉 AI，让它重试一次
                    if attempt == 0:
                        messages.append({"role": "assistant", "content": (raw or "")[:2000]})
                        messages.append({"role": "user", "content":
                            f"你刚才的输出有问题：{last_err}。重新只输出一个合法的 JSON 对象，"
                            "不要再犯同样的错。物品名要用库里真实存在的中文名或完整 ID，"
                            "TaCZ 子类型的 id 必须从我给你的目录里挑。"})

                return self._json({"ok": False,
                                   "error": f"AI 连试两次都没成：{last_err}",
                                   "raw": ((raw or "")[:600])}, 200)
            return self._err("未知路径 " + path, 404)
        except ValueError as ex:
            return self._err(str(ex), 403)
        except Exception as ex:
            return self._err(f"服务端异常: {ex}", 500)

    # ---------- static ----------
    def _static(self, p: Path):
        p = p.resolve()
        base = HERE.resolve()
        if base not in p.parents and p != base:
            return self._err("路径越界", 403)
        if not p.is_file():
            return self._err("未找到 " + p.name, 404)
        ctype = {
            ".html": "text/html; charset=utf-8",
            ".js": "application/javascript; charset=utf-8",
            ".css": "text/css; charset=utf-8",
            ".json": "application/json; charset=utf-8",
            ".png": "image/png",
            ".svg": "image/svg+xml",
            ".ico": "image/x-icon",
        }.get(p.suffix.lower(), "application/octet-stream")
        self._send(200, p.read_bytes(), ctype)

    # ---------- 分析 ----------
    def _audit(self):
        problems, total_err, total_warn = [], 0, 0
        for f in sorted(ROOT.rglob("*.json")):
            rel = str(f.relative_to(ROOT)).replace("\\", "/")
            try:
                obj = json.loads(f.read_text(encoding="utf-8"))
            except Exception as ex:
                problems.append({"path": rel, "errors": [f"JSON 解析失败: {ex}"], "warnings": []})
                total_err += 1
                continue
            rep = validate(obj)
            if rep["errors"] or rep["warnings"]:
                problems.append({"path": rel, **rep})
                total_err += len(rep["errors"])
                total_warn += len(rep["warnings"])
        return {"problems": problems, "errorCount": total_err, "warningCount": total_warn,
                "scanned": len(list(ROOT.rglob("*.json")))}

    def _duplicates(self):
        buckets = {}
        for f in sorted(ROOT.rglob("*.json")):
            rel = str(f.relative_to(ROOT)).replace("\\", "/")
            try:
                obj = json.loads(f.read_text(encoding="utf-8"))
            except Exception:
                continue
            key = json.dumps(obj, ensure_ascii=False, sort_keys=True)
            buckets.setdefault(key, []).append(rel)
        groups = [{"files": v, "count": len(v)} for v in buckets.values() if len(v) > 1]
        groups.sort(key=lambda g: -g["count"])
        return groups

    def _find(self, q):
        """反查：哪些池文件引用了名字（ID 或中文名）里含 q 的条目"""
        hits = []
        names = item_names()

        def walk(rel, entries, pool_i, idxs):
            for i, e in enumerate(entries):
                if not isinstance(e, dict):
                    continue
                nm = e.get("name") or e.get("value") or ""
                if not isinstance(nm, str) or not nm:
                    continue
                zh = names.get(nm, "")
                if q in nm.lower() or (zh and q in zh.lower()):
                    hits.append({
                        "path": rel,
                        "pool": pool_i,
                        "index": idxs + [i],
                        "type": _norm(e.get("type")),
                        "name": nm,
                        "zh": zh,
                        "weight": e.get("weight", 1),
                    })
                for sub in ("children", "entries"):
                    if isinstance(e.get(sub), list):
                        walk(rel, e[sub], pool_i, idxs + [i])

        for f in sorted(ROOT.rglob("*.json")):
            rel = str(f.relative_to(ROOT)).replace("\\", "/")
            try:
                obj = json.loads(f.read_text(encoding="utf-8"))
            except Exception:
                continue
            for pi, p in enumerate(obj.get("pools") or []):
                if isinstance(p, dict):
                    walk(rel, p.get("entries") or [], pi, [])
        hits.sort(key=lambda h: (h["path"], h["pool"], h["index"]))
        return hits


def main():
    global ROOT, PORT, MOCK_LLM
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=None,
                    help="战利品表目录；不填就自动探测当前整合包")
    ap.add_argument("--port", type=int, default=8787)
    ap.add_argument("--no-browser", action="store_true")
    ap.add_argument("--mock-llm", action="store_true",
                    help="离线模拟 AI（不需要 API Key），用于验证对话链路")
    args = ap.parse_args()
    ROOT = Path(args.root) if args.root else pick_default_root()
    PORT = args.port
    MOCK_LLM = args.mock_llm

    print("=" * 62)
    print("  CAF 战利品池可视化编辑器")
    print("=" * 62)
    print(f"  池文件目录 : {ROOT}  {'[存在]' if ROOT.is_dir() else '[不存在!]'}")
    print(f"  物品数据库 : {'已就绪' if (DATA / 'items.json').exists() else '未生成（先跑 build_item_db.py）'}")
    print(f"  本地地址   : http://127.0.0.1:{PORT}")
    print("=" * 62)
    print("  按 Ctrl+C 停止")
    print()

    httpd = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    if not args.no_browser:
        threading.Timer(0.8, lambda: webbrowser.open(f"http://127.0.0.1:{PORT}")).start()
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止。")
    finally:
        httpd.server_close()


if __name__ == "__main__":
    main()

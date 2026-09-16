"""扫描 mods/*.jar，建立「命名空间 → MOD 中文名」映射。

中文名来源优先级：
  1. jar 文件名的 [中文名] 前缀（用户自己整理的，最直观）
  2. jar 内 assets/<ns>/lang/zh_cn.json 里的 itemGroup / 模组名键
  3. META-INF/mods.toml 的 displayName（多为英文，兜底）

输出 data/mod_names.json：
  { "tacz": {"zh": "TaCZ", "en": "Timeless and Classics Zero", "jar": "..."} }
"""
import zipfile
import re
import json
import pathlib
import sys
import tomllib

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent                       # 整合包根目录
MODS = ROOT / "mods"
OUT = HERE / "data" / "mod_names.json"

# 少数没有 mod jar 的命名空间，手工补
MANUAL = {
    "minecraft": "Minecraft 原版",
    "forge": "Forge 加载器",
    "kubejs": "KubeJS",
    "c": "通用标签（c:）",
    "caf": "CAF 齿轮与腐肉",
    "chaosz": "CAF 齿轮与腐肉",
    "tacz": "TaCZ 现代枪械",
    "lrtactical": "TaCZ 战术扩展",
    "curios": "Curios 饰品栏",
    "lootr": "Lootr 独立宝箱",
}


def jar_cn_name(filename):
    """从 jar 文件名提取 [中文名] 前缀"""
    m = re.match(r"^\[([^\]]+)\]", filename)
    if m:
        return m.group(1).strip()
    return None


def read_toml_mods(z):
    """读 mods.toml，返回 [(modId, displayName), ...]"""
    out = []
    for name in z.namelist():
        low = name.lower()
        if low.endswith("mods.toml") and "/" in name:
            try:
                txt = z.read(name).decode("utf-8", "ignore")
            except Exception:
                continue
            try:
                data = tomllib.loads(txt)
            except Exception:
                # toml 里有 ${file.jarVersion} 之类占位符，tomllib 可能嫌它不合法——退化成正则
                ids = re.findall(r'modId\s*=\s*"([^"]+)"', txt)
                names = re.findall(r'displayName\s*=\s*"([^"]*)"', txt)
                for i, mid in enumerate(ids):
                    out.append((mid, names[i] if i < len(names) else ""))
                continue
            mods = data.get("mods") or []
            if isinstance(mods, dict):
                mods = [mods]
            for m in mods:
                if isinstance(m, dict) and m.get("modId"):
                    out.append((m["modId"], m.get("displayName") or ""))
    return out


def read_zh_lang(z, ns):
    """从 assets/<ns>/lang/zh_cn.json 里找模组的显示名"""
    cands = [f"assets/{ns}/lang/zh_cn.json"]
    for name in z.namelist():
        low = name.lower()
        if low.endswith("lang/zh_cn.json") and ns in low:
            cands.append(name)
    for c in dict.fromkeys(cands):
        if c not in z.namelist():
            continue
        try:
            d = json.loads(z.read(c).decode("utf-8", "ignore"))
        except Exception:
            continue
        if not isinstance(d, dict):
            continue
        # 常见键：模组名写在 itemGroup 里，或用 mod.<ns> 之类
        for key in (f"itemGroup.{ns}", f"mod.{ns}", f"mod.{ns}.name",
                    "itemGroup.tab." + ns, f"block.{ns}.{ns}"):
            if isinstance(d.get(key), str) and d[key].strip():
                return d[key].strip()
    return None


def main():
    if not MODS.is_dir():
        print(f"[错误] 找不到 mods 目录：{MODS}")
        return 1
    jars = sorted([p for p in MODS.iterdir() if p.suffix.lower() == ".jar"])
    print(f"扫描 {len(jars)} 个 jar…")

    result = {}
    problems = []
    for i, jar in enumerate(jars, 1):
        try:
            with zipfile.ZipFile(jar) as z:
                mods = read_toml_mods(z)
                if not mods:
                    continue
                cn = jar_cn_name(jar.name)
                for mid, disp in mods:
                    lang_zh = read_zh_lang(z, mid)
                    # 中文名优先级：文件名前缀 > lang 文件 > 无
                    zh = cn or lang_zh
                    en = disp or mid
                    if mid in result:
                        # 已存在：只在新的更好时覆盖
                        old = result[mid]
                        if not old.get("zh") and zh:
                            old["zh"] = zh
                            old["jar"] = jar.name
                        continue
                    result[mid] = {"zh": zh, "en": en,
                                   "jar": jar.name,
                                   "from": "文件名" if cn else ("lang" if lang_zh else "无")}
        except Exception as e:
            problems.append(f"{jar.name}: {e}")
        if i % 50 == 0:
            print(f"  …{i}/{len(jars)}")

    # 手工补充（只在缺失时加）
    for ns, zh in MANUAL.items():
        if ns not in result:
            result[ns] = {"zh": zh, "en": ns, "jar": None, "from": "手工"}

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding="utf-8")

    have = sum(1 for v in result.values() if v.get("zh"))
    print(f"\n完成：{len(result)} 个 modId，其中 {have} 个有中文名")
    print(f"输出：{OUT}")
    missing = [k for k, v in result.items() if not v.get("zh")]
    if missing:
        print(f"无中文名（{len(missing)}）：{missing[:20]}")
    if problems:
        print(f"\n跳过 {len(problems)} 个：")
        for p in problems[:10]:
            print("  " + p)
    return 0


if __name__ == "__main__":
    sys.exit(main())

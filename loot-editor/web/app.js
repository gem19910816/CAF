/* ============================================================
   CAF 战利品池可视化编辑器
   ============================================================ */
'use strict';

/* ---------------- 状态 ---------------- */
const S = {
  root: '',
  files: [],
  itemMap: {},       // "ns:id" -> {id,zh,en,icon,ns,path}
  tacz: {},          // NBT 键 -> {base,label,items:[{id,name,pack}],missing:[]}
  modNames: {},      // modid -> {zh,en,jar}（scan_mod_names.py 生成）
  taczIdx: {},       // "NBT键|子类型ID" -> {id,name,pack}
  cur: null,         // {path, data, report, dirty}
  open: new Set(),   // 展开详情的条目 "p:e"
  sel: new Set(),    // 选中的条目 "p:e"
  showAll: {},       // 池索引 -> 是否显示全部条目
  filter: '',
  poolOpen: {},      // 池索引 -> 是否折叠
  deleted: [],       // 最近删除栈：{path,pool,index,entry,label,when}
  chestCache: null,  // 开箱预览缓存 {path, items, hadChance}，只在「重新抽取」/换文件时重roll
  poolSearch: {},    // 池索引 -> 条目搜索关键词
  undo: [],          // 撤销栈：{path, json, data}
  redo: [],          // 重做栈
  undoSnap: null,    // 当前已记录的状态快照
  undoAt: 0,         // 上次记录时间（用于合并连续输入）
};

const PAGE = 80;     // 每个池默认最多显示多少条

/* ---------------- 小工具 ---------------- */
const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => Array.from(r.querySelectorAll(s));
const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) =>
  ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const norm = (rid) => (typeof rid === 'string' && rid.indexOf(':') < 0 ? 'minecraft:' + rid : rid);
// 资源位置格式：大写 / 空格 / 中文都会让游戏解析失败（整张表加载不了）
const RID_NS = /^[a-z0-9_.-]+$/;
const RID_PATH = /^[a-z0-9_/.-]+$/;
function ridProblem(v) {
  if (typeof v !== 'string' || !v) return '必须是字符串';
  const s = v.startsWith('#') ? v.slice(1) : v;
  if (s.includes(':')) {
    const [ns, ...rest] = s.split(':');
    const path = rest.join(':');
    if (!ns) return '命名空间为空';
    if (!path) return '冒号后面是空的';
    if (!RID_NS.test(ns)) return `命名空间含非法字符（${ns}）—— 只能用小写字母、数字、_ . -`;
    if (!RID_PATH.test(path)) return `路径含非法字符（${path}）—— 只能用小写字母、数字、_ / . -`;
    return '';
  }
  if (!RID_PATH.test(s)) return `含非法字符（${v}）—— 只能用小写字母、数字、_ / . -`;
  return '';   // 缺命名空间由后端给提示，这里不拦
}
const num = (v, d = 0) => (typeof v === 'number' && isFinite(v) ? v : d);
const pct = (v) => (v * 100 >= 10 ? (v * 100).toFixed(1) : (v * 100).toFixed(2)) + '%';

function deepClone(o) { return JSON.parse(JSON.stringify(o)); }

/* ---------------- 记住上次状态（localStorage） ---------------- */
const LS_LAST = 'caf_loot_last_file';
const LS_RECENT = 'caf_loot_recent_items';

function rememberLastFile(path) {
  try { localStorage.setItem(LS_LAST, path); } catch (e) { /* 无痕模式等，忽略 */ }
}
function lastFile() {
  try { return localStorage.getItem(LS_LAST) || ''; } catch (e) { return ''; }
}
function rememberItem(id) {
  try {
    const arr = recentItems().filter((x) => x !== id);
    arr.unshift(id);
    localStorage.setItem(LS_RECENT, JSON.stringify(arr.slice(0, 24)));
  } catch (e) { /* 忽略 */ }
}
function recentItems() {
  try { return JSON.parse(localStorage.getItem(LS_RECENT) || '[]'); } catch (e) { return []; }
}

function toast(msg, kind = '') {
  const box = $('#toasts');
  const d = document.createElement('div');
  d.className = 'toast ' + kind;
  d.textContent = msg;
  box.appendChild(d);
  setTimeout(() => { d.style.opacity = '0'; d.style.transition = '.25s'; }, 2400);
  setTimeout(() => d.remove(), 2750);
}

async function api(path, opts) {
  const r = await fetch(path, opts);
  const txt = await r.text();
  let data;
  try { data = JSON.parse(txt); } catch (e) { throw new Error('服务端返回了非 JSON 内容：' + txt.slice(0, 160)); }
  if (!r.ok) throw new Error(data.error || ('HTTP ' + r.status));
  return data;
}
const post = (p, body) => api(p, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(body || {}),
});

/* ---------------- 物品字典 ---------------- */
function itemInfo(id) {
  const k = String(id || '');
  if (S.itemMap[k]) return S.itemMap[k];
  if (k.startsWith('#')) return { id: k, zh: '标签 ' + k.slice(1), en: '', icon: null, tag: true };
  return { id: k, zh: '', en: '', icon: null };
}
function itemLabel(id) {
  const it = itemInfo(id);
  return it.zh || it.en || String(id || '');
}

/* 模组的中文/友好名：「沉浸农艺 (farm_and_charm)」—— 没扫到名字就显示原始 modid */
function modLabel(ns) {
  const m = S.modNames[ns];
  if (!m) return ns;
  const name = m.zh || m.en || '';
  if (!name || name === ns) return ns;
  return `${name} (${ns})`;
}

/* 这个物品 ID 在游戏里到底存不存在？
   返回 null = 确认存在；返回字符串 = 不存在的原因。
   判定来自 data/registry.json（build_registry.py 按官方注册表规则生成，
   并用游戏日志校准过）——不是「物品库里有就放行」那种自嗨。
   Minecraft 只要遇到一个不存在的 ID，整张战利品表都会加载失败，所以这里必须严格。 */
function itemBad(id) {
  const k = String(id || '');
  if (!k || k.startsWith('#')) return null;      // tag 条目另算，不是物品 ID
  const info = S.itemMap[k];
  if (info) {
    if (info.valid === false) return info.why || '判定为游戏里不存在';
    if (info.valid === undefined && info.active === false) return `物品来自已禁用的模组（${info.ns}）`;
    return null;
  }
  return '不在物品库里 —— 游戏里多半不存在（新装了模组就点「重建物品库」再确认）';
}
function itemExists(id) { return !itemBad(id); }
function validItemList() {
  return Object.values(S.itemMap).filter((it) => it.valid !== false && it.active !== false);
}
function iconUrl(id) {
  const it = itemInfo(id);
  return it.icon ? '/' + it.icon : null;
}
function iconTag(id, size) {
  const u = iconUrl(id);
  const st = size ? `width:${size}px;height:${size}px` : '';
  if (u) return `<img class="entry-icon" style="${st}" src="${esc(u)}" loading="lazy" onerror="this.style.visibility='hidden'">`;
  const it = itemInfo(id);
  const src = it.zh || it.en || it.id || '?';
  const ch = (src.replace(/[^\u4e00-\u9fa5a-zA-Z0-9]/g, '').charAt(0) || '?');
  return `<div class="entry-icon" style="${st};display:grid;place-items:center;color:#8b98ad;font-size:11px;font-weight:700;background:#eef1f6">${esc(ch)}</div>`;
}

/* 条目图标：TaCZ 子类型用它自己的 slot 贴图，否则用基物品的图标 */
function entryIconTag(e, size) {
  const t = taczOf(e);
  if (t) {
    const info = S.taczIdx[t.key + '|' + t.id];
    if (info && info.icon) {
      const st = size ? `width:${size}px;height:${size}px` : '';
      return `<img class="entry-icon" style="${st}" src="/${esc(info.icon)}" loading="lazy" onerror="this.style.visibility='hidden'">`;
    }
  }
  return iconTag(e && (e.name || e.value), size);
}

/* ---------------- TaCZ 子类型识别 ----------------
   tacz:ammo / tacz:attachment / tacz:modern_kinetic_gun / lrtactical:throwable
   / lrtactical:consumable 这些物品本身没有区分度，靠 set_nbt 里的
   AmmoId / AttachmentId / GunId / ThrowableId / ConsumableId 决定实际内容。
--------------------------------------------------- */
const TACZ_KEYS = ['GunId', 'AttachmentId', 'AmmoId', 'ThrowableId', 'ConsumableId'];

function taczOf(e) {
  const fns = (e && e.functions) || [];
  for (const f of fns) {
    if (norm(f.function) !== 'minecraft:set_nbt' || typeof f.tag !== 'string') continue;
    for (const k of TACZ_KEYS) {
      const m = f.tag.match(new RegExp(k + ':"([^"]+)"'));
      if (!m) continue;
      const info = S.taczIdx[k + '|' + m[1]];
      return {
        key: k,
        id: m[1],
        name: (info && info.name) || m[1],
        pack: info ? info.pack : '',
        known: !!info,
      };
    }
  }
  return null;
}

/* ---------------- 网络音乐唱片：从 NBT 里取歌名/歌手 ---------------- */
function songOf(e) {
  const fns = (e && e.functions) || [];
  for (const f of fns) {
    if (norm(f.function) !== 'minecraft:set_nbt' || typeof f.tag !== 'string') continue;
    if (f.tag.indexOf('NetMusicSongInfo') < 0) continue;
    const nm = f.tag.match(/name:"([^"]*)"/);
    const ar = f.tag.match(/artists:\[([^\]]*)\]/);
    const artists = ar
      ? (ar[1].match(/"([^"]*)"/g) || []).map((s) => s.slice(1, -1)).join(' / ')
      : '';
    if (!nm && !artists) continue;
    return { name: nm ? nm[1] : '', artists };
  }
  return null;
}

function entryExtra(e) {
  const parts = [];
  const t = taczOf(e);
  if (t) parts.push({ kind: t.known ? 'tacz' : 'tacz-missing', text: t.name, sub: t.id });
  const s = songOf(e);
  if (s) parts.push({ kind: 'song', text: s.name || '（未命名）', sub: s.artists });
  return parts;
}

/* 找出这个物品属于哪个 TaCZ 分类（基物品匹配） */
function taczCatOf(name) {
  const n = norm(name);
  return Object.entries(S.tacz || {}).find(([, d]) => d.base === n) || null;
}

/* 就地改写 set_nbt 里的某个子类型字段，保留其它 NBT 内容 */
function setTaczSub(e, key, id) {
  e.functions = e.functions || [];
  let f = e.functions.find((x) => norm(x.function) === 'minecraft:set_nbt'
    && typeof x.tag === 'string' && x.tag.indexOf('NetMusicSongInfo') < 0);
  if (!f) {
    f = { function: 'minecraft:set_nbt', tag: '{}' };
    e.functions.push(f);
  }
  let inner = String(f.tag).replace(/^\{/, '').replace(/\}$/, '').trim();
  const re = new RegExp('(^|,)\\s*' + key + ':"[^"]*"');
  if (re.test(inner)) {
    inner = inner.replace(re, (m, p1) => (p1 === ',' ? ',' : '') + key + ':"' + id + '"');
  } else {
    inner = inner ? inner + ',' + key + ':"' + id + '"' : key + ':"' + id + '"';
  }
  f.tag = '{' + inner + '}';
}

/* 从 set_nbt 里摘掉某个子类型字段；整条 set_nbt 变空则删掉该函数 */
function removeTaczSub(e, key) {
  if (!Array.isArray(e.functions)) return;
  e.functions.forEach((f) => {
    if (norm(f.function) !== 'minecraft:set_nbt' || typeof f.tag !== 'string') return;
    if (f.tag.indexOf('NetMusicSongInfo') >= 0) return;
    let inner = f.tag.replace(/^\{/, '').replace(/\}$/, '').trim();
    const re = new RegExp('(^|,)\\s*' + key + ':"[^"]*"');
    inner = inner.replace(re, (m, p1) => (p1 === ',' ? '' : ''));
    inner = inner.replace(/^\s*,/, '').replace(/,\s*$/, '').replace(/,\s*,/g, ',');
    f.tag = '{' + inner + '}';
  });
  e.functions = e.functions.filter((f) =>
    !(norm(f.function) === 'minecraft:set_nbt' && String(f.tag).trim() === '{}'));
}

/* 条目详情里的子类型下拉行 */
function taczBaseRow(e, pi, ei) {
  const cat = taczCatOf(e.name);
  if (!cat) return '';
  const [nbt, d] = cat;
  const cur = taczOf(e);
  const items = d.items || [];
  return `<div class="row">
    <span class="lbl">${esc(d.label || nbt)}</span>
    <select class="txt" style="flex:1 1 320px" data-act="taczSub" data-pi="${pi}" data-ei="${ei}" data-nbt="${esc(nbt)}">
      <option value="">（未指定 —— 游戏里会是一个空白物品）</option>
      ${items.map((it) => `<option value="${esc(it.id)}" ${cur && cur.id === it.id ? 'selected' : ''}>${esc(it.name)}  —  ${esc(it.id)}</option>`).join('')}
    </select>
    <span class="hint">共 ${items.length} 种可选${cur && !cur.known ? ' · 当前值不在已安装枪包里' : ''}</span>
  </div>`;
}

/* ---------------- 函数 / 条件 定义表 ---------------- */
const FUNC_DEFS = {
  'minecraft:set_count': { label: '设置数量', f: [['count', 'range']] },
  'minecraft:set_nbt': { label: '写入 NBT', f: [['tag', 'nbt']] },
  'minecraft:set_damage': { label: '设置耐久', f: [['damage', 'ratio']] },
  'minecraft:set_name': { label: '设置名称', f: [['name', 'text']] },
  'minecraft:set_lore': { label: '设置描述文字', f: [['lore', 'json']] },
  'minecraft:enchant_randomly': { label: '随机附魔', f: [['enchantments', 'list'], ['only_compatible', 'bool']] },
  'minecraft:enchant_with_levels': { label: '按等级附魔', f: [['levels', 'range'], ['treasure', 'bool']] },
  'minecraft:looting_enchant': { label: '抢夺加成数量', f: [['count', 'range']] },
  'minecraft:set_potion': { label: '设置药水', f: [['id', 'text']] },
  'minecraft:set_stew_effect': { label: '设置迷之炖菜效果', f: [['effects', 'json']] },
  'minecraft:exploration_map': { label: '生成探险地图', f: [['destination', 'text'], ['decoration', 'text'], ['zoom', 'number'], ['search_radius', 'number'], ['skip_existing_chunks', 'bool']] },
  'minecraft:furnace_smelt': { label: '熔炉烧炼产物', f: [] },
  'minecraft:smelt': { label: '烧炼产物', f: [] },
  'minecraft:limit_count': { label: '限制总数量', f: [['limit', 'range']] },
  'minecraft:apply_bonus': { label: '幸运/时运加成', f: [['enchantment', 'text'], ['formula', 'select:minecraft:binomial_with_bonus_count,minecraft:ore_drops,minecraft:uniform_bonus_count'], ['parameters', 'json']] },
  'minecraft:copy_name': { label: '复制来源名称', f: [['source', 'select:this,killer,direct_killer,killer_player,block_entity']] },
  'minecraft:copy_nbt': { label: '复制 NBT', f: [['source', 'select:this,killer,direct_killer,killer_player,block_entity'], ['ops', 'json']] },
  'minecraft:copy_state': { label: '复制方块状态', f: [['block', 'text'], ['properties', 'list']] },
  'minecraft:explosion_decay': { label: '爆炸衰减', f: [] },
  'minecraft:set_attributes': { label: '设置属性', f: [['modifiers', 'json']] },
  'minecraft:set_enchantments': { label: '设置附魔', f: [['enchantments', 'json'], ['add', 'bool']] },
  'minecraft:set_instrument': { label: '设置山羊角乐器', f: [['options', 'text']] },
  'minecraft:set_contents': { label: '设置容器内容物', f: [['entries', 'json']] },
  'minecraft:set_banner_pattern': { label: '设置旗帜图案', f: [['patterns', 'json'], ['append', 'bool']] },
  'minecraft:set_book_cover': { label: '设置成书封面', f: [['title', 'text'], ['author', 'text'], ['generation', 'number']] },
  'minecraft:set_written_book_pages': { label: '设置成书页面', f: [['pages', 'json'], ['mode', 'select:replace,append']] },
  'minecraft:set_fireworks': { label: '设置烟花', f: [['explosions', 'json'], ['flight_duration', 'number']] },
  'minecraft:set_firework_explosion': { label: '设置烟花爆炸', f: [['shape', 'text'], ['colors', 'json'], ['fade_colors', 'json'], ['trail', 'bool'], ['flicker', 'bool']] },
  'minecraft:set_loot_table': { label: '设置容器战利品表', f: [['name', 'text'], ['seed', 'number']] },
  'minecraft:toggle_tooltips': { label: '切换提示显示', f: [['toggles', 'json']] },
};
const FUNC_LIST = Object.keys(FUNC_DEFS);

const COND_DEFS = {
  'minecraft:random_chance': { label: '随机概率', f: [['chance', 'prob']] },
  'minecraft:random_chance_with_looting': { label: '随机概率(受抢夺影响)', f: [['chance', 'prob'], ['looting_multiplier', 'number']] },
  'minecraft:killed_by_player': { label: '必须被玩家击杀', f: [] },
  'minecraft:survives_explosion': { label: '爆炸后必须幸存', f: [] },
  'minecraft:entity_properties': { label: '实体属性匹配', f: [['entity', 'select:this,killer,direct_killer,killer_player'], ['predicate', 'json']] },
  'minecraft:block_state_property': { label: '方块状态匹配', f: [['block', 'text'], ['properties', 'json']] },
  'minecraft:match_tool': { label: '匹配工具', f: [['predicate', 'json']] },
  'minecraft:table_bonus': { label: '附魔等级概率表', f: [['enchantment', 'text'], ['chances', 'json']] },
  'minecraft:inverted': { label: '取反（不满足才生效）', f: [['term', 'json']] },
  'minecraft:alternative': { label: '任一条件满足', f: [['terms', 'json']] },
  'minecraft:time_check': { label: '游戏内时间检查', f: [['value', 'range'], ['period', 'number']] },
  'minecraft:weather_check': { label: '天气检查', f: [['raining', 'bool'], ['thundering', 'bool']] },
  'minecraft:location_check': { label: '位置检查', f: [['predicate', 'json'], ['offsetX', 'number'], ['offsetY', 'number'], ['offsetZ', 'number']] },
  'minecraft:entity_scores': { label: '实体计分板检查', f: [['entity', 'select:this,killer,direct_killer,killer_player'], ['scores', 'json']] },
  'minecraft:value_check': { label: '数值范围检查', f: [['value', 'json'], ['range', 'json']] },
  'minecraft:damage_source_properties': { label: '伤害来源属性', f: [['predicate', 'json']] },
  'minecraft:reference': { label: '引用其他条件', f: [['name', 'text']] },
};
const COND_LIST = Object.keys(COND_DEFS);

const TABLE_TYPES = [
  ['minecraft:generic', '通用'],
  ['minecraft:chest', '箱子'],
  ['minecraft:block', '方块'],
  ['minecraft:entity', '实体'],
  ['minecraft:fishing', '钓鱼'],
  ['minecraft:gift', '礼物'],
  ['minecraft:barter', '以物易物'],
  ['minecraft:advancement_reward', '进度奖励'],
  ['minecraft:advancement_entity', '进度实体'],
  ['minecraft:archaeology', '考古'],
  ['minecraft:selector', '选择器'],
  ['minecraft:command', '命令'],
  ['minecraft:empty', '空'],
];

const ENTRY_TYPES = [
  ['minecraft:item', '物品'],
  ['minecraft:tag', '物品标签'],
  ['minecraft:empty', '空（占位）'],
  ['minecraft:loot_table', '引用其他战利品表'],
  ['minecraft:group', '组（全部掉落）'],
  ['minecraft:alternatives', '择一（只掉一个）'],
  ['minecraft:sequence', '顺序（依次掉落）'],
  ['minecraft:dynamic', '动态掉落'],
];

/* ============================================================
   侧栏
   ============================================================ */
function renderSidebar() {
  const kw = S.filter.trim().toLowerCase();
  const files = S.files.filter((f) => !kw || f.path.toLowerCase().includes(kw));
  const groups = {};
  files.forEach((f) => { (groups[f.dir || ''] = groups[f.dir || ''] || []).push(f); });

  const totalEntries = S.files.reduce((a, f) => a + f.entries, 0);
  $('#sideStat').innerHTML =
    `<span>${S.files.length} 个文件</span><span>${totalEntries.toLocaleString()} 条条目</span>`;

  let html = '';
  const keys = Object.keys(groups).sort();
  if (!keys.length) html = '<div class="muted" style="padding:14px 8px;font-size:12px">没有匹配的池文件</div>';
  for (const g of keys) {
    html += `<div class="dir-group"><div class="dir-name">${g ? '📁 ' + esc(g) : '📄 根目录'}</div>`;
    for (const f of groups[g]) {
      const active = S.cur && S.cur.path === f.path;
      html += `<div class="file-item ${active ? 'active' : ''} ${f.valid ? '' : 'bad'}" data-path="${esc(f.path)}">
        <span class="fname" title="${esc(f.path)}">${esc(f.name)}</span>
        <span class="fmeta">${f.entries}</span>
        <span class="ftools">
          <button class="icon-btn" data-act="rename" title="重命名">✎</button>
          <button class="icon-btn" data-act="delete" title="删除">🗑</button>
        </span>
      </div>`;
    }
    html += '</div>';
  }
  $('#fileTree').innerHTML = html;
}

/* ============================================================
   编辑器
   ============================================================ */
function loadFile(path) {
  if (S.cur && S.cur.dirty && S.cur.path !== path) {
    if (!confirm('当前文件有未保存的改动，确定要切换吗？')) return;
  }
  return api('/api/file?path=' + encodeURIComponent(path)).then((res) => {
    if (!res.ok) { toast(res.error, 'err'); return; }
    S.cur = { path: res.path, data: res.data, report: res.report, dirty: false,
      failedInGame: !!res.failedInGame, lootId: res.lootId || '', logCalibrated: res.logCalibrated || '' };
    S.open.clear(); S.sel.clear(); S.showAll = {}; S.poolOpen = {};
    resetUndo();
    rememberLastFile(res.path);
    renderSidebar();
    renderEditor();
  }).catch((e) => toast(e.message, 'err'));
}

function renderEditor() {
  if (!S.cur) return;
  $('#emptyState').classList.add('hidden');
  $('#editor').classList.remove('hidden');
  const d = S.cur.data;
  const pools = d.pools || [];
  const totalE = pools.reduce((a, p) => a + ((p.entries || []).length), 0);
  const rep = S.cur.report || { errors: [], warnings: [] };

  let status = '<span class="badge ok" id="statusBadge">✓ 语法通过</span>';
  if (rep.errors.length) status = `<span class="badge err" id="statusBadge">✕ ${rep.errors.length} 个错误</span>`;
  else if (rep.warnings.length) status = `<span class="badge warn" id="statusBadge">⚠ ${rep.warnings.length} 个提示</span>`;

  let html = `
  <div class="file-head">
    <div class="file-title">
      <div class="fp">${esc(S.cur.path)}</div>
      <div class="fs">
        <span>${pools.length} 个池 · ${totalE} 条条目</span>
        <span>类型
          <select class="txt" id="tableType" style="padding:1px 6px;font-size:11.5px">
            ${TABLE_TYPES.map(([v, l]) => `<option value="${v}" ${(d.type || 'minecraft:generic') === v ? 'selected' : ''}>${l} · ${v}</option>`).join('')}
            ${TABLE_TYPES.some(([v]) => v === d.type) || !d.type ? '' : `<option value="${esc(d.type)}" selected>自定义 · ${esc(d.type)}</option>`}
          </select>
        </span>
        ${status}
        ${S.cur.dirty ? '<span class="badge warn">未保存</span>' : ''}
      </div>
    </div>
    <div class="file-actions">
      <button class="btn" id="btnUndo" title="撤销 (Ctrl+Z)" ${S.undo.length ? '' : 'disabled'}>↶</button>
      <button class="btn" id="btnRedo" title="重做 (Ctrl+Y)" ${S.redo.length ? '' : 'disabled'}>↷</button>
      <span class="vsep"></span>
      <button class="btn" id="btnGet" title="生成游戏内指令：刷箱子 / 直接刷身上">🎮 游戏内获取</button>
      <button class="btn pri" id="btnOpenSrc" title="用系统默认编辑器打开这个 .json 源文件">📂 打开源文件</button>
      <button class="btn" id="btnCleanDead" title="找出并删除引用了游戏里不存在的物品、或已卸载枪包的条目">🧹 清理废弃</button>
      <button class="btn" id="btnDupItems" title="找出同一个池里重复出现的同一物品">🔁 查重复</button>
      <button class="btn" id="btnValidate">校验</button>
      <button class="btn" id="btnAddPool">＋ 新建池</button>
      <button class="btn" id="btnFoldAll" title="收起/展开全部池子，池子多时不用一直往下拉">⇅ 全部收起</button>
      <button class="btn" id="btnSave">保存</button>
    </div>
  </div>`;

  if (rep.errors.length || rep.warnings.length) {
    html += `<details class="report-item" ${rep.errors.length ? 'open' : ''}>
      <summary>${rep.errors.length ? `<span class="badge err">${rep.errors.length} 错误</span>` : ''}
        ${rep.warnings.length ? `<span class="badge warn">${rep.warnings.length} 提示</span>` : ''}
        <span class="muted">点击展开</span></summary>
      <div class="report-lines">
        ${rep.errors.map((x) => `<div class="report-line e"><span class="tag">错误</span><span>${esc(x)}</span></div>`).join('')}
        ${rep.warnings.map((x) => `<div class="report-line w"><span class="tag">提示</span><span>${esc(x)}</span></div>`).join('')}
      </div></details>`;
  }

  if (S.cur.failedInGame) {
    html += `<div class="report-item game-failed" open>
      <div class="gf-line">⛔ <b>这张表在游戏里加载失败</b>（游戏日志判定：<code class="mono">${esc(S.cur.lootId || S.cur.path)}</code> 解析错误）
      —— 游戏里 Lootr 箱子会显示「故障」、开不出任何东西。
      ${rep.errors.length ? '上方红色错误就是原因，修好后保存，重启游戏让它重新加载。'
                          : '本地校验没抓到全部原因 —— 多半是引用了游戏注册表里没有的物品；先点「重建物品库」刷新判定，再「校验」。'}
      <span class="muted">（日志校准时间：${esc(S.cur.logCalibrated || '未知')}）</span></div>
    </div>`;
  }

  html += `<div class="editor-split"><div class="editor-main">`;
  if (!pools.length) html += '<div class="muted" style="padding:22px;text-align:center">这个文件还没有任何池，点右上角「新建池」开始。</div>';
  pools.forEach((p, i) => { html += renderPool(p, i); });
  html += `</div><aside class="chest-panel" id="chestPanel"></aside></div>`;

  $('#editor').innerHTML = html;
  bindEditorEvents();   // 每次渲染后确保委托事件在位（loadFile 走的是这条路）
  // 全部收起/展开
  const foldBtn = $('#btnFoldAll');
  if (foldBtn) {
    const allCollapsed = pools.length > 0 && pools.every((_, i) => S.poolOpen[i]);
    foldBtn.textContent = allCollapsed ? '⇅ 全部展开' : '⇅ 全部收起';
    foldBtn.onclick = () => {
      const target = !pools.every((_, i) => S.poolOpen[i]);
      pools.forEach((_, i) => { S.poolOpen[i] = target; });
      refresh(true);
    };
  }
  refreshAIStatus();    // 同步 AI 抽屉里「正在编辑」的状态
  renderChest();
  syncStickyOffsets();  // 刷新 --filebar-h，让池工具栏/开箱预览避开文件工具栏
}

/* 文件工具栏（.file-head）本身是 sticky 的，会盖住后面吸顶的元素。
   这里量出它吸顶后占用的高度，写进 CSS 变量 --filebar-h，
   池工具栏（.pool-sticky）和开箱预览（.chest-panel）的 sticky top 都避开这段。 */
function syncStickyOffsets() {
  const fh = document.querySelector('.file-head');
  const root = document.documentElement;
  if (!fh) { root.style.setProperty('--filebar-h', '0px'); return; }
  const stickTop = parseFloat(getComputedStyle(fh).top) || 0;  // 通常是 -16px
  const h = Math.max(0, Math.round(fh.offsetHeight + stickTop));
  root.style.setProperty('--filebar-h', h + 'px');
}

function rollsAvg(r) {
  if (typeof r === 'number') return r;
  if (r && typeof r === 'object') {
    if (typeof r.n === 'number' && typeof r.p === 'number') return r.n * r.p;
    const a = num(r.min, 1), b = num(r.max, 1);
    return (a + b) / 2;
  }
  return 1;
}

/* 整箱为空的概率：各池 P(空) 相乘。
   P(池空) = (1 - 池触发率) + 触发率 × (空条目权重占比)^抽取次数 */
function emptyProbability(data) {
  const pools = (data && data.pools) || [];
  if (!pools.length) return 1;
  let p = 1;
  for (const pool of pools) {
    // 池级触发率（random_chance / random_chance_with_looting）
    let chance = 1;
    for (const c of pool.conditions || []) {
      const t = norm(c.condition);
      if (t === 'minecraft:random_chance' || t === 'minecraft:random_chance_with_looting') {
        chance *= num(c.chance, 0.5);
      }
    }
    const entries = pool.entries || [];
    const total = entries.reduce((a, e) => a + Math.max(0, num(e.weight, 1)), 0);
    const rolls = Math.max(0, rollsAvg(pool.rolls)) + Math.max(0, rollsAvg(pool.bonus_rolls || 0));
    let pEmpty;
    if (total <= 0) {
      pEmpty = 1;   // 没权重就出不了东西
    } else {
      const emptyW = entries.reduce((a, e) => a + (norm(e.type) === 'minecraft:empty' ? Math.max(0, num(e.weight, 1)) : 0), 0);
      const eRatio = emptyW / total;
      pEmpty = Math.pow(eRatio, rolls);
    }
    p *= (1 - chance) + chance * pEmpty;
  }
  return p;
}

/* 空箱的「原因 + 怎么改」：池触发率 / 空条目 / 没有条目
   数据来自后端 /api/validate 的 report.output —— 与这里的 emptyProbability 同一套公式，
   校验和预览不会各说各话。 */
function poolGateHint(rep) {
  if (!rep || !rep.pools || !rep.pools.length) return '';
  const parts = [];
  rep.pools.forEach((p, i) => {
    if (p.noOutput) parts.push(`池${i + 1} 里没有可产出的条目`);
    else if (p.gated) parts.push(`池${i + 1} 只有 ${Math.round(p.triggerChance * 100)}% 概率触发（random_chance）`);
    else if (p.emptyWeightRatio > 0.3) parts.push(`池${i + 1} 有 ${Math.round(p.emptyWeightRatio * 100)}% 权重落在「minecraft:empty」上`);
  });
  if (!parts.length) return '';
  const fixable = rep.pools.some((p) => p.gated);
  return '原因：' + parts.join('；')
    + (fixable ? '。想让每箱都有东西，把池里的 random_chance 条件删掉即可。' : '。');
}

function rollsText(r) {  if (typeof r === 'number') return String(r);
  if (r && typeof r === 'object') {
    if (r.type === 'minecraft:binomial') return `二项 n=${num(r.n, 1)} p=${num(r.p, 0.5)}`;
    return `${num(r.min, 1)}~${num(r.max, 1)}`;
  }
  return '1';
}

/* 只渲染某个池的「条目列表 + 分页按钮」。
   池内搜索时用它局部替换，不重渲染整个编辑器——否则输入框被销毁重建，
   中文输入法的组字状态会断掉（拼音打不出汉字）。 */
function poolEntryBoxHTML(pi) {
  const pl = pool(pi);
  const entries = pl.entries || [];
  const showAll = S.showAll[pi];
  const total = entries.reduce((a, e) => a + Math.max(1, num(e.weight, 1)), 0);
  const avg = rollsAvg(pl.rolls);
  const kw = (S.poolSearch[pi] || '').trim().toLowerCase();
  let indexed = entries.map((e, ei) => [ei, e]);
  if (kw) {
    indexed = indexed.filter(([ei, e]) => {
      const label = entrySummary(e).toLowerCase();
      const id = String(e.name || e.value || '').toLowerCase();
      const sub = entryExtra(e).map((x) => (x.text || '') + ' ' + (x.sub || '')).join(' ').toLowerCase();
      return label.includes(kw) || id.includes(kw) || sub.includes(kw);
    });
  }
  const list = (showAll || kw) ? indexed : indexed.slice(0, PAGE);
  return `<div class="entries">
      <div class="entry-head">
        <span></span><span></span><span>物品</span><span>权重</span><span>出现概率</span><span>数量</span><span></span>
      </div>
      ${list.length ? list.map(([ei, e]) => renderEntry(e, pi, ei, total, avg)).join('')
      : `<div class="muted" style="padding:18px;text-align:center">没有匹配「${esc(kw)}」的条目</div>`}
    </div>
    ${!kw && entries.length > PAGE && !showAll ? `<div style="text-align:center;margin-top:9px">
      <button class="btn sm" data-act="showall" data-pi="${pi}">还有 ${entries.length - PAGE} 条未显示 —— 显示全部</button></div>` : ''}
    ${!kw && showAll && entries.length > PAGE ? `<div style="text-align:center;margin-top:9px">
      <button class="btn sm" data-act="showless" data-pi="${pi}">收起</button></div>` : ''}`;
}

function renderPool(pool, pi) {
  const entries = pool.entries || [];
  const collapsed = S.poolOpen[pi];
  const total = entries.reduce((a, e) => a + Math.max(1, num(e.weight, 1)), 0);
  const conds = pool.conditions || [];

  let h = `<div class="pool-card">
    <div class="pool-sticky">
      <div class="pool-head ${collapsed ? 'collapsed' : ''}">
        <span class="pool-idx">${pi + 1}</span>
        <div class="pool-title">
          <input data-act="poolname" data-pi="${pi}" value="${esc(pool.name || '')}" placeholder="池名称（可留空）">
          <span class="badge">抽 ${esc(rollsText(pool.rolls))} 次</span>
          <span class="badge pri">${entries.length} 条</span>
          <span class="badge">总权重 ${total}</span>
          ${(() => {
            // 抽空概率：空条目权重占比（单次抽不到东西的概率）
            const emptyW = entries.reduce((a, e) => a + (norm(e.type) === 'minecraft:empty' ? Math.max(0, num(e.weight, 1)) : 0), 0);
            if (emptyW > 0 && total > 0) {
              const p = emptyW / total;
              return `<span class="badge empty-badge" title="空条目权重 ${emptyW} / 总 ${total}">抽空 ${(p * 100).toFixed(p >= 0.1 ? 0 : 1)}%</span>`;
            }
            return '';
          })()}
          ${conds.length ? `<span class="badge warn">${conds.length} 个条件</span>` : ''}
        </div>
        <button class="btn sm ghost" data-act="movePoolUp" data-pi="${pi}" ${pi === 0 ? 'disabled' : ''} title="上移这个池">↑</button>
        <button class="btn sm ghost" data-act="movePoolDown" data-pi="${pi}" title="下移这个池">↓</button>
        <button class="btn sm" data-act="togglepool" data-pi="${pi}">${collapsed ? '展开' : '收起'}</button>
        <button class="btn sm" data-act="dupPool" data-pi="${pi}">复制池</button>
        <button class="btn sm danger" data-act="delPool" data-pi="${pi}">删除池</button>
      </div>`;

  if (collapsed) {
    // 折叠时给一行内容概要：前几条物品名，扫一眼就知道这个池装的是什么
    const names = entries.slice(0, 10).map((e) => entrySummary(e));
    h += `<div class="pool-peek">${esc(names.join('、'))}${entries.length > 10 ? ` …等 ${entries.length} 条` : ''}</div>`;
  }
  if (!collapsed) {
    h += `<div class="sec-h">条目（权重越高越容易抽到）
        <span class="spacer"></span>
        <input class="input pool-search" data-act="poolSearch" data-pi="${pi}" value="${esc(S.poolSearch[pi] || '')}" placeholder="🔍 搜索本池条目…" autocomplete="off">
        <button class="btn sm" data-act="addItem" data-pi="${pi}">＋ 添加物品</button>
        ${Object.keys(S.tacz || {}).length ? `<button class="btn sm" data-act="addTacz" data-pi="${pi}" title="枪械 / 配件 / 弹药 / 投掷物 / 消耗品，自动生成带 NBT 的完整条目">＋ 枪械·弹药…</button>` : ''}
        <button class="btn sm" data-act="addEmpty" data-pi="${pi}">＋ 空条目</button>
        <button class="btn sm" data-act="addEntryType" data-pi="${pi}">＋ 其他类型</button>
      </div>
    </div>
    <div class="pool-body">
      <div class="row">
        <span class="lbl">抽取次数</span>
        <div class="seg" data-seg="rolls" data-pi="${pi}">
          <button data-mode="fix" class="${typeof pool.rolls === 'number' ? 'on' : ''}">固定</button>
          <button data-mode="range" class="${typeof pool.rolls === 'object' ? 'on' : ''}">随机区间</button>
        </div>
        <span id="rollsEdit${pi}">${rollsEditor(pool.rolls, pi)}</span>
        <span class="hint">平均每次开箱抽 ${rollsAvg(pool.rolls)} 次</span>
      </div>
      <div class="row">
        <span class="lbl">额外次数</span>
        <span id="bonusEdit${pi}">${bonusEditor(pool.bonus_rolls, pi)}</span>
        <span class="hint">通常用于幸运附魔加成，留空表示无</span>
      </div>
      <div class="row">
        <span class="lbl">池条件</span>
        <button class="btn sm" data-act="addPoolCond" data-pi="${pi}">＋ 添加条件</button>
        <span class="hint">不满足条件的池整体不产出</span>
      </div>
      ${(conds.length ? `<div style="margin:0 0 10px 70px">${conds.map((c, ci) => condCard(c, ci, `p${pi}`)).join('')}</div>` : '')}

      <div class="detail-sec">
        ${renderBulkBar(pi)}
        <div data-entrybox="${pi}">${poolEntryBoxHTML(pi)}</div>
      </div>
    </div>`;
  } else {
    h += '</div>';
  }
  h += '</div>';
  return h;
}

function rollsEditor(r, pi) {
  const isRange = typeof r === 'object' && r !== null;
  if (isRange) {
    return `<input class="num" type="number" min="0" data-act="rollsMin" data-pi="${pi}" value="${num(r.min, 1)}">
      <span class="muted">~</span>
      <input class="num" type="number" min="0" data-act="rollsMax" data-pi="${pi}" value="${num(r.max, 1)}">`;
  }
  return `<input class="num" type="number" min="0" data-act="rollsFix" data-pi="${pi}" value="${num(r, 1)}">`;
}
function bonusEditor(b, pi) {
  if (b === undefined || b === null) {
    return `<button class="btn sm" data-act="addBonus" data-pi="${pi}">＋ 启用</button>`;
  }
  if (typeof b === 'object') {
    return `<input class="num" type="number" min="0" data-act="bonusMin" data-pi="${pi}" value="${num(b.min, 0)}">
      <span class="muted">~</span>
      <input class="num" type="number" min="0" data-act="bonusMax" data-pi="${pi}" value="${num(b.max, 0)}">
      <button class="btn sm ghost" data-act="delBonus" data-pi="${pi}">移除</button>`;
  }
  return `<input class="num" type="number" min="0" data-act="bonusFix" data-pi="${pi}" value="${num(b, 0)}">
    <button class="btn sm ghost" data-act="delBonus" data-pi="${pi}">移除</button>`;
}

function renderBulkBar(pi) {
  const total = (pool(pi).entries || []).length;
  const selCount = Array.from(S.sel).filter((k) => k.startsWith(pi + ':')).length;
  return `<div class="row bulk-bar ${selCount ? 'has-sel' : ''}">
    <span class="lbl">已选 ${selCount} 条</span>
    <button class="btn sm" data-act="bulkSelAll" data-pi="${pi}" title="选中本池全部 ${total} 条">全选</button>
    <button class="btn sm" data-act="bulkSelInvert" data-pi="${pi}" title="反转选择">反选</button>
    ${S.poolSearch[pi] ? `<button class="btn sm" data-act="bulkSelMatch" data-pi="${pi}" title="选中当前搜索匹配到的条目">选中搜索结果</button>` : ''}
    <span class="muted">权重</span><input class="num" id="bulkW${pi}" type="number" min="1" value="1">
    <button class="btn sm" data-act="bulkWeight" data-pi="${pi}">应用</button>
    <span class="muted">数量</span><input class="num" id="bulkN${pi}" type="number" min="1" value="1">
    <button class="btn sm" data-act="bulkCount" data-pi="${pi}">应用</button>
    <span class="spacer"></span>
    <button class="btn sm danger" data-act="bulkDel" data-pi="${pi}" ${selCount ? '' : 'disabled'}>删除所选</button>
    <button class="btn sm ghost" data-act="bulkClear" data-pi="${pi}">取消选择</button>
  </div>`;
}

function entrySummary(e) {
  const t = norm(e.type);
  if (t === 'minecraft:item') return itemLabel(e.name);
  if (t === 'minecraft:tag') return '标签 ' + String(e.name || '').replace(/^#/, '');
  if (t === 'minecraft:empty') return '抽空（不掉东西）';
  if (t === 'minecraft:loot_table') return '表 ' + (e.value || '');
  if (t === 'minecraft:dynamic') return '动态 ' + (e.name || '');
  if (t === 'minecraft:group') return '组（全部掉落）';
  if (t === 'minecraft:alternatives') return '择一';
  if (t === 'minecraft:sequence') return '顺序';
  return t;
}

function countOf(e) {
  const fns = e.functions || [];
  const f = fns.find((x) => norm(x.function) === 'minecraft:set_count');
  if (!f) return null;
  return f.count;
}
function countText(c) {
  if (c == null) return '1';
  if (typeof c === 'number') return String(c);
  return `${num(c.min, 1)}~${num(c.max, 1)}`;
}

function renderEntry(e, pi, ei, total, avgRolls) {
  const key = pi + ':' + ei;
  const t = norm(e.type);
  const w = Math.max(0, num(e.weight, 1));
  const p = total > 0 ? w / total : 0;
  const isOpen = S.open.has(key);
  const isSel = S.sel.has(key);
  const isItem = t === 'minecraft:item';
  const isTag = t === 'minecraft:tag';
  const nbt = (e.functions || []).some((f) => norm(f.function) === 'minecraft:set_nbt');
  const cText = countText(countOf(e));
  const expect = p * avgRolls;
  const extras = entryExtra(e);
  const subIds = extras.map((x) => x.sub).filter(Boolean).join(' · ');
  const notInGame = isItem && e.name ? itemBad(e.name) : null;

  let h = `<div class="entry-row ${isSel ? 'sel' : ''} ${t === 'minecraft:empty' ? 'empty-entry' : ''}" data-key="${key}">
    <span><input type="checkbox" data-act="selEntry" data-pi="${pi}" data-ei="${ei}" ${isSel ? 'checked' : ''}></span>
    <span class="entry-icon-wrap">${isItem || isTag ? entryIconTag(e) : '<div class="entry-icon" style="display:grid;place-items:center;color:#93a0b5;font-size:11px">◈</div>'}</span>
    <span class="entry-name">
      <div class="cn">${esc(entrySummary(e))}${notInGame ? `<span class="extra-tag not-in-game" title="${esc(notInGame)}">游戏里没有</span>` : ''}${extras.map((x) =>
        `<span class="extra-tag ${x.kind}" title="${esc(x.sub)}">${esc(x.text)}</span>`).join('')}${nbt && !extras.length ? '<span class="nbt-mark" title="带 NBT 标签">NBT</span>' : ''}</div>
      <div class="id">${esc(e.name || e.value || t)}${subIds ? ' <span class="sub-id">· ' + esc(subIds) + '</span>' : ''}</div>
    </span>
    <span><input class="num" type="number" min="1" step="1" data-act="weight" data-pi="${pi}" data-ei="${ei}" value="${w}"></span>
    <span class="prob-wrap">
      <span class="prob-bar"><span class="prob-fill ${p > 0.2 ? 'hi' : ''}" style="width:${Math.min(100, p * 100).toFixed(1)}%"></span></span>
      <span class="prob-txt">${pct(p)}</span>
    </span>
    <span class="muted" style="font-size:11.5px" title="每次开箱平均掉落 ${expect.toFixed(2)} 个">${esc(cText)} <span class="muted">(≈${expect.toFixed(2)})</span></span>
    <span class="entry-tools">
      <button class="btn sm ghost" data-act="toggleEntry" data-pi="${pi}" data-ei="${ei}">${isOpen ? '收起' : '编辑'}</button>
      <button class="btn sm ghost" data-act="delEntry" data-pi="${pi}" data-ei="${ei}">✕</button>
    </span>
  </div>`;

  if (isOpen) h += `<div class="entry-detail">${entryDetail(e, pi, ei)}</div>`;
  return h;
}

function entryDetail(e, pi, ei) {
  const t = norm(e.type);
  const fns = e.functions || [];
  const conds = e.conditions || [];
  const needName = ['minecraft:item', 'minecraft:tag', 'minecraft:dynamic'].includes(t);
  const isChild = ['minecraft:group', 'minecraft:alternatives', 'minecraft:sequence'].includes(t);

  let h = `<div class="detail-sec">
    <div class="sec-h">基本</div>
    <div class="row">
      <span class="lbl">类型</span>
      <select class="txt" data-act="entryType" data-pi="${pi}" data-ei="${ei}">
        ${ENTRY_TYPES.map(([v, l]) => `<option value="${v}" ${norm(e.type) === v ? 'selected' : ''}>${l} (${v})</option>`).join('')}
      </select>
      <span class="lbl">权重</span>
      <input class="num" type="number" min="0" data-act="weight" data-pi="${pi}" data-ei="${ei}" value="${Math.max(0, num(e.weight, 1))}">
      <span class="lbl">品质</span>
      <input class="num" type="number" data-act="quality" data-pi="${pi}" data-ei="${ei}" value="${num(e.quality, 0)}">
      <span class="hint">品质影响幸运附魔的加权</span>
    </div>
    ${needName ? `<div class="row">
      <span class="lbl">${t === 'minecraft:tag' ? '标签 ID' : '物品 ID'}</span>
      <input class="txt mono" style="flex:1 1 300px" data-act="entryName" data-pi="${pi}" data-ei="${ei}" value="${esc(e.name || '')}" placeholder="${t === 'minecraft:tag' ? '#minecraft:planks' : 'minecraft:diamond'}">
      <button class="btn sm" data-act="pickEntry" data-pi="${pi}" data-ei="${ei}">从物品库选</button>
      ${e.name ? `<button class="btn sm ghost" data-act="findEntry" data-pi="${pi}" data-ei="${ei}" title="查这个物品还出现在哪些池子里">反查</button>` : ''}
    </div>` : ''}
    ${t === 'minecraft:loot_table' ? `<div class="row">
      <span class="lbl">战利品表</span>
      <input class="txt mono" style="flex:1 1 300px" data-act="entryValue" data-pi="${pi}" data-ei="${ei}" value="${esc(e.value || '')}" placeholder="chaoszpack_lc_loot:chests/zhanlipin/muxiang">
    </div>` : ''}
    ${t === 'minecraft:empty' ? '<div class="hint" style="margin-left:0">空条目：占一份权重但不产出任何东西。用它来降低"必定出货"的概率。</div>' : ''}
    ${taczBaseRow(e, pi, ei)}
  </div>
  ${isChild ? childrenEditor(e, pi, ei) : ''}`;

  h += `<div class="detail-sec">
    <div class="sec-h">函数（对物品做的事）
      <span class="spacer"></span>
      <button class="btn sm" data-act="addFunc" data-pi="${pi}" data-ei="${ei}">＋ 添加函数</button>
    </div>
    ${fns.length ? fns.map((f, fi) => funcCard(f, fi, pi, ei)).join('') : '<div class="hint" style="margin-left:0">暂无函数。常用：设置数量、写入 NBT。</div>'}
  </div>`;

  h += `<div class="detail-sec">
    <div class="sec-h">条目条件（满足才掉落）
      <span class="spacer"></span>
      <button class="btn sm" data-act="addCond" data-pi="${pi}" data-ei="${ei}">＋ 添加条件</button>
    </div>
    ${conds.length ? conds.map((c, ci) => condCard(c, ci, `e${pi}_${ei}`)).join('') : '<div class="hint" style="margin-left:0">无条件 = 必然进入抽取。</div>'}
  </div>`;

  return h;
}

/* ---------------- 子条目（组合类型：组 / 择一 / 顺序） ---------------- */
const COMBINE_TYPES = ['minecraft:group', 'minecraft:alternatives', 'minecraft:sequence'];

function cpathGet(cpath) {
  const arr = String(cpath).split(',').map(Number);
  let cur = entry(arr[0], arr[1]);
  for (let i = 2; i < arr.length; i++) {
    if (!cur || !Array.isArray(cur.children)) return null;
    cur = cur.children[arr[i]];
  }
  return cur;
}

function childrenEditor(e, pi, ei) {
  const ch = e.children || [];
  const t = norm(e.type);
  const hint = t === 'minecraft:alternatives'
    ? '「择一」按权重从下面挑一个掉落，其余丢弃。'
    : t === 'minecraft:sequence'
      ? '「顺序」依次尝试掉落下面每一项，遇到不满足条件的就停。'
      : '「组」把下面所有项全部掉落。';
  let h = `<div class="detail-sec">
    <div class="sec-h">子条目（${ch.length} 个）
      <span class="spacer"></span>
      <button class="btn sm" data-act="addChild" data-pi="${pi}" data-ei="${ei}">＋ 添加物品</button>
      <button class="btn sm" data-act="addChildEmpty" data-pi="${pi}" data-ei="${ei}">＋ 空条目</button>
    </div>
    <div class="hint" style="margin:0 0 8px 0">${hint}</div>`;
  if (!ch.length) {
    h += '<div class="hint warn-text" style="margin-left:0">还没有子条目 —— 空的组合条目在游戏里不产出任何东西。</div>';
  } else {
    h += `<div class="entries">
      <div class="entry-head" style="grid-template-columns:34px minmax(140px,1fr) 74px 96px 60px">
        <span></span><span>子条目</span><span>权重</span><span>数量</span><span></span></div>`;
    ch.forEach((c, ci) => { h += childRow(c, pi, ei, ci); });
    h += '</div>';
  }
  h += '</div>';
  return h;
}

function childRow(c, pi, ei, ci) {
  const cp = `${pi},${ei},${ci}`;
  const t = norm(c.type);
  const isItem = t === 'minecraft:item' || t === 'minecraft:tag';
  const nbt = (c.functions || []).some((f) => norm(f.function) === 'minecraft:set_nbt');
  const deep = COMBINE_TYPES.includes(t);
  const cc = countOf(c);
  const cv = cc == null ? 1 : (typeof cc === 'number' ? cc : num(cc.min, 1));
  return `<div class="entry-row ${t === 'minecraft:empty' ? 'empty-entry' : ''}" style="grid-template-columns:34px minmax(140px,1fr) 74px 96px 60px">
    <span class="entry-icon-wrap">${isItem ? iconTag(c.name) : '<div class="entry-icon" style="display:grid;place-items:center;color:#93a0b5;font-size:11px">◈</div>'}</span>
    <span class="entry-name">
      <div class="cn">${esc(entrySummary(c))}${nbt ? '<span class="nbt-mark" title="带 NBT 标签">NBT</span>' : ''}${deep ? '<span class="nbt-mark" title="里面还有一层子条目">嵌套</span>' : ''}</div>
      <div class="id">${esc(c.name || c.value || t)}</div>
    </span>
    <span><input class="num" type="number" min="0" data-act="childWeight" data-cpath="${cp}" value="${Math.max(0, num(c.weight, 1))}"></span>
    <span><input class="num" type="number" min="1" data-act="childCount" data-cpath="${cp}" value="${Math.max(1, cv)}" title="掉落数量（会写入 set_count 函数）"></span>
    <span class="entry-tools">
      <button class="btn sm ghost" data-act="childPick" data-cpath="${cp}">改</button>
      <button class="btn sm ghost" data-act="delChild" data-cpath="${cp}">✕</button>
    </span>
  </div>`;
}

/* ---------------- 函数卡片 ---------------- */
function funcCard(f, fi, pi, ei) {
  const t = norm(f.function);
  const def = FUNC_DEFS[t];
  const scope = `e${pi}_${ei}`;
  const unknown = !def;
  let h = `<div class="func-card">
    <div class="func-head">
      <select class="txt" data-act="funcType" data-pi="${pi}" data-ei="${ei}" data-fi="${fi}">
        ${unknown ? `<option value="${esc(f.function)}" selected>未知：${esc(f.function)}</option>` : ''}
        ${FUNC_LIST.map((k) => `<option value="${k}" ${t === k ? 'selected' : ''}>${FUNC_DEFS[k].label} (${k.replace('minecraft:', '')})</option>`).join('')}
      </select>
      <span class="spacer"></span>
      <button class="btn sm ghost" data-act="delFunc" data-pi="${pi}" data-ei="${ei}" data-fi="${fi}">移除</button>
    </div>
    <div class="func-fields">
      ${def ? def.f.map(([k, kind]) => field(kind, k, f[k], { pi, ei, fi, scope, act: 'funcField' })).join('')
            : '<span class="hint">这是一个未知函数，内容会原样保留。切到已知类型会重建字段。</span>'}
    </div>
  </div>`;
  return h;
}

/* ---------------- 条件卡片 ---------------- */
function condCard(c, ci, scope) {
  const t = norm(c.condition);
  const def = COND_DEFS[t];
  const unknown = !def;
  return `<div class="cond-card">
    <div class="func-head">
      <select class="txt" data-act="condType" data-scope="${scope}" data-ci="${ci}">
        ${unknown ? `<option value="${esc(c.condition)}" selected>未知：${esc(c.condition)}</option>` : ''}
        ${COND_LIST.map((k) => `<option value="${k}" ${t === k ? 'selected' : ''}>${COND_DEFS[k].label} (${k.replace('minecraft:', '')})</option>`).join('')}
      </select>
      <span class="spacer"></span>
      <button class="btn sm ghost" data-act="delCond" data-scope="${scope}" data-ci="${ci}">移除</button>
    </div>
    <div class="func-fields">
      ${def ? def.f.map(([k, kind]) => field(kind, k, c[k], { scope, ci, act: 'condField' })).join('')
            : '<span class="hint">未知条件，内容原样保留。</span>'}
    </div>
  </div>`;
}

/* ---------------- 通用字段控件 ---------------- */
function field(kind, key, val, ctx) {
  const attrs = `data-act="${ctx.act}" data-scope="${esc(ctx.scope)}" data-kind="${esc(kind)}" ${ctx.pi != null ? `data-pi="${ctx.pi}" data-ei="${ctx.ei}"` : ''} ${ctx.fi != null ? `data-fi="${ctx.fi}"` : ''} ${ctx.ci != null ? `data-ci="${ctx.ci}"` : ''} data-key="${esc(key)}"`;
  const label = `<span class="fld"><span class="muted">${esc(key)}</span>`;

  if (kind === 'bool') {
    return `${label}<input type="checkbox" ${attrs} ${val ? 'checked' : ''}></span>`;
  }
  if (kind === 'range') {
    const isObj = val && typeof val === 'object';
    const mn = isObj ? num(val.min, 1) : num(val, 1);
    const mx = isObj ? num(val.max, 1) : num(val, 1);
    return `${label}<input class="num" type="number" ${attrs} data-part="min" value="${mn}">
      <span class="muted">~</span>
      <input class="num" type="number" ${attrs} data-part="max" value="${mx}"></span>`;
  }
  if (kind === 'number') {
    return `${label}<input class="num" type="number" ${attrs} value="${num(val, 0)}"></span>`;
  }
  if (kind === 'prob' || kind === 'ratio') {
    return `${label}<input class="num" type="number" step="0.01" min="0" max="1" ${attrs} value="${num(val, kind === 'prob' ? 0.5 : 1)}"></span>`;
  }
  if (kind === 'nbt') {
    return `<div style="width:100%"><div class="muted" style="margin-bottom:4px">${esc(key)}（SNBT，形如 {Damage:0}）</div>
      <textarea class="txt" ${attrs} placeholder="{Damage:0}">${esc(val == null ? '' : val)}</textarea></div>`;
  }
  if (kind === 'json' || kind === 'list') {
    const txt = val === undefined ? '' : JSON.stringify(val, null, 0);
    return `<div style="width:100%"><div class="muted" style="margin-bottom:4px">${esc(key)}${kind === 'list' ? '（JSON 数组，如 ["minecraft:sharpness"]）' : '（JSON）'}</div>
      <textarea class="txt" ${attrs} placeholder='${kind === 'list' ? '["minecraft:sharpness"]' : '{}'}'>${esc(txt)}</textarea></div>`;
  }
  if (kind.startsWith('select:')) {
    const opts = kind.slice(7).split(',');
    return `${label}<select class="txt" ${attrs}>
      ${opts.map((o) => `<option value="${esc(o)}" ${String(val) === o ? 'selected' : ''}>${esc(o)}</option>`).join('')}
    </select></span>`;
  }
  return `${label}<input class="txt mono" style="flex:1 1 220px" ${attrs} value="${esc(val == null ? '' : val)}"></span>`;
}

/* ============================================================
   物品选择器
   ============================================================ */
function openPicker(onPick, opts) {
  opts = opts || {};
  const picked = new Set();        // 普通物品 ID
  const pickedSub = new Map();     // "NBT键|子类型ID" -> {key,id,name,base}
  let anchorIdx = -1;              // Shift 范围选的锚点（filtered 里的下标）
  let tab = opts.tab || 'item';    // 'item' 或某个 NBT 键
  let kw = '';
  let nsFilter = '';
  let shown = 300;
  let filtered = [];

  const nsList = (() => {
    const c = {};
    validItemList().forEach((it) => { c[it.ns] = (c[it.ns] || 0) + 1; });
    return Object.entries(c).sort((a, b) => b[1] - a[1]);
  })();

  const taczTabs = Object.entries(S.tacz)
    .filter(([, d]) => (d.items || []).length)
    .map(([nbt, d]) => [nbt, d]);

  function compute() {
    const k = kw.trim().toLowerCase();
    if (tab === 'item') {
      // 只列「确认在游戏里存在」的物品：以前把方块状态、旧版本残留都列出来，
      // 加进去游戏就整表加载失败（这就是餐厅/商店用不了的原因）
      let list = validItemList();
      if (nsFilter) list = list.filter((it) => it.ns === nsFilter);
      if (k) {
        list = list.filter((it) =>
          (it.id || '').toLowerCase().includes(k) ||
          (it.zh || '').toLowerCase().includes(k) ||
          (it.en || '').toLowerCase().includes(k));
      }
      list.sort((a, b) => ((a.zh ? 0 : 1) - (b.zh ? 0 : 1)) || a.id.localeCompare(b.id));
      filtered = list;
    } else {
      const d = S.tacz[tab] || { items: [] };
      let list = d.items || [];
      if (k) {
        list = list.filter((it) =>
          (it.id || '').toLowerCase().includes(k) ||
          (it.name || '').toLowerCase().includes(k) ||
          (it.pack || '').toLowerCase().includes(k));
      }
      filtered = list;
    }
    if (shown < 300) shown = 300;
  }

  function selCount() { return picked.size + pickedSub.size; }

  /* 选中/取消一个网格项。按住 Shift = 从上次点的那项一路选到当前项（批量）。 */
  function toggleItemAt(idx, shift) {
    if (idx < 0) return;
    const it = filtered[idx];
    if (!it) return;
    const key = tab === 'item' ? it.id : (tab + '|' + it.id);
    const set = tab === 'item' ? picked : pickedSub;
    if (shift && anchorIdx >= 0 && anchorIdx < filtered.length) {
      const a = Math.min(anchorIdx, idx), b = Math.max(anchorIdx, idx);
      const aKey = tab === 'item' ? filtered[anchorIdx].id : (tab + '|' + filtered[anchorIdx].id);
      const want = set.has(aKey);
      for (let i = a; i <= b; i++) {
        const x = filtered[i];
        const k2 = tab === 'item' ? x.id : (tab + '|' + x.id);
        if (want) {
          if (tab === 'item') set.add(k2);
          else set.set(k2, { key: tab, id: x.id, name: x.name || '',
                             base: S.tacz[tab].base, label: S.tacz[tab].label });
        } else set.delete(k2);
      }
    } else {
      if (set.has(key)) set.delete(key);
      else {
        if (tab === 'item') set.add(key);
        else set.set(key, { key: tab, id: it.id, name: it.name || '',
                            base: S.tacz[tab].base, label: S.tacz[tab].label });
      }
      anchorIdx = idx;
    }
  }

  function paintGrid() {
    const show = filtered.slice(0, shown);
    if (tab === 'item') {
      $('#pickerGrid').innerHTML = show.map((it) => {
        const cat = taczCatOf(it.id);
        return `
        <div class="picker-item ${picked.has(it.id) ? 'on' : ''}" data-id="${esc(it.id)}" title="${esc(it.id)}">
          ${iconTag(it.id, 26)}
          <div class="pi-txt">
            <div class="pi-cn">${esc(it.zh || it.en || it.id)}${cat ? `<span class="extra-tag tacz" style="margin-left:5px">要选${S.tacz[cat[0]].label}</span>` : ''}</div>
            <div class="pi-id">${esc(it.id)}</div>
          </div>
        </div>`;
      }).join('') ||
        '<div class="muted" style="padding:22px;grid-column:1/-1">没有匹配的物品。换个关键词，或把完整 ID 粘到右上角输入框。</div>';
    } else {
      const base = (S.tacz[tab] || {}).base || '';
      $('#pickerGrid').innerHTML = show.map((it) => {
        const k = tab + '|' + it.id;
        const iconHtml = it.icon
          ? `<img class="entry-icon" style="width:26px;height:26px" src="/${esc(it.icon)}" loading="lazy" onerror="this.style.visibility='hidden'">`
          : iconTag(base, 26);
        return `
        <div class="picker-item ${pickedSub.has(k) ? 'on' : ''}" data-sub="${esc(k)}" title="${esc(it.id)}">
          ${iconHtml}
          <div class="pi-txt">
            <div class="pi-cn">${esc(it.name || it.id)}</div>
            <div class="pi-id">${esc(it.id)}</div>
            ${it.pack ? `<div class="pi-pack">${esc(it.pack)}</div>` : ''}
          </div>
        </div>`;
      }).join('') ||
        '<div class="muted" style="padding:22px;grid-column:1/-1">没有匹配的子类型。</div>';
    }
    $('#pickerCount').innerHTML =
      `匹配 <b>${filtered.length}</b> 个 · 已显示 ${Math.min(shown, filtered.length)} 个` +
      (filtered.length > shown ? '（继续往下滚动会自动加载）' : '');
    $('#pickerSel').textContent = selCount() ? `已选 ${selCount()} 个` : '';
  }

  function repaint() { compute(); paintGrid(); paintRecent(); }

  function paintTabs() {
    const tabs = [['item', '普通物品', validItemList().length]].concat(
      taczTabs.map(([nbt, d]) => [nbt, d.label || nbt, (d.items || []).length]));
    $('#pickerTabs').innerHTML = tabs.map(([v, l, n]) =>
      `<button class="ptab ${tab === v ? 'on' : ''}" data-tab="${esc(v)}">${esc(l)}<span class="n"> ${n}</span></button>`
    ).join('');
  }

  $('#modalBox').className = 'modal-box';
  $('#modalBox').innerHTML = `
    <div class="modal-head">
      <h3>选择物品</h3>
      <span class="picker-count" id="pickerSel"></span>
      <button class="btn sm" id="pickerSelectAll" title="把当前筛选出来的全部选中（配合模组筛选/搜索批量加物品）">全选筛选结果</button>
      <button class="btn sm" id="pickerClearSel" title="清空已选">清空</button>
      <button class="btn" data-close="1">关闭</button>
    </div>
    <div class="modal-body">
      <div class="picker-sticky">
        <div class="picker-tabs" id="pickerTabs"></div>
        <div class="picker-bar" id="pickerNsBar">
          <input class="input" id="pickerSearch" placeholder="搜中文名 / 英文名 / 物品 ID（也支持 tacz: 这种模组前缀）…" autocomplete="off">
          <select class="txt" id="pickerNs" style="flex:0 0 auto">
            <option value="">全部模组（${validItemList().length} 项）</option>
            ${nsList.map(([ns, n]) => `<option value="${esc(ns)}">${esc(modLabel(ns))} · ${n} 项</option>`).join('')}
          </select>
        </div>
        <div class="picker-bar" id="pickerManualBar" style="display:none">
          <input class="input mono" id="pickerManual" placeholder="找不到？直接把物品 ID 粘到这里（可一次粘多个，用换行/逗号隔开）" autocomplete="off">
          <button class="btn" id="pickerManualAdd">添加这个 ID</button>
        </div>
        <div class="picker-bar" id="pickerIdBar" style="display:none">
          <span class="muted">检测到物品 ID：</span>
          <code id="pickerIdPreview" class="mono"></code>
          <button class="btn sm pri" id="pickerIdAdd">添加这个 ID</button>
        </div>
        <div id="pickerWarn"></div>
        <div class="picker-count" id="pickerCount" style="margin-bottom:9px"></div>
        <div id="pickerRecent"></div>
      </div>
      <div class="picker-grid" id="pickerGrid"></div>
    </div>
    <div class="modal-foot">
      <span class="muted" id="pickerHint">点一下选中 / 再点取消</span>
      <span class="spacer"></span>
      <button class="btn" data-close="1">取消</button>
      <button class="btn pri" id="pickerOk">确定添加</button>
    </div>`;

  $('#modal').classList.remove('hidden');

  function applyTab() {
    const isItem = tab === 'item';
    $('#pickerNsBar').style.display = isItem ? '' : 'none';
    // 手动 ID 栏已合并到搜索框（检测到 ID 格式时自动显示「添加 ID」按钮）
    $('#pickerManualBar').style.display = 'none';
    $('#pickerIdBar').style.display = 'none';
    if (isItem) {
      $('#pickerSearch').placeholder = '搜中文名 / 英文名 / 物品 ID（也支持 tacz: 这种模组前缀）…';
      $('#pickerHint').textContent = '点选 · Shift+点=范围选 · 空白处拖框=批量选';
      $('#pickerWarn').innerHTML = '';
    } else {
      const d = S.tacz[tab] || {};
      const base = itemLabel(d.base || '');
      $('#pickerSearch').placeholder = `搜${d.label || ''}的名字 / ID / 枪包名…`;
      $('#pickerHint').textContent = `选中后会生成 ${d.base} + ${tab} 的完整条目`;
      const miss = d.missing || [];
      $('#pickerWarn').innerHTML = miss.length
        ? `<div class="picker-warn">⚠ 有 ${miss.length} 个 ${d.label}被你的池子引用，但对应的枪包已不在整合包里（例如 ${esc(miss.slice(0, 3).join('、'))}）。它们不会出现在上面的列表里，游戏里抽到会是空白物品。</div>`
        : '';
      if (base) $('#pickerHint').textContent = `基物品 ${d.base}（${base}）`;
    }
    paintTabs();
    repaint();
  }

  applyTab();
  $('#pickerSearch').focus();

  // 最近用过：点一下直接选中，省得每次重新搜
  function paintRecent() {
    const box = $('#pickerRecent');
    if (!box) return;
    if (tab !== 'item') { box.innerHTML = ''; return; }
    const rec = recentItems().filter((id) => itemExists(id));
    if (!rec.length) { box.innerHTML = ''; return; }
    box.innerHTML = '<div class="picker-recent"><span class="rc-lbl">最近</span>'
      + rec.slice(0, 12).map((id) => {
          const on = picked.has(id) ? ' on' : '';
          return `<span class="rc-chip${on}" data-recent="${esc(id)}" title="${esc(id)}">${esc(itemLabel(id))}</span>`;
        }).join('')
      + '</div>';
  }
  $('#pickerRecent').addEventListener('click', (e) => {
    const b = e.target.closest('[data-recent]');
    if (!b) return;
    const id = b.dataset.recent;
    if (picked.has(id)) picked.delete(id); else { picked.add(id); }
    paintRecent();
    paintGrid();
    $('#pickerSel').textContent = selCount() ? `已选 ${selCount()} 个` : '';
  });

  $('#pickerTabs').addEventListener('click', (e) => {
    const b = e.target.closest('.ptab');
    if (!b) return;
    tab = b.dataset.tab;
    kw = ''; shown = 300;
    $('#pickerSearch').value = '';
    applyTab();
  });

  // 搜索输入：input 事件在 IME 组合期间可能不准，compositionend 兜底
  let searchTimer = 0;
  const onSearch = () => {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(() => {
      kw = $('#pickerSearch').value; shown = 300;
      // 检测是否像物品 ID（包含 : 且不含空格）→ 显示「添加 ID」栏
      const v = kw.trim();
      const isId = /^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(v);
      $('#pickerIdBar').style.display = isId ? '' : 'none';
      if (isId) $('#pickerIdPreview').textContent = v;
      repaint();
    }, 120);
  };
  $('#pickerSearch').addEventListener('input', onSearch);
  $('#pickerSearch').addEventListener('compositionend', onSearch);
  $('#pickerNs').addEventListener('change', (e) => { nsFilter = e.target.value; shown = 300; repaint(); });

  // 「添加 ID」按钮：把搜索框的内容当作物品 ID 直接加入已选
  // 严格模式：先过格式，再过「游戏里到底有没有」—— 不存在的物品会让整张表加载失败
  $('#pickerIdAdd').addEventListener('click', () => {
    const v = $('#pickerSearch').value.trim();
    if (!v) return;
    const ids = v.split(/[\s,，;；]+/).filter(Boolean);
    const bad = [], ghost = [], good = [];
    ids.forEach((x) => {
      if (ridProblem(x)) bad.push(x);
      else if (itemBad(x)) ghost.push(x);
      else good.push(x);
    });
    if (bad.length) {
      toast(`格式不对，没加：${bad.slice(0, 2).join('、')}（${ridProblem(bad[0])}）`, 'err');
    }
    if (ghost.length) {
      const why = itemBad(ghost[0]);
      const ok = window.confirm(
        `这 ${ghost.length} 个 ID 游戏里不存在：\n${ghost.slice(0, 6).join('\n')}\n\n` +
        `原因：${why}\n\n` +
        `Minecraft 只要遇到一个不存在的物品 ID，整张战利品表都会加载失败（宝箱全空）。\n` +
        `确定还要加进去吗？`);
      if (ok) ghost.forEach((x) => picked.add(x));
    }
    good.forEach((x) => picked.add(x));
    if (!good.length && !ghost.length) return;      // 全被格式挡下了，输入框留着让他改
    $('#pickerSearch').value = ''; kw = '';
    $('#pickerIdBar').style.display = 'none';
    toast(`已加入 ${good.length + ghost.length} 个`, 'ok');
    $('#pickerSel').textContent = selCount() ? `已选 ${selCount()} 个` : '';
    repaint();
  });

  $('#pickerGrid').addEventListener('click', (e) => {
    const it = e.target.closest('.picker-item');
    if (!it) return;
    if (tab === 'item') {
      const id = it.dataset.id;
      // 点到 TaCZ 基物品（枪械/弹药/配件/投掷物/消耗品）→ 自动跳到对应子类型页签去选具体型号
      const cat = taczCatOf(id);
      if (cat) {
        tab = cat[0];
        // 跳页签必须把搜索词一起清掉，否则旧关键词套到新页签的列表上 → 搜不出东西
        kw = ''; shown = 300;
        $('#pickerSearch').value = '';
        toast(`「${itemLabel(id)}」要选具体${S.tacz[tab].label}——已跳到对应分类`, 'ok');
        applyTab();
        return;
      }
    }
    const key = tab === 'item' ? it.dataset.id : it.dataset.sub;
    const idx = filtered.findIndex((x) => (tab === 'item' ? x.id : (tab + '|' + x.id)) === key);
    toggleItemAt(idx, e.shiftKey);
    paintGrid();
    $('#pickerSel').textContent = selCount() ? `已选 ${selCount()} 个` : '';
  });

  /* ---- 拖框批量选择：在网格空白处按住左键拖出矩形，中心落进框里的卡片全部选中 ---- */
  (() => {
    const grid = $('#pickerGrid');
    let startX = 0, startY = 0, box = null, dragging = false, moved = false;
    grid.style.position = 'relative';
    grid.addEventListener('pointerdown', (e) => {
      if (e.button !== 0) return;
      if (e.target.closest('.picker-item')) return;   // 点在卡片上走正常点击
      dragging = true; moved = false;
      const r = grid.getBoundingClientRect();
      startX = e.clientX - r.left + grid.scrollLeft;
      startY = e.clientY - r.top + grid.scrollTop;
      grid.setPointerCapture(e.pointerId);
    });
    grid.addEventListener('pointermove', (e) => {
      if (!dragging) return;
      const r = grid.getBoundingClientRect();
      const cx = e.clientX - r.left + grid.scrollLeft;
      const cy = e.clientY - r.top + grid.scrollTop;
      if (!moved && Math.hypot(cx - startX, cy - startY) < 6) return;
      moved = true;
      if (!box) {
        box = document.createElement('div');
        box.className = 'marquee-box';
        grid.appendChild(box);
      }
      const x = Math.min(startX, cx), y = Math.min(startY, cy);
      const w = Math.abs(cx - startX), h = Math.abs(cy - startY);
      Object.assign(box.style, { left: x + 'px', top: y + 'px', width: w + 'px', height: h + 'px' });
      const gr = grid.getBoundingClientRect();
      $$('.picker-item', grid).forEach((el) => {
        const er = el.getBoundingClientRect();
        const px = er.left - gr.left + grid.scrollLeft + er.width / 2;
        const py = er.top - gr.top + grid.scrollTop + er.height / 2;
        if (px >= x && px <= x + w && py >= y && py <= y + h) {
          const key = tab === 'item' ? el.dataset.id : el.dataset.sub;
          if (!(tab === 'item' ? picked.has(key) : pickedSub.has(key))) {
            const idx = filtered.findIndex((x2) => (tab === 'item' ? x2.id : (tab + '|' + x2.id)) === key);
            if (idx >= 0) { toggleItemAt(idx, false); el.classList.add('on'); }
          }
        }
      });
      $('#pickerSel').textContent = selCount() ? `已选 ${selCount()} 个` : '';
    });
    const end = () => {
      if (box) { box.remove(); box = null; }
      dragging = false;
    };
    grid.addEventListener('pointerup', end);
    grid.addEventListener('pointercancel', end);
    grid.addEventListener('click', (e) => {
      if (moved) { e.stopPropagation(); moved = false; }
    }, true);
  })();

  /* ---- 全选当前筛选 / 清空选择 ---- */
  $('#pickerSelectAll').addEventListener('click', () => {
    if (tab === 'item') filtered.forEach((x) => picked.add(x.id));
    else filtered.forEach((x) => pickedSub.set(tab + '|' + x.id, {
      key: tab, id: x.id, name: x.name || '', base: S.tacz[tab].base, label: S.tacz[tab].label }));
    paintGrid();
    $('#pickerSel').textContent = selCount() ? `已选 ${selCount()} 个` : '';
    toast(`已全选 ${filtered.length} 个（当前筛选结果）`, 'ok');
  });
  $('#pickerClearSel').addEventListener('click', () => {
    picked.clear(); pickedSub.clear(); anchorIdx = -1;
    paintGrid(); paintRecent();
    $('#pickerSel').textContent = '';
  });

  const body = $('#modalBox .modal-body');
  body.addEventListener('scroll', () => {
    if (body.scrollTop + body.clientHeight >= body.scrollHeight - 150 && shown < filtered.length) {
      shown += 300;
      paintGrid();
    }
  });

  const addManual = () => {
    const raw = $('#pickerManual').value.trim();
    if (!raw) return;
    // 支持一次粘多个（换行 / 逗号 / 空格分隔）
    const ids = raw.split(/[\s,，;；]+/).filter(Boolean);
    const bad = [], good = [];
    ids.forEach((v) => { (ridProblem(v) ? bad : good).push(v); });
    if (bad.length) {
      toast(`格式不对，没加：${bad.slice(0, 2).join('、')}（${ridProblem(bad[0])}）`, 'err');
      if (!good.length) return;
    }
    good.forEach((v) => picked.add(v));
    $('#pickerManual').value = '';
    toast(`已加入 ${good.length} 个`, 'ok');
    $('#pickerSel').textContent = selCount() ? `已选 ${selCount()} 个` : '';
    paintRecent();
  };
  $('#pickerManualAdd').addEventListener('click', addManual);
  $('#pickerManual').addEventListener('keydown', (e) => { if (e.key === 'Enter') addManual(); });

  $('#pickerOk').addEventListener('click', () => {
    if (!selCount()) { toast('还没选任何物品', 'warn'); return; }
    // TaCZ 子类型 -> 完整条目（基物品 + set_nbt）
    const extras = Array.from(pickedSub.values()).map((s) => ({
      type: 'minecraft:item',
      name: s.base,
      weight: 1,
      functions: [{ function: 'minecraft:set_nbt', tag: `{${s.key}:"${s.id}"}` }],
    }));
    closeModal();
    Array.from(picked).forEach(rememberItem);   // 记进「最近用过」
    onPick(Array.from(picked), extras);
  });
}

/* ============================================================
   模态框
   ============================================================ */
function closeModal() { modalSeq++; $('#modal').classList.add('hidden'); $('#modalBox').innerHTML = ''; }

/* 模态框序号守卫：每次打开/关闭模态框都会递增。
   异步 API 回调在写 DOM 前先核对序号，不一致说明用户已经把这个模态关掉了，
   直接放弃这次渲染——否则会在关闭后写进一个已被清空的 #modalBox。 */
let modalSeq = 0;
const modalAlive = (tk) => tk === modalSeq;

function openAudit() {
  const tk = ++modalSeq;
  $('#modalBox').className = 'modal-box';
  $('#modalBox').innerHTML = `<div class="modal-head"><h3>语法体检</h3><button class="btn" data-close="1">关闭</button></div>
    <div class="modal-body"><div class="muted">正在扫描…</div></div>`;
  $('#modal').classList.remove('hidden');
  api('/api/audit').then((r) => {
    if (!modalAlive(tk)) return;
    const body = $('#modalBox .modal-body');
    let h = `<div class="stat-grid">
      <div class="stat-box"><div class="sv">${r.scanned}</div><div class="sk">扫描文件</div></div>
      <div class="stat-box"><div class="sv ${r.errorCount ? 'err-text' : 'ok-text'}">${r.errorCount}</div><div class="sk">错误</div></div>
      <div class="stat-box"><div class="sv ${r.warningCount ? 'warn-text' : 'ok-text'}">${r.warningCount}</div><div class="sk">提示</div></div>
    </div>`;
    if (!r.problems.length) {
      h += '<div class="badge ok">✓ 全部文件语法正确，没有发现问题</div>';
    } else {
      for (const p of r.problems) {
        h += `<details class="report-item" ${p.errors.length ? 'open' : ''}>
          <summary>
            <span class="mono">${esc(p.path)}</span>
            ${p.errors.length ? `<span class="badge err">${p.errors.length} 错误</span>` : ''}
            ${p.warnings.length ? `<span class="badge warn">${p.warnings.length} 提示</span>` : ''}
          </summary>
          <div class="report-lines">
            ${p.errors.map((x) => `<div class="report-line e"><span class="tag">错误</span><span>${esc(x)}</span></div>`).join('')}
            ${p.warnings.map((x) => `<div class="report-line w"><span class="tag">提示</span><span>${esc(x)}</span></div>`).join('')}
          </div>
          <div style="padding:0 11px 9px"><button class="btn sm" data-open="${esc(p.path)}">打开这个文件</button></div>
        </details>`;
      }
    }
    body.innerHTML = h;
  }).catch((e) => {
    if (!modalAlive(tk)) return;
    $('#modalBox .modal-body').innerHTML = `<div class="err-text">${esc(e.message)}</div>`;
  });
}

function openDupes() {
  const tk = ++modalSeq;
  $('#modalBox').className = 'modal-box sm';
  $('#modalBox').innerHTML = `<div class="modal-head"><h3>重复池分析</h3><button class="btn" data-close="1">关闭</button></div>
    <div class="modal-body"><div class="muted">正在比对…</div></div>`;
  $('#modal').classList.remove('hidden');
  api('/api/duplicates').then((r) => {
    if (!modalAlive(tk)) return;
    const body = $('#modalBox .modal-body');
    if (!r.groups.length) { body.innerHTML = '<div class="badge ok">✓ 没有内容完全相同的池文件</div>'; return; }
    let h = `<div class="muted" style="margin-bottom:12px">下面这些文件内容<b>完全一致</b>。改其中一个不会影响其他的——如果它们本该同步，建议合并成一份再被多处引用。</div>`;
    r.groups.forEach((g, i) => {
      h += `<details class="report-item" open><summary><span class="badge pri">${g.count} 份相同</span>
        <span class="mono">${esc(g.files[0])}</span></summary>
        <div class="report-lines">${g.files.map((f) => `<div class="report-line"><span>${esc(f)}</span></div>`).join('')}</div>
      </details>`;
    });
    body.innerHTML = h;
  }).catch((e) => {
    if (!modalAlive(tk)) return;
    $('#modalBox .modal-body').innerHTML = `<div class="err-text">${esc(e.message)}</div>`;
  });
}

function openFind(initial) {
  const tk = ++modalSeq;
  $('#modalBox').className = 'modal-box';
  $('#modalBox').innerHTML = `
    <div class="modal-head">
      <h3>反查物品</h3>
      <span class="picker-count">看看某个物品都被哪些池子用了</span>
      <button class="btn" data-close="1">关闭</button>
    </div>
    <div class="modal-body">
      <div class="picker-bar">
        <input class="input" id="findInput" placeholder="物品 ID 或名字片段，例如 caf:money / 钻石 / tacz" value="${esc(initial || '')}" autocomplete="off">
        <button class="btn pri" id="findGo">查找</button>
      </div>
      <div id="findResult"><div class="muted">输入关键词后回车。会列出所有引用它的池文件和位置。</div></div>
    </div>`;
  $('#modal').classList.remove('hidden');
  $('#findInput').focus();

  const go = () => {
    const q = $('#findInput').value.trim();
    if (!q) return;
    $('#findResult').innerHTML = '<div class="muted">查找中…</div>';
    api('/api/find?q=' + encodeURIComponent(q)).then((r) => {
      if (!modalAlive(tk)) return;
      const box = $('#findResult');
      if (!box) return;
      if (!r.hits.length) {
        box.innerHTML = `<div class="badge warn">没有找到引用「${esc(q)}」的条目</div>`;
        return;
      }
      const byFile = {};
      r.hits.forEach((h) => { (byFile[h.path] = byFile[h.path] || []).push(h); });
      let h = `<div class="muted" style="margin-bottom:10px">共 <b>${r.hits.length}</b> 处引用，分布在 <b>${Object.keys(byFile).length}</b> 个文件</div>`;
      Object.keys(byFile).sort().forEach((p) => {
        h += `<details class="report-item" open><summary>
          <span class="mono">${esc(p)}</span>
          <span class="badge pri">${byFile[p].length} 处</span>
        </summary><div class="report-lines">`;
        byFile[p].forEach((hit) => {
          const label = hit.name.startsWith('#') ? hit.name : itemLabel(hit.name);
          h += `<div class="report-line">
            <span class="tag" style="background:var(--pri-soft);color:var(--pri)">池${hit.pool + 1}</span>
            <span>${iconTag(hit.name, 18)}</span>
            <span>${esc(label)} <span class="muted mono">${esc(hit.name)}</span> · 权重 ${esc(String(hit.weight))}</span>
          </div>`;
        });
        h += `</div><div style="padding:0 11px 9px"><button class="btn sm" data-open="${esc(p)}">打开这个文件</button></div></details>`;
      });
      box.innerHTML = h;
    }).catch((e) => {
      if (!modalAlive(tk)) return;
      const box = $('#findResult');
      if (box) box.innerHTML = `<div class="err-text">${esc(e.message)}</div>`;
    });
  };
  $('#findGo').addEventListener('click', go);
  $('#findInput').addEventListener('keydown', (e) => { if (e.key === 'Enter') go(); });
  if (initial) go();
}

/* ============================================================
   编辑动作
   ============================================================ */
/* ---------------- 撤销 / 重做 ---------------- */
const UNDO_MAX = 100;        // 最多记多少步
const UNDO_MERGE_MS = 700;   // 连续输入（打字）合并成一步，不会一个字一步

function _snapNow() {
  return { path: S.cur.path, json: JSON.stringify(S.cur.data), data: deepClone(S.cur.data) };
}

function resetUndo() {
  S.undo = [];
  S.redo = [];
  S.undoSnap = S.cur ? _snapNow() : null;
  S.undoAt = 0;
}

function markDirty() {
  if (S.cur) {
    S.cur.dirty = true;
    const t = Date.now();
    if (S.undoSnap && S.undoSnap.path === S.cur.path) {
      const now = JSON.stringify(S.cur.data);
      if (now !== S.undoSnap.json) {
        // 距上次记录很近 → 当作同一步（打字合并），不再压栈
        const merge = (t - S.undoAt) < UNDO_MERGE_MS && S.undo.length > 0;
        if (!merge) {
          S.undo.push(S.undoSnap);
          if (S.undo.length > UNDO_MAX) S.undo.shift();
        }
        S.redo.length = 0;                 // 有新改动就作废重做栈
        S.undoSnap = { path: S.cur.path, json: now, data: deepClone(S.cur.data) };
        S.undoAt = t;
      }
    } else {
      S.undoSnap = _snapNow();             // 换了文件：重新开始记
      S.undoAt = t;
    }
  }
  scheduleLiveValidate();
}

function doUndo() {
  if (!S.cur) return;
  if (!S.undo.length) { toast('没有可撤销的了'); return; }
  const cur = _snapNow();
  const prev = S.undo.pop();
  S.redo.push(cur);
  if (S.redo.length > UNDO_MAX) S.redo.shift();
  S.cur.data = prev.data;
  S.cur.dirty = true;
  S.undoSnap = { path: prev.path, json: prev.json, data: deepClone(prev.data) };
  S.undoAt = 0;
  refresh(true);
  scheduleLiveValidate();
  toast(`已撤销（还能撤 ${S.undo.length} 步）`);
}

function doRedo() {
  if (!S.cur) return;
  if (!S.redo.length) { toast('没有可重做的了'); return; }
  const cur = _snapNow();
  const next = S.redo.pop();
  S.undo.push(cur);
  if (S.undo.length > UNDO_MAX) S.undo.shift();
  S.cur.data = next.data;
  S.cur.dirty = true;
  S.undoSnap = { path: next.path, json: next.json, data: deepClone(next.data) };
  S.undoAt = 0;
  refresh(true);
  scheduleLiveValidate();
  toast(`已重做（还能重做 ${S.redo.length} 步）`);
}

/* 编辑后防抖触发一次校验：让状态徽章和开箱预览跟着数据走，但不重排编辑区 */
let _lvTimer = null;
function scheduleLiveValidate() {
  if (!S.cur) return;
  clearTimeout(_lvTimer);
  _lvTimer = setTimeout(() => {
    if (!S.cur) return;
    post('/api/validate', { data: S.cur.data }).then((r) => {
      if (!S.cur) return;
      S.cur.report = r.report;
      const b = $('#statusBadge');
      if (b) {
        if (r.report.errors.length) { b.className = 'badge err'; b.textContent = `✕ ${r.report.errors.length} 个错误`; }
        else if (r.report.warnings.length) { b.className = 'badge warn'; b.textContent = `⚠ ${r.report.warnings.length} 个提示`; }
        else { b.className = 'badge ok'; b.textContent = '✓ 语法通过'; }
      }
      renderChest();
    }).catch(() => {});
  }, 450);
}

/* ============================================================
   开箱预览（模拟游戏里的箱子实际开出什么）
   规则：当前战利品表有任何「错误」就一件都不显示 —— 因为非法的表在游戏里本来就开不出来。
   ============================================================ */

function rollInt(r) {
  if (r == null) return 0;
  if (typeof r === 'number') return Math.round(r);
  if (typeof r === 'object') {
    if (typeof r.n === 'number' && typeof r.p === 'number') {  // 二项分布
      let c = 0;
      for (let i = 0; i < r.n; i++) if (Math.random() < r.p) c++;
      return c;
    }
    const a = num(r.min, 0), b = num(r.max, 0);
    return Math.floor(a + Math.random() * (b - a + 1));
  }
  return 0;
}

function condPasses(c) {
  const t = norm(c.condition);
  if (t === 'minecraft:random_chance' || t === 'minecraft:random_chance_with_looting') {
    return Math.random() < num(c.chance, 0.5);
  }
  return true;   // 其余条件（比如需要玩家击杀）在预览里按满足处理
}

function pickWeighted(entries) {
  const valid = (entries || []).filter((e) => Math.max(0, num(e.weight, 1)) > 0);
  const total = valid.reduce((a, e) => a + Math.max(0, num(e.weight, 1)), 0);
  if (total <= 0) return null;
  let r = Math.random() * total;
  for (const e of valid) {
    r -= Math.max(0, num(e.weight, 1));
    if (r < 0) return e;
  }
  return valid[valid.length - 1];
}

function collectEntry(e, out, depth) {
  if (!e || depth > 6) return;
  if (e.conditions && !e.conditions.every(condPasses)) return;
  const t = norm(e.type);
  if (t === 'minecraft:empty') return;
  if (t === 'minecraft:item') {
    let count = 1;
    const cf = (e.functions || []).find((f) => norm(f.function) === 'minecraft:set_count');
    if (cf) count = Math.max(1, rollInt(cf.count));
    const extras = entryExtra(e);
    const sub = extras.find((x) => x.kind.startsWith('tacz') || x.kind === 'song');
    const tt = taczOf(e);
    const tinfo = tt ? S.taczIdx[tt.key + '|' + tt.id] : null;
    out.push({
      id: e.name, count,
      label: sub ? sub.text : itemLabel(e.name),
      missing: !!itemBad(e.name),
      icon: (tinfo && tinfo.icon) ? tinfo.icon : null,
    });
    return;
  }
  if (t === 'minecraft:group') { (e.children || []).forEach((c) => collectEntry(c, out, depth + 1)); return; }
  // 官方语义：alternatives 按顺序取「第一个条件全部通过」的子项（不是按权重抽）。
  // 真实战利品表里它就是这么用的：把条件苛刻的条目放前面当「优先掉落」。
  if (t === 'minecraft:alternatives') {
    const p = (e.children || []).find((c) => !c.conditions || c.conditions.every(condPasses));
    if (p) collectEntry(p, out, depth + 1);
    return;
  }
  if (t === 'minecraft:sequence') { (e.children || []).forEach((c) => collectEntry(c, out, depth + 1)); return; }
  // tag / loot_table / dynamic：预览里给个占位
  out.push({ id: e.name || e.value || t, count: 1, label: entrySummary(e), placeholder: true });
}

/* 和 Lootr 对齐的开箱模拟。
   Lootr 行为（反编译 lootr-forge-1.20-0.7.35.94 的 unpackLootTable 确认）：
   - 表解析失败 → 箱子不填任何东西，玩家打开时收到「模组[ns]错误！找不到战利品表」消息 = 「故障」；
   - 表有效 → 按 vanilla LootTable.fill 抽取，种子 = f(箱子, 玩家UUID)，每个玩家开到的都不同；
   - 27 格内随机摆放（fill 内部 shuffle）。
   预览里「重新抽取」= 模拟另一个玩家开同一个箱子。
   简化项：不模拟玩家幸运值（luck 只影响 quality 和 table_bonus，整合包条目基本不用）。 */
function simulateLoot(data) {
  const rep = (S.cur && S.cur.report) || { errors: [], warnings: [] };
  if (rep.errors.length) return { blocked: true, items: [] };
  const out = [];
  let hadChance = false;
  (data.pools || []).forEach((p) => {
    // 按真实概率来：池级 random_chance 决定这个池触不触发
    let poolChance = 1;
    for (const c of p.conditions || []) {
      const t = norm(c.condition);
      if (t === 'minecraft:random_chance' || t === 'minecraft:random_chance_with_looting') {
        poolChance *= num(c.chance, 0.5);
        hadChance = true;
      }
    }
    if (Math.random() >= poolChance) return;   // 池没触发，跳过
    const n = rollInt(p.rolls) + rollInt(p.bonus_rolls || 0);
    for (let i = 0; i < n; i++) {
      const e = pickWeighted(p.entries || []);
      if (e) collectEntry(e, out, 0);
    }
  });
  return { blocked: false, items: out, hadChance };
}

const CHEST_SLOTS = 27;   // 标准箱子 9×3

/* 开箱预览的结构签名：条目增删/类型/数量/标签变了就变（不含权重）。
   用来判断该不该重roll——结构变了才重roll，改权重不晃眼。 */
function chestSignature(d) {
  try {
    return JSON.stringify((d.pools || []).map((p) => [
      p.rolls, p.bonus_rolls,
      (p.entries || []).map((e) => [e.type, e.name || e.value,
        (e.functions || []).map((f) => (f.function || '') + (f.count ? JSON.stringify(f.count) : '') + (f.tag || ''))]),
    ]));
  } catch (e) { return String(Math.random()); }
}

function renderChest(reroll) {
  const panel = $('#chestPanel');
  if (!panel || !S.cur) return;
  const rep = S.cur.report || { errors: [], warnings: [] };
  const blocked = rep.errors.length > 0 || !!S.cur.failedInGame;
  // 纯手动：只有点「重新抽取」、换了文件、或上次是被挡住的状态才重roll。
  // 平时编辑（包括加/删物品、改权重）一律不动它——用户明确要手动控制。
  // 结构变了只给个「已改动」提示，不主动换箱。
  const sig = chestSignature(S.cur.data);
  const stale = S.chestCache && S.chestCache.path === S.cur.path && S.chestCache.sig !== sig;
  if (!blocked &&
      (reroll || !S.chestCache || S.chestCache.path !== S.cur.path || S.chestCache.blocked)) {
    const sim = simulateLoot(S.cur.data);
    // 一次性把物品摆进格子并缓存下来（包括布局）
    const SLOTS = CHEST_SLOTS;
    const slots = new Array(SLOTS).fill(null);
    const usedIdx = new Set();
    sim.items.slice(0, SLOTS).forEach((it) => {
      let idx, guard = 0;
      do { idx = Math.floor(Math.random() * SLOTS); guard++; } while (usedIdx.has(idx) && guard < 200);
      usedIdx.add(idx);
      slots[idx] = it;
    });
    S.chestCache = { path: S.cur.path, sig, items: sim.items, hadChance: sim.hadChance, slots, blocked: false };
  }
  // 被挡住且还没有缓存（比如一打开就是错的表）：给个空缓存避免崩
  if (!S.chestCache) S.chestCache = { path: S.cur.path, sig, items: [], hadChance: false, slots: [], blocked: true };
  const items = S.chestCache.items;
  const hadChance = S.chestCache.hadChance;
  const slots = S.chestCache.slots;

  let gridHtml = '';
  if (blocked) {
    gridHtml = S.cur.failedInGame && !rep.errors.length
      ? `<div class="chest-blocked">
      <div class="cb-icon">🧨</div>
      <div>这张表<b>在游戏里加载失败</b> —— 玩家打开箱子会收到「找不到这个容器的战利品表，战利品无法生成」，箱子里什么都没有。</div>
      <div class="muted" style="margin-top:6px;font-size:11.5px">游戏日志上次启动时判定它解析错误；本地校验没抓到全部原因时，先「重建物品库」刷新判定再修。</div>
    </div>`
      : `<div class="chest-blocked">
      <div class="cb-icon">⛔</div>
      <div>这张表有 <b>${rep.errors.length} 个错误</b>，游戏里开不出任何东西。</div>
      <div class="muted" style="margin-top:6px;font-size:11.5px">先修好错误（见上方红色提示），这里才会模拟出内容。</div>
    </div>`;
  } else if (!items.length) {
    // 空箱也要画出一个 27 格全空的箱子——「直观地看到空」
    gridHtml = '<div class="chest-grid chest-grid-empty">'
      + '<div class="chest-slot"></div>'.repeat(27)
      + '</div>'
      + '<div class="chest-empty-note">这一箱是空的（概率/权重所致，属正常）。点「重新抽取」再开一箱。</div>';
  } else {
    gridHtml = '<div class="chest-grid">' + slots.map((it) => {
      if (!it) return '<div class="chest-slot"></div>';
      const iconHtml = it.icon
        ? `<img class="entry-icon" style="width:26px;height:26px" src="/${esc(it.icon)}" loading="lazy" onerror="this.style.visibility='hidden'">`
        : (it.placeholder ? '<div class="chest-ph">◈</div>' : iconTag(it.id, 30));
      return `<div class="chest-slot ${it.missing ? 'missing' : ''}" title="${esc(it.label)}\n${esc(it.id)}${it.missing ? '\n（游戏里没有这个物品）' : ''}">
        ${iconHtml}
        ${it.count > 1 ? `<span class="chest-count">${it.count}</span>` : ''}
        <span class="chest-label">${esc(it.label)}</span>
      </div>`;
    }).join('') + '</div>';
  }

  // 整箱空概率：从当前数据实时算，跟随机那一箱无关，改权重立刻变
  const pEmpty = emptyProbability(S.cur.data);
  const pEmptyPct = (pEmpty * 100);
  const emptyCls = pEmptyPct >= 50 ? 'hi' : (pEmptyPct > 0 ? 'mid' : 'none');

  panel.innerHTML = `
    <div class="chest-head">
      <div class="chest-title">开箱预览</div>
      <button class="btn sm pri" id="btnReroll" title="重新模拟一箱">🎲 重新抽取</button>
    </div>
    <div class="chest-empty-stat ${emptyCls}">
      <div class="ces-num">${pEmptyPct.toFixed(pEmptyPct >= 10 ? 0 : 1)}<span>%</span></div>
      <div class="ces-txt">整箱是空的概率<br><span class="muted" style="font-weight:400;font-size:10.5px">（什么都不掉）</span></div>
    </div>
    <div class="chest-note">随机模拟的一箱${hadChance ? '（含池触发概率）' : ''}，只有你点「重新抽取」才会换${stale ? ' · <span class="chest-stale">内容已改动</span>' : ''}</div>
    ${pEmptyPct >= 30 ? `<div class="chest-warn">📉 游戏里约 ${pEmptyPct.toFixed(0)}% 的开箱不会出任何东西 —— ${esc(poolGateHint((S.cur && S.cur.report && S.cur.report.output) || null) || '条目权重/抽取次数配置导致')}<br><span class="muted">这是官方允许的写法（校验不会报错），但玩家会以为箱子坏了；已开过的箱子不会重抽，改完要用新箱子测。</span></div>` : ''}
    ${gridHtml}
    ${items.length ? `<div class="chest-sum">共 ${items.length} 种 / ${items.reduce((a, x) => a + x.count, 0)} 个</div>` : ''}
    ${blocked ? '' : (items.some((x) => x.missing) ? '<div class="chest-warn">红色底 = 游戏里没有的物品</div>' : '')}`;
}

/* ============================================================
   最近删除（点恢复无损还原）
   ============================================================ */
function pushDeleted(rec) {
  S.deleted.push({ when: Date.now(), ...rec });
  if (S.deleted.length > 50) S.deleted.splice(0, S.deleted.length - 50);
}

/* ============================================================
   切换战利品表目录（任何目录都能导进来）
   ============================================================ */
async function openChangeRoot() {
  const isDesktop = window.pywebview && window.pywebview.api && window.pywebview.api.pick_directory;
  let picked = null;
  if (isDesktop) {
    try {
      picked = await window.pywebview.api.pick_directory();   // 原生文件夹对话框
    } catch (e) { picked = null; }
    if (picked == null) return;   // 用户取消
    await doSetRoot(picked);
    return;
  }
  // 网页版：没有原生对话框，给个输入框粘路径
  $('#modalBox').className = 'modal-box sm';
  $('#modalBox').innerHTML = `
    <div class="modal-head"><h3>切换战利品表目录</h3><button class="btn" data-close="1">关闭</button></div>
    <div class="modal-body ai-set">
      <div class="set-row">
        <label>目录的完整路径（里面应是 *.json 战利品表）</label>
        <input type="text" id="rootPathInput" class="mono" placeholder="D:/…/loot_tables" value="${esc(S.root)}">
        <div class="hint">桌面程序里这个对话框会变成系统原生的「选择文件夹」窗口。网页版请手动粘贴路径。</div>
      </div>
    </div>
    <div class="modal-foot">
      <span class="spacer"></span>
      <button class="btn" data-close="1">取消</button>
      <button class="btn pri" id="rootGo">切换</button>
    </div>`;
  $('#modal').classList.remove('hidden');
  $('#rootGo').addEventListener('click', () => { doSetRoot($('#rootPathInput').value.trim()); });
  $('#rootPathInput').addEventListener('keydown', (e) => { if (e.key === 'Enter') doSetRoot($('#rootPathInput').value.trim()); });
}

/* ---------------- 整合包面板：换实例 / 重建物品库 ---------------- */
async function openPackDialog() {
  let info = { instance: '', current: S.root, dirs: [] };
  try { info = await api('/api/instances'); } catch (e) { /* 忽略 */ }

  $('#modalBox').className = 'modal-box';
  $('#modalBox').innerHTML = `
    <div class="modal-head"><h3>📦 整合包</h3><button class="btn" data-close="1">关闭</button></div>
    <div class="modal-body">
      <div class="set-row">
        <label>当前整合包实例</label>
        <div class="mono" style="word-break:break-all;font-size:12px">${esc(info.instance || '（没探测到）')}</div>
        <div class="hint">自动从工具所在位置往上找带 mods/ 或 kubejs/ 的目录。换整合包时把整个 _loot_editor 拷过去即可。</div>
      </div>
      <div class="set-row">
        <label>这个实例里的战利品表目录（点一下切过去）</label>
        <div id="packDirs">${(info.dirs || []).length
          ? info.dirs.map((d) => `<button class="btn sm block" data-dir="${esc(d.path)}"
              style="text-align:left;margin-bottom:5px">${esc(d.label)}<br>
              <span class="muted mono" style="font-size:11px">${esc(d.path)}</span></button>`).join('')
          : '<div class="muted">没找到 kubejs/data/*/loot_tables 或数据包目录</div>'}</div>
      </div>
      <div class="set-row">
        <label>物品库（名字 + 图标）</label>
        <div class="hint">换整合包后要按新实例重扫。扫不到的旧条目会保留，不会弄丢。</div>
        <button class="btn pri" id="packScan">重建物品库</button>
        <button class="btn" id="packScanFast">快速重建（不导图标）</button>
      </div>
      <div id="packLog" class="mono" style="font-size:11.5px;white-space:pre-wrap;max-height:220px;overflow:auto;margin-top:10px"></div>
    </div>
    <div class="modal-foot"><span class="spacer"></span><button class="btn" data-close="1">关闭</button></div>`;
  $('#modal').classList.remove('hidden');

  $('#packDirs').addEventListener('click', (e) => {
    const b = e.target.closest('[data-dir]');
    if (!b) return;
    closeModal();
    doSetRoot(b.dataset.dir);
  });

  const runScan = (noIcons) => {
    const log = $('#packLog');
    const t0 = Date.now();
    log.textContent = '正在扫描…（模组多的话要一两分钟，别关窗口）';
    post('/api/scan', { instance: info.instance, noIcons }).then(async (r) => {
      log.textContent = r.log || (r.ok ? '完成' : '失败');
      if (!r.ok) { toast('扫描失败，看下面日志', 'err'); return; }
      // 关键：重建完立刻把新的物品库拉回前端，不用刷新页面、更不用重启程序
      log.textContent += '\n\n[前端] 正在重新载入物品库…';
      try {
        const st = await loadItemDb({ silent: true });
        await reloadList();
        // 重渲染当前文件，让新物品的图标/中文名立刻显示出来
        if (S.cur) {
          const path = S.cur.path;
          await loadFile(path);
        }
        const spent = ((Date.now() - t0) / 1000).toFixed(0);
        log.textContent += `\n[前端] 已载入 ${st.itemCount.toLocaleString()} 个物品、`
          + `${st.taczKinds} 类 TaCZ 子类型 —— 重建完成，可以继续用了（共 ${spent}s）`;
        toast(`物品库已重建：${st.itemCount.toLocaleString()} 个物品，可继续使用`, 'ok');
      } catch (e) {
        log.textContent += `\n[前端] 重新载入失败：${e.message}（试试刷新页面）`;
        toast('重建成功但前端载入失败：' + e.message, 'err');
      }
    }).catch((e) => { log.textContent = e.message; toast(e.message, 'err'); });
  };
  $('#packScan').addEventListener('click', () => runScan(false));
  $('#packScanFast').addEventListener('click', () => runScan(true));
}

/* ---------------- 游戏内获取：生成指令 ---------------- */
function openGetDialog() {
  if (!S.cur) return;
  const ns = (S.root || '').replace(/[\\/]+$/, '').split(/[\\/]/).pop() || 'minecraft';
  const id = S.cur.lootId || (ns + ':' + S.cur.path.replace(/\.json$/, ''));
  const cmds = [
    ['刷 Lootr 箱子', '每人独立战利品，开过的人不会重复拿', `setblock ~ ~ ~ lootr:lootr_chest{LootTable:"${id}"}`],
    ['刷 Lootr 木桶', '同上，木桶外观', `setblock ~ ~ ~ lootr:lootr_barrel{LootTable:"${id}"}`],
    ['刷普通箱子', '所有人共享同一份战利品（原版行为）', `setblock ~ ~ ~ minecraft:chest{LootTable:"${id}"}`],
    ['直接刷到身上', '把这一箱的内容直接进背包', `loot give @s loot ${id}`],
    ['刷到地上', '在脚下生成掉落物', `loot spawn ~ ~ ~ loot ${id}`],
    ['塞进容器', '往准星对着的容器里塞一份', `loot insert ~ ~ ~ loot ${id}`],
  ];

  $('#modalBox').className = 'modal-box';
  const pEmpty = emptyProbability(S.cur.data);
  const outRep = (S.cur.report && S.cur.report.output) || null;
  let caveats = '';
  if (pEmpty >= 0.3) {
    const why = outRep ? poolGateHint(outRep) : '';
    caveats += `<div class="picker-warn">📉 这张表<b>约 ${(pEmpty * 100).toFixed(0)}% 的开箱是空的</b> ——
      所以你在游戏里连开几个箱子可能一件都没有，这不代表表坏了。${why ? '<br>' + esc(why) : ''}</div>`;
  }
  caveats += `<div class="picker-warn">⚠ 开过的箱子不会重抽：普通箱子和 Lootr 箱子的战利品都是
    <b>第一次打开时抽好并存在箱子/存档里</b>的。表修好之后，<b>要用新位置（重新 setblock）或没去过的建筑</b>测；
    已经开过的箱子永远是当时的（可能是空的）那一份。</div>`;
  $('#modalBox').innerHTML = `
    <div class="modal-head"><h3>🎮 游戏内获取</h3><button class="btn" data-close="1">关闭</button></div>
    <div class="modal-body">
      <div class="set-row">
        <label>战利品表</label>
        <div class="mono" style="font-size:12px;word-break:break-all">${esc(id)}</div>
        <div class="hint">在游戏聊天栏输入（需要开启作弊）。点指令右边的「复制」。</div>
      </div>
      ${caveats}
      <div class="set-row">
        <label>重复次数（只对「直接刷到身上」有效）</label>
        <input class="num" id="getTimes" type="number" min="1" max="50" value="1">
        <span class="muted">刷多次可以快速看掉落分布</span>
      </div>
      ${cmds.map(([t, d, c], i) => `
        <div class="set-row">
          <label>${esc(t)} <span class="muted" style="font-weight:400">— ${esc(d)}</span></label>
          <div style="display:flex;gap:6px;align-items:flex-start">
            <code class="mono" id="getCmd${i}" style="flex:1;font-size:11.5px;word-break:break-all;
              background:var(--panel-2);border:1px solid var(--line);border-radius:6px;padding:6px 8px">/${esc(c)}</code>
            <button class="btn sm" data-copy="${i}">复制</button>
          </div>
        </div>`).join('')}
    </div>
    <div class="modal-foot"><span class="spacer"></span><button class="btn" data-close="1">关闭</button></div>`;
  $('#modal').classList.remove('hidden');

  $('#modalBox').addEventListener('click', (e) => {
    const b = e.target.closest('[data-copy]');
    if (!b) return;
    const i = +b.dataset.copy;
    let text = '/' + cmds[i][2];
    if (i === 3) {
      const n = Math.max(1, Math.min(50, +($('#getTimes').value || 1)));
      if (n > 1) text = Array(n).fill(text).join('\n');   // 重复多次，粘进聊天栏逐条执行
    }
    copyText(text).then((ok) => toast(ok ? '已复制' : '复制失败，请手动选中', ok ? 'ok' : 'err'))
                  .catch(() => toast('复制失败，请手动选中', 'err'));
  });
}

/* 复制到剪贴板（桌面端优先用 pywebview 的原生剪贴板） */
async function copyText(text) {
  try {
    if (window.pywebview && window.pywebview.api && window.pywebview.api.copy_text) {
      await window.pywebview.api.copy_text(text);
      return true;
    }
  } catch (e) { /* 落到浏览器方案 */ }
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch (e) {
    const ta = document.createElement('textarea');
    ta.value = text;
    document.body.appendChild(ta);
    ta.select();
    const ok = document.execCommand('copy');
    document.body.removeChild(ta);
    return ok;
  }
}

async function doSetRoot(path) {
  if (!path) { toast('没填目录', 'warn'); return; }
  try {
    const r = await post('/api/setroot', { path });
    if (!r.ok) { toast(r.error || '切换失败', 'err'); return; }
    S.cur = null; S.chestCache = null;
    $('#editor').classList.add('hidden');
    $('#emptyState').classList.remove('hidden');
    $('#metaRoot').textContent = r.root;
    closeModal();
    await reloadList();
    toast(`已切换目录（${r.files} 个池文件）`, 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

/* ============================================================
   新建池文件（丝滑对话框：选目录 + 填名字）
   ============================================================ */
function openNewFileDialog() {
  // 收集已有目录
  const dirs = [...new Set(S.files.map((f) => f.dir || ''))].sort();
  $('#modalBox').className = 'modal-box sm';
  $('#modalBox').innerHTML = `
    <div class="modal-head"><h3>新建池文件</h3><button class="btn" data-close="1">关闭</button></div>
    <div class="modal-body ai-set">
      <div class="set-row">
        <label>放在哪个目录</label>
        <div class="picker-tabs" id="nfDirs" style="margin-bottom:8px">
          ${dirs.map((d) => `<button class="ptab ${d === 'chests/zhanlipin' ? '' : ''}" data-dir="${esc(d)}">${d ? '📁 ' + esc(d) : '📄 根目录'}</button>`).join('')}
        </div>
        <input type="text" id="nfDir" class="mono" value="chests/zhanlipin" placeholder="目录，例如 chests/zhanlipin">
        <div class="hint">点上面的目录快捷选，或直接改这里</div>
      </div>
      <div class="set-row">
        <label>文件名（不用写 .json）</label>
        <input type="text" id="nfName" class="mono" placeholder="新池" value="新池">
      </div>
    </div>
    <div class="modal-foot">
      <span class="muted">会在 <b id="nfPreview"></b> 创建</span>
      <span class="spacer"></span>
      <button class="btn" data-close="1">取消</button>
      <button class="btn pri" id="nfGo">创建</button>
    </div>`;
  $('#modal').classList.remove('hidden');
  const upd = () => {
    const dir = $('#nfDir').value.trim().replace(/^\/+|\/+$/g, '');
    const name = $('#nfName').value.trim() || '新池';
    $('#nfPreview').textContent = (dir ? dir + '/' : '') + name + '.json';
  };
  $$('#nfDirs .ptab').forEach((b) => b.addEventListener('click', () => {
    $('#nfDir').value = b.dataset.dir; upd();
  }));
  $('#nfDir').addEventListener('input', upd);
  $('#nfName').addEventListener('input', upd);
  upd();
  $('#nfGo').addEventListener('click', () => {
    const dir = $('#nfDir').value.trim().replace(/^\/+|\/+$/g, '');
    const name = ($('#nfName').value.trim() || '新池').replace(/\.json$/i, '');
    const path = (dir ? dir + '/' : '') + name + '.json';
    post('/api/new', { path }).then((r) => {
      if (!r.ok) return toast(r.error, 'err');
      closeModal();
      toast('已创建 ' + r.path, 'ok');
      reloadList().then(() => loadFile(r.path));
    }).catch((e) => toast(e.message, 'err'));
  });
}

/* ============================================================
   清理废弃条目：一键找出并删除「游戏里没有的物品 / 已卸载枪包」
   ============================================================ */
function isDeadEntry(e) {
  // 返回废弃原因字符串，或 null
  const t = norm(e.type);
  if (t === 'minecraft:item' && e.name && !e.name.startsWith('#')) {
    const why = itemBad(e.name);
    if (why) return why;
  }
  const tc = taczOf(e);
  if (tc && !tc.known) {
    return `枪包已卸载（${tc.id}）`;
  }
  return null;
}

function openCleanDead() {
  if (!S.cur) { toast('先打开一个池文件', 'warn'); return; }
  const dead = [];
  (S.cur.data.pools || []).forEach((p, pi) => {
    (p.entries || []).forEach((e, ei) => {
      const why = isDeadEntry(e);
      if (why) dead.push({ pi, ei, label: entrySummary(e), name: e.name || '', why });
    });
  });
  $('#modalBox').className = 'modal-box';
  $('#modalBox').innerHTML = `
    <div class="modal-head"><h3>清理废弃条目</h3><button class="btn" data-close="1">关闭</button></div>
    <div class="modal-body">
      ${dead.length ? `
        <div class="muted" style="margin-bottom:10px">当前文件里有 <b style="color:var(--err)">${dead.length}</b> 条废弃条目（引用了游戏里没有的东西）。删掉不影响其它的。</div>
        ${dead.map((d, i) => `
          <div class="del-row">
            <span class="entry-icon-wrap">${iconTag(d.name)}</span>
            <span class="del-txt">
              <div class="cn">${esc(d.label)}</div>
              <div class="id muted">池${d.pi + 1} 第${d.ei + 1}条 · ${esc(d.name)} · <span style="color:var(--err)">${esc(d.why)}</span></div>
            </span>
          </div>`).join('')}
      ` : '<div class="badge ok" style="display:block;padding:16px;text-align:center">✓ 没有废弃条目，都健在</div>'}
    </div>
    ${dead.length ? `<div class="modal-foot">
      <span class="muted">删除的条目会进「最近删除」，可以恢复</span>
      <span class="spacer"></span>
      <button class="btn" data-close="1">取消</button>
      <button class="btn danger" id="cleanDeadGo">删除这 ${dead.length} 条</button>
    </div>` : ''}`;
  $('#modal').classList.remove('hidden');
  const goBtn = $('#cleanDeadGo');
  if (goBtn) goBtn.addEventListener('click', () => {
    // 从后往前删，索引不会乱
    const byPool = {};
    dead.forEach((d) => { (byPool[d.pi] = byPool[d.pi] || []).push(d.ei); });
    Object.entries(byPool).forEach(([pi, idxs]) => {
      idxs.sort((a, b) => b - a).forEach((ei) => {
        const e = S.cur.data.pools[pi].entries[ei];
        pushDeleted({ path: S.cur.path, pool: +pi, index: ei, entry: deepClone(e), label: entrySummary(e) });
        S.cur.data.pools[pi].entries.splice(ei, 1);
      });
    });
    markDirty(); refresh(true);
    closeModal();
    toast(`已清理 ${dead.length} 条废弃条目（可到「最近删除」恢复）`, 'ok');
  });
}

/* ============================================================
   查重复：同一个池里，同一物品+同样NBT重复出现的条目
   ============================================================ */
function entryKey(e) {
  // 判断两条目是不是「同一个东西」：类型 + 名字 + 全部 functions + conditions + children
  // 注意：以前只比 set_nbt，会把「同一物品但数量不同」的两条误判成重复，
  // 合并时权重相加就等于偷偷改了产出，这里按完整条目比。
  const t = norm(e.type);
  const funcs = (e.functions || []).map((f) => stableJson(f)).sort();
  const conds = (e.conditions || []).map((c) => stableJson(c)).sort();
  const kids = (e.children || []).map((c) => entryKey(c));
  const extra = [
    t,
    e.name || e.value || '',
    e.expand === undefined ? '' : String(e.expand),   // tag 条目的 expand
    JSON.stringify(funcs),
    JSON.stringify(conds),
    JSON.stringify(kids),
  ].join('::');
  return extra;
}

/* 把对象按键名排序后序列化：NBT 里键的顺序不同，游戏里其实是同一个东西 */
function stableJson(v) {
  if (v === null || typeof v !== 'object') return JSON.stringify(v);
  if (Array.isArray(v)) return '[' + v.map(stableJson).join(',') + ']';
  return '{' + Object.keys(v).sort().map((k) => JSON.stringify(k) + ':' + stableJson(v[k])).join(',') + '}';
}

function openDupItems() {
  if (!S.cur) { toast('先打开一个池文件', 'warn'); return; }
  const groups = [];
  (S.cur.data.pools || []).forEach((p, pi) => {
    const seen = {};
    (p.entries || []).forEach((e, ei) => {
      const k = entryKey(e);
      if (e.type === 'minecraft:empty') return;   // 空条目不算重复
      (seen[k] = seen[k] || []).push(ei);
    });
    Object.values(seen).forEach((idxs) => {
      if (idxs.length > 1) {
        groups.push({ pi, idxs, entry: p.entries[idxs[0]] });
      }
    });
  });
  $('#modalBox').className = 'modal-box';
  $('#modalBox').innerHTML = `
    <div class="modal-head"><h3>查重复</h3><button class="btn" data-close="1">关闭</button></div>
    <div class="modal-body">
      ${groups.length ? `
        <div class="muted" style="margin-bottom:10px">同一个池里，同一物品+同样 NBT 出现了多次。它们概率是叠加的，可以合并成一条（权重相加）。</div>
        ${groups.map((g, i) => `
          <div class="del-row">
            <span class="entry-icon-wrap">${iconTag(g.entry.name)}</span>
            <span class="del-txt">
              <div class="cn">${esc(entrySummary(g.entry))}</div>
              <div class="id muted">池${g.pi + 1} · 重复 ${g.idxs.length} 次（权重各 ${g.idxs.map((x) => Math.max(1, num(g.entry.weight, 1))).join(' / ') || ''}）</div>
            </span>
            <button class="btn sm" data-merge="${i}">合并成一条</button>
          </div>`).join('')}
      ` : '<div class="badge ok" style="display:block;padding:16px;text-align:center">✓ 没有重复条目</div>'}
    </div>
    ${groups.length ? `<div class="modal-foot">
      <span class="muted">合并 = 权重相加，多余条目删除（进最近删除可恢复）</span>
      <span class="spacer"></span>
      <button class="btn" data-close="1">取消</button>
      <button class="btn pri" id="dupMergeAll">全部合并</button>
    </div>` : ''}`;
  $('#modal').classList.remove('hidden');

  const doMerge = (g) => {
    const p = S.cur.data.pools[g.pi];
    // 把权重相加到第一条，其余删掉
    let totalW = 0;
    g.idxs.forEach((ei) => { totalW += Math.max(1, num(p.entries[ei].weight, 1)); });
    g.idxs.slice(1).sort((a, b) => b - a).forEach((ei) => {
      pushDeleted({ path: S.cur.path, pool: g.pi, index: ei, entry: deepClone(p.entries[ei]), label: entrySummary(p.entries[ei]) });
      p.entries.splice(ei, 1);
    });
    p.entries[g.idxs[0]].weight = totalW;
  };
  $$('#modalBox [data-merge]').forEach((btn) => {
    btn.addEventListener('click', () => {
      const g = groups[+btn.dataset.merge];
      doMerge(g);
      markDirty(); refresh(true);
      btn.closest('.del-row').classList.add('restored');
      btn.textContent = '已合并';
      btn.disabled = true;
      toast('已合并', 'ok');
    });
  });
  const allBtn = $('#dupMergeAll');
  if (allBtn) allBtn.addEventListener('click', () => {
    groups.forEach(doMerge);
    markDirty(); refresh(true);
    closeModal();
    toast(`已合并 ${groups.length} 组重复条目`, 'ok');
  });
}

function openRecentDeleted() {
  const list = S.deleted.slice().reverse().slice(0, 10);   // 最近的在前
  $('#modalBox').className = 'modal-box';
  $('#modalBox').innerHTML = `
    <div class="modal-head"><h3>最近删除</h3><button class="btn" data-close="1">关闭</button></div>
    <div class="modal-body">
      ${list.length ? '' : '<div class="muted" style="padding:22px;text-align:center">还没有删过东西</div>'}
      ${list.map((r, i) => `
        <div class="del-row">
          <span class="entry-icon-wrap">${r.isPool ? '<div class="entry-icon" style="display:grid;place-items:center;color:#93a0b5;font-size:11px">池</div>' : iconTag(r.entry && r.entry.name)}</span>
          <span class="del-txt">
            <div class="cn">${esc(r.label)}</div>
            <div class="id muted">${esc(r.path)}${r.isPool ? '' : ` · 池${r.pool + 1}`} · ${new Date(r.when).toLocaleTimeString('zh-CN')}</div>
          </span>
          <button class="btn sm" data-restore="${i}">恢复</button>
        </div>`).join('')}
    </div>`;
  $('#modal').classList.remove('hidden');
  $$('#modalBox [data-restore]').forEach((btn) => {
    btn.addEventListener('click', () => {
      restoreDeleted(list[+btn.dataset.restore]);
      const row = btn.closest('.del-row');
      if (row) row.classList.add('restored');
      btn.textContent = '已恢复';
      btn.disabled = true;
    });
  });
}

function restoreDeleted(rec) {
  const doRestore = () => {
    const d = S.cur.data;
    d.pools = d.pools || [];
    if (rec.isPool) {
      d.pools.splice(Math.min(rec.pool, d.pools.length), 0, rec.entry);
    } else if (rec.isChild) {
      let p = d.pools[rec.pool];
      if (!p) { d.pools.push(p = { rolls: 1, entries: [] }); }
      p.entries = p.entries || [];
      let parent = p.entries[rec.index];
      if (!parent) { p.entries.push(parent = { type: 'minecraft:group', weight: 1, children: [] }); }
      parent.children = parent.children || [];
      parent.children.splice(Math.min(rec.childIdx, parent.children.length), 0, rec.entry);
    } else {
      let p = d.pools[rec.pool];
      if (!p) { d.pools.push(p = { rolls: 1, entries: [] }); }
      p.entries = p.entries || [];
      p.entries.splice(Math.min(rec.index, p.entries.length), 0, rec.entry);
    }
    markDirty();
    refresh(true);
    toast('已恢复', 'ok');
  };
  if (S.cur && S.cur.path === rec.path) { doRestore(); return; }
  loadFile(rec.path).then(() => { if (S.cur && S.cur.path === rec.path) doRestore(); });
  closeModal();
}

/* ============================================================
   右键菜单（条目上的上下文菜单）
   ============================================================ */
let _ctxEl = null;
function closeCtxMenu() { if (_ctxEl) { _ctxEl.remove(); _ctxEl = null; } }

function showCtxMenu(x, y, title, items) {
  closeCtxMenu();
  const m = document.createElement('div');
  m.className = 'ctx-menu';
  m.innerHTML = (title ? `<div class="ctx-title">${esc(title)}</div><div class="ctx-sep"></div>` : '')
    + items.map((it, i) => it === '-' ? '<div class="ctx-sep"></div>'
      : `<div class="ctx-item ${it.danger ? 'danger' : ''}" data-i="${i}">${it.icon || ''} ${esc(it.label)}</div>`).join('');
  document.body.appendChild(m);
  // 防超出屏幕
  const r = m.getBoundingClientRect();
  m.style.left = Math.min(x, window.innerWidth - r.width - 8) + 'px';
  m.style.top = Math.min(y, window.innerHeight - r.height - 8) + 'px';
  m.querySelectorAll('.ctx-item').forEach((el) => {
    el.addEventListener('click', () => {
      const it = items[+el.dataset.i];
      closeCtxMenu();
      if (it && it.fn) it.fn();
    });
  });
  _ctxEl = m;
  setTimeout(() => document.addEventListener('click', closeCtxMenu, { once: true }), 0);
  document.addEventListener('keydown', function esc(e) {
    if (e.key === 'Escape') { closeCtxMenu(); document.removeEventListener('keydown', esc); }
  });
}

function entryCtxMenu(ev, pi, ei) {
  ev.preventDefault();
  const e = entry(pi, ei);
  if (!e) return;
  const doAct = (act) => {
    // 复用主事件派发的动作
    const btn = document.querySelector(`[data-act="${act}"][data-pi="${pi}"][data-ei="${ei}"]`);
    if (btn) { btn.dispatchEvent(new MouseEvent('click', { bubbles: true })); }
  };
  showCtxMenu(ev.clientX, ev.clientY, entrySummary(e), [
    { icon: '✎', label: '编辑这条', fn: () => doAct('toggleEntry') },
    { icon: '🔍', label: '反查这个物品', fn: () => doAct('findEntry') },
    '-',
    { icon: '⧉', label: '复制条目', fn: () => { pool(pi).entries.splice(ei + 1, 0, deepClone(e)); markDirty(); refresh(true); } },
    { icon: '✕', label: '删除这条', danger: true, fn: () => doAct('delEntry') },
  ]);
}

function poolCtxMenu(ev, pi) {
  ev.preventDefault();
  const p = pool(pi);
  if (!p) return;
  const doAct = (act) => {
    const btn = document.querySelector(`[data-act="${act}"][data-pi="${pi}"]`);
    if (btn) { btn.dispatchEvent(new MouseEvent('click', { bubbles: true })); }
  };
  showCtxMenu(ev.clientX, ev.clientY, `池 ${pi + 1}${p.name ? ' · ' + p.name : ''}`, [
    { icon: '＋', label: '添加物品', fn: () => doAct('addItem') },
    { icon: '🔫', label: '加枪械/弹药…', fn: () => doAct('addTacz') },
    { icon: '⧉', label: '复制这个池', fn: () => doAct('dupPool') },
    '-',
    { icon: '↑', label: '上移', fn: () => doAct('movePoolUp') },
    { icon: '↓', label: '下移', fn: () => doAct('movePoolDown') },
    { icon: '✕', label: '删除这个池', danger: true, fn: () => doAct('delPool') },
  ]);
}

function bindCtxMenus() {
  const root = $('#editor');
  if (!root) return;
  root.addEventListener('contextmenu', (ev) => {
    // 条目行右键
    const row = ev.target.closest('.entry-row[data-key]');
    if (row) {
      const [pi, ei] = row.dataset.key.split(':').map(Number);
      entryCtxMenu(ev, pi, ei);
      return;
    }
    // 池标题右键
    const head = ev.target.closest('.pool-head');
    if (head) {
      const pi = +head.querySelector('[data-pi]').dataset.pi;
      poolCtxMenu(ev, pi);
    }
  });
}

function pool(pi) { return S.cur.data.pools[pi]; }
function entry(pi, ei) { return S.cur.data.pools[pi].entries[ei]; }

function parseScope(scope) {
  // "p3" -> 池条件 ; "e3_5" -> 条目条件
  if (scope.startsWith('p')) return { kind: 'pool', pi: +scope.slice(1) };
  const m = /^e(\d+)_(\d+)$/.exec(scope);
  return { kind: 'entry', pi: +m[1], ei: +m[2] };
}

function condArray(scope) {
  const s = parseScope(scope);
  const o = s.kind === 'pool' ? pool(s.pi) : entry(s.pi, s.ei);
  if (!o.conditions) o.conditions = [];
  return o.conditions;
}

function refresh(keepScroll) {
  const sc = window.scrollY;
  renderEditor();
  bindEditorEvents();
  if (keepScroll) window.scrollTo(0, sc);
}

function bindEditorEvents() {
  const root = $('#editor');
  if (!root) return;
  root.onclick = onEditorClick;
  root.oninput = onEditorInput;
  root.onchange = onEditorChange;
}

/* ---------- 点击 ---------- */
function onEditorClick(ev) {
  const btn = ev.target.closest('[data-act]');
  if (!btn) return;
  const act = btn.dataset.act;
  const pi = btn.dataset.pi != null ? +btn.dataset.pi : null;
  const ei = btn.dataset.ei != null ? +btn.dataset.ei : null;
  const fi = btn.dataset.fi != null ? +btn.dataset.fi : null;
  const ci = btn.dataset.ci != null ? +btn.dataset.ci : null;
  const scope = btn.dataset.scope;

  const A = {
    /* --- 文件级 --- */
    validate() {
      post('/api/validate', { data: S.cur.data }).then((r) => {
        S.cur.report = r.report;
        refresh(true);
        if (!r.report.errors.length && !r.report.warnings.length) toast('语法完全正确 ✓', 'ok');
        else toast(`错误 ${r.report.errors.length} · 提示 ${r.report.warnings.length}`, r.report.errors.length ? 'err' : 'warn');
      });
    },
    addPool() {
      S.cur.data.pools = S.cur.data.pools || [];
      S.cur.data.pools.push({ name: 'pool_' + (S.cur.data.pools.length + 1), rolls: 1, entries: [] });
      markDirty(); refresh(true);
    },
    delPool() {
      if (!confirm(`确定删除第 ${pi + 1} 个池？`)) return;
      const p = pool(pi);
      pushDeleted({ path: S.cur.path, isPool: true, pool: pi, entry: deepClone(p),
        label: `池「${p.name || pi + 1}」（${(p.entries || []).length} 条）` });
      S.cur.data.pools.splice(pi, 1); markDirty(); refresh(true);
    },
    dupPool() {
      S.cur.data.pools.splice(pi + 1, 0, deepClone(pool(pi))); markDirty(); refresh(true);
    },
    togglepool() { S.poolOpen[pi] = !S.poolOpen[pi]; refresh(true); },
    movePoolUp() {
      if (pi <= 0) return;
      const ps = S.cur.data.pools;
      const tmp = ps[pi - 1]; ps[pi - 1] = ps[pi]; ps[pi] = tmp;
      markDirty(); refresh(true);
    },
    movePoolDown() {
      const ps = S.cur.data.pools;
      if (pi >= ps.length - 1) return;
      const tmp = ps[pi + 1]; ps[pi + 1] = ps[pi]; ps[pi] = tmp;
      markDirty(); refresh(true);
    },
    showall() { S.showAll[pi] = true; refresh(true); },
    collapseentries() { S.showAll[pi] = false; refresh(true); },
    showless() { S.showAll[pi] = false; refresh(true); },

    /* --- 池级 --- */
    addBonus() { pool(pi).bonus_rolls = { min: 0, max: 0 }; markDirty(); refresh(true); },
    delBonus() { delete pool(pi).bonus_rolls; markDirty(); refresh(true); },
    addPoolCond() { condArray('p' + pi).push({ condition: 'minecraft:random_chance', chance: 0.5 }); markDirty(); refresh(true); },

    /* --- 条目级 --- */
    addItem() {
      openPicker((ids, extras) => {
        const p = pool(pi);
        p.entries = p.entries || [];
        ids.forEach((id) => p.entries.push({ type: 'minecraft:item', name: id, weight: 1 }));
        (extras || []).forEach((e) => p.entries.push(deepClone(e)));
        const n = ids.length + (extras || []).length;
        markDirty(); refresh(true);
        toast(`已添加 ${n} 个物品`, 'ok');
      });
    },
    addEmpty() {
      pool(pi).entries.push({ type: 'minecraft:empty', weight: 1 }); markDirty(); refresh(true);
    },
    addTacz() {
      // 打开选择器并直接停在 TaCZ 分类上；默认挑这个文件里用得最多的那一类
      const keys = Object.keys(S.tacz || {});
      if (!keys.length) { toast('没有可用的枪包目录', 'warn'); return; }
      const used = {};
      (pool(pi).entries || []).forEach((e) => {
        const t = taczOf(e);
        if (t) used[t.key] = (used[t.key] || 0) + 1;
      });
      const best = keys.slice().sort((a, b) => (used[b] || 0) - (used[a] || 0))[0];
      openPicker((ids, extras) => {
        const p = pool(pi);
        p.entries = p.entries || [];
        ids.forEach((id) => p.entries.push({ type: 'minecraft:item', name: id, weight: 1 }));
        (extras || []).forEach((e) => p.entries.push(deepClone(e)));
        const n = ids.length + (extras || []).length;
        markDirty(); refresh(true);
        toast(`已添加 ${n} 个条目`, 'ok');
      }, { tab: best });
    },
    addEntryType() {
      openEntryTypePicker((t) => {
        const e = { type: t, weight: 1 };
        if (t === 'minecraft:tag') e.name = '#minecraft:planks';
        if (t === 'minecraft:item') e.name = 'minecraft:stone';
        if (t === 'minecraft:loot_table') e.value = 'chaoszpack_lc_loot:chests/muxiang';
        if (t === 'minecraft:dynamic') e.name = 'minecraft:contents';
        if (['minecraft:group', 'minecraft:alternatives', 'minecraft:sequence'].includes(t)) e.children = [];
        pool(pi).entries.push(e); markDirty(); refresh(true);
      });
    },
    delEntry() {
      const e = entry(pi, ei);
      pushDeleted({ path: S.cur.path, pool: pi, index: ei, entry: deepClone(e),
        label: entrySummary(e) });
      pool(pi).entries.splice(ei, 1); markDirty(); refresh(true);
    },
    toggleEntry() {
      const k = pi + ':' + ei;
      if (S.open.has(k)) S.open.delete(k); else S.open.add(k);
      refresh(true);
    },
    findEntry() {
      const e = entry(pi, ei);
      if (e.name) openFind(e.name);
    },
    pickEntry() {
      openPicker((ids, extras) => {
        const e = entry(pi, ei);
        if (extras && extras.length) {
          // TaCZ 子类型：整条替换（基物品 + NBT），保留权重
          const w = e.weight;
          Object.keys(e).forEach((k) => delete e[k]);
          Object.assign(e, deepClone(extras[0]));
          if (w != null) e.weight = w;
        } else if (ids.length) {
          e.name = ids[0];
          if (norm(e.type) === 'minecraft:tag' && !e.name.startsWith('#')) e.name = '#' + e.name;
        } else return;
        markDirty(); refresh(true);
      });
    },
    /* --- 子条目（组合类型内部） --- */
    addChild() {
      openPicker((ids, extras) => {
        const e = entry(pi, ei);
        e.children = e.children || [];
        ids.forEach((id) => e.children.push({ type: 'minecraft:item', name: id, weight: 1 }));
        (extras || []).forEach((x) => e.children.push(deepClone(x)));
        const n = ids.length + (extras || []).length;
        markDirty(); refresh(true);
        toast(`已添加 ${n} 个子条目`, 'ok');
      });
    },
    addChildEmpty() {
      const e = entry(pi, ei);
      e.children = e.children || [];
      e.children.push({ type: 'minecraft:empty', weight: 1 });
      markDirty(); refresh(true);
    },
    delChild() {
      const arr = String(btn.dataset.cpath).split(',').map(Number);
      const ci = arr.pop();
      const parent = cpathGet(arr.join(','));
      if (!parent || !Array.isArray(parent.children)) return;
      const removed = parent.children[ci];
      pushDeleted({ path: S.cur.path, pool: arr[0], index: arr[1], childIdx: ci, isChild: true,
        entry: deepClone(removed), label: entrySummary(removed) });
      parent.children.splice(ci, 1);
      markDirty(); refresh(true);
    },
    childPick() {
      const target = cpathGet(btn.dataset.cpath);
      if (!target) return;
      openPicker((ids, extras) => {
        if (extras && extras.length) {
          const w = target.weight;
          Object.keys(target).forEach((k) => delete target[k]);
          Object.assign(target, deepClone(extras[0]));
          if (w != null) target.weight = w;
        } else if (ids.length) {
          target.name = ids[0];
          if (norm(target.type) === 'minecraft:tag' && !target.name.startsWith('#')) target.name = '#' + target.name;
        } else return;
        markDirty(); refresh(true);
      });
    },

    addFunc() {
      const e = entry(pi, ei);
      e.functions = e.functions || [];
      e.functions.push({ function: 'minecraft:set_count', count: { min: 1, max: 1 } });
      markDirty(); refresh(true);
    },
    delFunc() { entry(pi, ei).functions.splice(fi, 1); markDirty(); refresh(true); },
    addCond() { condArray(`e${pi}_${ei}`).push({ condition: 'minecraft:random_chance', chance: 0.5 }); markDirty(); refresh(true); },
    delCond() { condArray(scope).splice(ci, 1); markDirty(); refresh(true); },

    /* --- 批量 --- */
    bulkWeight() {
      const n = parseInt($('#bulkW' + pi).value, 10);
      const w = isFinite(n) ? Math.max(0, n) : 1;
      Array.from(S.sel).filter((k) => k.startsWith(pi + ':')).forEach((k) => {
        const e = pool(pi).entries[+k.split(':')[1]];
        if (e) e.weight = w;
      });
      markDirty(); refresh(true); toast('已批量设置权重为 ' + w, 'ok');
    },
    bulkCount() {
      const n = Math.max(1, parseInt($('#bulkN' + pi).value, 10) || 1);
      Array.from(S.sel).filter((k) => k.startsWith(pi + ':')).forEach((k) => {
        const e = pool(pi).entries[+k.split(':')[1]];
        if (!e) return;
        e.functions = e.functions || [];
        const f = e.functions.find((x) => norm(x.function) === 'minecraft:set_count');
        if (f) f.count = { min: n, max: n };
        else e.functions.unshift({ function: 'minecraft:set_count', count: { min: n, max: n } });
      });
      markDirty(); refresh(true); toast('已批量设置数量为 ' + n, 'ok');
    },
    bulkDel() {
      const ks = Array.from(S.sel).filter((k) => k.startsWith(pi + ':')).map((k) => +k.split(':')[1]).sort((a, b) => b - a);
      if (!ks.length || !confirm(`确定删除所选 ${ks.length} 条？`)) return;
      // 从后往前删，索引不会乱；记录时每条的 index 是删除前的位置
      ks.forEach((i) => {
        const e = pool(pi).entries[i];
        pushDeleted({ path: S.cur.path, pool: pi, index: i, entry: deepClone(e), label: entrySummary(e) });
        pool(pi).entries.splice(i, 1);
      });
      S.sel.clear(); markDirty(); refresh(true);
    },
    bulkSelAll() {
      (pool(pi).entries || []).forEach((_, i) => S.sel.add(pi + ':' + i));
      refresh(true);
    },
    bulkSelInvert() {
      (pool(pi).entries || []).forEach((_, i) => {
        const k = pi + ':' + i;
        if (S.sel.has(k)) S.sel.delete(k); else S.sel.add(k);
      });
      refresh(true);
    },
    bulkSelMatch() {
      const q = (S.poolSearch[pi] || '').trim().toLowerCase();
      if (!q) return;
      let n = 0;
      (pool(pi).entries || []).forEach((e, i) => {
        const txt = (entrySummary(e) + ' ' + (e.name || e.value || '')).toLowerCase();
        if (txt.includes(q)) { S.sel.add(pi + ':' + i); n++; }
      });
      refresh(true); toast(`已选中 ${n} 条匹配项`, 'ok');
    },
    bulkClear() { S.sel.clear(); refresh(true); },
  };

  if (A[act]) { ev.preventDefault(); A[act](); }
}

/* ---------- 输入 ---------- */
function onEditorInput(ev) {
  const t = ev.target.closest('[data-act]');
  if (!t) return;
  const act = t.dataset.act;
  const pi = t.dataset.pi != null ? +t.dataset.pi : null;
  const ei = t.dataset.ei != null ? +t.dataset.ei : null;
  const fi = t.dataset.fi != null ? +t.dataset.fi : null;
  const ci = t.dataset.ci != null ? +t.dataset.ci : null;
  const scope = t.dataset.scope;
  const key = t.dataset.key;
  const part = t.dataset.part;
  const v = t.value;

  const numeric = () => { const n = parseFloat(v); return isFinite(n) ? n : 0; };

  if (act === 'weight') {
    const e = entry(pi, ei);
    e.weight = Math.max(1, parseInt(v, 10) || 1);
    markDirty();
    liveWeights(pi);
    return;
  }
  if (act === 'quality') { entry(pi, ei).quality = parseInt(v, 10) || 0; markDirty(); return; }
  if (act === 'childWeight') {
    const target = cpathGet(t.dataset.cpath);
    if (!target) return;
    const n = parseInt(v, 10);
    target.weight = isFinite(n) ? Math.max(0, n) : 1;
    markDirty(); return;
  }
  if (act === 'childCount') {
    const target = cpathGet(t.dataset.cpath);
    if (!target) return;
    const n = Math.max(1, parseInt(v, 10) || 1);
    target.functions = target.functions || [];
    const f = target.functions.find((x) => norm(x.function) === 'minecraft:set_count');
    if (f) f.count = { min: n, max: n };
    else target.functions.unshift({ function: 'minecraft:set_count', count: { min: n, max: n } });
    markDirty(); return;
  }
  if (act === 'entryName') { entry(pi, ei).name = v; markDirty(); return; }
  if (act === 'entryValue') { entry(pi, ei).value = v; markDirty(); return; }
  if (act === 'poolname') { pool(pi).name = v; markDirty(); return; }
  if (act === 'poolSearch') {
    // 池内搜索：只替换条目列表那一块，绝不重渲染整个编辑器。
    // （重渲染会销毁输入框，中文输入法的组字状态跟着断掉 → 打不出汉字）
    S.poolSearch[pi] = v;
    const box = document.querySelector(`[data-entrybox="${pi}"]`);
    if (box) box.innerHTML = poolEntryBoxHTML(pi);
    return;
  }
  if (act === 'rollsFix') { pool(pi).rolls = Math.max(0, parseInt(v, 10) || 0); markDirty(); return; }
  if (act === 'rollsMin') { const r = pool(pi).rolls; r.min = Math.max(0, parseInt(v, 10) || 0); markDirty(); return; }
  if (act === 'rollsMax') { const r = pool(pi).rolls; r.max = Math.max(0, parseInt(v, 10) || 0); markDirty(); return; }
  if (act === 'bonusFix') { pool(pi).bonus_rolls = Math.max(0, parseInt(v, 10) || 0); markDirty(); return; }
  if (act === 'bonusMin') { const r = pool(pi).bonus_rolls; r.min = Math.max(0, parseInt(v, 10) || 0); markDirty(); return; }
  if (act === 'bonusMax') { const r = pool(pi).bonus_rolls; r.max = Math.max(0, parseInt(v, 10) || 0); markDirty(); return; }

  if (act === 'funcField') {
    const f = entry(pi, ei).functions[fi];
    setFieldValue(f, key, t, numeric, part);
    markDirty(); return;
  }
  if (act === 'condField') {
    const c = condArray(scope)[ci];
    setFieldValue(c, key, t, numeric, part);
    markDirty(); return;
  }
}

function setFieldValue(obj, key, el, numeric, part) {
  const kind = el.dataset.kind || '';
  if (el.type === 'checkbox') { obj[key] = el.checked; return; }
  if (part === 'min' || part === 'max') {
    if (!obj[key] || typeof obj[key] !== 'object') {
      const base = typeof obj[key] === 'number' ? obj[key] : 1;
      obj[key] = { min: base, max: base };
    }
    obj[key][part] = numeric();
    return;
  }
  if (el.type === 'number') { obj[key] = numeric(); return; }
  const raw = el.value;
  if (kind === 'json' || kind === 'list') {
    const s = raw.trim();
    if (!s) { obj[key] = kind === 'list' ? [] : {}; return; }
    try { obj[key] = JSON.parse(s); return; } catch (e) { obj[key] = raw; return; }
  }
  obj[key] = raw;
}

/* ---------- 下拉切换 ---------- */
function onEditorChange(ev) {
  const el0 = ev.target;
  if (el0.id === 'tableType') {
    S.cur.data.type = el0.value;
    markDirty(); refresh(true);
    return;
  }
  const t = el0.closest('[data-act]');
  if (!t) return;
  const act = t.dataset.act;
  const pi = t.dataset.pi != null ? +t.dataset.pi : null;
  const ei = t.dataset.ei != null ? +t.dataset.ei : null;
  const fi = t.dataset.fi != null ? +t.dataset.fi : null;
  const ci = t.dataset.ci != null ? +t.dataset.ci : null;
  const scope = t.dataset.scope;

  if (act === 'selEntry') {
    const k = pi + ':' + ei;
    if (t.checked) S.sel.add(k); else S.sel.delete(k);
    refresh(true); return;
  }
  if (act === 'taczSub') {
    const e = entry(pi, ei);
    const nbt = t.dataset.nbt;
    if (!t.value) removeTaczSub(e, nbt);
    else setTaczSub(e, nbt, t.value);
    markDirty(); refresh(true); return;
  }
  if (act === 'entryType') {
    const e = entry(pi, ei);
    const nt = t.value;
    e.type = nt;
    if (['minecraft:group', 'minecraft:alternatives', 'minecraft:sequence'].includes(nt)) {
      e.children = e.children || [];
      delete e.name; delete e.value;
    } else if (nt === 'minecraft:empty') {
      delete e.name; delete e.value; delete e.children;
    } else if (nt === 'minecraft:loot_table') {
      delete e.name; e.value = e.value || 'chaoszpack_lc_loot:chests/muxuan';
    } else {
      delete e.value; delete e.children;
      e.name = e.name || 'minecraft:stone';
    }
    markDirty(); refresh(true); return;
  }
  if (act === 'funcType') {
    const f = entry(pi, ei).functions[fi];
    const nt = t.value;
    const old = deepClone(f);
    const fresh = { function: nt };
    const def = FUNC_DEFS[nt];
    if (def) def.f.forEach(([k, kind]) => {
      if (old[k] !== undefined) { fresh[k] = old[k]; return; }
      if (kind === 'bool') fresh[k] = false;
      else if (kind === 'range') fresh[k] = { min: 1, max: 1 };
      else if (kind === 'number') fresh[k] = 0;
      else if (kind === 'prob') fresh[k] = 0.5;
      else if (kind === 'ratio') fresh[k] = 1;
      else if (kind === 'json' || kind === 'list') fresh[k] = kind === 'list' ? [] : {};
      else fresh[k] = '';
    });
    Object.assign(f, fresh);
    Object.keys(old).forEach((k) => { if (!(k in fresh) && k !== 'function') delete f[k]; });
    markDirty(); refresh(true); return;
  }
  if (act === 'condType') {
    const c = condArray(scope)[ci];
    const nt = t.value;
    const old = deepClone(c);
    const fresh = { condition: nt };
    const def = COND_DEFS[nt];
    if (def) def.f.forEach(([k, kind]) => {
      if (old[k] !== undefined) { fresh[k] = old[k]; return; }
      if (kind === 'bool') fresh[k] = false;
      else if (kind === 'range') fresh[k] = { min: 0, max: 0 };
      else if (kind === 'number') fresh[k] = 0;
      else if (kind === 'prob') fresh[k] = 0.5;
      else if (kind === 'json') fresh[k] = {};
      else fresh[k] = '';
    });
    Object.keys(c).forEach((k) => delete c[k]);
    Object.assign(c, fresh);
    markDirty(); refresh(true); return;
  }
  if (act === 'funcField' || act === 'condField') {
    onEditorInput(ev);
  }
}

/* ---------- 局部刷新概率条 ---------- */
function liveWeights(pi) {
  const p = pool(pi);
  const entries = p.entries || [];
  const total = entries.reduce((a, e) => a + Math.max(0, num(e.weight, 1)), 0);
  const avg = rollsAvg(p.rolls);
  const card = $$('.pool-card')[pi];
  if (!card) return;
  const rows = $$('.entry-row', card);
  rows.forEach((row) => {
    const k = row.dataset.key;
    if (!k) return;
    const e = entries[+k.split(':')[1]];
    if (!e) return;
    const w = Math.max(0, num(e.weight, 1));
    const prob = total ? w / total : 0;
    const fill = $('.prob-fill', row);
    const txt = $('.prob-txt', row);
    if (fill) { fill.style.width = Math.min(100, prob * 100).toFixed(1) + '%'; fill.classList.toggle('hi', prob > 0.2); }
    if (txt) { txt.textContent = pct(prob); txt.classList.toggle('err-text', w === 0); }
    const cnt = $('.entry-tools', row).previousElementSibling;
    if (cnt) cnt.innerHTML = esc(countText(countOf(e))) + ` <span class="muted">(≈${(prob * avg).toFixed(2)})</span>`;
  });
  const badges = $$('.pool-title .badge', card);
  badges.forEach((b) => { if (b.textContent.startsWith('总权重')) b.textContent = '总权重 ' + total; });
}

/* ---------- 条目类型选择器 ---------- */
function openEntryTypePicker(cb) {
  $('#modalBox').className = 'modal-box sm';
  $('#modalBox').innerHTML = `
    <div class="modal-head"><h3>选择条目类型</h3><button class="btn" data-close="1">关闭</button></div>
    <div class="modal-body">
      ${ENTRY_TYPES.map(([v, l]) => `<div class="picker-item" data-t="${v}" style="margin-bottom:7px">
        <div class="pi-txt"><div class="pi-cn">${l}</div><div class="pi-id">${v}</div></div></div>`).join('')}
    </div>`;
  $('#modal').classList.remove('hidden');
  $('#modalBox').onclick = (e) => {
    const it = e.target.closest('[data-t]');
    if (!it) return;
    $('#modalBox').onclick = null;
    closeModal(); cb(it.dataset.t);
  };
}

/* ============================================================
   保存
   ============================================================ */
function saveFile() {
  if (!S.cur) return;
  const btn = $('#btnSave');
  if (btn) { btn.disabled = true; btn.textContent = '保存中…'; }
  post('/api/file?path=' + encodeURIComponent(S.cur.path), { data: S.cur.data })
    .then((r) => {
      if (!r.ok) {
        S.cur.report = r.report || S.cur.report;
        refresh(true);
        toast(r.error || '保存失败', 'err');
        if (r.report && r.report.errors.length) {
          alert('校验未通过，已拒绝写入：\n\n' + r.report.errors.slice(0, 12).join('\n'));
        }
        return;
      }
      S.cur.dirty = false;
      S.cur.report = r.report;
      refresh(true);
      toast('已保存 ✓', 'ok');
      reloadList();
    })
    .catch((e) => toast(e.message, 'err'))
    .finally(() => { const b = $('#btnSave'); if (b) { b.disabled = false; b.textContent = '保存'; } });
}

function reloadList() {
  return api('/api/list').then((r) => {
    S.files = r.files;
    renderSidebar();
  });
}

async function syncFiles(src, targets) {
  if (!targets.length) return;
  if (!confirm(`把 ${src} 的内容覆盖到以下 ${targets.length} 个文件？\n\n${targets.join('\n')}\n\n（每个被覆盖的文件都会自动留备份）`)) return;
  try {
    const res = await api('/api/file?path=' + encodeURIComponent(src));
    if (!res.ok) return toast(res.error, 'err');
    let ok = 0, fail = [];
    for (const t of targets) {
      const r = await post('/api/file?path=' + encodeURIComponent(t), { data: res.data });
      if (r.ok) ok++; else fail.push(t);
    }
    closeModal();
    toast(`已同步 ${ok} 个文件${fail.length ? '，失败 ' + fail.length + ' 个' : ''}`, fail.length ? 'warn' : 'ok');
    reloadList();
  } catch (e) { toast(e.message, 'err'); }
}

/* ============================================================
   全局事件
   ============================================================ */
function bindGlobal() {
  // 窗口尺寸变化时，文件工具栏可能换行导致高度变化 → 重算吸顶偏移
  window.addEventListener('resize', () => syncStickyOffsets());
  $('#fileTree').addEventListener('click', (e) => {
    const tool = e.target.closest('[data-act]');
    const item = e.target.closest('.file-item');
    if (!item) return;
    const path = item.dataset.path;
    if (tool) {
      e.stopPropagation();
      const act = tool.dataset.act;
      if (act === 'rename') {
        const nv = prompt('新的文件名（可含子目录，如 chests/new.json）：', path);
        if (!nv || nv === path) return;
        post('/api/rename', { from: path, to: nv }).then((r) => {
          if (!r.ok) return toast(r.error, 'err');
          toast('已重命名', 'ok');
          if (S.cur && S.cur.path === path) S.cur.path = r.path;
          reloadList();
        }).catch((err) => toast(err.message, 'err'));
      } else if (act === 'delete') {
        if (!confirm(`确定删除 ${path}？\n（会移到 _loot_editor/_trash，可以找回）`)) return;
        post('/api/delete', { path }).then((r) => {
          if (!r.ok) return toast(r.error, 'err');
          toast('已移入回收目录', 'ok');
          if (S.cur && S.cur.path === path) { S.cur = null; $('#editor').classList.add('hidden'); $('#emptyState').classList.remove('hidden'); }
          reloadList();
        }).catch((err) => toast(err.message, 'err'));
      }
      return;
    }
    loadFile(path);
  });

  $('#fileSearch').addEventListener('input', (e) => { S.filter = e.target.value; renderSidebar(); });
  $('#btnReload').addEventListener('click', () => { reloadList().then(() => toast('已重新载入文件列表', 'ok')); });
  $('#btnAudit').addEventListener('click', openAudit);
  $('#btnDupes').addEventListener('click', openDupes);
  $('#btnFind').addEventListener('click', () => openFind(''));
  $('#btnDeleted').addEventListener('click', openRecentDeleted);
  $('#btnChangeRoot').addEventListener('click', openChangeRoot);
  $('#btnPack').addEventListener('click', openPackDialog);
  $('#btnCleanDead') && $('#btnCleanDead').addEventListener('click', openCleanDead);
  $('#btnAI').addEventListener('click', () => toggleAI());
  $('#btnAISettings').addEventListener('click', openAISettings);
  const aiClose = $('#aiClose');
  if (aiClose) aiClose.addEventListener('click', () => toggleAI(false));
  const aiSendBtn = $('#aiSend');
  if (aiSendBtn) aiSendBtn.addEventListener('click', aiSend);
  const aiInp = $('#aiInput');
  if (aiInp) aiInp.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); aiSend(); }
  });

  $('#btnNewFile').addEventListener('click', openNewFileDialog);

  $('#modal').addEventListener('click', (e) => {
    if (e.target.dataset.close) { closeModal(); return; }
    const ob = e.target.closest('[data-open]');
    if (ob) { closeModal(); loadFile(ob.dataset.open); }
  });

  // 编辑器内的事件委托必须在启动时就绑好。
  // loadFile() 只调 renderEditor() 不调 refresh()，若等到 refresh() 才绑，
  // 打开文件后工具栏按钮会全部失效。
  bindEditorEvents();
  bindCtxMenus();

  document.addEventListener('keydown', (e) => {
    const mod = e.ctrlKey || e.metaKey;
    const k = e.key.toLowerCase();
    if (mod && k === 's') {                       // 保存
      e.preventDefault();
      if (S.cur) saveFile();
    } else if (mod && k === 'z' && !e.shiftKey) { // 撤销
      e.preventDefault();
      doUndo();
    } else if (mod && (k === 'y' || (k === 'z' && e.shiftKey))) {  // 重做
      e.preventDefault();
      doRedo();
    } else if (mod && k === 'f') {                // 搜文件
      e.preventDefault();
      const el = $('#fileSearch');
      if (el) { el.focus(); el.select(); }
    } else if (mod && k === 'e') {                // 搜当前文件的条目
      e.preventDefault();
      const el = $('#entrySearch') || $('#poolSearch0');
      if (el) el.focus();
    } else if (e.key === 'Escape') {
      closeModal();
    }
  });

  window.addEventListener('beforeunload', (e) => {
    if (S.cur && S.cur.dirty) { e.preventDefault(); e.returnValue = ''; }
  });
}

/* ---------- 编辑器按钮的委托绑定（renderEditor 后重新挂） ---------- */
document.addEventListener('click', (e) => {
  const t = e.target;
  if (t.id === 'btnSave') { saveFile(); }
  else if (t.id === 'btnUndo') { doUndo(); }
  else if (t.id === 'btnRedo') { doRedo(); }
  else if (t.id === 'btnValidate') { post('/api/validate', { data: S.cur.data }).then((r) => { S.cur.report = r.report; refresh(true); toast(r.report.errors.length ? `发现 ${r.report.errors.length} 个错误` : '语法正确 ✓', r.report.errors.length ? 'err' : 'ok'); }); }
  else if (t.id === 'btnGet') { openGetDialog(); }
  else if (t.id === 'btnAddPool') { S.cur.data.pools = S.cur.data.pools || []; S.cur.data.pools.push({ name: 'pool_' + ((S.cur.data.pools.length) + 1), rolls: 1, entries: [] }); markDirty(); refresh(true); }
  else if (t.id === 'btnOpenSrc' && S.cur) {
    api('/api/open?path=' + encodeURIComponent(S.cur.path))
      .then((r) => { if (r.ok) toast('已用系统默认编辑器打开 ' + S.cur.path, 'ok'); else toast(r.error, 'err'); })
      .catch((err) => toast(err.message, 'err'));
  }
  else if (t.id === 'btnReroll') { renderChest(true); }
  else if (t.id === 'btnCleanDead') { openCleanDead(); }
  else if (t.id === 'btnDupItems') { openDupItems(); }
  else if (t.dataset && t.dataset.seg === 'rolls') { /* 由子按钮处理 */ }
}, true);

document.addEventListener('click', (e) => {
  const seg = e.target.closest('[data-seg="rolls"] button');
  if (seg) {
    const box = seg.parentElement;
    const pi = +box.dataset.pi;
    const mode = seg.dataset.mode;
    const p = pool(pi);
    if (mode === 'fix' && typeof p.rolls !== 'number') p.rolls = Math.max(0, Math.round(rollsAvg(p.rolls)));
    if (mode === 'range' && typeof p.rolls === 'number') p.rolls = { min: p.rolls, max: p.rolls };
    markDirty(); refresh(true);
  }
});

/* ============================================================
   AI 对话
   ============================================================ */
const AI = {
  history: [],     // {role:'user'|'assistant', content}
  busy: false,
};

function aiScrollBottom() {
  const log = $('#aiLog');
  if (log) log.scrollTop = log.scrollHeight;
}

function aiLogMsg(text, cls) {
  const d = document.createElement('div');
  d.className = 'ai-msg ' + (cls || 'bot');
  d.textContent = text;
  $('#aiLog').appendChild(d);
  aiScrollBottom();
  return d;
}

function refreshAIStatus() {
  const el = $('#aiStatus');
  if (!el) return;
  if (!S.cur) { el.textContent = '先在左边打开一个池文件'; return; }
  el.textContent = '正在编辑：' + S.cur.path;
}

function toggleAI(show) {
  const d = $('#aiDrawer');
  const want = (show === undefined) ? d.classList.contains('hidden') : show;
  d.classList.toggle('hidden', !want);
  if (want) { refreshAIStatus(); const inp = $('#aiInput'); if (inp) inp.focus(); }
}

async function aiSend() {
  const inp = $('#aiInput');
  const text = inp.value.trim();
  if (!text || AI.busy) return;
  if (!S.cur) {
    aiLogMsg('先在左边打开一个池文件，我才知道要改哪张表。', 'bot err');
    return;
  }
  AI.busy = true;
  inp.value = '';
  aiLogMsg(text, 'user');
  AI.history.push({ role: 'user', content: text });
  const thinking = aiLogMsg('AI 正在思考…', 'bot');
  thinking.style.opacity = '.6';
  try {
    const r = await post('/api/chat', {
      path: S.cur.path,
      message: text,
      history: AI.history.slice(0, -1),
    });
    thinking.remove();
    if (!r.ok) {
      if (r.need_setup) { aiLogMsg(r.error, 'bot err'); openAISettings(); }
      else aiLogMsg('出错了：' + (r.error || '未知错误') + (r.raw ? '\n\nAI 原始输出：\n' + r.raw : ''), 'bot err');
      AI.history.pop();
      return;
    }
    AI.history.push({ role: 'assistant', content: r.reply });
    appendAIPreview(r);
  } catch (e) {
    thinking.remove();
    aiLogMsg('请求失败：' + e.message, 'bot err');
    AI.history.pop();
  } finally {
    AI.busy = false;
  }
}

function appendAIPreview(r) {
  const rep = r.report || { errors: [], warnings: [] };
  const validOk = rep.errors.length === 0;
  const d = document.createElement('div');
  d.className = 'ai-msg bot';
  let notesHtml = '';
  if (r.notes && r.notes.length) {
    notesHtml = `<ul class="pv-notes">${r.notes.map((n) => `<li>${esc(n)}</li>`).join('')}</ul>`;
  } else if (r.mode === 'replace') {
    notesHtml = '<div class="pv-notes muted" style="padding-left:0">整表替换</div>';
  }
  d.innerHTML = `
    <div>${esc(r.reply)}</div>
    <div class="ai-preview">
      <div class="pv-head">改动预览（还没写进文件）</div>
      ${notesHtml}
      <div class="pv-valid ${validOk ? 'ok' : 'err'}">${validOk ? '✓ 校验通过' : '✕ ' + rep.errors.length + ' 个错误，不能应用'}</div>
      <div class="pv-actions">
        <button class="btn pri sm" data-aiapply="1" ${validOk ? '' : 'disabled'}>应用到当前文件</button>
        <button class="btn sm ghost" data-aicancel="1">取消</button>
      </div>
    </div>`;
  $('#aiLog').appendChild(d);
  aiScrollBottom();
  const applyBtn = d.querySelector('[data-aiapply]');
  const cancelBtn = d.querySelector('[data-aicancel]');
  if (applyBtn) applyBtn.addEventListener('click', () => applyAIResult(r, d));
  if (cancelBtn) cancelBtn.addEventListener('click', () => {
    const pv = d.querySelector('.ai-preview');
    if (pv) pv.remove();
    aiLogMsg('已取消，没有改动。', 'bot');
  });
}

function applyAIResult(r, msgEl) {
  if (!S.cur) return;
  S.cur.data = r.preview;
  S.cur.report = r.report;
  markDirty();
  refresh(true);
  if (msgEl) {
    msgEl.classList.add('applied');
    const pv = msgEl.querySelector('.ai-preview');
    if (pv) pv.remove();
  }
  aiLogMsg('已应用到编辑区。这只是暂存，点顶栏「保存」才会真正写进文件。', 'bot');
  toast('AI 改动已应用（未保存）', 'ok');
}

async function openAISettings() {
  let c = {};
  try { c = (await api('/api/ai/config')).config || {}; } catch (e) {}
  $('#modalBox').className = 'modal-box sm';
  $('#modalBox').innerHTML = `
    <div class="modal-head"><h3>AI 接口设置</h3><button class="btn" data-close="1">关闭</button></div>
    <div class="modal-body ai-set">
      <div class="set-row"><label>接口地址（OpenAI 兼容，结尾别带 /chat/completions）</label>
        <input type="text" id="aiBaseUrl" value="${esc(c.base_url || '')}" placeholder="https://api.deepseek.com/v1"></div>
      <div class="set-row"><label>API Key</label>
        <input type="password" id="aiKey" placeholder="${c.configured ? '已保存（留空表示不修改）' : 'sk-...'}">
        <div class="hint">${c.configured ? '当前已保存一个 Key，留空则不改动。' : '还没配置 Key。'}</div></div>
      <div class="set-row"><label>模型名</label>
        <input type="text" id="aiModel" value="${esc(c.model || '')}" placeholder="deepseek-chat"></div>
      <div class="set-row"><label style="display:flex;align-items:center;gap:6px;font-weight:400">
        <input type="checkbox" id="aiProxy" ${c.use_proxy ? 'checked' : ''}> 走系统代理（只有连境外 API 如 OpenAI 官方才需要）</label></div>
      <div class="test-line">
        <button class="btn" id="aiTestConn">测试连接</button>
        <button class="btn" id="aiListModels">拉取可用模型</button>
        <span class="muted" id="aiTestResult"></span>
      </div>
      <div id="aiModelList" style="margin-top:6px"></div>
      <div class="hint" style="margin-top:12px">支持任何 OpenAI 兼容接口：DeepSeek、Kimi、通义、智谱、OpenAI、本地 Ollama 等。<br>
        Key 只存在你这台机器的 <code>_loot_editor/ai_config.json</code>，不会上传。</div>
    </div>
    <div class="modal-foot">
      <span class="spacer"></span>
      <button class="btn" data-close="1">取消</button>
      <button class="btn pri" id="aiSaveCfg">保存</button>
    </div>`;
  $('#modal').classList.remove('hidden');

  $('#aiTestConn').addEventListener('click', async () => {
    $('#aiTestResult').textContent = '测试中…';
    try {
      const payload = {
        base_url: $('#aiBaseUrl').value.trim(),
        model: $('#aiModel').value.trim(),
        use_proxy: $('#aiProxy').checked,
      };
      const keyVal = $('#aiKey').value.trim();
      if (keyVal) payload.api_key = keyVal;
      const rr = await post('/api/ai/test', payload);
      $('#aiTestResult').innerHTML = rr.ok
        ? '<span style="color:var(--ok)">✓ 连上了：' + esc(rr.reply) + '</span>'
        : '<span style="color:var(--err)">✕ ' + esc(rr.error) + '</span>';
    } catch (e) { $('#aiTestResult').textContent = '请求失败：' + e.message; }
  });

  $('#aiListModels').addEventListener('click', async () => {
    $('#aiTestResult').textContent = '拉取模型列表…';
    try {
      const payload = {
        base_url: $('#aiBaseUrl').value.trim(),
        model: $('#aiModel').value.trim(),
        use_proxy: $('#aiProxy').checked,
      };
      const keyVal = $('#aiKey').value.trim();
      if (keyVal) payload.api_key = keyVal;
      const rr = await post('/api/ai/models', payload);
      if (!rr.ok) {
        $('#aiTestResult').innerHTML = '<span style="color:var(--err)">✕ ' + esc(rr.error) + '</span>';
        $('#aiModelList').innerHTML = '';
        return;
      }
      $('#aiTestResult').innerHTML = `<span style="color:var(--ok)">✓ 连接正常，共 ${rr.count} 个可用模型</span>`;
      $('#aiModelList').innerHTML = '<div class="hint" style="margin-bottom:5px">点一个填进上面的模型名：</div>'
        + rr.models.map((m) => `<span class="badge" style="margin:0 5px 5px 0;cursor:pointer" data-model="${esc(m)}">${esc(m)}</span>`).join('');
      $$('#aiModelList [data-model]').forEach((b) => b.addEventListener('click', () => {
        $('#aiModel').value = b.dataset.model;
      }));
    } catch (e) { $('#aiTestResult').textContent = '请求失败：' + e.message; }
  });

  $('#aiSaveCfg').addEventListener('click', async () => {
    const keyVal = $('#aiKey').value.trim();
    const payload = {
      base_url: $('#aiBaseUrl').value.trim(),
      model: $('#aiModel').value.trim(),
      use_proxy: $('#aiProxy').checked,
    };
    if (keyVal) payload.api_key = keyVal;
    const rr = await post('/api/ai/config', payload);
    if (rr.ok) { closeModal(); toast('AI 接口已保存', 'ok'); }
    else toast(rr.error || '保存失败', 'err');
  });
}

/* ============================================================
   启动
   ============================================================ */
/* 载入 / 重新载入物品库（物品名、图标、TaCZ 目录）。
   重建物品库后必须调这个——否则前端还拿着旧的 S.itemMap，
   新建的物品搜不到，得重启程序才行。 */
async function loadItemDb(opts) {
  opts = opts || {};
  const meta = await api('/api/meta');
  S.root = meta.root;
  const mr = $('#metaRoot'), mi = $('#metaItems');
  if (mr) mr.textContent = meta.root;
  if (mi) mi.textContent = meta.itemCount ? `物品库 ${meta.itemCount.toLocaleString()} 项` : '物品库未生成';

  S.itemMap = {};
  Object.keys(S.taczIdx).forEach((k) => delete S.taczIdx[k]);

  if (meta.itemCount) {
    if (!opts.silent) toast(`正在载入 ${meta.itemCount.toLocaleString()} 个物品…`);
    const db = await api('/api/items');
    (db.items || []).forEach((it) => { S.itemMap[it.id] = it; });
    S.modNames = db.modNames || {};
  } else {
    toast('物品数据库还没生成，物品只会显示 ID', 'warn');
  }

  try {
    const tc = await api('/api/tacz');
    S.tacz = tc || {};
    Object.entries(S.tacz).forEach(([nbt, d]) => {
      (d.items || []).forEach((it) => { S.taczIdx[nbt + '|' + it.id] = it; });
    });
  } catch (e) { S.tacz = {}; }

  return { itemCount: validItemList().length, taczKinds: Object.keys(S.tacz).length };
}

async function boot() {
  try {
    await loadItemDb();

    await reloadList();
    bindGlobal();
    // 自动打开上次编辑的文件（还在的话），省得每次重新找
    const last = lastFile();
    if (last && S.files.some((f) => f.path === last)) {
      await loadFile(last);
    }
    toast(`就绪：${S.files.length} 个池文件 · ${validItemList().length} 个真实物品`
          + `（已排除 ${Object.keys(S.itemMap).length - validItemList().length} 个游戏里不存在的 ID）`, 'ok');
  } catch (e) {
    document.body.innerHTML = `<div style="padding:40px;font-family:sans-serif">
      <h2 style="color:#dc2626">启动失败</h2><pre>${esc(e.message)}</pre>
      <p>确认服务是通过 <code>python server.py</code> 启动的。</p></div>`;
  }
}

boot();

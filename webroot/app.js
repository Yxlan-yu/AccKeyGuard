import { exec, getPackagesInfo } from './kernelsu.js';

/* =========================================================================
   AccKeyGuard WebUI  v1.3.0

   设计要点（相对旧版的改动）：
   - 一个开关只有一个含义：列表里的开关 = 「保活守护」，不再和「无障碍开关」混在一起
   - 没有「保存设置」按钮：改动即时落盘（防抖 350ms），不会出现忘记保存而丢失改动
   - 不再弹出「是否顺带启用」确认框：开启守护时若服务没开就直接启用（这正是守护的含义）
   - 列表不再随状态重排：排序稳定，轮询只原地更新徽标，不会在滑动时跳走
   - 详情 / 设置 / 日志都是底部弹层，关闭即回到原位，不丢滚动位置
   - 守护未运行时给出可点击的横幅，一键启动
   ========================================================================= */

const MOD_ID = 'acckeyguard';
const KS = '/data/adb/ksu/bin/ksud';
const MODDIR = `/data/adb/modules/${MOD_ID}`;
const PIDFILE = `${MODDIR}/data/accd.pid`;
const LOGFILE = `${MODDIR}/data/accd.log`;

const CFG_ENABLED = 'enabled_services';
const CFG_THEME = 'theme';
const CFG_INTERVAL = 'check_interval';

const VERSION = 'v1.3.0';
const POLL_MS = 5000;
const FULL_REFRESH_MS = 20000;

const LS_THEME = 'akg.theme';
const LS_LABELS = 'akg.labels';

const HAS_KSU = typeof window.ksu !== 'undefined';

/* 只给一个 id 名时按 id 查找，其余一律当 CSS 选择器 */
const $ = (sel, root = document) => {
  if (root === document && /^[A-Za-z][\w-]*$/.test(sel)) return document.getElementById(sel);
  return root.querySelector(sel);
};
const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));

/* ------------------------------------------------------------------ state */

const state = {
  services: [],          // 设备上所有无障碍服务组件
  installed: new Set(),  // 同上，Set 形式
  enabled: new Set(),    // 系统当前已启用
  wanted: new Set(),     // 用户守护列表
  labels: {},            // pkg -> 应用名

  query: '',
  filter: 'all',

  guardAlive: false,
  guardPid: '',
  interval: 20,

  loaded: false,
  loading: false,
  error: '',

  pendingSave: false,
  saveTimer: null,
  syncTimer: null,

  theme: 'auto',
  openDetail: null,      // { comp, sync }
  logTimer: null,
  lastFull: 0,
};

/* ------------------------------------------------------------------ utils */

function escapeHtml(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, ch => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[ch]));
}

function hueFor(pkg) {
  let h = 0;
  for (let i = 0; i < pkg.length; i++) h = (h * 31 + pkg.charCodeAt(i)) % 360;
  return h;
}

function appLabel(comp) {
  const pkg = comp.split('/')[0];
  return state.labels[pkg] || pkg;
}

function shortService(comp) {
  const svc = comp.split('/')[1] || '';
  const i = svc.lastIndexOf('.');
  return i >= 0 ? svc.slice(i + 1) : svc;
}

function normComp(c) {
  const s = String(c).trim();
  const i = s.indexOf('/');
  if (i < 0) return s;
  const pkg = s.slice(0, i);
  const cls = s.slice(i + 1);
  if (cls.startsWith('.')) return pkg + '/' + pkg + cls;
  return s;
}

function normalizeList(str) {
  const out = [];
  String(str || '').split(':').forEach(item => {
    const t = item.trim();
    if (!t || t === 'null') return;
    const c = normComp(t);
    if (c && !out.includes(c)) out.push(c);
  });
  return out;
}

function cmpLabel(a, b) {
  const la = appLabel(a), lb = appLabel(b);
  const r = la.localeCompare(lb, 'zh-Hans-CN', { sensitivity: 'base' });
  return r || a.localeCompare(b);
}

function copyText(text) {
  const done = () => toast('已复制');
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(text).then(done).catch(fallback);
  } else {
    fallback();
  }
  function fallback() {
    try {
      const ta = document.createElement('textarea');
      ta.value = text;
      ta.setAttribute('readonly', '');
      ta.style.position = 'fixed';
      ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.select();
      document.execCommand('copy');
      ta.remove();
      done();
    } catch (e) {
      toast('复制失败');
    }
  }
}

function ksudCfg(sub) {
  return `KSU_MODULE=${MOD_ID} ${KS} module config ${sub} 2>/dev/null`;
}

async function execRaw(cmd, timeoutMs = 10000) {
  try {
    return await exec(cmd, { timeoutMs });
  } catch (e) {
    return { errno: -1, stdout: '', stderr: String(e) };
  }
}

async function run(cmd, timeoutMs = 10000) {
  const r = await execRaw(cmd, timeoutMs);
  return r.errno ? '' : (r.stdout || '');
}

function decodeB64(s) {
  if (!s) return '';
  try {
    const bin = atob(s);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    return new TextDecoder('utf-8').decode(bytes);
  } catch (e) {
    return '';
  }
}

function parseBlocks(out) {
  const res = {};
  let cur = null;
  String(out || '').split(/\r?\n/).forEach(line => {
    const m = /^@@([A-Z]+)@@$/.exec(line.trim());
    if (m) {
      cur = m[1];
      res[cur] = '';
      return;
    }
    if (cur && line.trim() !== '') res[cur] += line.trim();
  });
  return res;
}

/* ------------------------------------------------------------------ theme */

function resolveTheme(v) {
  if (v === 'auto') {
    return (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) ? 'dark' : 'light';
  }
  return v === 'dark' ? 'dark' : 'light';
}

function applyTheme(v) {
  state.theme = ['auto', 'light', 'dark'].includes(v) ? v : 'auto';
  document.documentElement.setAttribute('data-theme', resolveTheme(state.theme));
  try { localStorage.setItem(LS_THEME, state.theme); } catch (e) {}
}

async function loadTheme() {
  const raw = (await run(ksudCfg(`get ${CFG_THEME}`), 6000)).trim();
  applyTheme(['auto', 'light', 'dark'].includes(raw) ? raw : 'auto');
}

async function setTheme(v) {
  applyTheme(v);
  await run(ksudCfg(`set ${CFG_THEME} '${v}'`));
  toast('主题已切换');
}

/* -------------------------------------------------------------- guard data */

function buildFullCmd() {
  return [
    'KON=$(settings get secure enabled_accessibility_services 2>/dev/null)',
    `KCFG=$(${ksudCfg(`get ${CFG_ENABLED}`)})`,
    `KINT=$(${ksudCfg(`get ${CFG_INTERVAL}`)})`,
    'KSVC=$(cmd package query-services --user 0 -a android.accessibilityservice.AccessibilityService --components 2>/dev/null)',
    `KPID=$(cat ${PIDFILE} 2>/dev/null)`,
    'if [ -n "$KPID" ] && kill -0 "$KPID" 2>/dev/null && grep -q accd.sh "/proc/$KPID/cmdline" 2>/dev/null; then KALIVE=1; else KALIVE=0; fi',
    "echo '@@SVC@@'; printf '%s' \"$KSVC\" | base64 | tr -d '\\n'; echo",
    "echo '@@ON@@'; printf '%s' \"$KON\" | base64 | tr -d '\\n'; echo",
    "echo '@@CFG@@'; printf '%s' \"$KCFG\" | base64 | tr -d '\\n'; echo",
    "echo '@@INT@@'; printf '%s' \"$KINT\" | base64 | tr -d '\\n'; echo",
    "echo '@@PID@@'; printf '%s' \"$KPID\" | base64 | tr -d '\\n'; echo",
    "echo '@@ALIVE@@'; echo \"$KALIVE\"",
  ].join('\n');
}

function buildPollCmd() {
  return [
    'KON=$(settings get secure enabled_accessibility_services 2>/dev/null)',
    `KCFG=$(${ksudCfg(`get ${CFG_ENABLED}`)})`,
    `KPID=$(cat ${PIDFILE} 2>/dev/null)`,
    'if [ -n "$KPID" ] && kill -0 "$KPID" 2>/dev/null && grep -q accd.sh "/proc/$KPID/cmdline" 2>/dev/null; then KALIVE=1; else KALIVE=0; fi',
    "echo '@@ON@@'; printf '%s' \"$KON\" | base64 | tr -d '\\n'; echo",
    "echo '@@CFG@@'; printf '%s' \"$KCFG\" | base64 | tr -d '\\n'; echo",
    "echo '@@PID@@'; printf '%s' \"$KPID\" | base64 | tr -d '\\n'; echo",
    "echo '@@ALIVE@@'; echo \"$KALIVE\"",
  ].join('\n');
}

function applyGuardState(blocks) {
  const onList = normalizeList(decodeB64(blocks.ON || ''));
  state.enabled = new Set(onList);

  state.guardAlive = String(blocks.ALIVE || '').trim() === '1';
  state.guardPid = decodeB64(blocks.PID || '').trim();
}

/* ------------------------------------------------------------------ status */

function setStatus(kind, text) {
  const chip = $('statusChip');
  const label = $('statusText');
  if (chip.dataset.kind === kind && label.textContent === text) return;
  chip.dataset.kind = kind;
  chip.dataset.state = kind;
  label.textContent = text;
}

function flashSync(text, isError) {
  const el = $('syncNote');
  el.textContent = text;
  el.classList.toggle('err', !!isError);
  el.classList.add('show');
  clearTimeout(state.syncTimer);
  state.syncTimer = setTimeout(() => el.classList.remove('show'), 1600);
}

/* ----------------------------------------------------------------- saving */

function scheduleSave() {
  state.pendingSave = true;
  flashSync('保存中…');
  clearTimeout(state.saveTimer);
  state.saveTimer = setTimeout(saveConfig, 350);
}

async function saveConfig() {
  const val = Array.from(state.wanted).join(':');
  const r = await execRaw(ksudCfg(`set ${CFG_ENABLED} '${val}'`), 8000);
  state.pendingSave = false;
  if (r.errno !== 0) {
    flashSync('保存失败', true);
    toast('保存失败，请检查 KernelSU 权限');
    return;
  }
  flashSync('已保存');
  if (state.openDetail) state.openDetail.sync();
}

/* ---------------------------------------------------------------- fetch all */

async function loadAll(opts = {}) {
  if (state.loading) return;
  state.loading = true;
  if (!opts.silent) setStatus('warn', '正在扫描…');

  const r = await execRaw(buildFullCmd(), 20000);
  const blocks = parseBlocks(r.stdout);
  state.loading = false;

  if (r.errno !== 0 || !blocks.SVC) {
    state.error = '读取失败';
    state.loaded = true;
    setStatus('bad', '读取失败');
    render();
    return;
  }
  state.error = '';

  const raw = decodeB64(blocks.SVC).split(/\r?\n/).map(s => s.trim()).filter(Boolean).map(normComp);
  const uniq = [];
  raw.forEach(c => { if (!uniq.includes(c)) uniq.push(c); });
  state.services = uniq;
  state.installed = new Set(uniq);

  applyGuardState(blocks);

  const cfgStr = decodeB64(blocks.CFG || '').trim();
  const interval = parseInt(decodeB64(blocks.INT || '').trim(), 10);
  state.interval = (interval >= 5 && interval <= 300) ? interval : 20;

  let cfgList = normalizeList(cfgStr);
  let needAdopt = false;
  if (!cfgStr) {
    // 与 accd.sh 的「首次运行接管现有服务」保持一致，避免界面与守护各记一份
    cfgList = Array.from(state.enabled);
    needAdopt = cfgList.length > 0;
  }
  state.wanted = new Set(cfgList);

  state.loaded = true;
  state.lastFull = Date.now();
  render();

  if (needAdopt) saveConfig();

  // 主题
  loadTheme();

  // 应用名（异步，不阻塞）
  loadLabels();
}

async function loadLabels() {
  const pkgs = Array.from(new Set(state.services.map(c => c.split('/')[0])));
  if (!pkgs.length) return;

  // 先用本地缓存，界面立刻可读
  try {
    const cached = JSON.parse(localStorage.getItem(LS_LABELS) || '{}');
    if (cached && typeof cached === 'object') {
      Object.keys(cached).forEach(k => { if (pkgs.includes(k)) state.labels[k] = cached[k]; });
    }
  } catch (e) {}
  if (Object.keys(state.labels).length) render();

  let filled = false;

  try {
    if (typeof window.$packageManager !== 'undefined' && window.$packageManager.getInstalledPackages) {
      const raw = window.$packageManager.getInstalledPackages(0, 0);
      const list = raw ? JSON.parse(raw) : [];
      if (Array.isArray(list) && list.length) {
        filled = mergeLabels(list);
      }
    }
  } catch (e) {}

  if (!filled) {
    try {
      const list = await getPackagesInfo(pkgs);
      if (Array.isArray(list) && list.length) filled = mergeLabels(list);
    } catch (e) {}
  }

  if (!filled) return;

  try {
    localStorage.setItem(LS_LABELS, JSON.stringify(state.labels));
  } catch (e) {}

  render();
}

function mergeLabels(list) {
  let n = 0;
  list.forEach(item => {
    if (!item) return;
    const pkg = item.packageName || item.package || item.name;
    const label = item.appLabel || item.label || item.appName;
    if (pkg && label && state.labels[pkg] !== label) {
      state.labels[pkg] = String(label);
      n++;
    }
  });
  return n > 0;
}

/* --------------------------------------------------------------- rendering */

const FILTERS = [
  { k: 'all', label: '全部' },
  { k: 'guarded', label: '守护中' },
  { k: 'on', label: '已启用' },
  { k: 'off', label: '未启用' },
];

function counts() {
  const installed = state.services;
  let guarded = 0, on = 0, off = 0;
  installed.forEach(c => {
    if (state.wanted.has(c)) guarded++;
    if (state.enabled.has(c)) on++; else off++;
  });
  return { all: installed.length, guarded, on, off };
}

function renderFilters() {
  const row = $('filterRow');
  const n = counts();
  row.innerHTML = FILTERS.map(f => `
    <button class="chip ${state.filter === f.k ? 'active' : ''}" data-filter="${f.k}" role="tab" aria-selected="${state.filter === f.k}">
      ${f.label}<span class="n">${n[f.k]}</span>
    </button>`).join('');
}

function visibleItems() {
  const q = state.query.trim().toLowerCase();
  const items = [];

  state.services.forEach(comp => {
    if (q) {
      const pkg = comp.split('/')[0];
      const hay = (comp + ' ' + pkg + ' ' + (state.labels[pkg] || '')).toLowerCase();
      if (!hay.includes(q)) return;
    }
    const guarded = state.wanted.has(comp);
    const on = state.enabled.has(comp);
    if (state.filter === 'guarded' && !guarded) return;
    if (state.filter === 'on' && !on) return;
    if (state.filter === 'off' && on) return;
    items.push({ comp, missing: false });
  });

  // 已不在设备上、但仍在守护列表里的组件
  Array.from(state.wanted).forEach(comp => {
    if (state.installed.has(comp)) return;
    if (q) {
      const pkg = comp.split('/')[0];
      if (!(comp + ' ' + pkg).toLowerCase().includes(q)) return;
    }
    if (state.filter === 'on' || state.filter === 'off') return;
    items.push({ comp, missing: true });
  });

  items.sort((a, b) => {
    if (a.missing !== b.missing) return a.missing ? 1 : -1;
    return cmpLabel(a.comp, b.comp);
  });
  return items;
}

function rowHtml(comp, missing) {
  const pkg = comp.split('/')[0];
  const label = appLabel(comp);
  const guarded = state.wanted.has(comp);
  const on = state.enabled.has(comp);
  const svc = shortService(comp);
  const hue = hueFor(pkg);
  const initial = escapeHtml((label || '?').charAt(0).toUpperCase());

  let stateHtml;
  if (missing) stateHtml = '<span class="state err">不在设备上</span>';
  else if (on) stateHtml = '<span class="state on">已启用</span>';
  else if (guarded) stateHtml = '<span class="state err">未启用</span>';
  else stateHtml = '<span class="state off">未启用</span>';

  const sub = missing
    ? '<span class="row-svc">应用可能已卸载</span>'
    : `<span class="sep">·</span><span class="row-svc">${escapeHtml(svc || '默认服务')}</span>`;

  return `
  <div class="row ${guarded ? 'guarded' : ''} ${!missing && guarded && !on ? 'warn' : ''}"
       data-comp="${escapeHtml(comp)}" ${missing ? 'data-missing="1"' : ''} tabindex="0" role="button"
       aria-label="${escapeHtml(label)}，${on ? '已启用' : '未启用'}，${guarded ? '守护中' : '未守护'}">
    <div class="avatar" style="background:hsl(${hue},52%,46%)">
      <span>${initial}</span>
      ${missing ? '' : `<img src="ksu://icon/${escapeHtml(pkg)}" alt="" loading="lazy" onerror="this.remove()">`}
    </div>
    <div class="row-main">
      <div class="row-title"><span class="name">${escapeHtml(label)}</span></div>
      <div class="row-sub">
        ${stateHtml}${sub}
      </div>
    </div>
    <label class="switch" title="保活守护">
      <input type="checkbox" role="switch" data-comp="${escapeHtml(comp)}" ${guarded ? 'checked' : ''}
             aria-label="${escapeHtml(label)} 保活守护">
      <span class="track"></span><span class="knob"></span>
    </label>
  </div>`;
}

function emptyHtml(icon, title, desc, action) {
  return `<div class="empty">
    <div class="emoji">${icon}</div>
    <h3>${escapeHtml(title)}</h3>
    <p>${desc}</p>
    ${action ? `<button class="btn soft" data-action="${action.k}">${escapeHtml(action.label)}</button>` : ''}
  </div>`;
}

function render() {
  renderFilters();
  renderBanner();

  const list = $('list');

  if (!state.loaded) {
    list.innerHTML = Array.from({ length: 4 }, () => '<div class="skeleton-row"></div>').join('');
    return;
  }

  if (state.error) {
    list.innerHTML = emptyHtml('⚠️', '读取失败', '无法读取无障碍服务列表，请确认模块已安装并授予 KernelSU 权限。',
      { k: 'reload', label: '重试' });
    return;
  }

  if (!HAS_KSU) {
    list.innerHTML = emptyHtml('🔒', '请在 KernelSU 管理器中打开',
      '此页面需要 KernelSU 提供的 WebUI 运行环境，直接用浏览器打开无法读取数据。');
    return;
  }

  if (!state.services.length) {
    list.innerHTML = emptyHtml('📭', '没有找到无障碍服务',
      '设备上还没有任何应用注册无障碍服务。<br>安装并授权相关应用后点下方按钮重新扫描。',
      { k: 'reload', label: '重新扫描' });
    return;
  }

  const items = visibleItems();
  if (!items.length) {
    list.innerHTML = state.query
      ? emptyHtml('🔍', '没有匹配的服务', `没有找到与「${escapeHtml(state.query.trim())}」相关的应用或服务。`,
        { k: 'clear', label: '清除搜索' })
      : emptyHtml('✅', '这里是空的', '当前筛选条件下没有服务，换个筛选条件试试。',
        { k: 'all', label: '查看全部' });
    return;
  }

  let html = '';
  let missingShown = false;
  items.forEach(it => {
    if (it.missing && !missingShown) {
      missingShown = true;
      html += '<div class="group-label">不在设备上</div>';
    }
    html += rowHtml(it.comp, it.missing);
  });

  const broken = state.services.filter(c => state.wanted.has(c) && !state.enabled.has(c)).length;
  html += `<div class="footnote">
    共 ${state.services.length} 个无障碍服务 · 守护中 ${counts().guarded} 个${broken ? ` · <b>${broken} 个未启用</b>` : ''}<br>
    状态每 ${Math.round(POLL_MS / 1000)} 秒自动刷新
  </div>`;

  list.innerHTML = html;
}

function patchRows() {
  const list = $('list');
  if (!list) return;
  $$('.row', list).forEach(row => {
    const comp = row.dataset.comp;
    if (!comp) return;
    const guarded = state.wanted.has(comp);
    const on = state.enabled.has(comp);
    row.classList.toggle('guarded', guarded);

    const cb = row.querySelector('input[type=checkbox]');
    if (cb && cb.checked !== guarded) cb.checked = guarded;

    const st = row.querySelector('.state');
    if (st) {
      if (on) { st.className = 'state on'; st.textContent = '已启用'; }
      else if (guarded) { st.className = 'state err'; st.textContent = '未启用'; }
      else { st.className = 'state off'; st.textContent = '未启用'; }
    }
    row.classList.toggle('warn', guarded && !on && row.dataset.missing !== '1');
    row.setAttribute('aria-label',
      `${appLabel(comp)}，${on ? '已启用' : '未启用'}，${guarded ? '守护中' : '未守护'}`);
  });
  if (state.openDetail) state.openDetail.sync();
}

function renderBanner() {
  const el = $('banner');
  const text = $('bannerText');
  const btn = $('bannerBtn');

  let tone = '', msg = '', action = null;

  if (state.loaded && !state.error) {
    const broken = state.services.filter(c => state.wanted.has(c) && !state.enabled.has(c)).length;
    if (!state.guardAlive) {
      tone = '';
      msg = '守护进程未运行，守护列表暂时不会被自动恢复。';
      action = { label: '启动', k: 'start' };
    } else if (state.wanted.size === 0) {
      tone = 'info';
      msg = '还没有守护任何服务，打开右侧开关即可加入守护。';
    } else if (broken > 0) {
      tone = '';
      msg = `${broken} 个守护中的服务当前处于停用状态，守护进程会在下一轮检查时恢复。`;
      action = { label: '立即同步', k: 'sync' };
    }
  }

  if (!msg) {
    el.hidden = true;
    return;
  }
  el.hidden = false;
  el.dataset.tone = tone;
  text.textContent = msg;
  if (action) {
    btn.hidden = false;
    btn.textContent = action.label;
    btn.dataset.action = action.k;
  } else {
    btn.hidden = true;
    btn.removeAttribute('data-action');
  }
}

/* ------------------------------------------------------------ guard toggle */

async function toggleGuard(comp, on) {
  if (on) {
    state.wanted.add(comp);
    if (!state.enabled.has(comp)) {
      await setAccessibility(comp, true);
      toast('已开启守护，并自动启用无障碍');
    } else {
      toast('已开启守护');
    }
  } else {
    state.wanted.delete(comp);
    toast('已移出守护');
  }

  scheduleSave();
  renderFilters();
  renderBanner();

  if (state.filter !== 'all' || state.query) render();
  else patchRows();
}

async function setAccessibility(comp, on) {
  const cur = normalizeList(await run('settings get secure enabled_accessibility_services 2>/dev/null'));
  const next = on
    ? (cur.includes(comp) ? cur : cur.concat(comp))
    : cur.filter(x => x !== comp);

  const val = next.join(':');
  await run(`settings put secure enabled_accessibility_services '${val}' 2>/dev/null`);
  if (on) {
    await run('settings put secure accessibility_enabled 1 2>/dev/null');
  } else if (!next.length) {
    await run('settings put secure accessibility_enabled 0 2>/dev/null');
  }

  state.enabled = new Set(next);
  return next;
}

/* ------------------------------------------------------------------ sheets */

let sheetWrap = null;
let sheetCloseCb = null;
let historyPushed = false;

function openSheet(opts) {
  closeSheet(true);

  const wrap = document.createElement('div');
  wrap.className = 'sheet-wrap';
  wrap.innerHTML = `
    <div class="scrim" data-close="1"></div>
    <div class="sheet" role="dialog" aria-modal="true" aria-labelledby="sheetTitle">
      <div class="sheet-grip"></div>
      <div class="sheet-head">
        <div>
          <div class="sheet-title" id="sheetTitle"></div>
          <div class="sheet-sub"></div>
        </div>
        <button class="icon-btn round" type="button" data-close="1" aria-label="关闭">✕</button>
      </div>
      <div class="sheet-body"></div>
    </div>`;

  $('.sheet-title', wrap).textContent = opts.title || '';
  const subEl = $('.sheet-sub', wrap);
  if (opts.subtitle) subEl.textContent = opts.subtitle; else subEl.remove();

  const body = $('.sheet-body', wrap);
  if (opts.content) body.appendChild(opts.content);

  wrap.addEventListener('click', e => {
    if (e.target.closest('[data-close]')) closeSheet();
  });

  document.body.appendChild(wrap);
  document.body.classList.add('no-scroll');
  requestAnimationFrame(() => wrap.classList.add('show'));

  sheetWrap = wrap;
  sheetCloseCb = opts.onClose || null;

  if (opts.onOpen) opts.onOpen(body, wrap);

  // 下滑关闭
  const sheet = $('.sheet', wrap);
  let startY = 0, dy = 0, dragging = false;
  const onDown = e => {
    if (e.target.closest('button, input, .seg')) return;
    dragging = true;
    startY = e.clientY;
    dy = 0;
    sheet.classList.add('dragging');
  };
  const onMove = e => {
    if (!dragging) return;
    dy = Math.max(0, e.clientY - startY);
    sheet.style.transform = `translateY(${dy}px)`;
  };
  const onUp = () => {
    if (!dragging) return;
    dragging = false;
    sheet.classList.remove('dragging');
    sheet.style.transform = '';
    if (dy > 90) closeSheet();
  };
  $('.sheet-grip', wrap).addEventListener('pointerdown', onDown);
  $('.sheet-head', wrap).addEventListener('pointerdown', onDown);
  window.addEventListener('pointermove', onMove);
  window.addEventListener('pointerup', onUp);
  wrap._cleanupDrag = () => {
    window.removeEventListener('pointermove', onMove);
    window.removeEventListener('pointerup', onUp);
  };

  if (HAS_KSU === false) return { wrap, body, close: closeSheet };

  try {
    if (history.pushState) {
      history.pushState({ akgSheet: 1 }, '');
      historyPushed = true;
    }
  } catch (e) {}

  return { wrap, body, close: closeSheet };
}

function closeSheet(fromHistory) {
  if (!sheetWrap) return;
  const wrap = sheetWrap;
  sheetWrap = null;

  if (wrap._cleanupDrag) wrap._cleanupDrag();
  if (sheetCloseCb) {
    try { sheetCloseCb(); } catch (e) {}
  }
  sheetCloseCb = null;

  wrap.classList.remove('show');
  document.body.classList.remove('no-scroll');
  setTimeout(() => wrap.remove(), 300);

  if (historyPushed && !fromHistory) {
    historyPushed = false;
    try { history.back(); } catch (e) {}
  } else {
    historyPushed = false;
  }
}

window.addEventListener('popstate', () => {
  if (sheetWrap) closeSheet(true);
});

function panel(children) {
  const p = document.createElement('div');
  p.className = 'panel';
  children.forEach(c => p.appendChild(c));
  return p;
}

function section(title, ...children) {
  const s = document.createElement('div');
  s.className = 'section';
  if (title) {
    const t = document.createElement('div');
    t.className = 'section-title';
    t.textContent = title;
    s.appendChild(t);
  }
  children.forEach(c => c && s.appendChild(c));
  return s;
}

function line(k, v, opts = {}) {
  const el = document.createElement('div');
  el.className = 'line' + (opts.clickable ? ' clickable' : '');
  const key = document.createElement('span');
  key.className = 'k';
  key.textContent = k;
  el.appendChild(key);
  if (v != null) {
    const val = document.createElement('span');
    val.className = 'v';
    val.textContent = v;
    el.appendChild(val);
  }
  if (opts.onClick) el.addEventListener('click', opts.onClick);
  return el;
}

function seg(options, value, onChange) {
  const el = document.createElement('div');
  el.className = 'seg';
  options.forEach(o => {
    const b = document.createElement('button');
    b.type = 'button';
    b.textContent = o.label;
    if (o.value === value) b.classList.add('active');
    b.addEventListener('click', () => {
      $$('button', el).forEach(x => x.classList.remove('active'));
      b.classList.add('active');
      onChange(o.value);
    });
    el.appendChild(b);
  });
  return el;
}

function switchLine(title, desc, checked, onToggle) {
  const el = document.createElement('div');
  el.className = 'switch-line';
  el.innerHTML = `
    <div class="txt">
      <div class="t"></div>
      <div class="d"></div>
    </div>
    <label class="switch">
      <input type="checkbox" role="switch">
      <span class="track"></span><span class="knob"></span>
    </label>`;
  $('.t', el).textContent = title;
  const d = $('.d', el);
  d.textContent = desc || '';
  const cb = $('input', el);
  cb.checked = !!checked;
  cb.setAttribute('aria-label', title);
  cb.addEventListener('change', () => onToggle(cb.checked));
  el._sync = (on, descText) => {
    cb.checked = !!on;
    if (descText != null) d.textContent = descText;
  };
  return el;
}

/* --------------------------------------------------------------- sheets ui */

function openSettings() {
  const body = document.createElement('div');

  /* 守护进程 */
  const statusVal = document.createElement('span');
  statusVal.className = 'v';
  statusVal.textContent = state.guardAlive
    ? `运行中${state.guardPid ? ' · PID ' + state.guardPid : ''}`
    : '未运行';
  statusVal.style.color = state.guardAlive ? 'var(--good)' : 'var(--danger)';
  const statusLine = document.createElement('div');
  statusLine.className = 'line';
  const sk = document.createElement('span');
  sk.className = 'k';
  sk.textContent = '守护进程';
  statusLine.appendChild(sk);
  statusLine.appendChild(statusVal);

  const intervalSeg = seg(
    [{ label: '10 秒', value: 10 }, { label: '20 秒', value: 20 }, { label: '30 秒', value: 30 }, { label: '60 秒', value: 60 }],
    state.interval,
    async v => {
      state.interval = v;
      await run(ksudCfg(`set ${CFG_INTERVAL} '${v}'`));
      toast(`检查间隔已设为 ${v} 秒`);
    }
  );
  const intervalLine = document.createElement('div');
  intervalLine.className = 'line';
  intervalLine.style.flexDirection = 'column';
  intervalLine.style.alignItems = 'stretch';
  intervalLine.style.gap = '8px';
  const il = document.createElement('div');
  il.style.fontSize = '14px';
  il.textContent = '检查间隔';
  intervalLine.appendChild(il);
  intervalLine.appendChild(intervalSeg);

  const actions = document.createElement('div');
  actions.className = 'line';
  actions.style.gap = '9px';
  const btnSync = document.createElement('button');
  btnSync.className = 'btn soft sm';
  btnSync.textContent = '立即同步';
  btnSync.addEventListener('click', async () => {
    btnSync.disabled = true;
    const out = await run(`sh ${MODDIR}/data/control.sh once`, 25000);
    btnSync.disabled = false;
    const line1 = out.trim().split('\n').filter(Boolean).pop() || '已同步';
    toast(line1.slice(0, 60));
    refreshLight();
  });
  const btnRestart = document.createElement('button');
  btnRestart.className = 'btn ghost sm';
  btnRestart.textContent = state.guardAlive ? '重启守护' : '启动守护';
  btnRestart.addEventListener('click', async () => {
    btnRestart.disabled = true;
    await run(`sh ${MODDIR}/data/control.sh restart`, 15000);
    btnRestart.disabled = false;
    toast(state.guardAlive ? '守护已重启' : '守护已启动');
    setTimeout(refreshLight, 600);
  });
  actions.appendChild(btnSync);
  actions.appendChild(btnRestart);

  body.appendChild(section('守护进程', panel([statusLine, intervalLine, actions])));

  /* 外观 */
  const themeSeg = seg(
    [{ label: '跟随系统', value: 'auto' }, { label: '浅色', value: 'light' }, { label: '深色', value: 'dark' }],
    state.theme,
    v => setTheme(v)
  );
  const themeLine = document.createElement('div');
  themeLine.className = 'line';
  themeLine.style.flexDirection = 'column';
  themeLine.style.alignItems = 'stretch';
  themeLine.style.gap = '8px';
  const tl = document.createElement('div');
  tl.style.fontSize = '14px';
  tl.textContent = '主题';
  themeLine.appendChild(tl);
  themeLine.appendChild(themeSeg);
  body.appendChild(section('外观', panel([themeLine])));

  /* 批量 */
  const batch = document.createElement('div');
  batch.className = 'line';
  batch.style.gap = '9px';
  const btnAllOn = document.createElement('button');
  btnAllOn.className = 'btn soft sm';
  btnAllOn.textContent = '全部守护';
  btnAllOn.addEventListener('click', async () => {
    const before = state.wanted.size;
    state.services.forEach(c => state.wanted.add(c));
    const added = state.wanted.size - before;
    scheduleSave();
    render();
    if (added) toast(`已加入 ${added} 个服务`);
    else toast('已全部在守护列表');
  });
  const btnAllOff = document.createElement('button');
  btnAllOff.className = 'btn ghost sm';
  btnAllOff.textContent = '全部取消';
  btnAllOff.addEventListener('click', async () => {
    const ok = await showDialog({
      title: '清空守护列表？',
      text: '所有服务都会移出守护，被系统关闭后不再自动恢复。无障碍开关本身不受影响。',
      actions: [
        { label: '清空', value: true, style: 'danger' },
        { label: '取消', value: false, style: 'ghost' },
      ],
    });
    if (!ok) return;
    state.wanted.clear();
    scheduleSave();
    render();
    toast('已清空守护列表');
  });
  batch.appendChild(btnAllOn);
  batch.appendChild(btnAllOff);

  const panelChildren = [batch];

  const missing = Array.from(state.wanted).filter(c => !state.installed.has(c));
  if (missing.length) {
    const btnClean = document.createElement('button');
    btnClean.className = 'btn ghost sm';
    btnClean.textContent = `清理 ${missing.length} 个失效项`;
    btnClean.style.margin = '0 15px 13px';
    btnClean.style.width = 'calc(100% - 30px)';
    btnClean.addEventListener('click', async () => {
      missing.forEach(c => state.wanted.delete(c));
      scheduleSave();
      render();
      toast(`已清理 ${missing.length} 个失效项`);
    });
    panelChildren.push(btnClean);
  }

  body.appendChild(section('批量操作', panel(panelChildren)));

  /* 日志 / 关于 */
  const logLine = line('查看守护日志', '›', { clickable: true, onClick: () => openLogs() });
  body.appendChild(section('日志', panel([logLine])));

  const about = document.createElement('div');
  about.className = 'footnote';
  about.innerHTML = `无障碍保活 ${escapeHtml(VERSION)}<br>KernelSU / Magisk 模块 · 作者 Yxlan-yu`;
  body.appendChild(about);

  openSheet({ title: '设置', content: body });
}

function openLogs() {
  const body = document.createElement('div');
  const pre = document.createElement('pre');
  pre.className = 'log';
  pre.textContent = '读取中…';

  const bar = document.createElement('div');
  bar.className = 'btn-row';
  bar.style.marginTop = '12px';
  const btnRefresh = document.createElement('button');
  btnRefresh.className = 'btn ghost sm';
  btnRefresh.textContent = '刷新';
  const btnCopy = document.createElement('button');
  btnCopy.className = 'btn soft sm';
  btnCopy.textContent = '复制全部';
  bar.appendChild(btnRefresh);
  bar.appendChild(btnCopy);

  body.appendChild(pre);
  body.appendChild(bar);

  const tick = async () => {
    const txt = await run(`tail -n 80 ${LOGFILE} 2>/dev/null`);
    const lines = txt.split(/\r?\n/).filter(l => l.trim() !== '');
    pre.textContent = lines.length ? lines.reverse().join('\n') : '（暂无日志）';
  };

  openSheet({
    title: '守护日志',
    subtitle: '最新在上，每 3 秒自动刷新',
    content: body,
    onOpen: () => {
      tick();
      state.logTimer = setInterval(() => {
        if (!document.hidden) tick();
      }, 3000);
    },
    onClose: () => {
      clearInterval(state.logTimer);
      state.logTimer = null;
    },
  });

  btnRefresh.addEventListener('click', tick);
  btnCopy.addEventListener('click', () => copyText(pre.textContent === '（暂无日志）' ? '' : pre.textContent));
}

function openDetail(comp) {
  const pkg = comp.split('/')[0];
  const label = appLabel(comp);
  const missing = !state.installed.has(comp);
  const svcFull = comp.split('/')[1] || '';

  const body = document.createElement('div');

  const hero = document.createElement('div');
  hero.className = 'detail-hero';
  hero.innerHTML = `
    <div class="avatar" style="background:hsl(${hueFor(pkg)},52%,46%)">
      <span>${escapeHtml((label || '?').charAt(0).toUpperCase())}</span>
      ${missing ? '' : `<img src="ksu://icon/${escapeHtml(pkg)}" alt="" onerror="this.remove()">`}
    </div>
    <div><h2></h2><div class="pkg"></div></div>`;
  $('h2', hero).textContent = label;
  $('.pkg', hero).textContent = pkg;
  body.appendChild(hero);

  const guardSwitch = switchLine('保活守护', '被系统关闭后自动恢复', state.wanted.has(comp), async on => {
    await toggleGuard(comp, on);
    sync();
  });

  const accSwitch = switchLine('无障碍服务', '当前状态', state.enabled.has(comp), async on => {
    if (!on && state.wanted.has(comp)) {
      // 正在守护却要停用 → 明确二选一，避免「关了 20 秒又被拉回来」的困惑
      accSwitch._sync(true, state.enabled.has(comp) ? '已启用' : '未启用');
      const choice = await showDialog({
        title: '该服务正在守护中',
        text: '停用后守护进程会在下一轮检查时重新启用它。要同时取消守护吗？',
        actions: [
          { label: '停用并取消守护', value: 'both', style: 'danger' },
          { label: '只停用（稍后会被恢复）', value: 'off', style: 'ghost' },
          { label: '取消', value: null, style: 'ghost' },
        ],
      });
      if (!choice) { sync(); return; }
      if (choice === 'both') {
        state.wanted.delete(comp);
        scheduleSave();
      }
      await setAccessibility(comp, false);
      toast(choice === 'both' ? '已停用并取消守护' : '已停用，守护会稍后恢复');
      sync();
      patchRows();
      renderFilters();
      renderBanner();
      return;
    }
    await setAccessibility(comp, on);
    toast(on ? '已启用无障碍' : '已停用无障碍');
    sync();
    patchRows();
    renderFilters();
    renderBanner();
  });

  if (missing) {
    const warn = document.createElement('div');
    warn.className = 'banner';
    warn.style.margin = '0 0 14px';
    warn.textContent = '该应用可能已被卸载或改名，守护项已失效，建议移除。';
    body.appendChild(warn);
  }

  body.appendChild(panel([guardSwitch, accSwitch]));

  const copyLine = line('复制组件名', null, {
    clickable: true,
    onClick: () => copyText(comp),
  });
  copyLine.style.justifyContent = 'space-between';
  const cv = document.createElement('span');
  cv.className = 'v';
  cv.style.maxWidth = '60%';
  cv.textContent = '复制';
  cv.style.color = 'var(--accent)';
  copyLine.appendChild(cv);

  body.appendChild(section('组件信息', panel([
    line('服务类', svcFull || '（默认服务）'),
    line('完整组件', comp),
    copyLine,
  ])));

  const note = document.createElement('div');
  note.className = 'footnote';
  note.textContent = `守护进程每 ${state.interval} 秒检查一次；关闭开关只会取消守护，不会停用无障碍服务。`;
  body.appendChild(note);

  function sync() {
    guardSwitch._sync(state.wanted.has(comp));
    accSwitch._sync(state.enabled.has(comp), state.enabled.has(comp) ? '已启用' : '未启用');
    guardSwitch.querySelector('.d').textContent = state.wanted.has(comp)
      ? '守护中 · 被系统关闭后自动恢复'
      : '未守护 · 系统关闭后不会自动恢复';
  }
  sync();

  const sheet = openSheet({
    title: '服务详情',
    content: body,
    onClose: () => { state.openDetail = null; },
  });
  state.openDetail = { comp, sync };
  return sheet;
}

/* ---------------------------------------------------------------- dialog */

function showDialog(opts) {
  return new Promise(resolve => {
    const wrap = document.createElement('div');
    wrap.className = 'dialog-wrap';
    wrap.innerHTML = `
      <div class="scrim"></div>
      <div class="dialog" role="alertdialog" aria-modal="true">
        <h3></h3>
        <p></p>
        <div class="dialog-actions"></div>
      </div>`;
    $('h3', wrap).textContent = opts.title || '';
    $('p', wrap).textContent = opts.text || '';

    const actions = $('.dialog-actions', wrap);
    let done = false;
    const finish = v => {
      if (done) return;
      done = true;
      wrap.classList.remove('show');
      setTimeout(() => wrap.remove(), 220);
      resolve(v);
    };

    (opts.actions || []).forEach(a => {
      const b = document.createElement('button');
      b.className = 'btn ' + (a.style || 'ghost');
      b.textContent = a.label;
      b.addEventListener('click', () => finish(a.value));
      actions.appendChild(b);
    });

    $('.scrim', wrap).addEventListener('click', () => finish(null));
    document.body.appendChild(wrap);
    requestAnimationFrame(() => wrap.classList.add('show'));
  });
}

/* ----------------------------------------------------------------- toast */

function toast(msg, action) {
  const host = $('toastHost');
  const el = document.createElement('div');
  el.className = 'toast';

  const text = document.createElement('span');
  text.className = 'msg';
  text.textContent = msg;
  el.appendChild(text);

  let dismissed = false;
  const dismiss = () => {
    if (dismissed) return;
    dismissed = true;
    el.classList.add('out');
    setTimeout(() => el.remove(), 220);
  };

  if (action) {
    const b = document.createElement('button');
    b.className = 'act';
    b.type = 'button';
    b.textContent = action.label;
    b.addEventListener('click', () => {
      try { action.onClick(); } finally { dismiss(); }
    });
    el.appendChild(b);
  }

  host.appendChild(el);
  while (host.children.length > 2) host.firstElementChild.remove();
  setTimeout(dismiss, action ? 4500 : 2000);
}

/* ------------------------------------------------------------------ poll */

async function refreshLight() {
  if (document.hidden || state.loading) return;
  const r = await execRaw(buildPollCmd(), 8000);
  if (r.errno !== 0) return;
  const blocks = parseBlocks(r.stdout);
  if (!blocks.ON) return;

  const beforeEnabled = state.enabled;
  const beforeAlive = state.guardAlive;
  const beforeWanted = state.wanted.size;

  const prevEnabled = beforeEnabled;
  applyGuardState(blocks);

  const enabledChanged =
    prevEnabled.size !== state.enabled.size ||
    Array.from(prevEnabled).some(v => !state.enabled.has(v));

  if (!state.pendingSave && !state.loading) {
    const cfgStr = decodeB64(blocks.CFG || '').trim();
    const cfgList = normalizeList(cfgStr);
    const same =
      cfgList.length === state.wanted.size &&
      cfgList.every(c => state.wanted.has(c));
    if (!same && cfgStr) {
      state.wanted = new Set(cfgList);
      render();
    }
  }

  if (enabledChanged || beforeAlive !== state.guardAlive) {
    patchRows();
    renderFilters();
    renderBanner();
  }
  if (beforeWanted !== state.wanted.size) renderFilters();

  updateStatusChip();
}

function updateStatusChip() {
  if (state.loading) { setStatus('warn', '正在扫描…'); return; }
  if (state.error) { setStatus('bad', '读取失败'); return; }
  if (state.guardAlive) setStatus('ok', '守护运行中');
  else setStatus('bad', '守护未运行');
}

function startPolling() {
  if (state.pollTimer) clearInterval(state.pollTimer);
  state.pollTimer = setInterval(refreshLight, POLL_MS);
}

/* ------------------------------------------------------------------ init */

function bindEvents() {
  $('btnSettings').addEventListener('click', openSettings);
  $('statusChip').addEventListener('click', openSettings);

  $('bannerBtn').addEventListener('click', async e => {
    const action = e.currentTarget.dataset.action;
    if (action === 'start') {
      toast('正在启动守护…');
      await run(`sh ${MODDIR}/data/control.sh restart`, 15000);
      setTimeout(refreshLight, 700);
    } else if (action === 'sync') {
      const out = await run(`sh ${MODDIR}/data/control.sh once`, 25000);
      toast((out.trim().split('\n').filter(Boolean).pop() || '已同步').slice(0, 60));
      refreshLight();
    }
  });

  $('filterRow').addEventListener('click', e => {
    const chip = e.target.closest('[data-filter]');
    if (!chip) return;
    state.filter = chip.dataset.filter;
    render();
    const list = $('list');
    if (list && list.scrollIntoView) window.scrollTo({ top: 0, behavior: 'smooth' });
  });

  $('searchInput').addEventListener('input', debounce(e => {
    state.query = e.target.value;
    $('searchClear').hidden = !state.query;
    render();
  }, 120));

  $('searchClear').addEventListener('click', () => {
    const input = $('searchInput');
    input.value = '';
    state.query = '';
    $('searchClear').hidden = true;
    render();
    input.focus();
  });

  $('list').addEventListener('click', async e => {
    const actionBtn = e.target.closest('[data-action]');
    if (actionBtn) {
      const k = actionBtn.dataset.action;
      if (k === 'reload') { loadAll(); }
      else if (k === 'clear') { $('searchClear').click(); }
      else if (k === 'all') { state.filter = 'all'; render(); }
      return;
    }
    if (e.target.closest('.switch')) return;
    const row = e.target.closest('.row');
    if (!row) return;
    openDetail(row.dataset.comp);
  });

  $('list').addEventListener('keydown', e => {
    if (e.key !== 'Enter' && e.key !== ' ') return;
    const row = e.target.closest('.row');
    if (!row) return;
    e.preventDefault();
    openDetail(row.dataset.comp);
  });

  $('list').addEventListener('change', e => {
    const cb = e.target.closest('input[type=checkbox]');
    if (!cb) return;
    const comp = cb.dataset.comp;
    if (!comp) return;
    toggleGuard(comp, cb.checked);
  });

  window.addEventListener('scroll', () => {
    $('topbar').classList.toggle('scrolled', window.scrollY > 4);
  }, { passive: true });

  document.addEventListener('visibilitychange', () => {
    if (document.hidden) {
      if (state.pollTimer) clearInterval(state.pollTimer);
      state.pollTimer = null;
    } else {
      startPolling();
      if (Date.now() - state.lastFull > FULL_REFRESH_MS) loadAll({ silent: true });
      else refreshLight();
    }
  });

  if (window.matchMedia) {
    window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
      if (state.theme === 'auto') applyTheme('auto');
    });
  }
}

function debounce(fn, ms) {
  let t = null;
  return function (...args) {
    clearTimeout(t);
    t = setTimeout(() => fn.apply(this, args), ms);
  };
}

async function init() {
  bindEvents();
  render();

  if (!HAS_KSU) {
    state.loaded = true;
    state.error = '';
    render();
    setStatus('bad', '需在 KernelSU 中打开');
    return;
  }

  await loadAll();
  updateStatusChip();
  startPolling();
}

init();

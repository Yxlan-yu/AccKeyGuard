import { exec, getPackagesInfo } from './kernelsu.js';

const MOD_ID = 'acckeyguard';
const $ = (id) => document.getElementById(id);

const REFRESH_MS = 5000;

const state = {
  all: [],        // 所有已安装无障碍服务 [{component, enabled}]
  wanted: [],     // 用户勾选要保活的
  wantedLoaded: false,
  enabledSet: new Set(), // 当前系统已启用（去重后）
  dirty: false,
  current: null,  // 详情视图当前服务
  labels: {},     // 包名 -> 应用名
  pollTimer: null,
  refreshing: false,
  query: '',
};

// ---------- 主题 ----------
const THEME_KEY = 'theme';
function appliedTheme() {
  const saved = state.theme || 'auto';
  if (saved === 'auto') {
    return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }
  return saved;
}
function applyTheme() {
  const t = appliedTheme();
  document.documentElement.setAttribute('data-theme', t);
  document.querySelectorAll('#themeBar .theme-btn').forEach(b => {
    b.classList.toggle('active', b.dataset.v === (state.theme || 'auto'));
  });
}
async function loadTheme() {
  const raw = await run(`KSU_MODULE=${MOD_ID} /data/adb/ksu/bin/ksud module config get ${THEME_KEY} 2>/dev/null`);
  state.theme = (raw || '').trim() || 'auto';
  if (!['auto', 'light', 'dark'].includes(state.theme)) state.theme = 'auto';
  applyTheme();
}
async function saveTheme(v) {
  state.theme = v;
  applyTheme();
  await run(`KSU_MODULE=${MOD_ID} /data/adb/ksu/bin/ksud module config set ${THEME_KEY} '${v}' 2>/dev/null`);
  toast('主题已切换');
}
function bindTheme() {
  document.querySelectorAll('#themeBar .theme-btn').forEach(btn => {
    btn.addEventListener('click', () => saveTheme(btn.dataset.v));
  });
  if (window.matchMedia) {
    window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
      if (state.theme === 'auto') applyTheme();
    });
  }
}

// 获取真实应用图标：直接用 KernelSU 图标 scheme
async function loadIcon(pkg) {
  return 'ksu://icon/' + pkg;
}

// 由包名生成稳定色相，做头像背景
function hueFor(pkg) {
  let h = 0;
  for (let i = 0; i < pkg.length; i++) h = (h * 31 + pkg.charCodeAt(i)) % 360;
  return h;
}

// 取应用名（优先中文 label）
function appLabel(pkg) {
  return state.labels[pkg] || pkg;
}

// 头像首字符
function initialChar(pkg) {
  const label = appLabel(pkg);
  return label.charAt(0).toUpperCase() || '?';
}

function toast(msg) {
  const t = document.createElement('div');
  t.className = 'toast';
  t.textContent = msg;
  document.body.appendChild(t);
  setTimeout(() => t.remove(), 2000);
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, ch => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[ch]);
}

async function run(cmd) {
  try {
    const { errno, stdout, stderr } = await exec(cmd);
    if (errno) return '';
    return stdout || '';
  } catch (e) {
    console.error(cmd, e);
    return '';
  }
}

// 单次 exec 拿到所有数据：服务列表 / enabled / config
async function loadAllData() {
  const cmd = [
    `KSVC=$(cmd package query-services --user 0 -a android.accessibilityservice.AccessibilityService --components 2>/dev/null)`,
    `KON=$(settings get secure enabled_accessibility_services 2>/dev/null)`,
    `KCFG=$(KSU_MODULE=${MOD_ID} /data/adb/ksu/bin/ksud module config get enabled_services 2>/dev/null)`,
    `echo -n "$KSVC" | base64 -w0; echo XSEP`,
    `echo -n "$KON" | base64 -w0; echo XSEP`,
    `echo -n "$KCFG" | base64 -w0; echo XSEP`,
  ].join('\n');
  const out = await Promise.race([
    run(cmd),
    new Promise(res => setTimeout(() => res(''), 8000)),
  ]);
  const parts = out.split('XSEP').filter(Boolean);
  const b = s => { try { return decodeURIComponent(escape(atob(s))); } catch (e) { return ''; } };
  const services = b(parts[0] || '').split('\n').map(s => s.trim()).filter(Boolean).map(normComp);
  const enabled = b(parts[1] || '');
  const cfg = b(parts[2] || '');
  return { services, enabled, cfg };
}

// 把 pkg/.Class 简写展开为完整 pkg/pkg.Class（query-services / settings get 通用）
function normComp(c) {
  const s = String(c).trim();
  const i = s.indexOf('/');
  if (i < 0) return s;
  const pkg = s.slice(0, i), cls = s.slice(i + 1);
  if (cls.startsWith('.')) return pkg + '/' + pkg + cls;
  return s;
}

function normalizeList(str) {
  // 把 pkg/.Class 简写展开为完整 pkg/pkg.Class，与 query-services 输出格式对齐
  return String(str || '').split(':').map(s => s.trim()).filter(Boolean).map(normComp)
    .filter((c, idx, arr) => arr.indexOf(c) === idx);
}

// 轻量轮询：只拉 enabled + config + 守护状态，更新徽标与状态；不重扫服务列表
// 这样系统侧被 accd 补写回后，页面 5 秒内自动跟上，无需退出重进
async function pollLight() {
  if (state.refreshing) return;
  state.refreshing = true;
  try {
    const [{ stdout: enabled, errno: enErr }, { stdout: cfgOut, errno: cfgErr }, alive] = await Promise.all([
      exec(`settings get secure enabled_accessibility_services 2>/dev/null`),
      exec(`KSU_MODULE=${MOD_ID} /data/adb/ksu/bin/ksud module config get enabled_services 2>/dev/null`),
      run(`kill -0 $(cat /data/adb/modules/${MOD_ID}/data/accd.pid 2>/dev/null) 2>/dev/null && echo alive`),
    ]);
    const enabledStr = enErr ? '' : (enabled || '');
    const cfgStr = cfgErr ? '' : (cfgOut || '');
    const newEnabled = new Set(normalizeList(enabledStr));

    // 配置同步：仅当没有未保存的本地改动时，才用后端配置覆盖 state.wanted。
    // 否则用户刚勾选/取消但还没点保存，轮询读到的是旧配置，把它还原回去 = "显示跟不上"。
    let cfgChanged = false;
    if ((cfgStr.trim() !== '' || !state.wantedLoaded) && !state.dirty) {
      const newWanted = normalizeList(cfgStr);
      cfgChanged = JSON.stringify(newWanted) !== JSON.stringify(state.wanted);
      if (cfgChanged) {
        state.wanted = newWanted;
        state.wantedLoaded = true;
        render();
      }
    }

    // enabled 变化：仅刷新徽标 + 详情（不整列重建，避免打断）
    const enabledChanged = state.enabledSet.size !== newEnabled.size ||
      [...state.enabledSet].some(v => !newEnabled.has(v)) ||
      [...newEnabled].some(v => !state.enabledSet.has(v));
    if (enabledChanged) {
      state.enabledSet = newEnabled;
      if ($('mainView') && !$('mainView').classList.contains('hidden')) {
        livePatchBadges();
      }
      if (state.current) {
        refreshDetailRow();
      }
    } else if (!cfgChanged) {
      state.enabledSet = newEnabled;
    }

    // 守护运行状态
    const pill = $('statusPill');
    if (pill) {
      const now = Date.now();
      const st = pill.dataset.st;
      if (alive.trim() === 'alive') { setPill('good', '守护运行中'); }
      else { setPill('off', '守护未运行'); }
    }
  } catch (e) {
    console.error('pollLight', e);
  } finally {
    state.refreshing = false;
  }
}

function setPill(cls, text) {
  const pill = $('statusPill');
  if (pill.dataset.st === cls + '|' + text) return;
  pill.className = 'pill ' + cls;
  pill.textContent = text;
  pill.dataset.st = cls + '|' + text;
}

// 只更新列表里已有的徽标 + checkbox 状态，并按最新状态重排，不重建 DOM（轮询时保持滚动位置与交互）
function livePatchBadges() {
  const list = $('serviceList');
  const rows = Array.from(list.querySelectorAll('.svc'));
  rows.forEach(row => {
    const comp = row.getAttribute('data-comp');
    if (!comp) return;
    const enabled = state.enabledSet.has(comp);
    const wanted = state.wanted.includes(comp);
    const badge = row.querySelector('.svc-badge');
    if (badge) { badge.textContent = enabled ? '已启用' : '已停用'; badge.className = 'svc-badge ' + (enabled ? 'on' : 'off'); }
    const cb = row.querySelector('input[type=checkbox][data-comp]');
    if (cb && cb.checked !== wanted) cb.checked = wanted;
  });
  // 原地重排：按最新状态把 DOM 节点移动到位，不重建
  rows.sort((a, b) => rankOf(a.getAttribute('data-comp')) - rankOf(b.getAttribute('data-comp')));
  rows.forEach(node => list.appendChild(node));
}

function refreshDetailRow() {
  if (!state.current) return;
  const comp = state.current;
  const enabled = state.enabledSet.has(comp);
  const wanted = state.wanted.includes(comp);
  const rows = $('detailContent').querySelectorAll('.detail-row .v');
  if (rows.length < 4) return;
  rows[2].textContent = enabled ? '已启用' : '已停用';
  rows[3].textContent = wanted ? '是' : '否';
}

async function load() {
  try {
    setPill('warn', '检查中');

    // 先渲染首屏骨架
    state.wanted = state.wanted || [];
    state.enabledSet = state.enabledSet || new Set();
    render();

    // 主题 + 数据
    await loadTheme();
    const data = await loadAllData();
    state.all = data.services;
    state.enabledSet = new Set(normalizeList(data.enabled));
    if (data.cfg.trim() !== '') state.wanted = normalizeList(data.cfg);
    state.wantedLoaded = true;
    render();
    setPill('good', '检查完成');
    $('btnSave').disabled = true;

    // 应用名异步补齐，不阻塞列表显示
    const pkgs = [...new Set(state.all.map(c => c.split('/')[0]))];
    state.labels = {};
    const fillLabels = list => {
      if (!Array.isArray(list)) return;
      list.forEach(i => {
        const nm = i && (i.packageName || i.package || i.name);
        const lb = i && (i.appLabel || i.label || i.appName);
        if (nm && lb) state.labels[nm] = lb;
      });
    };
    (async () => {
      let got = false;
      try {
        if (typeof window.$packageManager !== 'undefined') {
          const raw = window.$packageManager.getInstalledPackages(0, 0);
          if (raw) { fillLabels(JSON.parse(raw)); got = true; }
        }
      } catch (e) {}
      if (!got) {
        try { fillLabels(await getPackagesInfo(pkgs) || []); } catch (e) { console.error(e); }
      }
      if (Object.keys(state.labels).length) render();
    })();

    updateStatus();
    $('btnSave').disabled = true;
    startPolling();
  } catch (e) {
    console.error('load 失败', e);
    setPill('off', '加载失败');
    const list = $('serviceList');
    if (list) list.innerHTML = '<div class="loading">加载失败，请点重新扫描</div>';
  }
}

function updateStatus() {
  run('kill -0 $(cat /data/adb/modules/acckeyguard/data/accd.pid 2>/dev/null) 2>/dev/null && echo alive')
    .then(out => {
      if (out.trim() === 'alive') setPill('good', '守护运行中');
      else setPill('off', '守护未运行');
    });
}

function startPolling() {
  if (state.pollTimer) clearInterval(state.pollTimer);
  state.pollTimer = setInterval(pollLight, REFRESH_MS);
}

// 排序级别：0=已开启且已守护，1=已开启未守护，2=未开启
function rankOf(comp) {
  const enabled = state.enabledSet.has(comp);
  const wanted = state.wanted.includes(comp);
  if (enabled && wanted) return 0;
  if (enabled) return 1;
  return 2;
}

function render() {
  const list = $('serviceList');
  if (!state.all.length) { list.innerHTML = '<div class="loading">未扫描到无障碍服务</div>'; return; }

  const wantedSet = new Set(state.wanted);
  const q = state.query.trim().toLowerCase();
  const filtered = q
    ? state.all.filter(c => {
        const pkg = c.split('/')[0] || '';
        return c.toLowerCase().includes(q) || pkg.toLowerCase().includes(q) || appLabel(pkg).toLowerCase().includes(q);
      })
    : state.all;
  if (!filtered.length) { list.innerHTML = '<div class="loading">没有匹配“' + escapeHtml(state.query.trim()) + '”的服务</div>'; return; }

  const sorted = [...filtered].sort((a, b) => rankOf(a) - rankOf(b));
  const rows = sorted.map(comp => {
    const [pkg, svc] = comp.split('/');
    const enabled = state.enabledSet.has(comp);
    const wanted = wantedSet.has(comp);
    const hue = hueFor(pkg);
    const initial = initialChar(pkg);
    const label = appLabel(pkg);

    return `<div class="svc" data-comp="${comp}">
      <div class="svc-avatar" data-avatar="${pkg}" style="background:hsl(${hue},60%,45%)">${initial}</div>
      <div class="svc-info" data-comp="${comp}">
        <div class="svc-app">${label}</div>
        <div class="svc-pkg">${pkg !== label ? pkg : ''}</div>
        <div class="svc-cmp">${svc || ''}</div>
      </div>
      <span class="svc-badge ${enabled ? 'on' : 'off'}" style="flex-shrink:0;cursor:pointer" onclick="event.stopPropagation()" title="${enabled ? '点击停用' : '点击启用'}">${enabled ? '已启用' : '已停用'}</span>
      <label class="switch" onclick="event.stopPropagation()">
        <input type="checkbox" data-comp="${comp}" ${wanted ? 'checked' : ''}>
        <span class="slider"></span>
      </label>
    </div>`;
  }).join('');

  list.innerHTML = rows;

  // 图标懒加载
  const avatarEls = Array.from(list.querySelectorAll('[data-avatar]'));
  const onIcon = (el) => {
    const pkg = el.getAttribute('data-avatar');
    loadIcon(pkg).then(dataUrl => {
      if (!dataUrl) return;
      list.querySelectorAll(`[data-avatar="${pkg}"]:not(.loaded)`).forEach(v => {
        v.classList.add('loaded');
        v.innerHTML = `<img onerror="this.parentElement.classList.remove('loaded');this.remove()" src="${dataUrl}" style="width:100%;height:100%;border-radius:50%;object-fit:cover">`;
      });
    });
  };
  if ('IntersectionObserver' in window) {
    const io = new IntersectionObserver((entries) => {
      entries.forEach(en => {
        if (en.isIntersecting) { io.unobserve(en.target); onIcon(en.target); }
      });
    }, { rootMargin: '50px' });
    avatarEls.forEach(el => io.observe(el));
  } else {
    avatarEls.forEach(onIcon);
  }

  // 绑定 checkbox 变化
  list.querySelectorAll('input[type=checkbox]').forEach(cb => {
    cb.addEventListener('change', async () => {
      const comp = cb.dataset.comp;
      const willGuard = cb.checked;
      if (willGuard) { if (!state.wanted.includes(comp)) state.wanted.push(comp); }
      else state.wanted = state.wanted.filter(x => x !== comp);
      state.dirty = true;
      $('btnSave').disabled = false;

      // 顺带开关无障碍询问：
      // 勾选守护 → 若当前未启用，问是否顺带启用；取消守护 → 若当前启用，问是否顺带停用
      const currentlyOn = state.enabledSet.has(comp);
      if (willGuard && !currentlyOn) {
        const ok = await askConfirm('开启守护，是否顺带启用该无障碍服务？');
        if (ok) { await setAccessibility(comp, true); toast('已顺带启用无障碍'); }
      } else if (!willGuard && currentlyOn) {
        const ok = await askConfirm('取消守护，是否顺带停用该无障碍服务？');
        if (ok) { await setAccessibility(comp, false); toast('已顺带停用无障碍'); }
      }
      // 立即按最新状态重排 + 刷新徽标，不用等轮询
      livePatchBadges();
    });
  });

  // 点击卡片进入详情
  list.querySelectorAll('.svc-info').forEach(el => {
    el.addEventListener('click', () => showDetail(el.dataset.comp));
  });

  // 点击状态徽标直接切换无障碍启用/停用
  list.querySelectorAll('.svc-badge').forEach(b => {
    b.addEventListener('click', async () => {
      const comp = b.parentElement.dataset.comp;
      const on = !state.enabledSet.has(comp);
      await setAccessibility(comp, on);
      toast(on ? '已启用无障碍' : '已停用无障碍，若在守护列表将自动移出守护');
    });
  });
}

// 手写确认浮层（KernelSU WebView 的 window.confirm 不可靠，自建可靠）
function askConfirm(msg) {
  return new Promise(resolve => {
    const old = $('confirmOverlay');
    if (old) old.remove();
    const ov = document.createElement('div');
    ov.id = 'confirmOverlay';
    ov.className = 'confirm-overlay';
    ov.innerHTML = `
      <div class="confirm-card">
        <div class="confirm-msg">${msg}</div>
        <div class="confirm-actions">
          <button class="btn ghost" id="cfNo" type="button">取消</button>
          <button class="btn primary" id="cfYes" type="button">确定</button>
        </div>
      </div>`;
    const done = val => { ov.remove(); resolve(val); };
    ov.querySelector('#cfNo').addEventListener('click', () => done(false));
    ov.querySelector('#cfYes').addEventListener('click', () => done(true));
    ov.addEventListener('click', e => { if (e.target === ov) done(false); });
    document.body.appendChild(ov);
  });
}

// 直接启用/停用无障碍服务：读当前 enabled 全列表 → 增删目标 → 写回。
// 守护列表里的服务被停用时自动移出守护（accd 每 20s 会把守护列表内缺失的服务补回，
// 不移除会导致这边关了 accd 又开回）。
async function setAccessibility(comp, on) {
  const cur = normalizeList(await run('settings get secure enabled_accessibility_services 2>/dev/null'));
  let next;
  if (on) {
    next = cur.includes(comp) ? cur : [...cur, comp];
  } else {
    next = cur.filter(x => x !== comp);
  }
  if (on) {
    await run(`settings put secure enabled_accessibility_services '${next.join(':')}' 2>/dev/null`);
    await run(`settings put secure accessibility_enabled 1 2>/dev/null`);
  } else {
    if (next.length) {
      await run(`settings put secure enabled_accessibility_services '${next.join(':')}' 2>/dev/null`);
    } else {
      // 清空整个列表（实测空串可写入，读回为空）
      await run(`settings put secure enabled_accessibility_services '' 2>/dev/null`);
      await run(`settings put secure accessibility_enabled 0 2>/dev/null`);
    }
    // 若该服务在守护列表中，同步移出，避免 accd 补回
    if (state.wanted.includes(comp)) {
      state.wanted = state.wanted.filter(x => x !== comp);
      state.dirty = true;
      $('btnSave').disabled = false;
    }
  }
  // 更新本页状态，立即生效无需等轮询
  state.enabledSet = new Set(normalizeList(next));
  if (state.current === comp && $('detailView') && !$('detailView').classList.contains('hidden')) {
    refreshDetailRow();
  } else if ($('mainView') && !$('mainView').classList.contains('hidden')) {
    livePatchBadges();
  }
  return next;
}

function showDetail(comp) {
  state.current = comp;
  const [pkg, svc] = comp.split('/');
  const enabled = state.enabledSet.has(comp);
  const wanted = state.wanted.includes(comp);

  $('mainView').classList.add('hidden');
  $('detailView').classList.remove('hidden');

  const hue = hueFor(pkg);
  const initial = initialChar(pkg);
  const label = appLabel(pkg);

  $('detailContent').innerHTML = `
    <div class="detail-head">
      <div class="svc-avatar" data-avatar="${pkg}" style="background:hsl(${hue},60%,45%)">${initial}</div>
      <div>
        <h2>${label}</h2>
        <div class="detail-pkg">${pkg}</div>
      </div>
    </div>
    <div class="detail-card">
      <div class="detail-row"><span class="k">服务</span><span class="v">${svc || '（默认服务）'}</span></div>
      <div class="detail-row"><span class="k">完整组件</span><span class="v">${comp}</span></div>
      <div class="detail-row"><span class="k">当前状态</span><span class="v">${enabled ? '已启用' : '已停用'}</span></div>
      <div class="detail-row"><span class="k">守护中</span><span class="v">${wanted ? '是' : '否'}</span></div>
    </div>
    <div class="actions" style="margin-top:12px;flex-direction:column">
      <button id="btnAcc" class="btn ${enabled ? 'danger' : 'primary'}">${enabled ? '停用无障碍功能' : '启用无障碍功能'}</button>
      <div class="actions">
        <button id="btnToggle" class="btn ${wanted ? 'danger' : 'primary'}">${wanted ? '取消守护该服务' : '加入守护'}</button>
        <button id="btnSaveHome" class="btn primary">保存并返回主页</button>
      </div>
    </div>`;

  loadIcon(pkg).then(dataUrl => {
    if (!dataUrl) return;
    $('detailContent').querySelectorAll(`[data-avatar="${pkg}"]`).forEach(el => {
      el.innerHTML = `<img src="${dataUrl}" style="width:100%;height:100%;border-radius:50%;object-fit:cover">`;
    });
  });

  $('btnAcc').addEventListener('click', async () => {
    const next = await setAccessibility(comp, !enabled);
    toast(enabled ? '已停用无障碍，若在守护列表将自动移出守护' : '已启用无障碍');
    showDetail(comp);
  });

  $('btnToggle').addEventListener('click', () => {
    if (state.wanted.includes(comp)) {
      state.wanted = state.wanted.filter(x => x !== comp);
    } else {
      state.wanted.push(comp);
    }
    state.dirty = true;
    $('btnSave').disabled = false;
    showDetail(comp);
    toast('已更新，请保存');
  });

  $('btnSaveHome').addEventListener('click', async () => {
    await save();
    goHome();
    toast('已保存并返回主页');
  });
}

function goHome() {
  $('detailView').classList.add('hidden');
  $('mainView').classList.remove('hidden');
  if (state.logTimer) { clearInterval(state.logTimer); state.logTimer = null; }
  state.current = null;
  if (state.dirty) { render(); }
}

async function save() {
  const items = state.wanted.map(x => normalizeList(x)[0]).filter(x => x && x.length);
  const val = items.join(':');
  await run(`KSU_MODULE=${MOD_ID} /data/adb/ksu/bin/ksud module config set enabled_services '${val}' 2>/dev/null`);
  $('btnSave').disabled = true;
  state.dirty = false;
  toast('已保存');
  // 保存后立刻刷新一次，让状态即时跟上（accd.sh 会在下个周期按新配置同步）
  await pollLight();
}

async function showLogs() {
  $('detailView').classList.remove('hidden');
  $('mainView').classList.add('hidden');
  $('detailContent').innerHTML = `
    <div class="detail-card">
      <h2>守护日志</h2>
      <div id="logs">${await getLogTail() || '（暂无日志）'}</div>
    </div>`;
  // 日志自动刷新
  if (state.logTimer) clearInterval(state.logTimer);
  state.logTimer = setInterval(async () => {
    const box = $('logs');
    if (!box) return;
    const txt = await getLogTail();
    if (box.innerHTML !== txt) box.innerHTML = txt;
  }, 5000);
}

// 取日志尾部 50 行并倒序：新日志在前（accd 往文件末尾追加，tac 反转后取前 50）
async function getLogTail() {
  return run('tac /data/adb/modules/acckeyguard/data/accd.log 2>/dev/null | head -50');
}

$('btnRefresh').addEventListener('click', async () => {
  $('btnSave').disabled = true;
  await load();
  toast('已重新扫描');
});

$('btnSave').addEventListener('click', save);

$('btnBack').addEventListener('click', goHome);

$('btnLogs').addEventListener('click', showLogs);

const searchInput = $('searchInput');
const searchClear = $('searchClear');
function updateSearchClear() {
  if (searchClear) searchClear.classList.toggle('show', !!(searchInput && searchInput.value));
}
if (searchInput) {
  searchInput.addEventListener('input', () => {
    state.query = searchInput.value;
    updateSearchClear();
    if ($('mainView') && !$('mainView').classList.contains('hidden')) {
      render();
    }
  });
}
if (searchClear) {
  searchClear.addEventListener('click', () => {
    if (!searchInput) return;
    searchInput.value = '';
    state.query = '';
    updateSearchClear();
    if ($('mainView') && !$('mainView').classList.contains('hidden')) {
      render();
    }
    searchInput.focus();
  });
}

bindTheme();
load();
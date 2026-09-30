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
  const services = b(parts[0] || '').split('\n').map(s => s.trim()).filter(Boolean);
  const enabled = b(parts[1] || '');
  const cfg = b(parts[2] || '');
  return { services, enabled, cfg };
}

function normalizeList(str) {
  return String(str || '').split(':').map(s => s.trim()).filter(Boolean);
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

    if (cfgStr.trim() !== '' || !state.wantedLoaded) {
      const newWanted = normalizeList(cfgStr);
      const cfgChanged = JSON.stringify(newWanted) !== JSON.stringify(state.wanted);
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
    if (enabledChanged || cfgChanged) {
      state.enabledSet = newEnabled;
      if ($('mainView') && !$('mainView').classList.contains('hidden')) {
        livePatchBadges();
      }
      if (state.current) {
        refreshDetailRow();
      }
    } else {
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

// 只更新列表里已有的徽标 + checkbox 状态，不重建 DOM（轮询时保持滚动位置与交互）
function livePatchBadges() {
  document.querySelectorAll('#serviceList .svc').forEach(row => {
    const comp = row.getAttribute('data-comp');
    if (!comp) return;
    const enabled = state.enabledSet.has(comp);
    const wanted = state.wanted.includes(comp);
    const badge = row.querySelector('.svc-badge');
    if (badge) { badge.textContent = enabled ? '已启用' : '已停用'; badge.className = 'svc-badge ' + (enabled ? 'on' : 'off'); }
    const cb = row.querySelector('input[type=checkbox][data-comp]');
    if (cb && cb.checked !== wanted) cb.checked = wanted;
  });
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

function render() {
  const list = $('serviceList');
  if (!state.all.length) { list.innerHTML = '<div class="loading">未扫描到无障碍服务</div>'; return; }

  const wantedSet = new Set(state.wanted);
  const rows = state.all.map(comp => {
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
      <span class="svc-badge ${enabled ? 'on' : 'off'}" style="flex-shrink:0">${enabled ? '已启用' : '已停用'}</span>
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
        v.innerHTML = `<img src="${dataUrl}" style="width:100%;height:100%;border-radius:50%;object-fit:cover">`;
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
    cb.addEventListener('change', () => {
      const comp = cb.dataset.comp;
      if (cb.checked) { if (!state.wanted.includes(comp)) state.wanted.push(comp); }
      else state.wanted = state.wanted.filter(x => x !== comp);
      state.dirty = true;
      $('btnSave').disabled = false;
    });
  });

  // 点击卡片进入详情
  list.querySelectorAll('.svc-info').forEach(el => {
    el.addEventListener('click', () => showDetail(el.dataset.comp));
  });
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
    <div class="actions" style="margin-top:12px">
      <button id="btnToggle" class="btn ${wanted ? 'danger' : 'primary'}">${wanted ? '取消守护该服务' : '加入守护'}</button>
    </div>`;

  loadIcon(pkg).then(dataUrl => {
    if (!dataUrl) return;
    $('detailContent').querySelectorAll(`[data-avatar="${pkg}"]`).forEach(el => {
      el.innerHTML = `<img src="${dataUrl}" style="width:100%;height:100%;border-radius:50%;object-fit:cover">`;
    });
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
    toast('已更新，请返回并保存');
  });
}

async function save() {
  const items = state.wanted.filter(x => x.length);
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
      <div id="logs">${await run('cat /data/adb/modules/acckeyguard/data/accd.log 2>/dev/null | tail -50') || '（暂无日志）'}</div>
    </div>`;
  // 日志自动刷新
  if (state.logTimer) clearInterval(state.logTimer);
  state.logTimer = setInterval(async () => {
    const box = $('logs');
    if (!box) return;
    const txt = await run('cat /data/adb/modules/acckeyguard/data/accd.log 2>/dev/null | tail -50');
    if (box.innerHTML !== txt) box.innerHTML = txt;
  }, 5000);
}

$('btnRefresh').addEventListener('click', async () => {
  $('btnSave').disabled = true;
  await load();
  toast('已重新扫描');
});

$('btnSave').addEventListener('click', save);

$('btnBack').addEventListener('click', () => {
  $('detailView').classList.add('hidden');
  $('mainView').classList.remove('hidden');
  if (state.logTimer) { clearInterval(state.logTimer); state.logTimer = null; }
  if (state.dirty) { render(); }
});

$('btnLogs').addEventListener('click', showLogs);

bindTheme();
load();
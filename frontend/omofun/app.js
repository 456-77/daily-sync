// OmoFun Archiver 前端逻辑（Tauri withGlobalTauri）
// invoke 由 api-adapter.js 提供（Tauri / HTTP 双端）

const STATUS_NAMES = ['未知', '在看', '看完', '暂停'];
const JOB_NAMES = { poll: '存档', keepalive: '保活', restore: '恢复' };
const state = {
  tab: 'overview',
  entries: [],
  filter: 'all',
  search: '',
  selected: new Set(),
  job: { running: false, name: '', lastResult: null, logs: [] },
  config: null,
  dirty: false,
};

const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => Array.from(document.querySelectorAll(sel));

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}
function fmtDur(s) {
  s = Math.max(0, Math.trunc(s || 0));
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), x = s % 60;
  const p = (v) => String(v).padStart(2, '0');
  return h > 0 ? `${h}:${p(m)}:${p(x)}` : `${m}:${p(x)}`;
}
function pad2(v) {
  return String(v).padStart(2, '0');
}
// 后端存的是固定格式 UTC（...Z），页面必须转成本地时区再显示：
// 直接 slice(0,10) 会把傍晚 20:25 的操显示成 12:25（UTC）。
function parseIsoDate(iso) {
  if (!iso) return null;
  const d = new Date(iso);
  return isNaN(d.getTime()) ? null : d;
}
function localDate(iso) {
  const d = parseIsoDate(iso);
  if (!d) return '';
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
}
function fmtDay(iso) {
  return localDate(iso) || '—';
}
function fmtDateTime(iso) {
  const d = parseIsoDate(iso);
  if (!d) return '—';
  return `${localDate(iso)} ${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}`;
}
function categoryOf(e) {
  if (e.serverGoneAt || !e.lastServerSeenAt) return 'archived';
  if (e.status === 2) return 'done';
  if (e.status === 3) return 'paused';
  return 'watching';
}
function titleOf(e) {
  return e.title || e.key;
}
function badge(text, cls) {
  return `<span class="badge ${cls || ''}">${esc(text)}</span>`;
}
function renewBadge(e) {
  if (e.originallyWatchedAt && e.serverUpdatedAt
      && localDate(e.originallyWatchedAt) !== localDate(e.serverUpdatedAt)) {
    return ' <span class="renewed">已续期</span>';
  }
  return '';
}
function coverHtml(e, cls) {
  const initial = esc((titleOf(e).trim()[0] || '?'));
  const img = e.coverUrl
    ? `<img src="${esc(e.coverUrl)}" loading="lazy" referrerpolicy="no-referrer" onerror="this.remove()">`
    : '';
  return `<div class="cover ${cls || ''}">${img}${initial}</div>`;
}

// ---------- 报告 ----------

// 不依赖 window.open：弹窗拦截与内置浏览器会直接返回 null。
// 先同步打开本站的全屏遮罩，再把带 JWT 取回的完整 HTML 写进 iframe。
async function showReport() {
  const overlay = $('#reportOverlay');
  const frame = $('#reportFrame');
  overlay.hidden = false;
  frame.srcdoc = '<!DOCTYPE html><html><body style="font-family:sans-serif;padding:24px;color:#333">正在生成报告…</body></html>';
  try {
    frame.srcdoc = await invoke('get_report');
  } catch (e) {
    overlay.hidden = true;
    alert(e);
  }
}

function closeReport() {
  const overlay = $('#reportOverlay');
  if (!overlay) return;
  overlay.hidden = true;
  const frame = $('#reportFrame');
  if (frame) frame.srcdoc = '';
}

// ---------- 数据加载 ----------

async function loadOverview() {
  const ov = await invoke('get_overview');
  $('#uid').textContent = ov.accountUid ? `账号 UID ${ov.accountUid}` : '';
  $('#statChips').innerHTML = [
    [ov.total, '存档总数', '#e8eaf0'],
    [ov.watching, '在看', '#7c8cff'],
    [ov.done, '看完', '#5ad1a0'],
    [ov.paused, '暂停', '#f2b355'],
    [ov.archived, '仅存档', '#ff6b6b'],
  ].map(([v, l, c]) => `<div class="chip"><b style="color:${c}">${v}</b><span>${l}</span></div>`).join('');

  $('#recentStrip').innerHTML = (ov.recent || []).map((e) => `
    <div class="mini-card" title="${esc(titleOf(e))}">
      ${coverHtml(e)}
      <div class="t">${esc(titleOf(e))}</div>
      <div class="e">第${e.lastEpisodeIndex ?? '—'}集</div>
    </div>`).join('') || '<span class="muted">（暂无 —— 点"立即存档"拉取一次）</span>';

  $('#runsTable tbody').innerHTML = (ov.runs || []).map((r) => `
    <tr class="${r.ok ? 'ok' : 'fail'}">
      <td>${fmtDateTime(r.startedAt)}</td>
      <td>${r.ok ? '成功' : '失败'}</td>
      <td>${r.serverEntries ?? 0}</td>
      <td>${r.newEntries ?? 0}</td>
      <td>${esc(r.error || '')}</td>
    </tr>`).join('');
}

async function loadEntries() {
  state.entries = await invoke('get_entries');
  renderEntries();
}

function renderEntries() {
  const q = state.search.trim().toLowerCase();
  const list = state.entries.filter((e) => {
    const cat = categoryOf(e);
    return (state.filter === 'all' || state.filter === cat)
      && (!q || titleOf(e).toLowerCase().includes(q) || (e.contentKey || '').toLowerCase().includes(q));
  });
  $('#entryCount').textContent = `${list.length} 部`;
  $('#entryGrid').innerHTML = list.map((e) => {
    const pct = e.totalDurationSeconds > 0
      ? Math.min(100, Math.round((e.progressSeconds / e.totalDurationSeconds) * 1000) / 10)
      : (e.status === 2 ? 100 : 0);
    const ep = e.lastEpisodeIndex != null
      ? `第 ${e.lastEpisodeIndex} 集${e.episodeTitle ? ' · ' + esc(e.episodeTitle) : ''}`
      : (e.episodeTitle ? esc(e.episodeTitle) : '—');
    const badges = [badge(STATUS_NAMES[e.status ?? 0] || '未知',
      e.status === 2 ? 'ok' : e.status === 3 ? 'warn' : 'acc')];
    const line = e.lastLineId || e.playerName;
    if (line) badges.push(badge(line, 'dim'));
    if (e.serverGoneAt) badges.push(badge(`服务端已重置 ${fmtDay(e.serverGoneAt)}`, 'gone'));
    else if (!e.lastServerSeenAt) badges.push(badge('仅本地存档', 'gone'));
    return `<div class="card">
      ${coverHtml(e)}
      <div class="meta">
        <div class="title" title="${esc(titleOf(e))}">${esc(titleOf(e))}</div>
        <div class="ep">${ep}</div>
        <div class="bar"><div style="width:${pct}%"></div></div>
        <div class="num"><span>${fmtDur(e.progressSeconds)} / ${e.totalDurationSeconds ? fmtDur(e.totalDurationSeconds) : '—'}</span><span>${pct}%</span></div>
        <div class="num"><span>最后观看：${fmtDay(e.originallyWatchedAt || e.serverUpdatedAt)}${renewBadge(e)}</span></div>
        <div class="badges">${badges.join('')}</div>
      </div>
    </div>`;
  }).join('');
}

async function loadCandidates() {
  const list = await invoke('get_restore_candidates');
  $('#candidateCount').textContent = `共 ${list.length} 条`;
  $('#candidateList').innerHTML = list.map((e) => {
    const status = STATUS_NAMES[e.status ?? 0] || '未知';
    const gone = e.serverGoneAt ? `重置于 ${fmtDay(e.serverGoneAt)}` : '仅本地';
    return `<label>
      <input type="checkbox" data-key="${esc(e.key)}" ${state.selected.has(e.key) ? 'checked' : ''}>
      <span>${esc(titleOf(e))}</span>
      <span class="dim">第${e.lastEpisodeIndex ?? '—'}集 · ${fmtDur(e.progressSeconds)}/${fmtDur(e.totalDurationSeconds)} · ${status} · ${gone}</span>
    </label>`;
  }).join('') || '<span class="muted">（没有可恢复条目 —— 一切都还在服务端，很好！）</span>';
  $$('#candidateList input').forEach((cb) => {
    cb.addEventListener('change', () => {
      if (cb.checked) state.selected.add(cb.dataset.key);
      else state.selected.delete(cb.dataset.key);
      updateRestoreBtn();
    });
  });
  updateRestoreBtn();
}

function updateRestoreBtn() {
  const btn = $('#btnRestore');
  btn.disabled = state.job.running || state.selected.size === 0;
  btn.textContent = `♻ 恢复选中 ${state.selected.size} 条`;
}

async function loadTasks() {
  if (IS_WEB) {
    // 服务器版：显示内置调度器状态
    const sc = await invoke('get_scheduler');
    $('#taskList').innerHTML =
      '<div>存档调度：' + (sc.enabled ? '<span class=\"ok\">✔ 启用</span>' : '<span class=\"err\">✘ 停用</span>') + ' 每天 ' + esc(sc.pollTime) + '（上次：' + esc(sc.lastPollDate || '—') + '）</div>' +
      '<div>保活调度：每 ' + sc.keepaliveEveryDays + ' 天 ' + esc(sc.keepaliveTime) + '（上次：' + esc(sc.lastKeepaliveDate || '—') + '）</div>';
    $('#btnRegTasks').hidden = true;
    $('#btnUnregTasks').hidden = true;
    return;
  }
  const list = await invoke('tasks_status');
  $('#taskList').innerHTML = list.map((t) =>
    `<div>${t.registered ? '<span class="ok">✔</span>' : '<span class="muted">○</span>'} ${esc(t.name)}</div>`).join('');
}

async function loadConfig() {
  state.config = await invoke('get_config');
  $('#cfgDataDir').value = state.config.dataDir || 'data';
  $('#cfgCertPath').value = state.config.rootCertPath || '（部署环境提供）';
  if (IS_WEB) {
    // 路径/证书属于服务器部署环境（env），网页端只读；保存按钮只对桌面版有意义
    $('#cfgDataDir').disabled = true;
    $('#cfgCertPath').disabled = true;
    $('#btnSaveCfg').hidden = true;
    $('#cfgMsg').textContent = '路径与证书由服务器部署环境提供（env）';
  }
  // token 状态
  try {
    const ov = await invoke('get_overview');
    $('#uidSetting').textContent = ov.accountUid ?? '—';
    $('#tokenStatus').innerHTML = ov.accountUid
      ? '<span class="ok">✔ 已找到（OmoFun 客户端登录态）</span>'
      : '<span class="err">✘ 未找到/无法解析 —— 请确认服务器已挂载 auth-tokens.json</span>';
  } catch {
    $('#tokenStatus').innerHTML = '<span class="err">✘ 未找到 —— 请先登录一次 OmoFun 桌面客户端</span>';
  }
  // 保活设置回填
  const ka = state.config.keepalive || {};
  $$('input[name=scope]').forEach((r) => { r.checked = r.value === (ka.scope || 'watching'); });
  $('#minIdleLabel').textContent = ka.minIdleDays ?? 5;
  $('#maxWritesLabel').textContent = ka.maxWrites ?? 100;
  // 调度设置回填（服务器内置调度）
  const sc = state.config.schedule || {};
  $('#cfgScheduleEnabled').checked = sc.enabled !== false;
  $('#cfgPollTime').value = sc.pollTime || '09:00';
  $('#cfgKeepaliveEveryDays').value = sc.keepaliveEveryDays ?? 7;
  $('#cfgKeepaliveTime').value = sc.keepaliveTime || '09:20';
}

// ---------- 任务轮询 ----------

async function pollJob() {
  try {
    const job = await invoke('get_job');
    state.job = job;
    const changed = (job.running !== state._prevRunning) || (job.lastResult !== state._prevResult);
    $('#jobIndicator').textContent = job.running ? `⏳ ${JOB_NAMES[job.name] || job.name}中…` : '';
    const banner = $('#jobBanner');
    if (job.lastResult && (!job.running)) {
      banner.hidden = false;
      banner.textContent = job.lastResult;
      banner.style.color = job.lastResult.startsWith('失败') ? 'var(--gone)' : '#96dcb4';
    } else if (job.running) {
      banner.hidden = false;
      banner.textContent = `正在${JOB_NAMES[job.name] || job.name}…（详见日志）`;
      banner.style.color = 'var(--accent)';
    } else if (changed) {
      banner.hidden = true;
    }
    state._prevRunning = job.running;
    state._prevResult = job.lastResult;

    const logText = (job.logs || []).join('\n') || '（暂无）';
    for (const id of ['#logPanel', '#logPanel2']) {
      const el = $(id);
      if (el && el.textContent !== logText) el.textContent = logText;
    }

    // 按钮状态
    for (const id of ['#btnPoll', '#btnKeepalive', '#btnKeepalive2', '#btnRestore']) {
      const b = $(id);
      if (b) b.disabled = job.running || (id === '#btnRestore' && state.selected.size === 0);
    }

    if (changed && !job.running) {
      // 任务刚结束：刷新数据
      await loadOverview().catch(() => {});
      if (state.tab === 'browse') await loadEntries().catch(() => {});
      if (state.tab === 'actions') await loadCandidates().catch(() => {});
    }
  } catch { /* 窗口关闭瞬间可能报错，忽略 */ }
}

// ---------- 交互绑定 ----------

function switchTab(name) {
  state.tab = name;
  $$('.tab').forEach((t) => t.classList.toggle('active', t.dataset.tab === name));
  $$('main > section').forEach((s) => { s.hidden = s.id !== `tab-${name}`; });
  if (name === 'overview') loadOverview().catch(() => {});
  if (name === 'browse') loadEntries().catch(() => {});
  if (name === 'actions') { loadCandidates().catch(() => {}); loadConfig().catch(() => {}); }
  if (name === 'settings') { loadConfig().catch(() => {}); loadTasks().catch(() => {}); }
}

async function runJob(kind, keys = []) {
  const banner = $('#jobBanner');
  banner.hidden = false;
  banner.style.color = 'var(--accent)';
  banner.textContent = `正在${JOB_NAMES[kind] || kind}…`;
  try {
    await invoke('start_job', { kind, keys });
  } catch (e) {
    banner.textContent = `启动失败：${e.message || e}`;
    banner.style.color = 'var(--gone)';
  }
}

function bind() {
  $$('.tab').forEach((t) => t.addEventListener('click', () => switchTab(t.dataset.tab)));
  $('#search').addEventListener('input', (e) => { state.search = e.target.value; renderEntries(); });
  $$('.filter').forEach((b) => b.addEventListener('click', () => {
    state.filter = b.dataset.f;
    $$('.filter').forEach((x) => x.classList.toggle('active', x === b));
    renderEntries();
  }));

  $('#btnPoll').addEventListener('click', () => runJob('poll'));
  $('#btnKeepalive').addEventListener('click', () => runJob('keepalive'));
  $('#btnKeepalive2').addEventListener('click', () => runJob('keepalive'));
  $('#btnReport').addEventListener('click', async () => {
    if (IS_WEB) {
      await showReport();
    } else {
      invoke('open_report').catch((e) => alert(e));
    }
  });
  $('#reportClose').addEventListener('click', closeReport);
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && !$('#reportOverlay').hidden) closeReport();
  });
  $('#btnRefresh').addEventListener('click', async () => {
    const btn = $('#btnRefresh');
    const original = btn.textContent;
    btn.disabled = true;
    btn.textContent = '⏳ 刷新中…';
    try { await loadOverview(); } catch (e) { /* 保持原页面，错误由总览加载处提示 */ }
    btn.disabled = false;
    btn.textContent = original;
  });

  $('#btnRestore').addEventListener('click', () => runJob('restore', Array.from(state.selected)));
  $('#btnSelectAll').addEventListener('click', async () => {
    const list = await invoke('get_restore_candidates');
    list.forEach((e) => state.selected.add(e.key));
    loadCandidates();
  });
  $('#btnClearSel').addEventListener('click', () => { state.selected.clear(); loadCandidates(); });

  // 保活设置
  $$('input[name=scope]').forEach((r) => r.addEventListener('change', () => {
    state.config.keepalive.scope = r.value;
    saveKeepalive();
  }));
  $('#minIdleMinus').addEventListener('click', () => {
    state.config.keepalive.minIdleDays = Math.max(1, (state.config.keepalive.minIdleDays ?? 5) - 1);
    $('#minIdleLabel').textContent = state.config.keepalive.minIdleDays;
    saveKeepalive();
  });
  $('#minIdlePlus').addEventListener('click', () => {
    state.config.keepalive.minIdleDays = Math.min(13, (state.config.keepalive.minIdleDays ?? 5) + 1);
    $('#minIdleLabel').textContent = state.config.keepalive.minIdleDays;
    saveKeepalive();
  });

  async function saveKeepalive() {
    try {
      await invoke('save_config', { config: state.config });
      $('#cfgMsg').textContent = '保活设置已保存';
      setTimeout(() => { $('#cfgMsg').textContent = ''; }, 2000);
    } catch (e) { alert(e); }
  }

  // 服务器内置调度：修改后保存到 omofun_settings，下一轮 tick 即按新时间判断
  $('#btnSaveSchedule').addEventListener('click', async () => {
    if (!state.config) {
      $('#scheduleMsg').textContent = '配置尚未加载';
      return;
    }
    const pollTime = $('#cfgPollTime').value;
    const keepaliveTime = $('#cfgKeepaliveTime').value;
    const every = parseInt($('#cfgKeepaliveEveryDays').value, 10);
    const hhmm = /^([01]\d|2[0-3]):[0-5]\d$/;
    if (!hhmm.test(pollTime) || !hhmm.test(keepaliveTime)) {
      $('#scheduleMsg').textContent = '时间格式必须是 HH:MM';
      return;
    }
    if (!Number.isInteger(every) || every < 1 || every > 365) {
      $('#scheduleMsg').textContent = '保活周期需在 1~365 天之间';
      return;
    }
    state.config.schedule = {
      enabled: $('#cfgScheduleEnabled').checked,
      pollTime,
      keepaliveEveryDays: every,
      keepaliveTime,
    };
    $('#scheduleMsg').textContent = '保存中…';
    try {
      await invoke('save_config', { config: state.config });
      $('#scheduleMsg').textContent = '已保存，下一轮调度生效';
      await loadTasks();
    } catch (e) {
      $('#scheduleMsg').textContent = `保存失败：${e.message || e}`;
    }
  });

  $('#btnSaveCfg').addEventListener('click', async () => {
    state.config.dataDir = $('#cfgDataDir').value.trim() || 'data';
    const cert = $('#cfgCertPath').value.trim();
    state.config.rootCertPath = cert.startsWith('G:') || cert.startsWith('C:') ? cert : '';
    try {
      await invoke('save_config', { config: state.config });
      $('#cfgMsg').textContent = '已保存，重启后完全生效。';
    } catch (e) { alert(e); }
  });
  $('#cfgDataDir').addEventListener('input', () => { $('#btnSaveCfg').disabled = false; });
  $('#cfgCertPath').addEventListener('input', () => { $('#btnSaveCfg').disabled = false; });

  $('#btnRegTasks').addEventListener('click', async () => {
    $('#btnRegTasks').disabled = true;
    try {
      const msg = await invoke('register_tasks');
      $('#cfgMsg').textContent = msg;
      loadTasks();
    } catch (e) { $('#cfgMsg').textContent = `注册失败：${e}`; }
    $('#btnRegTasks').disabled = false;
  });
  $('#btnUnregTasks').addEventListener('click', async () => {
    try {
      const msg = await invoke('unregister_tasks');
      $('#cfgMsg').textContent = msg;
      loadTasks();
    } catch (e) { $('#cfgMsg').textContent = `卸载失败：${e}`; }
  });

  const logoutBtn = $('#btnLogout');
  if (logoutBtn) {
    logoutBtn.hidden = !IS_WEB;
    logoutBtn.addEventListener('click', () => {
      if (window.omofunLogout) window.omofunLogout();
      location.reload();
    });
  }

  const migrateBtn = $('#btnMigrateLegacy');
  if (migrateBtn) {
    // 桌面版没有这个接口（旧 SQLite 就在本地）；服务器版用于把旧 Rust 存档导进 MySQL
    migrateBtn.hidden = !IS_WEB;
    migrateBtn.addEventListener('click', async () => {
      if (!confirm('从服务器 data/omofun/omofun_history.db 导入旧存档？\nentries 只补缺；流水与运行记录仅在空表时导入。')) {
        return;
      }
      migrateBtn.disabled = true;
      try {
        const r = await invoke('migrate_legacy');
        $('#cfgMsg').textContent = `迁移完成：条目 ${r.entries}，流水 ${r.progressLog}，运行记录 ${r.runs}`;
      } catch (e) {
        $('#cfgMsg').textContent = `迁移失败：${e.message || e}`;
      }
      migrateBtn.disabled = false;
    });
  }
}

// ---------- 启动 ----------

(async function init() {
  bind();
  if (IS_WEB) {
    // 登录遮罩：任何 401 触发显示
    setUnauthorizedHandler(() => {
      document.getElementById('loginOverlay').hidden = false;
    });
    // 启动即探活：一个需要登录的轻量调用
    if (!window.omofunAuth || !window.omofunAuth()) {
      document.getElementById('loginOverlay').hidden = false;
    }
    try { await invoke('get_config'); }
    catch { /* 401 已由 handler 弹遮罩 */ }

    document.getElementById('loginBtn').addEventListener('click', async () => {
      const btn = document.getElementById('loginBtn');
      const msg = document.getElementById('loginMsg');
      btn.disabled = true;
      msg.textContent = '';
      try {
        const username = document.getElementById('loginUsername').value.trim();
        const password = document.getElementById('loginPassword').value;
        if (!username) {
          msg.textContent = '请输入用户名';
        } else {
          await window.omofunLogin(username, password);
          document.getElementById('loginOverlay').hidden = true;
          location.reload();
        }
      } catch (e) { msg.textContent = String(e.message || e); }
      btn.disabled = false;
    });
    ['loginUsername', 'loginPassword'].forEach((id) => {
      document.getElementById(id).addEventListener('keydown', (e) => {
        if (e.key === 'Enter') document.getElementById('loginBtn').click();
      });
    });
  }
  await loadOverview().catch((e) => { $('#jobBanner').hidden = false; $('#jobBanner').textContent = `加载失败：${e}`; });
  await loadConfig().catch(() => {});
  await loadCandidates().catch(() => {});
  setInterval(pollJob, 600);
  pollJob();
})();

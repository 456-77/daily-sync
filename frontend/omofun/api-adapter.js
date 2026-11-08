// OmoFun 存档页（daily-sync 版）的 API 适配层。
//
// 与旧 Rust 网页版 api-adapter.js 的差别：
//   1. 接口从 /api/<cmd> 改为 /api/v1/omofun/*（daily-sync 的 REST + ApiResponse 信封）；
//   2. 鉴权复用 daily-sync 的 JWT，令牌存在 localStorage["daily-sync-auth"]——与 /app
//      网页端同一份登录态，打开 /omofun/ 不需要重新登录；
//   3. 401 时先用 refreshToken 换新（一次性轮换），失败才弹登录遮罩。
//
// app.js 只依赖全局 invoke / IS_WEB，不感知这些差异。

const IS_WEB = !window.__TAURI__;
const API_BASE = '/api/v1/omofun';
const AUTH_KEY = 'daily-sync-auth';

let _onUnauthorized = null;

function setUnauthorizedHandler(fn) {
  _onUnauthorized = fn;
}

function loadAuth() {
  try {
    const parsed = JSON.parse(localStorage.getItem(AUTH_KEY) || 'null');
    if (parsed && typeof parsed.accessToken === 'string') return parsed;
  } catch (e) {
    // 损坏的存储当未登录处理
  }
  return null;
}

function setAuth(next) {
  if (next) localStorage.setItem(AUTH_KEY, JSON.stringify(next));
  else localStorage.removeItem(AUTH_KEY);
}

async function tryRefresh() {
  const auth = loadAuth();
  if (!auth || !auth.refreshToken) return false;
  try {
    const res = await fetch('/api/v1/auth/refresh', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: auth.refreshToken }),
    });
    const json = await res.json().catch(() => null);
    if (!res.ok || !json || json.code !== 0) return false;
    setAuth({
      accessToken: json.data.accessToken,
      refreshToken: json.data.refreshToken,
      username: auth.username,
    });
    return true;
  } catch (e) {
    return false;
  }
}

async function authedFetch(path, options = {}, allowRefresh = true) {
  const auth = loadAuth();
  const headers = Object.assign({}, options.headers || {});
  if (auth && auth.accessToken) headers.Authorization = 'Bearer ' + auth.accessToken;
  const res = await fetch(path, Object.assign({}, options, { headers }));

  if (res.status === 401 && allowRefresh && !path.startsWith('/api/v1/auth/')) {
    if (await tryRefresh()) return authedFetch(path, options, false);
    setAuth(null);
    if (_onUnauthorized) _onUnauthorized();
    throw new Error('未登录或登录已过期');
  }
  return res;
}

async function request(method, path, body) {
  const options = { method };
  if (body !== undefined) {
    options.headers = { 'Content-Type': 'application/json' };
    options.body = JSON.stringify(body);
  }
  const res = await authedFetch(API_BASE + path, options);
  const json = await res.json().catch(() => null);
  if (!res.ok || !json || json.code !== 0) {
    throw new Error((json && json.message) || ('HTTP ' + res.status));
  }
  return json.data;
}

/** report/export 是裸 HTML/JSON，不套 ApiResponse 信封。 */
async function requestText(path) {
  const res = await authedFetch(API_BASE + path);
  if (!res.ok) {
    let msg = await res.text();
    try {
      const parsed = JSON.parse(msg);
      msg = parsed.message || msg;
    } catch (e) {
      // 保留原文
    }
    throw new Error(msg || ('HTTP ' + res.status));
  }
  return res.text();
}

const COMMANDS = {
  get_overview: () => request('GET', '/overview'),
  get_entries: () => request('GET', '/entries'),
  get_restore_candidates: () => request('GET', '/restore-candidates'),
  get_job: () => request('GET', '/job'),
  get_config: () => request('GET', '/config'),
  get_scheduler: () => request('GET', '/scheduler'),
  start_job: (args) => request('POST', '/jobs', { kind: args.kind, keys: args.keys || [] }),
  save_config: (args) => request('PUT', '/config', args.config),
  probe: () => request('POST', '/probe'),
  migrate_legacy: () => request('POST', '/migrate-legacy'),
  get_report: () => requestText('/report'),
  get_export: () => requestText('/export'),
};

async function invoke(cmd, args = {}) {
  if (!IS_WEB) {
    return window.__TAURI__.core.invoke(cmd, args);
  }
  const handler = COMMANDS[cmd];
  if (!handler) throw new Error('未知命令: ' + cmd);
  return handler(args);
}

/** 用 daily-sync 账号密码登录（与 /app 网页端同一套 /api/v1/auth/login）。 */
async function omofunLogin(username, password) {
  const res = await fetch('/api/v1/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  });
  const json = await res.json().catch(() => null);
  if (!res.ok || !json || json.code !== 0) {
    throw new Error((json && json.message) || '登录失败');
  }
  setAuth({
    accessToken: json.data.accessToken,
    refreshToken: json.data.refreshToken,
    username,
  });
}

function omofunLogout() {
  setAuth(null);
}

window.invoke = invoke;
window.setUnauthorizedHandler = setUnauthorizedHandler;
window.omofunAuth = loadAuth;
window.omofunLogin = omofunLogin;
window.omofunLogout = omofunLogout;

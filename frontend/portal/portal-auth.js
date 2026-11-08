/* ============================================================================
   门户共享登录态（PortalAuth，vanilla + 零构建）

   为什么单独抽一份：门户主页、我的工具箱，以及后续 frontend/portal/ 下的
   工具页都需要「登录一次、全站复用」。登录态与 /app/ 网页端、/omofun/ 工具页
   共用同一个 localStorage 键，结构必须严格一致：

     localStorage["daily-sync-auth"] = {
       accessToken:  "...",   // 2 小时（JWT）
       refreshToken: "...",   // 30 天，一次性轮换
       username:     "..."
     }

   能力：
     getAuth() / loadAuth()  读当前登录态（损坏的存储按未登录）
     setAuth(next)           写/清登录态，广播 ds-auth-change / ds-logout
     setSession(data)        票据兑换等外部会话写入（保留 username）
     login(username, pass)   POST /api/v1/auth/login
     refresh()               POST /api/v1/auth/refresh（单页内并发合并）
     logout()                清本地登录态（后端没有登出接口，与 SPA 行为一致）
     apiFetch(path, opts)    自动带 Bearer；401 且非 /api/v1/auth/ 时刷新重试一次
     apiJson(path, opts)     同上，并解 ApiResponse 信封
     getMe()                 GET /api/v1/me，返回 role（ADMIN / USER）
     onChange(fn) / onLogout(fn)  订阅登录态变化

   注意：refresh token 是一次性轮换的，多标签同时刷新会有竞争；本模块只在
   单页内合并并发刷新。跨标签失败时用户重新登录即可（规范文档已写明）。
   ========================================================================== */
(function () {
  "use strict";

  var AUTH_KEY = "daily-sync-auth";
  var AUTH_EVENT = "ds-auth-change";
  var LOGOUT_EVENT = "ds-logout";

  /** 同一页面内的并发刷新合并：避免两个 401 同时消费同一个一次性 refresh token。 */
  var refreshPromise = null;

  function loadAuth() {
    try {
      var parsed = JSON.parse(localStorage.getItem(AUTH_KEY) || "null");
      if (parsed && typeof parsed.accessToken === "string") {
        return {
          accessToken: parsed.accessToken,
          refreshToken: typeof parsed.refreshToken === "string" ? parsed.refreshToken : "",
          username: typeof parsed.username === "string" ? parsed.username : ""
        };
      }
    } catch (e) {
      // 损坏的存储当未登录处理
    }
    return null;
  }

  function dispatchAuthChange(next) {
    try {
      window.dispatchEvent(new CustomEvent(AUTH_EVENT, { detail: next }));
    } catch (e) {
      // 极旧环境没有 CustomEvent 构造器时退回普通事件
      window.dispatchEvent(new Event(AUTH_EVENT));
    }
    if (!next) window.dispatchEvent(new Event(LOGOUT_EVENT));
  }

  function setAuth(next) {
    if (next) localStorage.setItem(AUTH_KEY, JSON.stringify(next));
    else localStorage.removeItem(AUTH_KEY);
    dispatchAuthChange(next);
    return next;
  }

  /** 外部会话写入（票据兑换）：data 形如 {accessToken, refreshToken, username}。 */
  function setSession(data) {
    if (!data || typeof data.accessToken !== "string") return null;
    var previous = loadAuth();
    return setAuth({
      accessToken: data.accessToken,
      refreshToken: typeof data.refreshToken === "string" ? data.refreshToken : "",
      username: typeof data.username === "string" && data.username
        ? data.username
        : (previous && previous.username) || ""
    });
  }

  function refresh() {
    if (refreshPromise) return refreshPromise;
    var auth = loadAuth();
    if (!auth || !auth.refreshToken) return Promise.resolve(false);

    refreshPromise = fetch("/api/v1/auth/refresh", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ refreshToken: auth.refreshToken })
    })
      .then(function (res) {
        return res.json().catch(function () { return null; });
      })
      .then(function (json) {
        if (!json || json.code !== 0 || !json.data
            || typeof json.data.accessToken !== "string") {
          return false;
        }
        setSession({
          accessToken: json.data.accessToken,
          refreshToken: json.data.refreshToken,
          username: auth.username
        });
        return true;
      })
      .catch(function () {
        return false;
      })
      .then(function (ok) {
        refreshPromise = null;
        return ok;
      }, function () {
        refreshPromise = null;
        return false;
      });
    return refreshPromise;
  }

  function apiFetch(path, options, allowRefresh) {
    var opts = options || {};
    var auth = loadAuth();
    var headers = {};
    if (opts.headers) {
      for (var key in opts.headers) {
        if (Object.prototype.hasOwnProperty.call(opts.headers, key)) {
          headers[key] = opts.headers[key];
        }
      }
    }
    if (auth && auth.accessToken) {
      headers.Authorization = "Bearer " + auth.accessToken;
    }
    var request = {};
    for (var field in opts) {
      if (Object.prototype.hasOwnProperty.call(opts, field)) request[field] = opts[field];
    }
    request.headers = headers;
    if (request.body !== undefined && request.body !== null
        && !headers["Content-Type"]) {
      headers["Content-Type"] = "application/json";
    }
    if (allowRefresh === undefined) allowRefresh = true;

    return fetch(path, request).then(function (res) {
      if (res.status === 401 && allowRefresh && auth
          && String(path).indexOf("/api/v1/auth/") !== 0) {
        return refresh().then(function (ok) {
          if (ok) return apiFetch(path, options, false);
          setAuth(null);
          var error = new Error("登录已过期，请重新登录");
          error.status = 401;
          throw error;
        });
      }
      return res;
    });
  }

  function apiJson(path, options) {
    return apiFetch(path, options).then(function (res) {
      return res.json().catch(function () { return null; }).then(function (json) {
        if (!res.ok || !json || json.code !== 0) {
          var message = json && json.message
            ? json.message
            : "请求失败（HTTP " + res.status + "）";
          var error = new Error(message);
          error.status = res.status;
          error.code = json && typeof json.code === "number" ? json.code : res.status;
          throw error;
        }
        return json.data;
      });
    });
  }

  function login(username, password) {
    return fetch("/api/v1/auth/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: username, password: password })
    })
      .then(function (res) {
        return res.json().catch(function () { return null; }).then(function (json) {
          if (!res.ok || !json || json.code !== 0 || !json.data) {
            var message = json && json.message
              ? json.message
              : "登录失败（HTTP " + res.status + "）";
            var error = new Error(message);
            error.status = res.status;
            throw error;
          }
          return setAuth({
            accessToken: json.data.accessToken,
            refreshToken: json.data.refreshToken,
            username: username
          });
        });
      })
      .catch(function (err) {
        if (err && err.status) throw err;
        throw new Error("网络错误，请稍后重试");
      });
  }

  function logout() {
    setAuth(null);
  }

  function getMe() {
    return apiJson("/api/v1/me");
  }

  function onChange(callback) {
    window.addEventListener(AUTH_EVENT, callback);
  }

  function onLogout(callback) {
    window.addEventListener(LOGOUT_EVENT, callback);
  }

  // 多标签页同步：别的标签登录/退出后，本页 UI 跟着更新。
  window.addEventListener("storage", function (event) {
    if (event.key !== AUTH_KEY && event.key !== null) return;
    dispatchAuthChange(loadAuth());
  });

  window.PortalAuth = {
    AUTH_KEY: AUTH_KEY,
    getAuth: loadAuth,
    loadAuth: loadAuth,
    setAuth: setAuth,
    setSession: setSession,
    login: login,
    refresh: refresh,
    logout: logout,
    apiFetch: apiFetch,
    apiJson: apiJson,
    getMe: getMe,
    onChange: onChange,
    onLogout: onLogout
  };
})();

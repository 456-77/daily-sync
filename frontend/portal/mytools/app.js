/* ============================================================================
   我的工具箱（Web 版仪表盘，/mytools/）

   工具清单在 tools.js 的 MY_TOOLS 里：**新增工具只改那个文件**，再 bump
   index.html 里 tools.js 的 ?v=；本文件只负责管理员门禁、渲染与状态探测。

   管理员门禁：所有工具清单在 PortalAuth.getMe() 返回 role=ADMIN 后才渲染。
   静态页面本身仍是公开可下载的 UI 壳，tools.js 不放密钥；真正敏感的数据
   必须放在 /api/v1/... 的 ADMIN 接口后面。

   与主页共用 localStorage["daily-sync-auth"]：在主页登录一次，进入本页
   不再输入账号密码；退出登录回主页表单。
   ========================================================================== */
(function () {
  "use strict";

  var HTTP_POLL_MS = 60000;
  var TOOLS = (window.MY_TOOLS || []).filter(function (tool) {
    return tool && tool.visible !== false;
  });
  var CATEGORY_ORDER = window.MY_TOOL_CATEGORY_ORDER || [];
  var page = { authorized: false, timer: null, checking: false, roleToken: null, category: "all" };
  /** 监控状态缓存：切换分类时重绘卡片不丢上一次探测结果。 */
  var monitorState = {};

  function escapeHtml(value) {
    return String(value).replace(/[&<>"']/g, function (c) {
      return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c];
    });
  }

  /** action/monitor 的 URL 白名单：只放行站内绝对路径与 http(s) 外链。 */
  function safeHref(href) {
    if (typeof href !== "string") return null;
    var value = href.trim();
    if (value.charAt(0) === "/" && value.charAt(1) !== "/") return value;
    if (/^https?:\/\//i.test(value)) return value;
    return null;
  }

  /** 工具 id 用于 monitor DOM id，非法字符替换掉，避免配置写错时 getElementById 失效。 */
  function idPart(value) {
    return String(value == null ? "" : value).replace(/[^A-Za-z0-9_-]/g, "-");
  }

  function fmtLatency(ms) {
    return ms < 1000 ? Math.round(ms) + " ms" : (ms / 1000).toFixed(1) + " s";
  }

  // ---------------------------------------------------------------- 数据源

  /** 同源/跨域 HTTP 探测：5s 超时，成功显示延迟。失败不外抛，落成红点+文案。 */
  function probeHttp(url) {
    var target = safeHref(url);
    if (!target) {
      return Promise.resolve({ dot: "bad", text: "配置无效", title: "monitor.url 只允许站内路径或 http(s) 地址" });
    }
    var started = performance.now();
    var controller = new AbortController();
    var timer = setTimeout(function () { controller.abort(); }, 5000);
    return fetch(target, { cache: "no-store", signal: controller.signal })
      .then(function (res) {
        clearTimeout(timer);
        if (res.ok) {
          return { dot: "ok", text: "正常 · " + fmtLatency(performance.now() - started) };
        }
        return { dot: "bad", text: "HTTP " + res.status };
      })
      .catch(function (err) {
        clearTimeout(timer);
        return { dot: "bad", text: "连接失败", title: "请求失败：" + String(err) };
      });
  }

  /** GitHub 最新 Release：浏览器端匿名 API（CORS 可用），限额 60 次/小时/IP。 */
  function probeGithub(repo) {
    return fetch("https://api.github.com/repos/" + encodeURIComponentPart(repo) + "/releases/latest", {
      cache: "no-store",
      headers: { Accept: "application/vnd.github+json" }
    })
      .then(function (res) {
        if (!res.ok) {
          return {
            dot: "bad",
            text: res.status === 403 ? "查询受限" : "查询失败",
            title: "GitHub API 返回 " + res.status +
              "（匿名限额 60 次/小时/IP，稍后手动刷新重试）"
          };
        }
        return res.json().then(function (data) {
          var tag = data && data.tag_name ? data.tag_name : "—";
          return { dot: "ok", text: "最新 " + tag };
        });
      })
      .catch(function (err) {
        return { dot: "bad", text: "查询失败", title: "请求失败：" + String(err) };
      });
  }

  // repo 只允许 owner/name 形态的字符（配置是手写的，仍做最小校验）
  function encodeURIComponentPart(repo) {
    return String(repo).trim().split("/").map(encodeURIComponent).join("/");
  }

  // ---------------------------------------------------------------- 渲染

  function actionHtml(action) {
    if (!action) return "";
    if (action.desktop) {
      return '<button class="tool-btn" type="button" disabled>' +
        escapeHtml(action.label) + '<span class="mt-desktop-tag">仅桌面</span></button>';
    }
    var href = safeHref(action.href);
    if (!href) {
      return '<button class="tool-btn" type="button" disabled title="链接配置无效">' +
        escapeHtml(action.label) + "</button>";
    }
    var external = /^https?:\/\//i.test(href);
    return '<a class="tool-btn' + (action.primary ? " primary" : "") + '" href="' +
      escapeHtml(href) + '"' +
      (external ? ' target="_blank" rel="noreferrer"' : "") + ">" +
      escapeHtml(action.label) + "</a>";
  }

  function monitorHtml(tool, monitor, index) {
    var key = idPart(tool.id) + "-" + index;
    var desktop = monitor.type === "desktop";
    var cached = monitorState[key];
    var dot = desktop ? "desktop" : (cached ? cached.dot : "pending");
    var text = desktop ? "仅桌面" : (cached ? cached.text : "检测中…");
    var title = desktop
      ? "该监控读取 PC 本地文件，仅桌面版可用"
      : (cached && cached.title ? cached.title : "");
    return '<div class="monitor-row" id="mon-' + key + '"' +
      (title ? ' title="' + escapeHtml(title) + '"' : "") + ">" +
      '<span class="mt-dot ' + dot + '"></span>' +
      '<span class="monitor-label">' + escapeHtml(monitor.label) + "</span>" +
      '<span class="monitor-value">' + escapeHtml(text) + "</span>" +
      "</div>";
  }

  function cardHtml(tool) {
    var badges = "";
    if (tool.badge) {
      badges += '<span class="tool-badge">' + escapeHtml(tool.badge) + "</span>";
    }
    if (tool.auth === "admin") {
      badges += '<span class="tool-badge is-auth">需管理员</span>';
    } else if (tool.auth === "user") {
      badges += '<span class="tool-badge is-auth">需登录</span>';
    }
    var tags = (tool.tags || []).map(function (tag) {
      return '<span class="tool-tag">' + escapeHtml(tag) + "</span>";
    }).join("");
    var monitors = (tool.monitors || []).map(function (monitor, index) {
      return monitorHtml(tool, monitor, index);
    }).join("");
    var actions = (tool.actions || []).map(actionHtml).join("");
    return '<article class="card tool-card">' +
      '<div class="tool-name-row">' +
        '<h3 class="tool-name">' + escapeHtml(tool.name) + "</h3>" +
        badges +
      "</div>" +
      (tool.description ? '<p class="tool-desc">' + escapeHtml(tool.description) + "</p>" : "") +
      (tags ? '<div class="tool-tags">' + tags + "</div>" : "") +
      (monitors ? '<div class="tool-monitors">' + monitors + "</div>" : "") +
      (actions ? '<div class="tool-actions">' + actions + "</div>" : "") +
      "</article>";
  }

  function tabHtml(category, label, count, active) {
    return '<button class="mt-filter-btn' + (active ? " is-on" : "") + '" type="button"' +
      ' role="tab" aria-selected="' + (active ? "true" : "false") + '"' +
      ' aria-controls="board" data-category="' + escapeHtml(category) + '">' +
      '<span>' + escapeHtml(label) + "</span>" +
      '<span class="count">' + count + "</span>" +
      "</button>";
  }

  function renderFilter(grouped, order) {
    var box = document.getElementById("tool-filter");
    if (!box) return;
    var html = tabHtml("all", "全部", TOOLS.length, page.category === "all");
    order.forEach(function (category) {
      html += tabHtml(category, category, grouped[category].length, page.category === category);
    });
    box.innerHTML = html;
    box.hidden = false;
    Array.prototype.forEach.call(box.querySelectorAll(".mt-filter-btn"), function (button) {
      button.addEventListener("click", function () {
        page.category = button.getAttribute("data-category") || "all";
        render();
      });
    });
  }

  function render() {
    var board = document.getElementById("board");
    var filter = document.getElementById("tool-filter");
    if (!TOOLS.length) {
      if (filter) filter.hidden = true;
      board.innerHTML = '<div class="catalog-empty">工具清单为空</div>';
      document.getElementById("tool-summary").textContent = "0 个工具";
      return;
    }

    var grouped = {};
    TOOLS.forEach(function (tool) {
      var category = tool.category || "未分类";
      if (!grouped[category]) grouped[category] = [];
      grouped[category].push(tool);
    });
    Object.keys(grouped).forEach(function (category) {
      grouped[category].sort(function (a, b) {
        return (a.order || 0) - (b.order || 0);
      });
    });

    var order = [];
    CATEGORY_ORDER.forEach(function (category) {
      if (grouped[category]) order.push(category);
    });
    Object.keys(grouped).forEach(function (category) {
      if (order.indexOf(category) === -1) order.push(category);
    });

    // 当前选中的分类可能已被下线/改名，回落到「全部」
    if (page.category !== "all" && !grouped[page.category]) {
      page.category = "all";
    }
    renderFilter(grouped, order);

    var cards = page.category === "all"
      ? order.reduce(function (all, category) {
          return all.concat(grouped[category]);
        }, [])
      : grouped[page.category];
    board.innerHTML = '<div class="mt-grid">' + cards.map(cardHtml).join("") + "</div>";

    var monitorCount = cards.reduce(function (sum, tool) {
      return sum + (tool.monitors ? tool.monitors.length : 0);
    }, 0);
    var summary = page.category === "all"
      ? TOOLS.length + " 个工具"
      : page.category + " · " + cards.length + " 个工具";
    document.getElementById("tool-summary").textContent =
      summary + " · " + monitorCount + " 个监控";
  }

  // ---------------------------------------------------------------- 登录态与门禁

  function renderAuthStrip() {
    var box = document.getElementById("auth-strip");
    if (!box) return;
    if (!window.PortalAuth) {
      box.textContent = "登录组件未加载";
      return;
    }
    var auth = window.PortalAuth.getAuth();
    if (!auth) {
      box.innerHTML = '未登录 · <a href="/#login">去主页登录</a>';
      return;
    }
    box.innerHTML = '<span class="dot" aria-hidden="true"></span>已登录 <b>' +
      escapeHtml(auth.username || "（未知用户）") +
      '</b> · <button type="button" id="auth-strip-logout">退出</button>';
    var btn = document.getElementById("auth-strip-logout");
    if (btn) {
      btn.addEventListener("click", function () {
        window.PortalAuth.logout();
        window.location.reload();
      });
    }
  }

  function stopTimer() {
    if (page.timer) {
      clearInterval(page.timer);
      page.timer = null;
    }
  }

  function bindGateActions() {
    var switchBtn = document.getElementById("gate-switch");
    if (switchBtn) {
      switchBtn.addEventListener("click", function () {
        if (window.PortalAuth) window.PortalAuth.logout();
        window.location.href = "/#login";
      });
    }
    var retryBtn = document.getElementById("gate-retry");
    if (retryBtn) {
      retryBtn.addEventListener("click", function () { checkAccess(); });
    }
  }

  function showGate(titleText, message, actionsHtml) {
    stopTimer();
    page.authorized = false;
    page.roleToken = null;
    page.category = "all";

    var filter = document.getElementById("tool-filter");
    if (filter) filter.hidden = true;

    var board = document.getElementById("board");
    if (board) {
      board.innerHTML = '<div class="card mt-gate">' +
        '<div class="mt-gate-icon" aria-hidden="true">🔒</div>' +
        '<div class="mt-gate-title">' + escapeHtml(titleText) + "</div>" +
        '<p class="mt-gate-desc">' + escapeHtml(message) + "</p>" +
        '<div class="mt-gate-actions">' + (actionsHtml || "") + "</div>" +
        "</div>";
    }
    var summary = document.getElementById("tool-summary");
    if (summary) summary.textContent = "管理员专属";
    var last = document.getElementById("last-refresh");
    if (last) last.textContent = "未授权";
    var refresh = document.getElementById("refresh-all");
    if (refresh) refresh.hidden = true;
    bindGateActions();
  }

  function showAuthorized() {
    if (!page.authorized) {
      page.authorized = true;
      render();
      refreshAll(true);
      if (!page.timer) {
        page.timer = setInterval(function () { refreshAll(false); }, HTTP_POLL_MS);
      }
    }
    var refresh = document.getElementById("refresh-all");
    if (refresh) refresh.hidden = false;
  }

  function checkAccess() {
    renderAuthStrip();
    if (!window.PortalAuth) {
      showGate("登录组件未加载", "请刷新页面重试。",
        '<button class="btn-primary" type="button" id="gate-retry">重试</button>');
      return;
    }
    var auth = window.PortalAuth.getAuth();
    if (!auth) {
      showGate("需要管理员账号",
        "我的工具箱仅对管理员开放。请先在主页登录管理员账号；登录状态会与云端网页端、OmoFun 等工具共用。",
        '<a class="btn-primary" href="/#login">去主页登录</a>');
      return;
    }
    if (page.authorized && page.roleToken === auth.accessToken) return;
    if (page.checking && page.roleToken === auth.accessToken) return;

    var token = auth.accessToken;
    page.roleToken = token;
    page.checking = true;
    window.PortalAuth.getMe().then(function (me) {
      if (page.roleToken !== token) return;
      if (me && me.role === "ADMIN") {
        showAuthorized();
      } else {
        showGate("当前账号没有管理员权限",
          "我的工具箱仅对 ADMIN 账号开放。请退出后换管理员账号登录。",
          '<button class="btn-primary" type="button" id="gate-switch">退出并换账号</button>');
      }
    }).catch(function (err) {
      if (page.roleToken !== token) return;
      var message = (err && err.message) || "登录已过期，请重新登录";
      if (!window.PortalAuth.getAuth()) {
        showGate("请重新登录", message,
          '<a class="btn-primary" href="/#login">去主页登录</a>');
      } else {
        showGate("无法验证管理员权限", message,
          '<button class="btn-primary" type="button" id="gate-retry">重试</button>' +
          '<a class="ghost-link" href="/#login">去主页登录</a>');
      }
    }).then(function () {
      page.checking = false;
    });
  }

  // ---------------------------------------------------------------- 状态更新

  function setStatus(toolId, index, status) {
    var key = idPart(toolId) + "-" + index;
    monitorState[key] = status;
    var row = document.getElementById("mon-" + key);
    if (!row) return;
    row.querySelector(".mt-dot").className = "mt-dot " + status.dot;
    row.querySelector(".monitor-value").textContent = status.text;
    row.title = status.title || "";
  }

  function checkOne(tool, index) {
    var monitor = tool.monitors[index];
    var task;
    if (monitor.type === "http") {
      task = probeHttp(monitor.url);
    } else if (monitor.type === "github") {
      task = probeGithub(monitor.repo);
    } else {
      return; // desktop 类型渲染时已是终态
    }
    task.then(function (status) {
      setStatus(tool.id, index, status);
    });
  }

  /**
   * 刷新全部监控。includeGithub=false 用于 60s 定时轮询——GitHub 匿名限额
   * 60 次/小时/IP，自动轮询会很快打爆（桌面版实锤过同款问题），所以 GitHub
   * 监控只在页面加载与手动点「全部刷新」时查。
   */
  function refreshAll(includeGithub) {
    if (!page.authorized) return;
    TOOLS.forEach(function (tool) {
      (tool.monitors || []).forEach(function (monitor, index) {
        if (monitor.type === "github" && !includeGithub) return;
        if (monitor.type === "desktop") return;
        setStatus(tool.id, index, { dot: "pending", text: "检测中…" });
        checkOne(tool, index);
      });
    });
    var last = document.getElementById("last-refresh");
    if (last) {
      last.textContent = "更新于 " +
        new Date().toLocaleTimeString("zh-CN", { hour12: false });
    }
  }

  // ---------------------------------------------------------------- 启动

  function start() {
    if (window.PortalAuth) {
      window.PortalAuth.onChange(function () { checkAccess(); });
      window.PortalAuth.onLogout(function () { checkAccess(); });
    }
    checkAccess();

    var button = document.getElementById("refresh-all");
    if (button) {
      button.addEventListener("click", function () {
        if (!page.authorized) return;
        button.classList.add("spinning");
        window.setTimeout(function () { button.classList.remove("spinning"); }, 600);
        refreshAll(true);
      });
    }
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", start);
  } else {
    start();
  }
})();

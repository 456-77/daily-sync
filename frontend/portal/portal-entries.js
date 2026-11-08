/* ============================================================================
   门户「更多入口」注册表（M12 门户扩展规范的一部分）

   这里只放「聚合页 / 内容页」的入口（当前只有我的工具箱）。具体工具
   （Quick Note、Daily Sync、OmoFun、插件等）统一进工具箱注册表
   frontend/portal/mytools/tools.js，不要再往首页挂单个工具卡。

   新增一个聚合页/内容页只改本文件的 PORTAL_ENTRIES：数组按 order 升序渲染，
   visible:false 可临时下线，不需要改 index.html，也不需要复制卡片 HTML。

   字段约定：
     id          必填，唯一标识；渲染成 id="entry-<id>"，建议小写字母/数字/连字符
     title       必填，入口标题
     description 可选，一句话说明
     href        必填，站内绝对路径（/ 开头）或 http(s):// 外链；
                 其他协议（javascript:/data: 等）会被丢弃
     external    可选，true 时新窗口打开；http(s):// 外链自动新窗口
     icon        可选，短文本/emoji；缺省取标题首字符
     badge       可选，左上角小标签（如「工具」「管理员」「新」）
     actionLabel 可选，右侧动作文案；缺省「打开 ↗」/「进入 →」
     order       可选，数字越小越靠前，缺省 0
     visible     可选，false 时临时隐藏（不删代码）

   主入口（云端网页端、下载区）有专属布局与票据逻辑，不放进本注册表。
   ========================================================================== */
(function () {
  "use strict";

  window.PORTAL_ENTRIES = [
    {
      id: "mytools",
      title: "我的工具箱",
      description: "管理员专属工具聚合面板：Quick Note、Daily Sync、OmoFun 存档与插件入口。",
      href: "/mytools/",
      icon: "🧰",
      badge: "管理员",
      actionLabel: "进入 →",
      order: 10
    }
    // 新增聚合页/内容页入口示例（具体工具请加到 mytools/tools.js）：
    // {
    //   id: "status",
    //   title: "服务状态",
    //   description: "查看同步通道与 API 的实时健康状态。",
    //   href: "/status/",
    //   icon: "◉",
    //   badge: "新",
    //   actionLabel: "查看 ↗",
    //   order: 20
    // }
  ];

  function escapeHtml(value) {
    return String(value).replace(/[&<>"']/g, function (c) {
      return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c];
    });
  }

  /** 只放行站内绝对路径与 http(s) 外链：门户是公开页面，不能让配置
      把 javascript:/data: 塞进 href。 */
  function safeHref(href) {
    if (typeof href !== "string") return null;
    var value = href.trim();
    if (value.charAt(0) === "/" && value.charAt(1) !== "/") return value;
    if (/^https?:\/\//i.test(value)) return value;
    return null;
  }

  function entryHtml(entry, href) {
    var external = entry.external === true || /^https?:\/\//i.test(href);
    var icon = entry.icon || String(entry.title).charAt(0);
    var action = entry.actionLabel || (external ? "打开 ↗" : "进入 →");
    return '<a class="card entry-item" id="entry-' + escapeHtml(entry.id || "") + '"' +
      ' href="' + escapeHtml(href) + '"' +
      (external ? ' target="_blank" rel="noreferrer"' : "") + '>' +
      '<span class="entry-icon" aria-hidden="true">' + escapeHtml(icon) + '</span>' +
      '<span class="entry-body">' +
        '<span class="entry-item-title">' + escapeHtml(entry.title) +
          (entry.badge ? '<span class="entry-badge">' + escapeHtml(entry.badge) + '</span>' : "") +
        '</span>' +
        (entry.description ? '<span class="entry-item-desc">' + escapeHtml(entry.description) + '</span>' : "") +
      '</span>' +
      '<span class="entry-action">' + escapeHtml(action) + '</span>' +
    '</a>';
  }

  function render() {
    var wrap = document.getElementById("portal-entries-wrap");
    var box = document.getElementById("portal-entries");
    if (!wrap || !box) return;

    var entries = (window.PORTAL_ENTRIES || [])
      .filter(function (entry) {
        return entry && entry.visible !== false && entry.title && safeHref(entry.href);
      })
      .sort(function (a, b) { return (a.order || 0) - (b.order || 0); });

    if (!entries.length) {
      // 没有入口时连标题一起隐藏，不留空区块
      wrap.hidden = true;
      return;
    }
    wrap.hidden = false;
    box.innerHTML = entries.map(function (entry) {
      return entryHtml(entry, safeHref(entry.href));
    }).join("");
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", render);
  } else {
    render();
  }
})();

/* ============================================================================
   我的工具箱工具注册表（/mytools/）

   新增工具只改本文件：把对象追加到 MY_TOOLS，然后 bump
   mytools/index.html 里 tools.js 的 ?v=。工具箱页面本身只做渲染与状态探测，
   不需要再改 app.js。

   字段：
     id           必填，唯一标识（用于状态行 DOM id，建议小写字母/数字/连字符）
     name         必填，工具名
     category     必填，分组；顺序由 MY_TOOL_CATEGORY_ORDER 决定
     description  一句话说明
     tags         技术栈标签数组
     actions      入口按钮数组：
                    { label, href, primary? }  站内路径或 http(s) 外链（外链新窗口）
                    { label, desktop: true }  桌面专属（Web 上灰显「仅桌面」）
     monitors     状态监控数组：
                    { type: "http", url, label }    HTTP 探测（同源或允许 CORS 的地址）
                    { type: "github", repo, label } GitHub 最新 Release（匿名限额
                                                    60 次/小时/IP，只在加载与手动
                                                    刷新时查，不自动轮询）
                    { type: "desktop", label }      桌面专属（浏览器读不到本地文件）
     auth         可选，"none" | "user" | "admin"；管理工具标 "admin"。
                  注意：/mytools/ 页面本身已做 ADMIN 门禁，这个字段用于说明工具
                  自身 API 的权限要求，不替代服务端鉴权。
     badge        可选，工具名旁的小标签（如「新」「Beta」）
     order        可选，同分类内排序，数字越小越靠前
     visible      可选，false 临时隐藏

   安全：本文件只放公开信息，不写任何密钥/令牌；所有动态文本由 app.js
   经 escapeHtml 渲染，action href 只放行 / 开头与 http(s)://。
   ========================================================================== */

window.MY_TOOL_CATEGORY_ORDER = ["笔记", "云端", "归档", "插件"];

window.MY_TOOLS = [
  {
    id: "quick-note",
    name: "Quick Note",
    category: "笔记",
    description: "Markdown 笔记（桌面 + 安卓），日常主力笔记工具",
    tags: ["Tauri v2", "React 19", "CodeMirror 6"],
    actions: [
      { label: "GitHub", href: "https://github.com/456-77/quick-note", primary: true },
      { label: "启动", desktop: true },
      { label: "项目目录", desktop: true }
    ],
    monitors: [{ type: "github", repo: "456-77/quick-note", label: "最新版本" }],
    order: 10
  },
  {
    id: "daily-sync",
    name: "Daily Sync",
    category: "云端",
    description: "云同步后端（Spring Boot 3 + MySQL），服务 quick-note 与 Obsidian 插件",
    tags: ["Spring Boot 3", "MyBatis-Plus", "MySQL"],
    actions: [
      { label: "门户页", href: "/" },
      { label: "网页端", href: "/app/" },
      { label: "项目目录", desktop: true }
    ],
    monitors: [{ type: "http", url: "/healthz", label: "服务心跳" }],
    order: 20
  },
  {
    id: "omofun",
    name: "OmoFun 观看历史存档",
    category: "归档",
    description: "播放历史永久存档、每日存档、保活与恢复；服务端已并入同一页面。",
    tags: ["Java", "MySQL", "每日存档", "保活/恢复"],
    actions: [{ label: "打开存档与报告", href: "/omofun/", primary: true }],
    monitors: [],
    auth: "admin",
    order: 30
  },
  {
    id: "quick-daily-note",
    name: "Quick Daily Note",
    category: "插件",
    description: "Obsidian 插件：快速日记 / 每日笔记，与 daily-sync 云同步",
    tags: ["Obsidian", "TypeScript"],
    actions: [
      { label: "GitHub", href: "https://github.com/456-77/quick-daily-note", primary: true },
      { label: "项目目录", desktop: true }
    ],
    monitors: [{ type: "github", repo: "456-77/quick-daily-note", label: "最新发布" }],
    order: 40
  }
];

// 新增工具模板（复制到 MY_TOOLS 数组里，改字段即可）：
// {
//   id: "status",
//   name: "服务状态",
//   category: "云端",
//   description: "查看同步通道与 API 的实时健康状态。",
//   tags: ["HTTP", "健康检查"],
//   actions: [{ label: "打开", href: "/status/", primary: true }],
//   monitors: [{ type: "http", url: "/healthz", label: "服务心跳" }],
//   auth: "admin",
//   badge: "新",
//   order: 50
// }

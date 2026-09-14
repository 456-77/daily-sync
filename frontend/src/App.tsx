import { useCallback, useEffect, useLayoutEffect, useRef, useState, type ReactNode } from "react";
import { api, getAuth, setAuth } from "./api";
import type { UserInfo, VaultInfo } from "./types";
import Login from "./Login";
import Vaults from "./Vaults";
import Records from "./Records";
import Todos from "./Todos";
import Profile from "./Profile";
import Admin from "./Admin";
import AuditLogs from "./AuditLogs";
import BackToTop from "./BackToTop";

/**
 * profile 与 admin 都不在 TABS 常量里：前者由顶栏用户名进入，
 * 后者只对管理员显示（运行时拼进导航）。
 */
type Tab = "vaults" | "records" | "todos" | "audit" | "profile" | "admin";

const TABS: { key: Tab; label: string }[] = [
  { key: "vaults", label: "仓库" },
  { key: "records", label: "日记浏览" },
  { key: "todos", label: "待办" },
  { key: "audit", label: "日志" },
];

/**
 * 页签容器：首次进入才挂载，之后一直留着，只把非当前页隐藏。
 * 之前是条件渲染，切走就整棵卸载 —— 选中的日期、读到的位置全丢，
 * 切回来等于重置。
 */
function TabPanel({
  active,
  opened,
  children,
}: {
  active: boolean;
  opened: boolean;
  children: ReactNode;
}) {
  if (!opened) return null;
  return <div style={active ? undefined : { display: "none" }}>{children}</div>;
}

/** 认证门 + 主框架：顶栏（用户名/退出）+ 三个功能页签 */
export default function App() {
  const [loggedIn, setLoggedIn] = useState(!!getAuth());
  const [tab, setTab] = useState<Tab>("records");
  /** 打开过的页签；初始页签已经在看，先记上 */
  const [opened, setOpened] = useState<Set<Tab>>(() => new Set<Tab>(["records"]));
  /** 每个页签各自的滚动位置：切走时记下，切回来还原 */
  const scrollMemo = useRef<Record<string, number>>({});
  const [vaults, setVaults] = useState<VaultInfo[]>([]);
  const [selectedVault, setSelectedVault] = useState<number | null>(null);
  /** 是否管理员，决定导航里是否出现「管理」；真正的边界在服务端 */
  const [isAdmin, setIsAdmin] = useState(false);

  const switchTab = useCallback(
    (next: Tab) => {
      if (next === tab) return;
      scrollMemo.current[tab] = window.scrollY;
      setOpened((prev) => (prev.has(next) ? prev : new Set(prev).add(next)));
      setTab(next);
    },
    [tab],
  );

  // 换页后把目标页原来的位置还原。各页一直挂着、高度就绪，
  // 所以在布局阶段直接跳，不会先闪一下顶部。
  useLayoutEffect(() => {
    window.scrollTo(0, scrollMemo.current[tab] ?? 0);
  }, [tab]);

  /** 退出（含令牌失效时的 ds-logout）：顺带清掉页签挂载与滚动记忆，
      免得下一个账号继承上一个账号的现场 */
  const logout = useCallback(() => {
    setAuth(null);
    setLoggedIn(false);
    setOpened(new Set<Tab>(["records"]));
    scrollMemo.current = {};
    setTab("records");
  }, []);

  useEffect(() => {
    window.addEventListener("ds-logout", logout);
    return () => window.removeEventListener("ds-logout", logout);
  }, [logout]);

  const reloadVaults = useCallback(() => {
    api
      .get<VaultInfo[]>("/api/v1/vaults")
      .then((list) => {
        setVaults(list);
        // 恢复上次选中的仓库（仍在列表中才有效），否则默认第一个
        setSelectedVault((cur) => {
          if (cur != null && list.some((v) => v.id === cur)) return cur;
          const saved = Number(localStorage.getItem("daily-sync-vault-id"));
          if (saved && list.some((v) => v.id === saved)) return saved;
          return list[0]?.id ?? null;
        });
      })
      .catch(() => {});
  }, []);

  useEffect(() => {
    if (loggedIn) reloadVaults();
  }, [loggedIn, reloadVaults]);

  // 角色决定是否显示管理入口；普通用户即使手动改 state 也过不了服务端
  useEffect(() => {
    if (!loggedIn) {
      setIsAdmin(false);
      return;
    }
    api
      .get<UserInfo>("/api/v1/me")
      .then((me) => setIsAdmin(me.role === "ADMIN"))
      .catch(() => setIsAdmin(false));
  }, [loggedIn]);

  const selectVault = useCallback((id: number) => {
    setSelectedVault(id);
    localStorage.setItem("daily-sync-vault-id", String(id));
  }, []);

  /** 管理员才多出「管理」页签，拼在导航末尾 */
  const visibleTabs = isAdmin ? [...TABS, { key: "admin" as Tab, label: "管理" }] : TABS;

  if (!loggedIn) {
    return <Login onDone={() => setLoggedIn(true)} />;
  }

  return (
    <div className="app">
      <header className="topbar">
        <span className="brand">Daily Sync</span>
        <nav className="tabs">
          {visibleTabs.map((t) => (
            <button
              key={t.key}
              className={tab === t.key ? "tab tab-active" : "tab"}
              onClick={() => switchTab(t.key)}
            >
              {t.label}
            </button>
          ))}
        </nav>
        <span className="topbar-right">
          {vaults.length > 0 && (
            <select
              className="vault-select"
              value={selectedVault ?? undefined}
              onChange={(e) => selectVault(Number(e.target.value))}
              title="切换当前仓库"
            >
              {vaults.length === 0 && <option value="">暂无仓库</option>}
              {vaults.map((v) => (
                <option key={v.id} value={v.id}>
                  {v.name}
                </option>
              ))}
            </select>
          )}
          <button
            type="button"
            className="topbar-user"
            onClick={() => switchTab("profile")}
            title="个人主页"
          >
            {getAuth()?.username}
          </button>
          <a
            href="#"
            onClick={(e) => {
              e.preventDefault();
              logout();
            }}
          >
            退出
          </a>
        </span>
      </header>
      <main className="content">
        <TabPanel active={tab === "vaults"} opened={opened.has("vaults")}>
          <Vaults vaults={vaults} reload={reloadVaults} />
        </TabPanel>
        <TabPanel active={tab === "records"} opened={opened.has("records")}>
          {selectedVault != null ? (
            <Records vaultId={selectedVault} />
          ) : (
            <div className="panel empty">请先在「仓库」页创建或选择一个仓库</div>
          )}
        </TabPanel>
        <TabPanel active={tab === "todos"} opened={opened.has("todos")}>
          {selectedVault != null ? (
            <Todos vaultId={selectedVault} />
          ) : (
            <div className="panel empty">请先在「仓库」页创建或选择一个仓库</div>
          )}
        </TabPanel>
        <TabPanel active={tab === "audit"} opened={opened.has("audit")}>
          <AuditLogs />
        </TabPanel>
        <TabPanel active={tab === "profile"} opened={opened.has("profile")}>
          <Profile />
        </TabPanel>
        <TabPanel active={tab === "admin"} opened={opened.has("admin") && isAdmin}>
          <Admin />
        </TabPanel>
      </main>
      <BackToTop />
    </div>
  );
}

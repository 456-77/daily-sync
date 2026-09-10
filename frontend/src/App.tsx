import { useCallback, useEffect, useState } from "react";
import { api, getAuth, setAuth } from "./api";
import type { VaultInfo } from "./types";
import Login from "./Login";
import Vaults from "./Vaults";
import Records from "./Records";
import Todos from "./Todos";
import Profile from "./Profile";
import AuditLogs from "./AuditLogs";

/** profile 不在 TABS 里：由顶栏用户名进入，不占主导航的位置 */
type Tab = "vaults" | "records" | "todos" | "audit" | "profile";

const TABS: { key: Tab; label: string }[] = [
  { key: "vaults", label: "仓库" },
  { key: "records", label: "日记浏览" },
  { key: "todos", label: "待办" },
  { key: "audit", label: "日志" },
];

/** 认证门 + 主框架：顶栏（用户名/退出）+ 三个功能页签 */
export default function App() {
  const [loggedIn, setLoggedIn] = useState(!!getAuth());
  const [tab, setTab] = useState<Tab>("records");
  const [vaults, setVaults] = useState<VaultInfo[]>([]);
  const [selectedVault, setSelectedVault] = useState<number | null>(null);

  useEffect(() => {
    const onLogout = () => setLoggedIn(false);
    window.addEventListener("ds-logout", onLogout);
    return () => window.removeEventListener("ds-logout", onLogout);
  }, []);

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

  const selectVault = useCallback((id: number) => {
    setSelectedVault(id);
    localStorage.setItem("daily-sync-vault-id", String(id));
  }, []);

  if (!loggedIn) {
    return <Login onDone={() => setLoggedIn(true)} />;
  }

  return (
    <div className="app">
      <header className="topbar">
        <span className="brand">Daily Sync</span>
        <nav className="tabs">
          {TABS.map((t) => (
            <button
              key={t.key}
              className={tab === t.key ? "tab tab-active" : "tab"}
              onClick={() => setTab(t.key)}
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
            onClick={() => setTab("profile")}
            title="个人主页"
          >
            {getAuth()?.username}
          </button>
          <a
            href="#"
            onClick={(e) => {
              e.preventDefault();
              setAuth(null);
              setLoggedIn(false);
            }}
          >
            退出
          </a>
        </span>
      </header>
      <main className="content">
        {tab === "vaults" && <Vaults vaults={vaults} reload={reloadVaults} />}
        {tab === "records" &&
          (selectedVault != null ? (
            <Records vaultId={selectedVault} />
          ) : (
            <div className="panel empty">请先在「仓库」页创建或选择一个仓库</div>
          ))}
        {tab === "todos" &&
          (selectedVault != null ? (
            <Todos vaultId={selectedVault} />
          ) : (
            <div className="panel empty">请先在「仓库」页创建或选择一个仓库</div>
          ))}
        {tab === "audit" && <AuditLogs />}
        {tab === "profile" && <Profile />}
      </main>
    </div>
  );
}

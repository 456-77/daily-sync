import { useCallback, useEffect, useState } from "react";
import { api, getAuth, setAuth } from "./api";
import type { VaultInfo } from "./types";
import Login from "./Login";
import Vaults from "./Vaults";
import Tokens from "./Tokens";
import Records from "./Records";

type Tab = "vaults" | "tokens" | "records";

const TABS: { key: Tab; label: string }[] = [
  { key: "vaults", label: "仓库" },
  { key: "tokens", label: "同步令牌" },
  { key: "records", label: "日记浏览" },
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
        setSelectedVault((cur) => cur ?? list[0]?.id ?? null);
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

  const currentVault = vaults.find((v) => v.id === selectedVault) ?? null;

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
          {currentVault && <span className="vault-chip">仓库：{currentVault.name}</span>}
          <span>{getAuth()?.username}</span>
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
        {tab === "vaults" && (
          <Vaults
            vaults={vaults}
            reload={reloadVaults}
            selectedId={selectedVault}
            onSelect={selectVault}
          />
        )}
        {tab === "tokens" &&
          (selectedVault != null ? (
            <Tokens vaultId={selectedVault} />
          ) : (
            <div className="panel empty">请先在「仓库」页创建或选择一个仓库</div>
          ))}
        {tab === "records" &&
          (selectedVault != null ? (
            <Records vaultId={selectedVault} />
          ) : (
            <div className="panel empty">请先在「仓库」页创建或选择一个仓库</div>
          ))}
      </main>
    </div>
  );
}

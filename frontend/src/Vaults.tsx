import { FormEvent, useEffect, useState } from "react";
import { api } from "./api";
import type { VaultInfo } from "./types";

/**
 * 仓库管理：列表 + 新建 + 选中当前仓库（令牌与日记页都基于它）。
 * 当前仓库 id 存 localStorage，刷新页面不丢。
 */
export default function Vaults({
  vaults,
  reload,
  selectedId,
  onSelect,
}: {
  vaults: VaultInfo[];
  reload: () => void;
  selectedId: number | null;
  onSelect: (id: number) => void;
}) {
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    const saved = localStorage.getItem("daily-sync-vault-id");
    if (saved && vaults.some((v) => v.id === Number(saved)) && Number(saved) !== selectedId) {
      onSelect(Number(saved));
    }
  }, [vaults, onSelect, selectedId]);

  const create = async (e: FormEvent) => {
    e.preventDefault();
    if (busy || !name.trim()) return;
    setBusy(true);
    setError("");
    try {
      const created = await api.post<VaultInfo>("/api/v1/vaults", { name: name.trim() });
      setName("");
      reload();
      onSelect(created.id);
    } catch (err) {
      setError(err instanceof Error ? err.message : "创建失败");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="panel">
      <div className="panel-head">
        <h2>仓库</h2>
        <span className="panel-hint">仓库 = 一个 Obsidian 库；在插件设置中填入为它签发的同步令牌</span>
      </div>
      <form className="inline-form" onSubmit={create}>
        <input
          placeholder="新仓库名（如：个人库）"
          value={name}
          onChange={(e) => setName(e.target.value)}
        />
        <button type="submit" disabled={busy || !name.trim()}>
          新建
        </button>
        {error && <span className="form-error">{error}</span>}
      </form>
      <table className="table">
        <thead>
          <tr>
            <th></th>
            <th>名称</th>
            <th>版本</th>
            <th>创建时间</th>
          </tr>
        </thead>
        <tbody>
          {vaults.map((v) => (
            <tr
              key={v.id}
              className={v.id === selectedId ? "row-selected" : ""}
              onClick={() => onSelect(v.id)}
            >
              <td className="col-radio">{v.id === selectedId ? "●" : ""}</td>
              <td>{v.name}</td>
              <td>v{v.version}</td>
              <td>{new Date(v.createdAt).toLocaleString()}</td>
            </tr>
          ))}
          {vaults.length === 0 && (
            <tr>
              <td colSpan={4} className="empty">
                还没有仓库，先建一个
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  );
}

import { useEffect, useState } from "react";
import { api } from "./api";
import PanelHelp from "./PanelHelp";
import type { VaultInfo } from "./types";

/**
 * 仓库列表（只读）。M5.1 起仓库由插件首次同步时按 Obsidian 仓库名自动创建，
 * 这里不再提供手动新建；当前仓库的切换在顶栏下拉框完成。
 */
export default function Vaults({ vaults, reload }: { vaults: VaultInfo[]; reload: () => void }) {
  // 进入页面时刷新一次列表（插件可能刚建了新仓库）
  useEffect(() => {
    reload();
  }, [reload]);

  const [deleting, setDeleting] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  /** 删除仓库：confirm 二次确认（删的是云端全部正文与附件，不可恢复）。 */
  const remove = async (vault: VaultInfo) => {
    const sure = window.confirm(
      `确定删除仓库「${vault.name}」？\n云端上的全部正文与附件都会被删除，本机文件不受影响，此操作不可恢复。`,
    );
    if (!sure) return;
    setDeleting(vault.id);
    setError(null);
    try {
      await api.del(`/api/v1/vaults/${vault.id}`);
      await reload();
    } catch (err) {
      setError(err instanceof Error ? err.message : "删除失败");
    } finally {
      setDeleting(null);
    }
  };

  return (
    <div className="panel">
      {error && <div className="form-error">{error}</div>}
      <div className="panel-head">
        <h2>仓库</h2>
        <PanelHelp>
          仓库 = 一个 Obsidian 库，由插件首次同步时按库名自动创建；顶栏下拉框切换当前仓库
        </PanelHelp>
      </div>
      <div className="table-wrap">
        <table className="table">
          <thead>
            <tr>
              <th>名称</th>
              <th>版本</th>
              <th>创建时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            {vaults.map((v) => (
              <tr key={v.id}>
                <td data-label="名称">{v.name}</td>
                <td data-label="版本">v{v.version}</td>
                <td data-label="创建时间">{new Date(v.createdAt).toLocaleString()}</td>
                <td data-label="操作">
                  <button
                    type="button"
                    className="danger"
                    disabled={deleting === v.id}
                    onClick={() => void remove(v)}
                  >
                    {deleting === v.id ? "删除中…" : "删除"}
                  </button>
                </td>
              </tr>
            ))}
            {vaults.length === 0 && (
              <tr>
                <td colSpan={4} className="empty">
                  还没有仓库——在 Obsidian 插件设置里填好账号并同步一次即可自动创建
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}

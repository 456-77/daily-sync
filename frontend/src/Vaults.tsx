import { useEffect } from "react";
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

  return (
    <div className="panel">
      <div className="panel-head">
        <h2>仓库</h2>
        <span className="panel-hint">
          仓库 = 一个 Obsidian 库，由插件首次同步时按库名自动创建；顶栏下拉框切换当前仓库
        </span>
      </div>
      <div className="table-wrap">
        <table className="table">
          <thead>
            <tr>
              <th>名称</th>
              <th>版本</th>
              <th>创建时间</th>
            </tr>
          </thead>
          <tbody>
            {vaults.map((v) => (
              <tr key={v.id}>
                <td data-label="名称">{v.name}</td>
                <td data-label="版本">v{v.version}</td>
                <td data-label="创建时间">{new Date(v.createdAt).toLocaleString()}</td>
              </tr>
            ))}
            {vaults.length === 0 && (
              <tr>
                <td colSpan={3} className="empty">
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

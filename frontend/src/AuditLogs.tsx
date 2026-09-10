import { useEffect, useState } from "react";
import { api } from "./api";
import type { AuditLog } from "./types";

/** 后端事件类型 → 中文标签；未知类型兜底显示原字符串 */
const ACTION_LABELS: Record<string, string> = {
  REGISTER: "注册",
  LOGIN_SUCCESS: "登录成功",
  LOGIN_FAIL: "登录失败",
  VAULT_CREATE: "创建仓库",
  TOKEN_ISSUE: "签发令牌",
  TOKEN_REVOKE: "撤销令牌",
  SYNC_AUTH_FAIL: "同步鉴权失败",
};

/** 失败类事件标红，其余按中性色显示 */
const DANGER_ACTIONS = new Set(["LOGIN_FAIL", "SYNC_AUTH_FAIL"]);

const PAGE_SIZE = 50;

/**
 * 审计日志（M5）：安全事件流水，只读。
 * 按用户维度（与仓库页签无关），新→旧，用 before=<最后一条 id> 游标翻页。
 */
export default function AuditLogs() {
  const [logs, setLogs] = useState<AuditLog[]>([]);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const [done, setDone] = useState(false); // 已到末页（本页不满 PAGE_SIZE）

  const load = async (before?: number) => {
    setLoading(true);
    setError("");
    try {
      const query = before ? `?limit=${PAGE_SIZE}&before=${before}` : `?limit=${PAGE_SIZE}`;
      const page = await api.get<AuditLog[]>(`/api/v1/audit-logs${query}`);
      setLogs((cur) => (before ? [...cur, ...page] : page));
      setDone(page.length < PAGE_SIZE);
    } catch (err) {
      setError(err instanceof Error ? err.message : "加载失败");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    setLogs([]);
    setDone(false);
    load();
  }, []);

  return (
    <div className="panel">
      <div className="panel-head">
        <h2>审计日志</h2>
        <span className="panel-hint">
          记录登录成败、令牌签发/撤销、同步鉴权失败等安全事件（仅自己可见）
        </span>
      </div>
      {error && <p className="form-error">{error}</p>}
      <table className="table">
        <thead>
          <tr>
            <th>时间</th>
            <th>事件</th>
            <th>详情</th>
            <th>来源 IP</th>
          </tr>
        </thead>
        <tbody>
          {logs.map((log) => (
            <tr key={log.id}>
              <td className="audit-time">{new Date(log.createdAt).toLocaleString()}</td>
              <td>
                {DANGER_ACTIONS.has(log.action) ? (
                  <span className="tag tag-danger">{ACTION_LABELS[log.action] ?? log.action}</span>
                ) : (
                  <span className="tag">{ACTION_LABELS[log.action] ?? log.action}</span>
                )}
              </td>
              <td className="audit-detail">{log.detail || "—"}</td>
              <td className="audit-ip">{log.ip || "—"}</td>
            </tr>
          ))}
          {logs.length === 0 && !loading && (
            <tr>
              <td colSpan={4} className="empty">
                还没有日志——下次登录起会在这里留下记录
              </td>
            </tr>
          )}
        </tbody>
      </table>
      <div className="audit-actions">
        {!done && logs.length > 0 && (
          <button disabled={loading} onClick={() => load(logs[logs.length - 1].id)}>
            {loading ? "加载中…" : "加载更多"}
          </button>
        )}
        <button disabled={loading} onClick={() => (setLogs([]), setDone(false), load())}>
          刷新
        </button>
        {done && logs.length > 0 && <span className="panel-hint">已到最早记录</span>}
      </div>
    </div>
  );
}

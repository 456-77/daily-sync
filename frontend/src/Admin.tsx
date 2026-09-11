import { useCallback, useEffect, useState } from "react";
import { api } from "./api";
import { ACTION_LABELS, DANGER_ACTIONS } from "./AuditLogs";
import type { AdminAuditLog, AdminUser } from "./types";

/**
 * 管理界面（入口仅管理员可见，但真正的边界在服务端：
 * /api/v1/admin/** 由拦截器要求 ADMIN 角色，前端隐藏只是体验）。
 *
 * 两块内容：用户管理、全量日志。
 */

const PAGE_SIZE = 50;

export default function Admin() {
  const [view, setView] = useState<"users" | "logs">("users");

  return (
    <div className="panel">
      <div className="panel-head">
        <h2>管理</h2>
        <span className="panel-hint">用户管理与全局操作日志（仅管理员可见）</span>
      </div>
      <div className="admin-switch">
        <button
          className={view === "users" ? "chip chip-active" : "chip"}
          onClick={() => setView("users")}
        >
          用户管理
        </button>
        <button
          className={view === "logs" ? "chip chip-active" : "chip"}
          onClick={() => setView("logs")}
        >
          全部日志
        </button>
      </div>
      {view === "users" ? <AdminUsers /> : <AdminLogs />}
    </div>
  );
}

function AdminUsers() {
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [flash, setFlash] = useState("");
  const [busy, setBusy] = useState(false);
  /** 正在重置密码的行（展开内联输入框） */
  const [resetId, setResetId] = useState<number | null>(null);
  const [resetPwd, setResetPwd] = useState("");

  const load = useCallback(async () => {
    setError("");
    try {
      setUsers(await api.get<AdminUser[]>("/api/v1/admin/users"));
    } catch (err) {
      setError(err instanceof Error ? err.message : "加载失败");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const toast = (text: string) => {
    setFlash(text);
    window.setTimeout(() => setFlash(""), 3000);
  };

  const toggleStatus = async (u: AdminUser) => {
    const next = u.status === 1 ? 0 : 1;
    if (next === 0 && !window.confirm(`禁用「${u.username}」？该用户当前会话会立即失效。`)) return;
    setBusy(true);
    setError("");
    try {
      await api.post(`/api/v1/admin/users/${u.id}/status`, { status: next });
      toast(next === 1 ? `已启用「${u.username}」` : `已禁用「${u.username}」`);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "操作失败");
    } finally {
      setBusy(false);
    }
  };

  const submitReset = async (u: AdminUser) => {
    if (resetPwd.length < 8) {
      setError("新密码至少 8 位");
      return;
    }
    setBusy(true);
    setError("");
    try {
      await api.post(`/api/v1/admin/users/${u.id}/password`, { newPassword: resetPwd });
      toast(`已重置「${u.username}」的密码，该用户所有设备需重新登录`);
      setResetId(null);
      setResetPwd("");
    } catch (err) {
      setError(err instanceof Error ? err.message : "重置失败");
    } finally {
      setBusy(false);
    }
  };

  const remove = async (u: AdminUser) => {
    if (
      !window.confirm(
        `删除「${u.username}」？其 ${u.vaultCount} 个仓库、${u.recordCount} 条记录与全部日志都会一并清除，不可恢复。`,
      )
    )
      return;
    setBusy(true);
    setError("");
    try {
      await api.del(`/api/v1/admin/users/${u.id}`);
      toast(`已删除「${u.username}」`);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "删除失败");
    } finally {
      setBusy(false);
    }
  };

  if (loading) return <div className="empty">加载中…</div>;

  return (
    <>
      {error && <div className="form-error">{error}</div>}
      {flash && <div className="admin-flash">{flash}</div>}
      <div className="table-wrap">
        <table className="table">
          <thead>
            <tr>
              <th>ID</th>
              <th>用户名</th>
              <th>昵称</th>
              <th>邮箱</th>
              <th>角色</th>
              <th>状态</th>
              <th>仓库</th>
              <th>记录</th>
              <th>注册时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            {users.map((u) => (
              <tr key={u.id}>
                <td data-label="ID">#{u.id}</td>
                <td data-label="用户名">{u.username}</td>
                <td data-label="昵称">{u.nickName || "—"}</td>
                <td data-label="邮箱">{u.email || "—"}</td>
                <td data-label="角色">
                  <span className={u.role === "ADMIN" ? "tag tag-admin" : "tag"}>{u.role}</span>
                </td>
                <td data-label="状态">
                  {u.status === 1 ? (
                    <span className="tag tag-ok">正常</span>
                  ) : (
                    <span className="tag tag-danger">已禁用</span>
                  )}
                </td>
                <td data-label="仓库">{u.vaultCount}</td>
                <td data-label="记录">{u.recordCount}</td>
                <td data-label="注册时间">{new Date(u.createdAt).toLocaleString()}</td>
                <td data-label="操作">
                  <div className="admin-actions">
                    <button disabled={busy} onClick={() => void toggleStatus(u)}>
                      {u.status === 1 ? "禁用" : "启用"}
                    </button>
                    <button
                      disabled={busy}
                      onClick={() => {
                        setResetId(resetId === u.id ? null : u.id);
                        setResetPwd("");
                        setError("");
                      }}
                    >
                      重置密码
                    </button>
                    <button className="danger" disabled={busy} onClick={() => void remove(u)}>
                      删除
                    </button>
                  </div>
                  {resetId === u.id && (
                    <div className="admin-reset">
                      <input
                        type="text"
                        value={resetPwd}
                        placeholder="新密码（8-64 位）"
                        onChange={(e) => setResetPwd(e.target.value)}
                      />
                      <button className="primary" disabled={busy} onClick={() => void submitReset(u)}>
                        确认
                      </button>
                      <button disabled={busy} onClick={() => setResetId(null)}>
                        取消
                      </button>
                    </div>
                  )}
                </td>
              </tr>
            ))}
            {users.length === 0 && (
              <tr>
                <td colSpan={10} className="empty">
                  还没有用户
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </>
  );
}

function AdminLogs() {
  const [logs, setLogs] = useState<AdminAuditLog[]>([]);
  const [loading, setLoading] = useState(false);
  const [done, setDone] = useState(false);
  const [error, setError] = useState("");

  const load = async (before?: number) => {
    setLoading(true);
    setError("");
    try {
      const query = before ? `?limit=${PAGE_SIZE}&before=${before}` : `?limit=${PAGE_SIZE}`;
      const page = await api.get<AdminAuditLog[]>(`/api/v1/admin/audit-logs${query}`);
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
    void load();
  }, []);

  return (
    <>
      {error && <div className="form-error">{error}</div>}
      <div className="table-wrap">
        <table className="table">
          <thead>
            <tr>
              <th>时间</th>
              <th>用户</th>
              <th>事件</th>
              <th>详情</th>
              <th>来源 IP</th>
            </tr>
          </thead>
          <tbody>
            {logs.map((log) => (
              <tr key={log.id}>
                <td className="audit-time" data-label="时间">
                  {new Date(log.createdAt).toLocaleString()}
                </td>
                <td data-label="用户">
                  {log.username ?? (log.userId == null ? "—" : `#${log.userId}`)}
                </td>
                <td data-label="事件">
                  {DANGER_ACTIONS.has(log.action) ? (
                    <span className="tag tag-danger">{ACTION_LABELS[log.action] ?? log.action}</span>
                  ) : (
                    <span className="tag">{ACTION_LABELS[log.action] ?? log.action}</span>
                  )}
                </td>
                <td className="audit-detail" data-label="详情">
                  {log.detail || "—"}
                </td>
                <td className="audit-ip" data-label="来源 IP">
                  {log.ip || "—"}
                </td>
              </tr>
            ))}
            {logs.length === 0 && !loading && (
              <tr>
                <td colSpan={5} className="empty">
                  还没有日志
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      <div className="audit-actions">
        {!done && logs.length > 0 && (
          <button disabled={loading} onClick={() => void load(logs[logs.length - 1].id)}>
            {loading ? "加载中…" : "加载更多"}
          </button>
        )}
        <button
          disabled={loading}
          onClick={() => {
            setLogs([]);
            setDone(false);
            void load();
          }}
        >
          刷新
        </button>
        {done && logs.length > 0 && <span className="panel-hint">已到最早记录</span>}
      </div>
    </>
  );
}

import { FormEvent, useState } from "react";
import { api, saveLogin } from "./api";

/** 登录页：用户名 + 密码（注册暂走 curl / 后续加开关） */
export default function Login({ onDone }: { onDone: () => void }) {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (busy) return;
    setBusy(true);
    setError("");
    try {
      const pair = await api.post<{ accessToken: string; refreshToken: string; expiresIn: number }>(
        "/api/v1/auth/login",
        { username, password },
      );
      saveLogin(pair, username);
      onDone();
    } catch (err) {
      setError(err instanceof Error ? err.message : "登录失败");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="login-wrap">
      <form className="login-card" onSubmit={submit}>
        <h1>Daily Sync</h1>
        <p className="login-sub">日记云同步 · 管理控制台</p>
        <label>
          用户名
          <input
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoComplete="username"
            autoFocus
          />
        </label>
        <label>
          密码
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
          />
        </label>
        {error && <div className="form-error">{error}</div>}
        <button type="submit" disabled={busy || !username || !password}>
          {busy ? "登录中…" : "登录"}
        </button>
      </form>
    </div>
  );
}

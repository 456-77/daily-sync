import { FormEvent, useState } from "react";
import { api, saveLogin } from "./api";
import type { TokenPair } from "./types";

/**
 * 登录 / 注册页。
 *
 * 两种模式共用一张卡片：登录成功后由父组件切到主界面；注册走
 * POST /api/v1/auth/register（服务端直接签发令牌对，注册即登录）。
 * 服务端配置了邀请码时注册必须携带，未配置则可留空。
 */
export default function Login({ onDone }: { onDone: () => void }) {
  const [mode, setMode] = useState<"login" | "register">("login");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [inviteCode, setInviteCode] = useState("");
  /** 一个开关同时控制密码与确认密码的可见性 */
  const [showPassword, setShowPassword] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const isRegister = mode === "register";
  const canSubmit = username !== "" && password !== "" && (!isRegister || confirm !== "");

  const switchMode = (next: "login" | "register") => {
    if (busy) return;
    setMode(next);
    setError("");
    setPassword("");
    setConfirm("");
    setShowPassword(false);
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (busy) return;
    if (isRegister && password !== confirm) {
      setError("两次输入的密码不一致");
      return;
    }
    setBusy(true);
    setError("");
    try {
      const code = inviteCode.trim();
      const pair = await api.post<TokenPair>(
        isRegister ? "/api/v1/auth/register" : "/api/v1/auth/login",
        isRegister && code !== ""
          ? { username, password, inviteCode: code }
          : { username, password },
      );
      saveLogin(pair, username);
      onDone();
    } catch (err) {
      setError(err instanceof Error ? err.message : isRegister ? "注册失败" : "登录失败");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="login-wrap">
      <form className="login-card" onSubmit={submit}>
        <h1>Daily Sync</h1>
        <p className="login-sub">
          {isRegister ? "创建账号，开始同步日记" : "日记云同步 · 管理控制台"}
        </p>
        <label>
          用户名
          <input
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoComplete="username"
            autoFocus
            placeholder={isRegister ? "3-32 位字母、数字或下划线" : undefined}
          />
        </label>
        <label>
          密码
          <span className="password-field">
            <input
              type={showPassword ? "text" : "password"}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete={isRegister ? "new-password" : "current-password"}
              placeholder={isRegister ? "8-64 位" : undefined}
            />
            <button
              type="button"
              className="password-toggle"
              onClick={() => setShowPassword((v) => !v)}
              aria-label={showPassword ? "隐藏密码" : "显示密码"}
              title={showPassword ? "隐藏密码" : "显示密码"}
            >
              {showPassword ? "隐藏" : "显示"}
            </button>
          </span>
        </label>
        {isRegister && (
          <>
            <label>
              确认密码
              <span className="password-field">
                <input
                  type={showPassword ? "text" : "password"}
                  value={confirm}
                  onChange={(e) => setConfirm(e.target.value)}
                  autoComplete="new-password"
                />
              </span>
            </label>
            <label>
              邀请码
              <input
                value={inviteCode}
                onChange={(e) => setInviteCode(e.target.value)}
                autoComplete="off"
                placeholder="选填，服务端未开启邀请码时留空"
              />
            </label>
          </>
        )}
        {error && <div className="form-error">{error}</div>}
        <button type="submit" disabled={busy || !canSubmit}>
          {busy ? (isRegister ? "注册中…" : "登录中…") : isRegister ? "注册" : "登录"}
        </button>
        <div className="auth-switch">
          <span>{isRegister ? "已有账号？" : "还没有账号？"}</span>
          <a
            href="#"
            onClick={(e) => {
              e.preventDefault();
              switchMode(isRegister ? "login" : "register");
            }}
          >
            {isRegister ? "返回登录" : "立即注册"}
          </a>
        </div>
      </form>
    </div>
  );
}

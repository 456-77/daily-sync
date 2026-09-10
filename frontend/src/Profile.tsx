import { FormEvent, useEffect, useState } from "react";
import { api, getAuth, setAuth } from "./api";
import type { UserInfo } from "./types";

/**
 * 个人主页：查看账号信息、修改资料（用户名 / 昵称 / 邮箱）、修改密码。
 *
 * 改密码成功后服务端会作废该用户全部 refresh token（所有设备重新登录），
 * 这里先给用户看一眼成功提示，再清掉本地登录态跳回登录页。
 */

type Msg = { kind: "ok" | "err"; text: string } | null;

export default function Profile() {
  const [info, setInfo] = useState<UserInfo | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");

  // 资料表单
  const [username, setUsername] = useState("");
  const [nickName, setNickName] = useState("");
  const [email, setEmail] = useState("");
  const [savingProfile, setSavingProfile] = useState(false);
  const [profileMsg, setProfileMsg] = useState<Msg>(null);

  // 密码表单
  const [oldPassword, setOldPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [savingPassword, setSavingPassword] = useState(false);
  const [passwordMsg, setPasswordMsg] = useState<Msg>(null);

  useEffect(() => {
    api
      .get<UserInfo>("/api/v1/me")
      .then((data) => {
        setInfo(data);
        setUsername(data.username);
        setNickName(data.nickName ?? "");
        setEmail(data.email ?? "");
      })
      .catch((err) => setLoadError(err instanceof Error ? err.message : "加载失败"))
      .finally(() => setLoading(false));
  }, []);

  const submitProfile = async (e: FormEvent) => {
    e.preventDefault();
    if (savingProfile) return;
    setSavingProfile(true);
    setProfileMsg(null);
    try {
      const updated = await api.post<UserInfo>("/api/v1/me/profile", {
        username: username.trim(),
        nickName: nickName.trim(),
        email: email.trim(),
      });
      setInfo(updated);
      setUsername(updated.username);
      setNickName(updated.nickName ?? "");
      setEmail(updated.email ?? "");
      // 顶栏显示的名字取自本地登录态，改名后要同步，否则页面还显示旧名
      const auth = getAuth();
      if (auth) setAuth({ ...auth, username: updated.username });
      setProfileMsg({ kind: "ok", text: "资料已保存" });
    } catch (err) {
      setProfileMsg({ kind: "err", text: err instanceof Error ? err.message : "保存失败" });
    } finally {
      setSavingProfile(false);
    }
  };

  const submitPassword = async (e: FormEvent) => {
    e.preventDefault();
    if (savingPassword) return;
    if (newPassword !== confirmPassword) {
      setPasswordMsg({ kind: "err", text: "两次输入的新密码不一致" });
      return;
    }
    setSavingPassword(true);
    setPasswordMsg(null);
    try {
      await api.post("/api/v1/me/password", { oldPassword, newPassword });
      // 服务端已作废全部 refresh token：本地登录态同步清掉，回登录页重新登
      setPasswordMsg({ kind: "ok", text: "密码已修改，所有设备需重新登录，正在退出…" });
      window.setTimeout(() => {
        setAuth(null);
        window.dispatchEvent(new Event("ds-logout"));
      }, 1500);
    } catch (err) {
      setPasswordMsg({ kind: "err", text: err instanceof Error ? err.message : "修改失败" });
      setSavingPassword(false);
    }
  };

  if (loading) {
    return (
      <div className="panel">
        <div className="empty">加载中…</div>
      </div>
    );
  }

  if (loadError || !info) {
    return (
      <div className="panel">
        <div className="form-error">{loadError || "加载失败"}</div>
      </div>
    );
  }

  return (
    <div className="panel">
      <div className="panel-head">
        <h2>个人主页</h2>
        <span className="panel-hint">账号信息与登录凭证；改密码后所有设备都需要重新登录</span>
      </div>

      <div className="profile-meta">
        <div className="profile-meta-row">
          <span className="profile-meta-label">账号 ID</span>
          <span>#{info.id}</span>
        </div>
        <div className="profile-meta-row">
          <span className="profile-meta-label">注册时间</span>
          <span>{new Date(info.createdAt).toLocaleString()}</span>
        </div>
      </div>

      <div className="profile-grid">
        <form className="profile-card" onSubmit={submitProfile}>
          <h3>账号资料</h3>
          <p className="profile-hint">用户名用于登录与同步，昵称与邮箱可以留空</p>

          <label className="profile-field">
            用户名
            <input
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              autoComplete="username"
              placeholder="3-32 位字母、数字或下划线"
            />
          </label>
          <label className="profile-field">
            昵称
            <input
              value={nickName}
              onChange={(e) => setNickName(e.target.value)}
              placeholder="选填，最长 32 字"
            />
          </label>
          <label className="profile-field">
            邮箱
            <input
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              autoComplete="email"
              placeholder="选填，填了需全局唯一"
            />
          </label>

          <div className="profile-actions">
            <button type="submit" className="primary" disabled={savingProfile || !username.trim()}>
              {savingProfile ? "保存中…" : "保存资料"}
            </button>
            {profileMsg && <span className={`profile-msg ${profileMsg.kind}`}>{profileMsg.text}</span>}
          </div>
        </form>

        <form className="profile-card" onSubmit={submitPassword}>
          <h3>修改密码</h3>
          <p className="profile-hint">密码 8-64 位；修改成功后所有设备都会退出登录</p>

          <label className="profile-field">
            当前密码
            <span className="password-field">
              <input
                type={showPassword ? "text" : "password"}
                value={oldPassword}
                onChange={(e) => setOldPassword(e.target.value)}
                autoComplete="current-password"
              />
              <button
                type="button"
                className="password-toggle"
                onClick={() => setShowPassword((v) => !v)}
                aria-label={showPassword ? "隐藏密码" : "显示密码"}
              >
                {showPassword ? "隐藏" : "显示"}
              </button>
            </span>
          </label>
          <label className="profile-field">
            新密码
            <span className="password-field">
              <input
                type={showPassword ? "text" : "password"}
                value={newPassword}
                onChange={(e) => setNewPassword(e.target.value)}
                autoComplete="new-password"
                placeholder="8-64 位"
              />
            </span>
          </label>
          <label className="profile-field">
            确认新密码
            <span className="password-field">
              <input
                type={showPassword ? "text" : "password"}
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                autoComplete="new-password"
              />
            </span>
          </label>

          <div className="profile-actions">
            <button
              type="submit"
              className="primary"
              disabled={savingPassword || !oldPassword || !newPassword || !confirmPassword}
            >
              {savingPassword ? (passwordMsg?.kind === "ok" ? "已修改" : "提交中…") : "修改密码"}
            </button>
            {passwordMsg && (
              <span className={`profile-msg ${passwordMsg.kind}`}>{passwordMsg.text}</span>
            )}
          </div>
        </form>
      </div>
    </div>
  );
}

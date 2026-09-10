import { FormEvent, useEffect, useState } from "react";
import { api } from "./api";
import type { TokenCreated, TokenInfo } from "./types";

/**
 * 同步令牌管理：签发 / 列表 / 撤销。
 * 明文令牌只在签发那一刻展示一次（弹窗 + 复制按钮），关闭即无法再取。
 */
export default function Tokens({ vaultId }: { vaultId: number }) {
  const [tokens, setTokens] = useState<TokenInfo[]>([]);
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [created, setCreated] = useState<TokenCreated | null>(null);
  const [copied, setCopied] = useState(false);

  const reload = () => {
    api
      .get<TokenInfo[]>(`/api/v1/vaults/${vaultId}/tokens`)
      .then(setTokens)
      .catch((err) => setError(err instanceof Error ? err.message : "加载失败"));
  };

  useEffect(() => {
    setTokens([]);
    setError("");
    reload();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [vaultId]);

  const issue = async (e: FormEvent) => {
    e.preventDefault();
    if (busy) return;
    setBusy(true);
    setError("");
    try {
      const result = await api.post<TokenCreated>(`/api/v1/vaults/${vaultId}/tokens`, {
        name: name.trim(),
      });
      setName("");
      setCreated(result);
      setCopied(false);
      reload();
    } catch (err) {
      setError(err instanceof Error ? err.message : "签发失败");
    } finally {
      setBusy(false);
    }
  };

  const revoke = async (t: TokenInfo) => {
    if (!window.confirm(`确定撤销令牌「${t.name || t.id}」？使用它的设备将立即无法同步。`)) return;
    try {
      await api.del(`/api/v1/vaults/${vaultId}/tokens/${t.id}`);
      reload();
    } catch (err) {
      setError(err instanceof Error ? err.message : "撤销失败");
    }
  };

  const copy = async () => {
    if (!created) return;
    try {
      await navigator.clipboard.writeText(created.token);
      setCopied(true);
    } catch {
      setCopied(false);
    }
  };

  return (
    <div className="panel">
      <div className="panel-head">
        <h2>同步令牌</h2>
        <span className="panel-hint">建议一台设备一枚（备注设备名）；撤销立即生效</span>
      </div>
      <form className="inline-form" onSubmit={issue}>
        <input
          placeholder="备注名（如：我的电脑）"
          value={name}
          onChange={(e) => setName(e.target.value)}
        />
        <button type="submit" disabled={busy} className="primary">
          签发令牌
        </button>
        {error && <span className="form-error">{error}</span>}
      </form>
      <table className="table">
        <thead>
          <tr>
            <th>备注名</th>
            <th>状态</th>
            <th>最近使用</th>
            <th>签发时间</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          {tokens.map((t) => (
            <tr key={t.id}>
              <td>{t.name || `#${t.id}`}</td>
              <td>
                {t.status === 1 ? (
                  <span className="tag tag-ok">正常</span>
                ) : (
                  <span className="tag tag-off">已撤销</span>
                )}
              </td>
              <td>{t.lastUsedAt ? new Date(t.lastUsedAt).toLocaleString() : "从未使用"}</td>
              <td>{new Date(t.createdAt).toLocaleString()}</td>
              <td>
                {t.status === 1 && (
                  <button className="danger" onClick={() => revoke(t)}>
                    撤销
                  </button>
                )}
              </td>
            </tr>
          ))}
          {tokens.length === 0 && (
            <tr>
              <td colSpan={5} className="empty">
                该仓库还没有令牌
              </td>
            </tr>
          )}
        </tbody>
      </table>

      {created && (
        <div className="modal-mask" onClick={() => setCreated(null)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <h3>令牌已签发 — 仅此一次展示</h3>
            <p className="modal-warn">
              关闭后无法再次查看。立即复制并粘贴到 Obsidian 插件设置的「同步令牌」中。
            </p>
            <textarea readOnly value={created.token} rows={3} onFocus={(e) => e.target.select()} />
            <div className="modal-actions">
              <button onClick={copy} className="primary">
                {copied ? "已复制 ✓" : "复制令牌"}
              </button>
              <button onClick={() => setCreated(null)}>我已保存，关闭</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

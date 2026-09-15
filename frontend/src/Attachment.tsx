import { useEffect, useState } from "react";
import { downloadAttachment, loadAttachmentUrl, type AttachmentTarget } from "./attachments";

/**
 * 内联图片附件（正文里的 `![[截图.png]]`）。
 *
 * 走 blob 而不是 `<img src="接口地址">`：img 标签发不出 Authorization 头。
 * 失败不静默裂图——显示文件名 + 服务端给的原因 + 重试，否则用户只会看到一片空白
 * 而不知道该去检查什么。
 *
 * 副作用依赖的是 target 的几个字符串字段而不是 target 对象本身：
 * target 由 MarkdownView 每次渲染时从哨兵解析而来，引用每次都变，
 * 拿它当依赖会让图反复重新加载。
 */
export function AttachmentImage({
  vaultId,
  target,
  alt,
}: {
  vaultId: number;
  target: AttachmentTarget;
  alt?: string;
}) {
  const [url, setUrl] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);

  const { path, name, from } = target;
  useEffect(() => {
    let cancelled = false;
    setError(null);
    setUrl(null);
    loadAttachmentUrl(vaultId, { path, name, from, label: "" })
      .then((next) => {
        if (!cancelled) setUrl(next);
      })
      .catch((e: unknown) => {
        if (!cancelled) setError(e instanceof Error ? e.message : "附件加载失败");
      });
    return () => {
      cancelled = true;
    };
  }, [vaultId, path, name, from, reloadKey]);

  if (error !== null) {
    return (
      <span className="attachment-error">
        <span className="attachment-error-text">🖼 {target.label}（{error}）</span>
        <button type="button" className="attachment-retry" onClick={() => setReloadKey((k) => k + 1)}>
          重试
        </button>
      </span>
    );
  }
  if (url === null) {
    return <span className="attachment-loading">🖼 {target.label}（加载中…）</span>;
  }
  return (
    <img
      className="attachment-img"
      src={url}
      alt={alt || target.label}
      style={target.width ? { width: `${target.width}px` } : undefined}
      loading="lazy"
    />
  );
}

/** 非图片附件（pdf）与普通 wiki 链接：一行可点下载的胶囊 */
export function AttachmentLink({ vaultId, target }: { vaultId: number; target: AttachmentTarget }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const onDownload = () => {
    setBusy(true);
    setError(null);
    downloadAttachment(vaultId, target)
      .catch((e: unknown) => setError(e instanceof Error ? e.message : "附件下载失败"))
      .finally(() => setBusy(false));
  };

  return (
    <span className="attachment-inline">
      <button type="button" className="attachment-chip" onClick={onDownload} disabled={busy}>
        📎 {target.label}
        {busy ? "（下载中…）" : ""}
      </button>
      {error !== null && <span className="attachment-error-text">下载失败：{error}</span>}
    </span>
  );
}

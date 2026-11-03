import { useEffect, useMemo, useState } from "react";
import { api } from "./api";
import { downloadAttachment, isImageTarget, loadAttachmentUrl } from "./attachments";
import type { VaultFile } from "./types";

/**
 * 「文件」页签：仓库内全部非正文字节（M8 全类型同步的网页侧入口）。
 *
 * M8 之前网页端只能看到「正文里引用到的」附件；没被任何笔记引用的文件
 * （docx、录音、数据文件……）在云端有、在网页上无处可看。这个页签直接列
 * attachments 表的全量元数据（后端 GET /api/v1/vaults/{id}/files）：
 * 按目录分组、可搜索；图片内联缩略图，其余类型给下载胶囊。
 *
 * 数据面刻意做薄：列表一次拉全（个人库量级），搜索过滤在前端做；缩略图与下载
 * 复用正文渲染同一套 blob 通道（attachments.ts 的缓存与 JWT 处理都是现成的）。
 * 待办快照走正文通道（daily_records），天然不会出现在这里。
 */

/** 字节数的人类可读形式；过 1KB 才带小数，超大值只取整数位避免字符串太长 */
function humanSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  const units = ["KB", "MB", "GB", "TB"];
  let value = bytes / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value >= 100 ? Math.round(value) : value.toFixed(1)} ${units[unit]}`;
}

/** 后端 LocalDateTime 序列形如 2026-11-03T21:15:33，网页只显示到分钟 */
function formatTime(value: string): string {
  return value.slice(0, 16).replace("T", " ");
}

/** 图片缩略图。与 AttachmentImage 同一套 blob 通道，失败降级成图标占位 */
function FileThumb({ vaultId, path, name }: { vaultId: number; path: string; name: string }) {
  const [url, setUrl] = useState<string | null>(null);
  const [error, setError] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setError(false);
    setUrl(null);
    loadAttachmentUrl(vaultId, { path, label: name })
      .then((next) => {
        if (!cancelled) setUrl(next);
      })
      .catch(() => {
        if (!cancelled) setError(true);
      });
    return () => {
      cancelled = true;
    };
  }, [vaultId, path, name]);

  if (error) return <span className="files-thumb files-thumb-icon">🖼</span>;
  if (url === null) return <span className="files-thumb files-thumb-icon" aria-label="加载中" />;
  return <img className="files-thumb" src={url} alt={name} loading="lazy" />;
}

/** 一行文件：缩略图（或类型图标）+ 名称与元信息 + 下载 */
function FileRow({ vaultId, file }: { vaultId: number; file: VaultFile }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const image = isImageTarget({ path: file.path, label: file.name });

  const download = () => {
    setBusy(true);
    setError(null);
    downloadAttachment(vaultId, { path: file.path, label: file.name })
      .catch((e: unknown) => setError(e instanceof Error ? e.message : "下载失败"))
      .finally(() => setBusy(false));
  };

  return (
    <div className="files-row">
      {image ? (
        <FileThumb vaultId={vaultId} path={file.path} name={file.name} />
      ) : (
        <span className="files-thumb files-thumb-icon">📎</span>
      )}
      <div className="files-info">
        <span className="files-name" title={file.path}>
          {file.name}
        </span>
        <span className="files-meta">
          {humanSize(file.size)} · {formatTime(file.updatedAt)}
          {error !== null ? ` · ${error}` : ""}
        </span>
      </div>
      <button type="button" className="attachment-chip files-dl" onClick={download} disabled={busy}>
        {busy ? "下载中…" : "下载"}
      </button>
    </div>
  );
}

export default function Files({ vaultId }: { vaultId: number }) {
  const [files, setFiles] = useState<VaultFile[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const [query, setQuery] = useState("");

  useEffect(() => {
    let cancelled = false;
    setFiles(null);
    setError(null);
    api
      .get<VaultFile[]>(`/api/v1/vaults/${vaultId}/files`)
      .then((list) => {
        if (!cancelled) setFiles(list);
      })
      .catch((e: unknown) => {
        if (!cancelled) setError(e instanceof Error ? e.message : "文件列表加载失败");
      });
    return () => {
      cancelled = true;
    };
  }, [vaultId, reloadKey]);

  const filtered = useMemo(() => {
    if (files === null) return [];
    const q = query.trim().toLowerCase();
    if (q === "") return files;
    return files.filter((file) => file.path.toLowerCase().includes(q));
  }, [files, query]);

  /**
   * 按目录分组。后端已按 path 升序返回，组内天然有序；组与组之间按首次出现
   * 的先后排（不是字典序），但同一目录的文件总是聚在一起，浏览体验一致。
   */
  const groups = useMemo(() => {
    const map = new Map<string, VaultFile[]>();
    for (const file of filtered) {
      const slash = file.path.lastIndexOf("/");
      const dir = slash < 0 ? "" : file.path.slice(0, slash);
      const list = map.get(dir);
      if (list) list.push(file);
      else map.set(dir, [file]);
    }
    return [...map.entries()];
  }, [filtered]);

  const totalSize = useMemo(
    () => (files ?? []).reduce((sum, file) => sum + file.size, 0),
    [files],
  );

  return (
    <div className="panel">
      <div className="panel-head">
        <h2 className="panel-title-lg">文件</h2>
        <span className="panel-hint">
          {files === null ? "加载中…" : `${files.length} 个文件 · ${humanSize(totalSize)}`}
        </span>
      </div>
      <input
        className="file-list-filter"
        value={query}
        placeholder="按路径筛选，如 attachments 或 .pdf"
        onChange={(e) => setQuery(e.target.value)}
      />
      {error !== null && (
        <div className="files-state">
          {error}{" "}
          <button type="button" className="attachment-retry" onClick={() => setReloadKey((k) => k + 1)}>
            重试
          </button>
        </div>
      )}
      {files !== null && files.length === 0 && (
        <div className="files-state">
          仓库内还没有非正文字节——客户端（M8 全类型同步）把同步范围内的任意文件传上来后，这里就能看到。
        </div>
      )}
      {files !== null && files.length > 0 && filtered.length === 0 && (
        <div className="files-state">没有匹配「{query.trim()}」的文件</div>
      )}
      <div className="files-body">
        {groups.map(([dir, items]) => (
          <div key={dir} className="files-group">
            <div className="files-group-title">
              {dir === "" ? "（根目录）" : dir} <span>{items.length}</span>
            </div>
            {items.map((file) => (
              <FileRow key={file.path} vaultId={vaultId} file={file} />
            ))}
          </div>
        ))}
      </div>
    </div>
  );
}

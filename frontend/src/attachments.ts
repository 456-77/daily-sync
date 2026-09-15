import { api } from "./api";

/**
 * 附件（粘贴图片 / PDF）在网页端的支持。
 *
 * 日记正文里是 Obsidian 的写法，react-markdown 一个都不认识：
 * - `![[截图.png]]`（粘贴图片的默认写法）根本不是 markdown，会被当纯文本原样显示；
 * - `![[截图.png|300]]` 里的 `|300` 是显示宽度；
 * - `![](attachments/a.png)` 是相对当前笔记的路径，浏览器会当站内 URL 去请求而 404。
 *
 * 所以渲染前先做一遍**纯文本改写**：把这些写法换成指向哨兵 URL 的标准 markdown，
 * 再由 MarkdownView 的 img / a 组件按哨兵识别、用带 JWT 的 blob 请求取回内容。
 * 选哨兵而不是直接写接口地址，是因为 `<img>` 发不出 Authorization 头，
 * 接口地址塞进 src 只会拿到 401（详见 api.ts 的 requestBlob）。
 *
 * 哨兵形如 `#ds-att:path=...&name=...`：这种"纯相对 URL"能通过 react-markdown 的
 * defaultUrlTransform（它会剥掉 ds-att:// 这类自定义协议，但放行相对 URL）。
 */

/** 图片类扩展名：内联渲染（其余走可点下载的胶囊） */
const IMAGE_EXTENSIONS = new Set(["png", "jpg", "jpeg", "gif", "webp"]);
/**
 * 允许同步的附件扩展名，必须与后端 AttachmentService 的白名单一致。
 * 不在名单里的附件根本没上云；改写它只会得到一张必然加载失败的图，
 * 不如原样保留文本，至少看得到文件名。
 */
const ATTACHMENT_EXTENSIONS = new Set([...IMAGE_EXTENSIONS, "pdf"]);

const SENTINEL_PREFIX = "#ds-att:";
/** 外部链接（http: / data: 等）不碰 */
const EXTERNAL_URL = /^[a-z][a-z0-9+.-]*:/i;

export interface AttachmentTarget {
  /** 库内精确路径（与 name 二选一） */
  path?: string;
  /** 文件名；`![[截图.png]]` 这种不带目录的写法用它，由服务端按全库解析 */
  name?: string;
  /** 引用方记录路径，服务端据此做「同目录优先」 */
  from?: string;
  /** `![[图|300]]` 里的显示宽度（px） */
  width?: number;
  /** 展示名（文件名或用户写的别名） */
  label: string;
}

/**
 * 把正文里的附件引用改写成哨兵 markdown。
 *
 * @param content    记录正文
 * @param recordPath 该记录在库内的路径（相对路径解析与"同目录优先"都要用）
 */
export function rewriteAttachments(content: string, recordPath: string): string {
  return outsideCode(content, (segment) => rewriteSegment(segment, recordPath));
}

/** 是否内联显示（图片）；false 表示渲染成可点下载的胶囊（pdf） */
export function isImageTarget(target: AttachmentTarget): boolean {
  // 必须看真实路径/文件名，不能看 label——label 可能是用户写的别名或 alt 文案，没有扩展名
  return IMAGE_EXTENSIONS.has(extensionOf(target.path ?? target.name ?? target.label));
}

/** 附件下载接口地址（网页端按仓库 id 寻址，与记录查询是同一套约定） */
export function attachmentUrl(vaultId: number, target: AttachmentTarget): string {
  const params = new URLSearchParams();
  if (target.path) params.set("path", target.path);
  if (target.name) params.set("name", target.name);
  if (target.from) params.set("from", target.from);
  return `/api/v1/vaults/${vaultId}/attachments?${params.toString()}`;
}

/** 从 img/a 组件的 src/href 还原出附件目标；不是哨兵就返回 null（交给默认渲染） */
export function parseAttachmentSrc(src: string | undefined): AttachmentTarget | null {
  if (!src || !src.startsWith(SENTINEL_PREFIX)) return null;
  const params = new URLSearchParams(src.slice(SENTINEL_PREFIX.length));
  const path = params.get("path") ?? undefined;
  const name = params.get("name") ?? undefined;
  if (!path && !name) return null;
  const width = Number(params.get("w"));
  return {
    path,
    name,
    from: params.get("from") ?? undefined,
    width: Number.isFinite(width) && width > 0 ? width : undefined,
    label: params.get("label") || basenameOf(path ?? name ?? ""),
  };
}

// ------------------------------------------------------------
// 取图与缓存
// ------------------------------------------------------------

/**
 * 接口地址 -> objectURL。缓存的意义：同一张图在每次重渲染、每次切页签时
 * 都会重新走一遍组件挂载，没有缓存就会反复下载同一张几 MB 的图。
 */
const blobUrls = new Map<string, string>();
/** 缓存上限：超了回收最早一条并释放内存。单页 200 张图是极罕见的量级 */
const BLOB_CACHE_MAX = 200;

/** 取附件的 objectURL（带缓存）。失败时抛 ApiError，由组件显示占位与重试 */
export async function loadAttachmentUrl(vaultId: number, target: AttachmentTarget): Promise<string> {
  const url = attachmentUrl(vaultId, target);
  const cached = blobUrls.get(url);
  if (cached) return cached;
  const blob = await api.blob(url);
  const objectUrl = URL.createObjectURL(blob);
  if (blobUrls.size >= BLOB_CACHE_MAX) {
    const oldest = blobUrls.keys().next().value;
    if (oldest !== undefined) {
      const stale = blobUrls.get(oldest);
      blobUrls.delete(oldest);
      if (stale) URL.revokeObjectURL(stale);
    }
  }
  blobUrls.set(url, objectUrl);
  return objectUrl;
}

/** 下载附件（pdf 胶囊点击时用）：取字节后借一个临时 <a download> 交给浏览器保存 */
export async function downloadAttachment(vaultId: number, target: AttachmentTarget): Promise<void> {
  const blob = await api.blob(attachmentUrl(vaultId, target));
  const objectUrl = URL.createObjectURL(blob);
  try {
    const link = document.createElement("a");
    link.href = objectUrl;
    link.download = target.label;
    document.body.appendChild(link);
    link.click();
    link.remove();
  } finally {
    URL.revokeObjectURL(objectUrl);
  }
}

// ------------------------------------------------------------
// 改写实现
// ------------------------------------------------------------

/**
 * 只在非代码区段上做替换：跳过 ``` / ~~~ 围栏块与行内 `code`。
 * 正文里写教程、贴示例时经常出现 `![[图]]` 字面量，改掉就成插图了
 * （插件端 removeImageLinksFromNote 也是同样的取舍）。
 */
function outsideCode(text: string, fn: (segment: string) => string): string {
  let fence: string | null = null;
  return text
    .split("\n")
    .map((line) => {
      const fenceMatch = /^\s*(```+|~~~+)/.exec(line);
      if (fenceMatch) {
        const marker = fenceMatch[1][0];
        if (fence === null) fence = marker;
        else if (fence === marker) fence = null;
        return line;
      }
      if (fence !== null) return line;
      return splitByInlineCode(line, fn);
    })
    .join("\n");
}

/** 把一行按行内代码切开，只对代码之外的片段应用替换 */
function splitByInlineCode(line: string, fn: (segment: string) => string): string {
  const out: string[] = [];
  const code = /`+[^`]*`+/g;
  let last = 0;
  let match: RegExpExecArray | null;
  while ((match = code.exec(line)) !== null) {
    out.push(fn(line.slice(last, match.index)), match[0]);
    last = match.index + match[0].length;
  }
  out.push(fn(line.slice(last)));
  return out.join("");
}

/** 顺序要紧：先处理 `![](...)`，后面两步产出的 `![label](哨兵)` 才不会被再改一遍 */
function rewriteSegment(segment: string, recordPath: string): string {
  return segment
    .replace(/!\[([^\]]*)\]\(\s*([^)\s]+)(?:\s+"[^"]*")?\s*\)/g, (all, alt: string, src: string) =>
      markdownImageReplacement(all, alt, src, recordPath),
    )
    .replace(/!\[\[([^\]|]+?)(?:\|([^\]]*))?\]\]/g, (all, target: string, alias: string | undefined) =>
      wikiReplacement(all, target, alias, recordPath, true),
    )
    .replace(/\[\[([^\]|]+?)(?:\|([^\]]*))?\]\]/g, (all, target: string, alias: string | undefined) =>
      wikiReplacement(all, target, alias, recordPath, false),
    );
}

/** `![](attachments/a.png)`：相对路径交给服务端解析（精确路径没命中时会按文件名兜底） */
function markdownImageReplacement(all: string, alt: string, src: string, recordPath: string): string {
  if (src.startsWith(SENTINEL_PREFIX) || src.startsWith("//") || EXTERNAL_URL.test(src)) return all;
  const target = buildTarget(src, alt, recordPath);
  if (!target) return all;
  return `![${target.label}](${sentinelOf(target)})`;
}

/** `![[图]]` / `![[图|300]]` / `[[文件.pdf]]` */
function wikiReplacement(
  all: string,
  rawTarget: string,
  alias: string | undefined,
  recordPath: string,
  embed: boolean,
): string {
  const target = buildTarget(rawTarget, alias, recordPath);
  if (!target) return all;
  if (!embed) {
    // 普通 wiki 链接（非嵌入）：一律渲染成可点下载的胶囊
    return `[${target.label}](${sentinelOf(target)})`;
  }
  // 图片用 `![]` 内联；pdf 之类改成普通链接，由 a 组件渲染成下载胶囊
  return isImageTarget(target)
    ? `![${target.label}](${sentinelOf(target)})`
    : `[${target.label}](${sentinelOf(target)})`;
}

/**
 * 解析一个引用目标。返回 null 表示"不该改写"（外部 URL、空目标、或不在同步白名单里的类型）。
 *
 * 带目录的写法按库内路径找；不带目录的（`![[截图.png]]`）交给服务端按文件名在全库解析——
 * Obsidian 的解析规则（同目录优先 / 最短路径）放在服务端一处实现，网页端与插件共用，
 * 前端因此不需要维护"文件名 -> 路径"的映射。
 */
function buildTarget(rawTarget: string, alias: string | undefined, recordPath: string): AttachmentTarget | null {
  const raw = rawTarget.trim();
  if (raw === "" || EXTERNAL_URL.test(raw)) return null;
  const target = raw.replace(/\\/g, "/").replace(/^\.\//, "");
  const base = basenameOf(target);
  if (!ATTACHMENT_EXTENSIONS.has(extensionOf(base))) return null;
  // Obsidian 的别名：`|300` / `|300x200` 是显示尺寸，其余当展示文案
  const aliasText = (alias ?? "").trim();
  const size = /^(\d+)(?:\s*[xX]\s*\d+)?$/.exec(aliasText);
  const width = size ? Number(size[1]) : undefined;
  const label = aliasText !== "" && width === undefined ? aliasText : base;
  return target.includes("/")
    ? { path: target, from: recordPath, width, label }
    : { name: base, from: recordPath, width, label };
}

function sentinelOf(target: AttachmentTarget): string {
  const params = new URLSearchParams();
  if (target.path) params.set("path", target.path);
  if (target.name) params.set("name", target.name);
  if (target.from) params.set("from", target.from);
  if (target.width) params.set("w", String(target.width));
  params.set("label", target.label);
  return SENTINEL_PREFIX + params.toString();
}

function basenameOf(path: string): string {
  return path.substring(path.lastIndexOf("/") + 1);
}

function extensionOf(path: string): string {
  const dot = path.lastIndexOf(".");
  const slash = path.lastIndexOf("/");
  return dot > slash + 1 ? path.substring(dot + 1).toLowerCase() : "";
}

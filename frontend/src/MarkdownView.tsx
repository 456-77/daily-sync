import { useEffect, useRef, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import rehypeHighlight from "rehype-highlight";

/**
 * 统一 Markdown 渲染：GFM（表格 / 任务列表 / 删除线 / 自动链接）+ 代码块高亮 + 目录。
 *
 * react-markdown 默认只解析 CommonMark，任务列表 `- [ ]`、表格、删除线都依赖
 * remark-gfm；代码高亮由 rehype-highlight 附上 hljs-* 类名，配色见 styles.css。
 * 日记与周记共用本组件，样式统一挂在 .md-body 上。
 *
 * 目录的标题**不靠解析 markdown 文本**（代码块里的 # 会被误判），而是渲染完成后
 * 从 DOM 里读真实标题节点，顺便回填 id 供跳转使用。
 *
 * 注：rehype-highlight 在模块顶层 import 了 lowlight 的全量语言集（common），
 * 打包时无法摇掉，因此这里不再自行注册语言——传 languages 只会变成额外增量。
 * 代价是产物多约 220KB（gzip 后约 67KB），换来 37 种语言开箱可用。
 */

interface Heading {
  id: string;
  text: string;
  level: number;
}

export default function MarkdownView({ content }: { content: string }) {
  const bodyRef = useRef<HTMLDivElement>(null);
  const [headings, setHeadings] = useState<Heading[]>([]);
  // 宽屏默认展开目录，窄屏默认收起，避免一进来就占掉半屏
  const [tocOpen, setTocOpen] = useState(() => window.innerWidth > 640);

  useEffect(() => {
    const el = bodyRef.current;
    if (!el) return;
    const nodes = Array.from(el.querySelectorAll("h1, h2, h3, h4, h5, h6"));
    const list: Heading[] = nodes.map((node, index) => {
      const id = `md-heading-${index}`;
      node.id = id;
      return {
        id,
        text: (node.textContent ?? "").trim(),
        level: Number(node.tagName.slice(1)),
      };
    });
    setHeadings(list);
  }, [content]);

  const jumpTo = (id: string) => {
    // 标题上的 scroll-margin-top（见 styles.css）负责让出顶栏高度
    document.getElementById(id)?.scrollIntoView({ behavior: "smooth", block: "start" });
  };

  return (
    <>
      {headings.length >= 2 && (
        <div className={tocOpen ? "md-toc md-toc-open" : "md-toc"}>
          <button
            type="button"
            className="md-toc-toggle"
            onClick={() => setTocOpen((v) => !v)}
            aria-expanded={tocOpen}
            title={tocOpen ? "收起目录" : "展开目录"}
          >
            <span className="md-toc-arrow">{tocOpen ? "▾" : "▸"}</span>
            <span>目录</span>
            <span className="md-toc-count">{headings.length}</span>
          </button>
          {tocOpen && (
            <nav className="md-toc-list">
              {headings.map((h) => (
                <button
                  key={h.id}
                  type="button"
                  className={`md-toc-item md-toc-h${h.level}`}
                  onClick={() => jumpTo(h.id)}
                  title={h.text}
                >
                  {h.text}
                </button>
              ))}
            </nav>
          )}
        </div>
      )}
      <div className="md-body" ref={bodyRef}>
        <ReactMarkdown
          remarkPlugins={[remarkGfm]}
          rehypePlugins={[[rehypeHighlight, { ignoreMissing: true }]]}
          components={{
            // node 是 react-markdown 注入的内部字段，透传会触发 React 未知属性告警
            a: ({ node: _node, ...rest }) => <a {...rest} target="_blank" rel="noopener noreferrer" />,
          }}
        >
          {content}
        </ReactMarkdown>
      </div>
    </>
  );
}

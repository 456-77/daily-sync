import { useEffect, useRef, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import rehypeHighlight from "rehype-highlight";

/**
 * 统一 Markdown 渲染：GFM（表格 / 任务列表 / 删除线 / 自动链接）+ 代码块高亮。
 *
 * react-markdown 默认只解析 CommonMark，任务列表 `- [ ]`、表格、删除线都依赖
 * remark-gfm；代码高亮由 rehype-highlight 附上 hljs-* 类名，配色见 styles.css。
 *
 * 目录不在这里渲染：标题列表通过 onHeadings 交给上层（TocPanel 挂在页面最左侧，
 * 比正文内嵌更贴近「目录在左」的用法）。标题**不靠解析 markdown 文本**
 * （代码块里的 # 会被误判），而是渲染完成后从 DOM 里读真实标题节点，顺便回填 id。
 *
 * 注：rehype-highlight 在模块顶层 import 了 lowlight 的全量语言集（common），
 * 打包时无法摇掉，因此这里不再自行注册语言——传 languages 只会变成额外增量。
 * 代价是产物多约 220KB（gzip 后约 67KB），换来 37 种语言开箱可用。
 */

export interface Heading {
  id: string;
  text: string;
  level: number;
}

export default function MarkdownView({
  content,
  onHeadings,
}: {
  content: string;
  /** 渲染完成后回报标题列表（调用方需保证引用稳定，否则会反复触发） */
  onHeadings?: (headings: Heading[]) => void;
}) {
  const bodyRef = useRef<HTMLDivElement>(null);

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
    onHeadings?.(list);
  }, [content, onHeadings]);

  return (
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
  );
}

import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import rehypeHighlight from "rehype-highlight";

/**
 * 统一 Markdown 渲染：GFM（表格 / 任务列表 / 删除线 / 自动链接）+ 代码块高亮。
 *
 * react-markdown 默认只解析 CommonMark，任务列表 `- [ ]`、表格、删除线都依赖
 * remark-gfm；代码高亮由 rehype-highlight 附上 hljs-* 类名，配色见 styles.css。
 * 日记与周记共用本组件，样式统一挂在 .md-body 上。
 *
 * 注：rehype-highlight 在模块顶层 import 了 lowlight 的全量语言集（common），
 * 打包时无法摇掉，因此这里不再自行注册语言——传 languages 只会变成额外增量。
 * 代价是产物多约 220KB（gzip 后约 67KB），换来 37 种语言开箱可用。
 */
export default function MarkdownView({ content }: { content: string }) {
  return (
    <div className="md-body">
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

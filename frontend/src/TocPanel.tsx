import { useState } from "react";
import type { Heading } from "./MarkdownView";

/**
 * 正文目录：挂在**页面最左侧**（不是正文里面），收起后只剩一个窄标签。
 *
 * - 宽屏：页面左栏的一列，position: sticky 跟着滚动；
 * - 窄屏：没有栏位，改成左下角悬浮浮层（见 styles.css 的媒体查询），
 *   不占空间也不挡顶部工具栏。
 * 标题少于 2 条时由调用方决定不渲染。
 */
export default function TocPanel({ headings }: { headings: Heading[] }) {
  // 宽屏默认展开，窄屏默认收起，免得一进来就糊住半个屏幕
  const [open, setOpen] = useState(() => window.innerWidth > 860);

  const jumpTo = (id: string) => {
    // 标题上的 scroll-margin-top（见 styles.css）负责让出顶栏高度
    document.getElementById(id)?.scrollIntoView({ behavior: "smooth", block: "start" });
  };

  return (
    <div className={open ? "toc-panel toc-panel-open" : "toc-panel"}>
      <button
        type="button"
        className="toc-toggle"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        title={open ? "收起目录" : "展开目录"}
      >
        <span className="toc-arrow">{open ? "▾" : "▸"}</span>
        <span>目录</span>
        <span className="toc-count">{headings.length}</span>
      </button>
      {open && (
        <nav className="toc-list">
          {headings.map((h) => (
            <button
              key={h.id}
              type="button"
              className={`toc-item toc-h${h.level}`}
              onClick={() => jumpTo(h.id)}
              title={h.text}
            >
              {h.text}
            </button>
          ))}
        </nav>
      )}
    </div>
  );
}

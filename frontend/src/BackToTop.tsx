import { useEffect, useState } from "react";

/**
 * 回到顶部：长日记 / 长周记读到底部后一键回顶。
 *
 * 正文卡片不限高、整页随 window 滚动（见 .record-content），所以直接看
 * window.scrollY，不做滚动容器探测。滚动量不到阈值就不渲染，短页面无感知。
 */
export default function BackToTop({ threshold = 400 }: { threshold?: number }) {
  const [visible, setVisible] = useState(false);

  useEffect(() => {
    const onScroll = () => setVisible(window.scrollY > threshold);
    onScroll(); // 刷新后浏览器会恢复上次的滚动位置，先对齐一次
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => window.removeEventListener("scroll", onScroll);
  }, [threshold]);

  if (!visible) return null;

  return (
    <button
      type="button"
      className="to-top"
      title="回到顶部"
      aria-label="回到顶部"
      onClick={() => {
        // 系统开了「减少动态效果」就别再平滑滚动
        const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
        window.scrollTo({ top: 0, behavior: reduce ? "auto" : "smooth" });
      }}
    >
      ↑
    </button>
  );
}

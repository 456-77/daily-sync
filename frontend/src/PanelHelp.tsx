import { useEffect, useRef, useState, type ReactNode } from "react";

/**
 * 面板标题旁的「?」：把长说明收起来，悬停（鼠标）或点击才弹出，
 * 点面板外任意位置或按 Esc 收起。
 *
 * 浮层以 .panel-head 为定位锚点（见 styles.css），所以必须放在 .panel-head 里。
 * 触摸设备没有 hover，只能靠点击；也因此不能只依赖 :hover 展开。
 */
export default function PanelHelp({ children }: { children: ReactNode }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLSpanElement>(null);

  useEffect(() => {
    if (!open) return;
    const onPointerDown = (e: PointerEvent) => {
      if (!ref.current?.contains(e.target as Node)) setOpen(false);
    };
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") setOpen(false);
    };
    document.addEventListener("pointerdown", onPointerDown);
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("pointerdown", onPointerDown);
      document.removeEventListener("keydown", onKeyDown);
    };
  }, [open]);

  return (
    <span className={open ? "panel-help panel-help-open" : "panel-help"} ref={ref}>
      <button
        type="button"
        className="panel-help-btn"
        aria-expanded={open}
        aria-label="说明"
        onClick={() => setOpen((v) => !v)}
      >
        ?
      </button>
      <p className="panel-help-text">{children}</p>
    </span>
  );
}

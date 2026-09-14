import { useEffect, useMemo, useRef, useState } from "react";
import { api } from "./api";
import MarkdownView from "./MarkdownView";
import PanelHelp from "./PanelHelp";
import type { DailyRecord, DateCount, WeeklyRecord } from "./types";

/**
 * 日记浏览：月历打点（各日记录数）→ 点日期看当天记录 → 看内容（Markdown 渲染）。
 * 数据来自插件同步上来的 daily_records（record_date 按文件名首段解析）。
 *
 * 日历第一列是「周记列」（显示 ISO 周号）：该周有周记就打一个菱形标记，
 * 点击在右侧看全文。周记没有 record_date，进不了按日查询，因此走单独的
 * /records/weekly（取周→路径）与 /records/file（按路径取正文）两个接口。
 *
 * 三种状态刻意分开，避免混在一起：
 * - 今天：浅蓝底 + 加粗日期
 * - 选中：主题色实心 + 白色描边
 * - 有日记：不染整格，只挂一个角标（数字＝篇数，超过 9 显示 9+）
 * 相邻月的日期统一渲染成灰色小字，点击即翻到那个月。
 */
interface YearMonth {
  year: number;
  month: number; // 1-12
}

const WEEKDAYS = ["一", "二", "三", "四", "五", "六", "日"];
const MONTHS = Array.from({ length: 12 }, (_, i) => i + 1);
/** 横滑判定：位移超过这个像素才算切月，避免和纵向滚动/点按冲突 */
const SWIPE_THRESHOLD = 50;

function iso(y: number, m: number, d: number): string {
  return `${y}-${String(m).padStart(2, "0")}-${String(d).padStart(2, "0")}`;
}

function todayIso(): string {
  const now = new Date();
  return iso(now.getFullYear(), now.getMonth() + 1, now.getDate());
}

/** YYYY-MM-DD 解析为本地日期（直接用 new Date("2026-09-05") 会按 UTC 解析，东八区会差一天） */
function parseIso(value: string): Date {
  const [y, m, d] = value.split("-").map(Number);
  return new Date(y, m - 1, d);
}

function monthOf(value: string): YearMonth {
  const [y, m] = value.split("-").map(Number);
  return { year: y, month: m };
}

/**
 * ISO 周标识（如 2026-W37），与插件端 moment 的 "GGGG-[W]WW" 结果一致。
 * 算法：把日期移到本周四，它落在哪一年就算该年的第几周 —— 跨年周的归属由此确定。
 */
function isoWeekKey(date: Date): string {
  const d = new Date(Date.UTC(date.getFullYear(), date.getMonth(), date.getDate()));
  const dayNum = d.getUTCDay() || 7;
  d.setUTCDate(d.getUTCDate() + 4 - dayNum);
  const yearStart = new Date(Date.UTC(d.getUTCFullYear(), 0, 1));
  const week = Math.ceil(((d.getTime() - yearStart.getTime()) / 86400000 + 1) / 7);
  return `${d.getUTCFullYear()}-W${String(week).padStart(2, "0")}`;
}

/** 某月日历的 42 格（6 周，周一开头），含前后月补位 */
function monthGrid({ year, month }: YearMonth): { date: string; inMonth: boolean }[] {
  const first = new Date(year, month - 1, 1);
  const startOffset = (first.getDay() + 6) % 7; // 周一=0
  const start = new Date(year, month - 1, 1 - startOffset);
  const cells: { date: string; inMonth: boolean }[] = [];
  for (let i = 0; i < 42; i++) {
    const d = new Date(start.getFullYear(), start.getMonth(), start.getDate() + i);
    cells.push({ date: iso(d.getFullYear(), d.getMonth() + 1, d.getDate()), inMonth: d.getMonth() + 1 === month });
  }
  return cells;
}

function shiftMonth({ year, month }: YearMonth, delta: number): YearMonth {
  const d = new Date(year, month - 1 + delta, 1);
  return { year: d.getFullYear(), month: d.getMonth() + 1 };
}

/** 角标里的篇数：超过 9 截断，避免把格子撑变形 */
function countLabel(n: number): string {
  return n > 9 ? "9+" : String(n);
}

export default function Records({ vaultId }: { vaultId: number }) {
  const today = todayIso();
  const thisMonth = useMemo(() => monthOf(today), [today]);

  const [ym, setYm] = useState<YearMonth>(thisMonth);
  const [counts, setCounts] = useState<Record<string, number>>({});
  const [selectedDate, setSelectedDate] = useState<string | null>(null);
  const [records, setRecords] = useState<DailyRecord[]>([]);
  const [selectedRecord, setSelectedRecord] = useState<DailyRecord | null>(null);
  /** 周标识 -> 周记路径，用于周记列打标记；正文等点开再取 */
  const [weeklyPaths, setWeeklyPaths] = useState<Record<string, string>>({});
  const [selectedWeek, setSelectedWeek] = useState<string | null>(null);
  const [weeklyRecord, setWeeklyRecord] = useState<DailyRecord | null>(null);
  const [error, setError] = useState("");
  /** 年月快速跳转面板 */
  const [pickerOpen, setPickerOpen] = useState(false);
  const [pickerYear, setPickerYear] = useState(thisMonth.year);
  const pickerRef = useRef<HTMLDivElement>(null);
  /** 点了「回到今天」后，等当前月的记录数回来再自动选中今天 */
  const [pendingToday, setPendingToday] = useState(false);
  /** 触摸横滑的起点 */
  const touchStart = useRef<{ x: number; y: number } | null>(null);

  const grid = useMemo(() => monthGrid(ym), [ym]);
  const isThisMonth = ym.year === thisMonth.year && ym.month === thisMonth.month;

  /** 42 格按周切成 6 行，行首带该行的 ISO 周标识 */
  const weeks = useMemo(() => {
    const rows: { week: string; cells: { date: string; inMonth: boolean }[] }[] = [];
    for (let i = 0; i < grid.length; i += 7) {
      const cells = grid.slice(i, i + 7);
      rows.push({ week: isoWeekKey(parseIso(cells[0].date)), cells });
    }
    return rows;
  }, [grid]);

  // 月历打点：各日记录数
  useEffect(() => {
    setError("");
    setCounts({});
    const from = grid[0].date;
    const to = grid[grid.length - 1].date;
    api
      .get<DateCount[]>(`/api/v1/vaults/${vaultId}/records/dates?from=${from}&to=${to}`)
      .then((list) => {
        const map: Record<string, number> = {};
        for (const item of list) map[item.date] = item.count;
        setCounts(map);
        // 「回到今天」翻月后，今天确实有记录就把当天打开
        if (pendingToday) {
          setPendingToday(false);
          if ((map[today] ?? 0) > 0) {
            setSelectedDate(today);
            setSelectedWeek(null);
          }
        }
      })
      .catch((err) => setError(err instanceof Error ? err.message : "加载日历失败"));
  }, [vaultId, ym, grid, pendingToday, today]);

  // 周记列打标记：只拿周标识与路径，正文不在这里下载
  useEffect(() => {
    setWeeklyPaths({});
    api
      .get<WeeklyRecord[]>(`/api/v1/vaults/${vaultId}/records/weekly`)
      .then((list) => {
        const map: Record<string, string> = {};
        for (const item of list) map[item.week] = item.path;
        setWeeklyPaths(map);
      })
      .catch((err) => setError(err instanceof Error ? err.message : "加载周记失败"));
  }, [vaultId]);

  // 选中日期的记录
  useEffect(() => {
    setRecords([]);
    setSelectedRecord(null);
    if (!selectedDate) return;
    api
      .get<DailyRecord[]>(`/api/v1/vaults/${vaultId}/records?date=${selectedDate}`)
      .then((list) => {
        setRecords(list);
        setSelectedRecord(list[0] ?? null);
      })
      .catch((err) => setError(err instanceof Error ? err.message : "加载日记失败"));
  }, [vaultId, selectedDate]);

  // 选中周的周记正文
  useEffect(() => {
    setWeeklyRecord(null);
    if (!selectedWeek) return;
    const path = weeklyPaths[selectedWeek];
    if (!path) return;
    api
      .get<DailyRecord>(`/api/v1/vaults/${vaultId}/records/file?path=${encodeURIComponent(path)}`)
      .then(setWeeklyRecord)
      .catch((err) => setError(err instanceof Error ? err.message : "加载周记失败"));
  }, [vaultId, selectedWeek, weeklyPaths]);

  /** 年月面板：点面板外或按 Esc 收起 */
  useEffect(() => {
    if (!pickerOpen) return;
    const onPointerDown = (e: PointerEvent) => {
      if (!pickerRef.current?.contains(e.target as Node)) setPickerOpen(false);
    };
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") setPickerOpen(false);
    };
    document.addEventListener("pointerdown", onPointerDown);
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("pointerdown", onPointerDown);
      document.removeEventListener("keydown", onKeyDown);
    };
  }, [pickerOpen]);

  /** 日期与周互斥：当前只看其中一种 */
  const pickDate = (date: string) => {
    setSelectedDate(date);
    setSelectedWeek(null);
  };

  const pickWeek = (week: string) => {
    setSelectedWeek((cur) => (cur === week ? null : week));
    setSelectedDate(null);
  };

  /**
   * 点格子：本月有日记 → 看那天；相邻月 → 先翻到那个月，
   * 那天正好有日记就顺手打开（否则只翻月，不留下选中态）。
   */
  const pickCell = (cell: { date: string; inMonth: boolean }, count: number) => {
    if (!cell.inMonth) {
      const next = monthOf(cell.date);
      setYm(next);
      setPickerOpen(false);
      if (count > 0) pickDate(cell.date);
      else {
        setSelectedDate(null);
        setSelectedWeek(null);
      }
      return;
    }
    if (count > 0) pickDate(cell.date);
  };

  /** 回到今天：翻到本月，并在记录数回来之后自动打开今天 */
  const goToday = () => {
    setYm(thisMonth);
    setPickerOpen(false);
    setPendingToday(true);
  };

  const jumpMonth = (delta: number) => {
    setYm((cur) => shiftMonth(cur, delta));
    setPickerOpen(false);
  };

  // 移动端横滑切月；纵向位移更大时不动，交给页面滚动
  const onTouchStart = (e: React.TouchEvent) => {
    const t = e.touches[0];
    touchStart.current = { x: t.clientX, y: t.clientY };
  };

  const onTouchEnd = (e: React.TouchEvent) => {
    const start = touchStart.current;
    touchStart.current = null;
    if (!start) return;
    const t = e.changedTouches[0];
    const dx = t.clientX - start.x;
    const dy = t.clientY - start.y;
    if (Math.abs(dx) < SWIPE_THRESHOLD || Math.abs(dx) < Math.abs(dy) * 1.5) return;
    jumpMonth(dx < 0 ? 1 : -1); // 往左滑 = 看下个月
  };

  return (
    <div className="panel">
      <div className="panel-head">
        <h2>日记浏览</h2>
        <PanelHelp>
          点有角标的日期看当天日记，点最左侧的周号看该周周记；相邻月的灰色日期可直接点击翻月。
          内容只读，编辑请在 Obsidian 中进行。
        </PanelHelp>
      </div>
      {error && <div className="form-error">{error}</div>}
      <div className="records-layout">
        <div className="calendar" onTouchStart={onTouchStart} onTouchEnd={onTouchEnd}>
          <div className="cal-nav">
            <button className="cal-nav-btn" onClick={() => jumpMonth(-1)} aria-label="上个月">
              ‹
            </button>
            <div className="cal-nav-center" ref={pickerRef}>
              <button
                type="button"
                className="cal-title"
                aria-expanded={pickerOpen}
                title="选择年月"
                onClick={() => {
                  setPickerYear(ym.year);
                  setPickerOpen((v) => !v);
                }}
              >
                {ym.year} 年 {ym.month} 月
                <span className="cal-caret">{pickerOpen ? "▴" : "▾"}</span>
              </button>
              {pickerOpen && (
                <div className="cal-picker">
                  <div className="cal-picker-head">
                    <button onClick={() => setPickerYear((y) => y - 1)} aria-label="上一年">
                      ‹
                    </button>
                    <span>{pickerYear} 年</span>
                    <button onClick={() => setPickerYear((y) => y + 1)} aria-label="下一年">
                      ›
                    </button>
                  </div>
                  <div className="cal-picker-months">
                    {MONTHS.map((m) => {
                      const cls = [
                        "cal-picker-month",
                        pickerYear === ym.year && m === ym.month ? "current" : "",
                        pickerYear === thisMonth.year && m === thisMonth.month ? "is-today" : "",
                      ]
                        .filter(Boolean)
                        .join(" ");
                      return (
                        <button
                          key={m}
                          className={cls}
                          onClick={() => {
                            setYm({ year: pickerYear, month: m });
                            setPickerOpen(false);
                          }}
                        >
                          {m}
                        </button>
                      );
                    })}
                  </div>
                </div>
              )}
            </div>
            {!isThisMonth && (
              <button className="cal-today-btn" onClick={goToday}>
                回到今天
              </button>
            )}
            <button className="cal-nav-btn" onClick={() => jumpMonth(1)} aria-label="下个月">
              ›
            </button>
          </div>
          <div className="cal-grid cal-weekdays">
            <span className="cal-week-head">周</span>
            {WEEKDAYS.map((w) => (
              <span key={w}>{w}</span>
            ))}
          </div>
          {weeks.map(({ week, cells }) => (
            <div
              className={weeklyPaths[week] ? "cal-grid cal-week-has" : "cal-grid"}
              key={week}
            >
              <button
                className={selectedWeek === week ? "cal-week-cell active" : "cal-week-cell"}
                onClick={() => pickWeek(week)}
                title={weeklyPaths[week] ? `${week} 周记` : `${week}（还没有周记）`}
              >
                {Number(week.slice(6))}
                {weeklyPaths[week] && <span className="cal-week-mark" />}
              </button>
              {cells.map((cell) => {
                const count = counts[cell.date] ?? 0;
                const cls = [
                  "cal-cell",
                  cell.inMonth ? "" : "out",
                  cell.date === today ? "today" : "",
                  cell.date === selectedDate ? "selected" : "",
                  count > 0 ? "has" : "",
                ]
                  .filter(Boolean)
                  .join(" ");
                return (
                  <button
                    key={cell.date}
                    className={cls}
                    onClick={() => pickCell(cell, count)}
                    // 相邻月的格子即使没有日记也能点（用来翻月）
                    disabled={cell.inMonth && count === 0}
                    title={
                      count > 0
                        ? `${cell.date} · ${count} 篇`
                        : cell.inMonth
                          ? undefined
                          : `跳到 ${monthOf(cell.date).year} 年 ${monthOf(cell.date).month} 月`
                    }
                  >
                    <span className="cal-day">{Number(cell.date.slice(8))}</span>
                    {count > 0 && <span className="cal-badge">{countLabel(count)}</span>}
                  </button>
                );
              })}
            </div>
          ))}
          <div className="cal-legend">
            <span className="cal-legend-item">
              <span className="cal-badge cal-legend-badge">3</span>
              有日记（数字＝篇数）
            </span>
            <span className="cal-legend-item">
              <span className="cal-week-mark" />
              有周记
            </span>
          </div>
        </div>
        <div className="records-main">
          {selectedWeek ? (
            weeklyRecord ? (
              <div className="record-view" key={weeklyRecord.id}>
                <div className="record-meta">
                  {weeklyRecord.path} · 更新于 {new Date(weeklyRecord.updatedAt).toLocaleString()}
                </div>
                <div className="record-content">
                  <MarkdownView content={weeklyRecord.content} />
                </div>
              </div>
            ) : weeklyPaths[selectedWeek] ? (
              <div className="empty">加载中…</div>
            ) : (
              <div className="empty">{selectedWeek} 还没有周记</div>
            )
          ) : selectedDate ? (
            records.length > 0 ? (
              <>
                <div className="record-chips">
                  {records.map((r) => (
                    <button
                      key={r.id}
                      className={selectedRecord?.id === r.id ? "chip chip-active" : "chip"}
                      onClick={() => setSelectedRecord(r)}
                    >
                      {r.path.split("/").pop()?.replace(/\.md$/, "") ?? r.path}
                    </button>
                  ))}
                </div>
                {selectedRecord && (
                  <div className="record-view" key={selectedRecord.id}>
                    <div className="record-meta">
                      {selectedRecord.path} · 更新于 {new Date(selectedRecord.updatedAt).toLocaleString()}
                    </div>
                    <div className="record-content">
                      <MarkdownView content={selectedRecord.content} />
                    </div>
                  </div>
                )}
              </>
            ) : (
              <div className="empty">该日期没有日记</div>
            )
          ) : (
            <div className="empty">选择日期或周号查看内容</div>
          )}
        </div>
      </div>
    </div>
  );
}

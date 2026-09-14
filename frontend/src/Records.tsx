import { Fragment, useEffect, useMemo, useRef, useState } from "react";
import { api } from "./api";
import MarkdownView from "./MarkdownView";
import PanelHelp from "./PanelHelp";
import type { DailyRecord, DateCount, WeeklyRecord } from "./types";

/**
 * 日记浏览：月历打点（各日记录数）→ 点日期看当天记录 → 看内容（Markdown 渲染）。
 * 数据来自插件同步上来的 daily_records（record_date 按文件名首段解析）。
 *
 * 版式刻意与 Obsidian 插件（quick-daily-note）的日历面板对齐：
 * - 一个 grid 装下 6 周（不是每周一个 grid），周号列与日期之间有一条贯穿的竖线
 * - 每格纵向排列：日期在上、记录圆点在下；圆点占位恒定，行高不会忽高忽低
 * - 今天＝主题色加粗文字（不铺底色）；选中＝实心主题色；相邻月＝淡灰
 * - 周记列显示 ISO 周号，有周记时同样打一个圆点
 * - 日历下方一行统计：本月 N 天 / 连续 N 天 / 今日 N 字
 * 比插件多的两件事：篇数放在 title 里（悬停可见），以及年月快跳、横滑切月、
 * 「回到今天」这些导航能力——插件里没有对应交互，不算视觉差异。
 */
interface YearMonth {
  year: number;
  month: number; // 1-12
}

const WEEKDAYS = ["一", "二", "三", "四", "五", "六", "日"];
const MONTHS = Array.from({ length: 12 }, (_, i) => i + 1);
/** 横滑判定：位移超过这个像素才算切月，避免和纵向滚动/点按冲突 */
const SWIPE_THRESHOLD = 50;
/** 连续天数的回溯上限（插件里是 730，这里取一年足够，少拉点数据） */
const STREAK_DAYS = 366;

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

/** ISO 周标识 → 该周周一的本地日期（isoWeekKey 的反函数，用来定位到那一周所在的月份） */
function isoWeekMonday(week: string): Date {
  const [year, weekNo] = week.split("-W").map(Number);
  const jan4 = new Date(year, 0, 4); // 含 1 月 4 日的那一周就是第 1 周
  const monday = new Date(jan4.getFullYear(), jan4.getMonth(), jan4.getDate() - ((jan4.getDay() + 6) % 7));
  monday.setDate(monday.getDate() + (weekNo - 1) * 7);
  return monday;
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
  /** 日历下方统计：连续天数 / 今日字数（与插件那行一致） */
  const [streak, setStreak] = useState<number | null>(null);
  const [todayChars, setTodayChars] = useState<number | null>(null);
  /** 文件列表：宽屏默认展开，窄屏默认收起（免得把正文顶到很下面） */
  const [filesOpen, setFilesOpen] = useState(() => window.innerWidth > 860);
  /** 文件列表的过滤词（日记按日期、周记按周号匹配） */
  const [fileFilter, setFileFilter] = useState("");
  /** 全部有日记的日期（不限当前月），供文件列表用 */
  const [allDiaries, setAllDiaries] = useState<DateCount[]>([]);

  const grid = useMemo(() => monthGrid(ym), [ym]);
  const isThisMonth = ym.year === thisMonth.year && ym.month === thisMonth.month;

  /** 42 格按周切成 6 行，行首带上该行的 ISO 周标识 */
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

  // 统计行：连续天数（往回数一年）与今日字数
  useEffect(() => {
    setStreak(null);
    setTodayChars(null);
    const cursor = new Date();
    const from = iso(
      new Date(cursor.getFullYear(), cursor.getMonth(), cursor.getDate() - STREAK_DAYS + 1).getFullYear(),
      new Date(cursor.getFullYear(), cursor.getMonth(), cursor.getDate() - STREAK_DAYS + 1).getMonth() + 1,
      new Date(cursor.getFullYear(), cursor.getMonth(), cursor.getDate() - STREAK_DAYS + 1).getDate(),
    );
    api
      .get<DateCount[]>(`/api/v1/vaults/${vaultId}/records/dates?from=${from}&to=${today}`)
      .then((list) => {
        const days = new Set(list.map((item) => item.date));
        let n = 0;
        const d = new Date();
        while (n < STREAK_DAYS) {
          if (!days.has(iso(d.getFullYear(), d.getMonth() + 1, d.getDate()))) break;
          n++;
          d.setDate(d.getDate() - 1);
        }
        setStreak(n);
      })
      .catch(() => setStreak(null));

    api
      .get<DailyRecord[]>(`/api/v1/vaults/${vaultId}/records?date=${today}`)
      .then((list) =>
        // 与插件一致：按「去掉空白后的字符数」统计
        setTodayChars(list.reduce((n, r) => n + r.content.replace(/\s+/g, "").length, 0)),
      )
      .catch(() => setTodayChars(null));
  }, [vaultId, today]);

  // 文件列表：列出全部有日记的日期（一次拉全量，后端按 record_date 分组很便宜）
  useEffect(() => {
    setAllDiaries([]);
    const future = new Date();
    future.setFullYear(future.getFullYear() + 1); // 预留给未来日期的日记
    const to = iso(future.getFullYear(), future.getMonth() + 1, future.getDate());
    api
      .get<DateCount[]>(`/api/v1/vaults/${vaultId}/records/dates?from=2000-01-01&to=${to}`)
      .then((list) => setAllDiaries(list.slice().sort((a, b) => (a.date < b.date ? 1 : -1))))
      .catch(() => setAllDiaries([]));
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

  /** 本月写日记的天数：counts 已覆盖整月，直接数 */
  const monthDays = useMemo(
    () => Object.keys(counts).filter((d) => monthOf(d).year === ym.year && monthOf(d).month === ym.month).length,
    [counts, ym],
  );

  /** 文件列表：周记按周号倒序 */
  const weeklyList = useMemo(() => Object.keys(weeklyPaths).sort().reverse(), [weeklyPaths]);
  const keyword = fileFilter.trim().toLowerCase();
  const diaryEntries = useMemo(
    () => (keyword ? allDiaries.filter((d) => d.date.toLowerCase().includes(keyword)) : allDiaries),
    [allDiaries, keyword],
  );
  const weekEntries = useMemo(
    () => (keyword ? weeklyList.filter((w) => w.toLowerCase().includes(keyword)) : weeklyList),
    [weeklyList, keyword],
  );

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
   * 点格子：本月有记录 → 看那天；相邻月 → 先翻到那个月，
   * 那天正好有记录就顺手打开（否则只翻月，不留下选中态）。
   */
  const pickCell = (cell: { date: string; inMonth: boolean }, count: number) => {
    if (!cell.inMonth) {
      setYm(monthOf(cell.date));
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

  /** 从文件列表打开某天：日历翻到那个月，并选中那一天 */
  const openFromList = (date: string) => {
    setYm(monthOf(date));
    setPickerOpen(false);
    pickDate(date);
  };

  /** 从文件列表打开某周：翻到该周所在的月份并按周查看（不走 pickWeek 的再次点击取消） */
  const openWeekFromList = (week: string) => {
    const monday = isoWeekMonday(week);
    setYm(monthOf(iso(monday.getFullYear(), monday.getMonth() + 1, monday.getDate())));
    setPickerOpen(false);
    setSelectedWeek(week);
    setSelectedDate(null);
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
          圆点表示那天有日记，周号列下的圆点表示该周有周记；点日期看当天日记，点周号看该周周记。
          相邻月的灰色日期可直接点击翻月。内容只读，编辑请在 Obsidian 中进行。
        </PanelHelp>
      </div>
      {error && <div className="form-error">{error}</div>}
      <div className="records-layout">
        <div className="records-side">
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

          {/* 整个月用一个 grid：周号列与日期之间那条竖线才能贯穿 6 周不断开 */}
          <div className="cal-grid">
            {/* 插件这里写的是 "W"，你之前提过首字母对中文用户不直观，保留「周」 */}
            <div className="cal-cell cal-weekday cal-week-head">周</div>
            {WEEKDAYS.map((w) => (
              <div className="cal-cell cal-weekday" key={w}>
                {w}
              </div>
            ))}
            {weeks.map(({ week, cells }) => (
              <Fragment key={week}>
                <button
                  className={selectedWeek === week ? "cal-week-cell active" : "cal-week-cell"}
                  onClick={() => pickWeek(week)}
                  title={weeklyPaths[week] ? `${week} 周记` : `${week}（还没有周记）`}
                >
                  {Number(week.slice(6))}
                  <span className={weeklyPaths[week] ? "cal-dot" : "cal-dot cal-dot-off"} />
                </button>
                {cells.map((cell) => {
                  const count = counts[cell.date] ?? 0;
                  const cls = [
                    "cal-cell",
                    cell.inMonth ? "" : "cal-outside",
                    cell.date === today ? "cal-today" : "",
                    cell.date === selectedDate ? "cal-selected" : "",
                  ]
                    .filter(Boolean)
                    .join(" ");
                  return (
                    <button
                      key={cell.date}
                      className={cls}
                      onClick={() => pickCell(cell, count)}
                      // 相邻月的格子即使没有记录也能点（用来翻月）
                      disabled={cell.inMonth && count === 0}
                      title={
                        count > 0
                          ? `${cell.date} · ${count} 篇`
                          : cell.inMonth
                            ? undefined
                            : `跳到 ${monthOf(cell.date).year} 年 ${monthOf(cell.date).month} 月`
                      }
                    >
                      {Number(cell.date.slice(8))}
                      {/* 圆点常驻占位，行高不随有无记录变化 */}
                      <span className={count > 0 ? "cal-dot" : "cal-dot cal-dot-off"} />
                    </button>
                  );
                })}
              </Fragment>
            ))}
          </div>

          <div className="cal-stats">
            <span>本月 {monthDays} 天</span>
            <span>连续 {streak ?? "—"} 天</span>
            <span>{todayChars === null ? "今日 …" : todayChars > 0 ? `今日 ${todayChars} 字` : "今日未写"}</span>
          </div>
          </div>

          {/* 文件列表：全部日记与周记，点一条即看内容（类似 Obsidian 左侧栏） */}
          <div className="file-list">
            <div className="file-list-head">
              <button
                type="button"
                className="file-list-toggle"
                aria-expanded={filesOpen}
                onClick={() => setFilesOpen((v) => !v)}
              >
                <span className="file-list-arrow">{filesOpen ? "▾" : "▸"}</span>
                文件列表
              </button>
              <span className="file-list-count">
                {allDiaries.length} 日记 · {weeklyList.length} 周记
              </span>
            </div>
            {filesOpen && (
              <>
                <input
                  className="file-list-filter"
                  value={fileFilter}
                  placeholder="筛选，如 2026-09 或 W38"
                  onChange={(e) => setFileFilter(e.target.value)}
                />
                <div className="file-list-body">
                  <div className="file-list-section">
                    日记 <span>{diaryEntries.length}</span>
                  </div>
                  {diaryEntries.map((d) => (
                    <button
                      key={d.date}
                      className={selectedDate === d.date ? "file-item active" : "file-item"}
                      onClick={() => openFromList(d.date)}
                      title={`${d.date} · ${d.count} 篇`}
                    >
                      <span className="file-item-name">{d.date}</span>
                      {d.count > 1 && <span className="file-item-count">{d.count}</span>}
                    </button>
                  ))}
                  {diaryEntries.length === 0 && <div className="file-list-empty">没有匹配的日记</div>}

                  <div className="file-list-section">
                    周记 <span>{weekEntries.length}</span>
                  </div>
                  {weekEntries.map((w) => (
                    <button
                      key={w}
                      className={selectedWeek === w ? "file-item active" : "file-item"}
                      onClick={() => openWeekFromList(w)}
                      title={`${w} 周记`}
                    >
                      <span className="file-item-name">{w}</span>
                    </button>
                  ))}
                  {weekEntries.length === 0 && <div className="file-list-empty">没有匹配的周记</div>}
                </div>
              </>
            )}
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

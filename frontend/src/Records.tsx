import { useEffect, useMemo, useState } from "react";
import { api } from "./api";
import MarkdownView from "./MarkdownView";
import type { DailyRecord, DateCount, WeeklyRecord } from "./types";

/**
 * 日记浏览：月历打点（各日记录数）→ 点日期看当天记录 → 看内容（Markdown 渲染）。
 * 数据来自插件同步上来的 daily_records（record_date 按文件名首段解析）。
 *
 * 日历第一列是「周记列」（与插件的 W 列一致）：每行一格代表该行所在的 ISO 周，
 * 该周有周记就打一个小圆点，点击在右侧看全文。周记没有 record_date，
 * 进不了按日查询，因此走单独的 /records/weekly（取周→路径）与
 * /records/file（按路径取正文）两个接口。
 */

interface YearMonth {
  year: number;
  month: number; // 1-12
}

const WEEKDAYS = ["一", "二", "三", "四", "五", "六", "日"];

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

export default function Records({ vaultId }: { vaultId: number }) {
  const [ym, setYm] = useState<YearMonth>(() => {
    const now = new Date();
    return { year: now.getFullYear(), month: now.getMonth() + 1 };
  });
  const [counts, setCounts] = useState<Record<string, number>>({});
  const [selectedDate, setSelectedDate] = useState<string | null>(null);
  const [records, setRecords] = useState<DailyRecord[]>([]);
  const [selectedRecord, setSelectedRecord] = useState<DailyRecord | null>(null);
  /** 周标识 -> 周记路径，用于周记列打点；正文等点开再取 */
  const [weeklyPaths, setWeeklyPaths] = useState<Record<string, string>>({});
  const [selectedWeek, setSelectedWeek] = useState<string | null>(null);
  const [weeklyRecord, setWeeklyRecord] = useState<DailyRecord | null>(null);
  const [error, setError] = useState("");

  const grid = useMemo(() => monthGrid(ym), [ym]);
  const today = todayIso();

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
      })
      .catch((err) => setError(err instanceof Error ? err.message : "加载日历失败"));
  }, [vaultId, ym, grid]);

  // 周记列打点：只拿周标识与路径，正文不在这里下载
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

  /** 日期与周互斥：当前只看其中一种 */
  const pickDate = (date: string) => {
    setSelectedDate(date);
    setSelectedWeek(null);
  };

  const pickWeek = (week: string) => {
    setSelectedWeek((cur) => (cur === week ? null : week));
    setSelectedDate(null);
  };

  return (
    <div className="panel">
      <div className="panel-head">
        <h2>日记浏览</h2>
        <span className="panel-hint">
          有记号的日期可点击查看；最左侧每行一格是周记，有周记的周会打点。内容只读，编辑请在 Obsidian 中进行
        </span>
      </div>
      {error && <div className="form-error">{error}</div>}
      <div className="records-layout">
        <div className="calendar">
          <div className="cal-nav">
            <button onClick={() => setYm(shiftMonth(ym, -1))}>‹</button>
            <span className="cal-title">
              {ym.year} 年 {ym.month} 月
            </span>
            <button onClick={() => setYm(shiftMonth(ym, 1))}>›</button>
          </div>
          <div className="cal-grid cal-weekdays">
            <span className="cal-week-head">W</span>
            {WEEKDAYS.map((w) => (
              <span key={w}>{w}</span>
            ))}
          </div>
          {weeks.map(({ week, cells }) => (
            <div className="cal-grid" key={week}>
              <button
                className={selectedWeek === week ? "cal-week-cell active" : "cal-week-cell"}
                onClick={() => pickWeek(week)}
                title={weeklyPaths[week] ? `${week} 周记` : `${week}（还没有周记）`}
              >
                {Number(week.slice(6))}
                {weeklyPaths[week] && <span className="cal-week-dot" />}
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
                    onClick={() => count > 0 && pickDate(cell.date)}
                    disabled={count === 0}
                    title={count > 0 ? `${count} 篇` : undefined}
                  >
                    <span className="cal-day">{Number(cell.date.slice(8))}</span>
                    {count > 0 && <span className="cal-badge">{count}</span>}
                  </button>
                );
              })}
            </div>
          ))}
        </div>
        <div className="records-main">
          {selectedWeek ? (
            weeklyRecord ? (
              <div className="record-view">
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
                  <div className="record-view">
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
            <div className="empty">点击日历上有记号的日期查看日记，或最左侧的周号查看周记</div>
          )}
        </div>
      </div>
    </div>
  );
}

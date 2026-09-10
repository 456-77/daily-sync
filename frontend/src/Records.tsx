import { useEffect, useMemo, useState } from "react";
import ReactMarkdown from "react-markdown";
import { api } from "./api";
import type { DailyRecord, DateCount } from "./types";

/**
 * 日记浏览：月历打点（各日记录数）→ 点日期看当天记录 → 看内容（Markdown 渲染）。
 * 数据来自插件同步上来的 daily_records（record_date 按文件名首段解析）。
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
  const [error, setError] = useState("");

  const grid = useMemo(() => monthGrid(ym), [ym]);
  const today = todayIso();

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

  return (
    <div className="panel">
      <div className="panel-head">
        <h2>日记浏览</h2>
        <span className="panel-hint">有记号的日期可点击查看；内容为只读，编辑请在 Obsidian 中进行</span>
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
            {WEEKDAYS.map((w) => (
              <span key={w}>{w}</span>
            ))}
          </div>
          <div className="cal-grid">
            {grid.map((cell) => {
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
                  onClick={() => count > 0 && setSelectedDate(cell.date)}
                  disabled={count === 0}
                  title={count > 0 ? `${count} 篇` : undefined}
                >
                  <span className="cal-day">{Number(cell.date.slice(8))}</span>
                  {count > 0 && <span className="cal-badge">{count}</span>}
                </button>
              );
            })}
          </div>
        </div>
        <div className="records-main">
          {selectedDate ? (
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
                      <ReactMarkdown>{selectedRecord.content}</ReactMarkdown>
                    </div>
                  </div>
                )}
              </>
            ) : (
              <div className="empty">该日期没有日记</div>
            )
          ) : (
            <div className="empty">点击左侧日历上有记号的日期查看日记</div>
          )}
        </div>
      </div>
    </div>
  );
}

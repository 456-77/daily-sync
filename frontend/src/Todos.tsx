import { useEffect, useState } from "react";
import { ApiError, api } from "./api";
import type { DailyRecord } from "./types";

/** 插件导出的待办数据文件（库根固定路径） */
const TODO_PATH = "daily-sync-todos.json";

/** 与插件里的 TodoItem 结构一致 */
interface TodoItem {
  text: string;
  done: boolean;
}

interface TodoPayload {
  version: number;
  updatedAt: string;
  /** 日期（YYYY-MM-DD）-> 当天待办 */
  todos: Record<string, TodoItem[]>;
}

/**
 * 待办事项（只读）：读插件同步上来的 daily-sync-todos.json。
 *
 * 插件侧只导出待办本身——库内的 quick-daily-note.json 还混着 emailAccessKey
 * 等配置，整份上传会把凭据送到服务端，所以那边单独导出了一份精简数据。
 * 结构就是插件的 settings.todos：日期 -> 条目数组。
 */
export default function Todos({ vaultId }: { vaultId: number }) {
  const [payload, setPayload] = useState<TodoPayload | null>(null);
  const [missing, setMissing] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  useEffect(() => {
    setLoading(true);
    setError("");
    setMissing(false);
    setPayload(null);
    api
      .get<DailyRecord>(`/api/v1/vaults/${vaultId}/records/file?path=${encodeURIComponent(TODO_PATH)}`)
      .then((record) => {
        try {
          setPayload(JSON.parse(record.content) as TodoPayload);
        } catch {
          setError("待办数据格式无法解析");
        }
      })
      .catch((err) => {
        // 404 表示这个文件还没同步上来（插件没升级，或升级后还没同步过）
        if (err instanceof ApiError && err.code === 404) setMissing(true);
        else setError(err instanceof Error ? err.message : "加载失败");
      })
      .finally(() => setLoading(false));
  }, [vaultId]);

  const dates = payload
    ? Object.keys(payload.todos)
        .filter((d) => payload.todos[d].length > 0)
        .sort()
        .reverse()
    : [];

  return (
    <div className="panel">
      <div className="panel-head">
        <h2>待办事项</h2>
        <span className="panel-hint">
          来自 Obsidian 插件的待办数据，随同步更新；这里只读，增删改请在 Obsidian 中进行
        </span>
      </div>
      {error && <div className="form-error">{error}</div>}
      {loading && <div className="empty">加载中…</div>}
      {!loading && missing && (
        <div className="empty">
          还没有待办数据——需要插件升级到 2.7.0 并在 Obsidian 里同步一次
        </div>
      )}
      {!loading && !missing && payload && dates.length === 0 && <div className="empty">还没有任何待办</div>}
      {!loading && dates.length > 0 && (
        <div className="todo-days">
          {dates.map((date) => {
            const items = payload!.todos[date];
            const doneCount = items.filter((i) => i.done).length;
            return (
              <div className="todo-day" key={date}>
                <div className="todo-day-head">
                  <span className="todo-date">{date}</span>
                  <span className="todo-progress">
                    {doneCount}/{items.length} 已完成
                  </span>
                </div>
                <ul className="todo-list">
                  {items.map((item, index) => (
                    <li key={index} className={item.done ? "todo-item done" : "todo-item"}>
                      <span className={item.done ? "todo-box checked" : "todo-box"} aria-hidden="true" />
                      <span className="todo-text">{item.text}</span>
                    </li>
                  ))}
                </ul>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

import { useEffect, useMemo, useState } from "react";
import { ApiError, api } from "./api";
import type { DailyRecord, TodoItem, TodoSnapshot } from "./types";

/** 待办数据在云端的固定路径（与插件 TODO_SYNC_PATH 一致） */
const TODO_PATH = "daily-sync-todos.json";
/** 快照格式版本，与插件 todos.ts 对齐 */
const SNAPSHOT_VERSION = 2;

/** 顺延前缀，与插件的 `[MM-DD 遗留]` 格式一致 */
const CARRY_PREFIX_RE = /^(?:\[[^\]]*遗留\]\s*)+/;

function todayIso(): string {
  const now = new Date();
  const m = String(now.getMonth() + 1).padStart(2, "0");
  const d = String(now.getDate()).padStart(2, "0");
  return `${now.getFullYear()}-${m}-${d}`;
}

function newId(): string {
  const c = globalThis.crypto as Crypto | undefined;
  if (c && typeof c.randomUUID === "function") return c.randomUUID();
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

/**
 * 待办事项：增 / 改 / 勾选 / 删 / 顺延，改动整份提交到云端。
 *
 * 提交的是「读到的快照 + 本次改动」，**前端不做合并**——条目级合并统一在
 * 插件侧实现（按 id 取更新的那一侧）。万一提交时漏掉了别的设备的并发改动，
 * 插件下次同步会把它合并回来并推回云端。
 */
export default function Todos({ vaultId }: { vaultId: number }) {
  const [snapshot, setSnapshot] = useState<TodoSnapshot | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [missing, setMissing] = useState(false);
  /** 云端还是旧格式（无 id），无法按条目编辑；等插件同步一次会自动升级 */
  const [legacy, setLegacy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  // 编辑与新增
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editText, setEditText] = useState("");
  const [newText, setNewText] = useState("");
  const [newDate, setNewDate] = useState(todayIso);

  // 筛选与折叠
  const [onlyPending, setOnlyPending] = useState(false);
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());

  useEffect(() => {
    setLoading(true);
    setError("");
    setMissing(false);
    setSnapshot(null);
    api
      .get<DailyRecord>(`/api/v1/vaults/${vaultId}/records/file?path=${encodeURIComponent(TODO_PATH)}`)
      .then((record) => {
        const parsed = JSON.parse(record.content) as TodoSnapshot;
        if (!parsed || !parsed.todos) {
          setError("待办数据格式无法识别");
          return;
        }
        setSnapshot(parsed);
        setLegacy(parsed.version !== SNAPSHOT_VERSION);
      })
      .catch((err) => {
        // 404 = 这个文件还没同步上来（插件没升级，或升级后还没同步过）
        if (err instanceof ApiError && err.code === 404) setMissing(true);
        else setError(err instanceof Error ? err.message : "加载失败");
      })
      .finally(() => setLoading(false));
  }, [vaultId]);

  const flash = (text: string) => {
    setNotice(text);
    window.setTimeout(() => setNotice(""), 2500);
  };

  /** 把改动后的 todos 整份提交；成功后就地更新本地快照 */
  const persist = async (todos: Record<string, TodoItem[]>, message: string) => {
    setSaving(true);
    setError("");
    try {
      const payload: TodoSnapshot = {
        version: SNAPSHOT_VERSION,
        updatedAt: new Date().toISOString(),
        todos,
      };
      await api.post(`/api/v1/vaults/${vaultId}/records/todos`, payload);
      setSnapshot(payload);
      flash(message);
    } catch (err) {
      setError(err instanceof Error ? err.message : "保存失败");
    } finally {
      setSaving(false);
    }
  };

  /** 复制一份 todos 应用改动后提交（避免直接改 state 里的对象） */
  const mutate = (fn: (todos: Record<string, TodoItem[]>) => void, message: string) => {
    if (!snapshot || saving || legacy) return;
    const draft: Record<string, TodoItem[]> = JSON.parse(JSON.stringify(snapshot.todos));
    fn(draft);
    void persist(draft, message);
  };

  const findItem = (todos: Record<string, TodoItem[]>, date: string, id: string) =>
    (todos[date] ?? []).find((item) => item.id === id);

  const addTodo = () => {
    const text = newText.trim();
    if (!text || !newDate) return;
    mutate((todos) => {
      (todos[newDate] ??= []).push({
        id: newId(),
        text,
        done: false,
        updatedAt: Date.now(),
      });
    }, "已添加");
    setNewText("");
  };

  const toggleTodo = (date: string, id: string) =>
    mutate((todos) => {
      const item = findItem(todos, date, id);
      if (!item) return;
      item.done = !item.done;
      item.updatedAt = Date.now();
    }, "已更新");

  const saveEdit = (date: string, id: string) => {
    const text = editText.trim();
    setEditingId(null);
    if (!text) return;
    mutate((todos) => {
      const item = findItem(todos, date, id);
      if (!item || item.text === text) return;
      item.text = text;
      item.updatedAt = Date.now();
    }, "已修改");
  };

  const removeTodo = (date: string, id: string, text: string) => {
    if (!window.confirm(`删除「${text}」？`)) return;
    // 打墓碑而不是移除：另一侧设备合并时看不到这条会把它当新增复活
    mutate((todos) => {
      const item = findItem(todos, date, id);
      if (!item) return;
      item.deleted = true;
      item.updatedAt = Date.now();
    }, "已删除");
  };

  /** 把该日未完成的待办顺延到目标日（默认今天；已经就是今天则顺延到明天） */
  const carryOver = (date: string) => {
    const today = todayIso();
    const target = date === today ? tomorrowIso() : today;
    const pending = (snapshot?.todos[date] ?? []).filter((i) => !i.done && !i.deleted);
    if (pending.length === 0) {
      setError("该日期没有未完成的待办");
      return;
    }
    if (!window.confirm(`把 ${pending.length} 项未完成待办顺延到 ${target}？`)) return;
    mutate((todos) => {
      const now = Date.now();
      const prefix = `[${date.slice(5)} 遗留]`;
      // 注意必须遍历 draft 里的条目（不是闭包里 snapshot 的），
      // 否则标记的墓碑落在没被提交的那份数据上，源日不会真正清空
      const source = todos[date] ?? [];
      const dest = (todos[target] ??= []);
      for (const item of source) {
        if (item.done || item.deleted) continue;
        // 源日打墓碑 + 目标日建新条目：同一个 id 不能同时出现在两天，否则合并会错乱
        item.deleted = true;
        item.updatedAt = now;
        dest.push({
          id: newId(),
          text: `${prefix} ${item.text.replace(CARRY_PREFIX_RE, "")}`,
          done: false,
          updatedAt: now,
        });
      }
    }, `已顺延到 ${target}`);
  };

  /** 处理后的日期列表：日期倒序，过滤墓碑与（可选的）已完成条目 */
  const days = useMemo(() => {
    const todos = snapshot?.todos ?? {};
    return Object.keys(todos)
      .sort()
      .reverse()
      .map((date) => {
        const all = (todos[date] ?? []).filter((item) => !item.deleted);
        const visible = onlyPending ? all.filter((item) => !item.done) : all;
        const done = all.filter((item) => item.done).length;
        return { date, items: visible, total: all.length, done };
      })
      .filter((day) => day.total > 0 && day.items.length > 0);
  }, [snapshot, onlyPending]);

  const toggleCollapse = (date: string) =>
    setCollapsed((cur) => {
      const next = new Set(cur);
      if (next.has(date)) next.delete(date);
      else next.add(date);
      return next;
    });

  if (loading) {
    return (
      <div className="panel">
        <div className="empty">加载中…</div>
      </div>
    );
  }

  return (
    <div className="panel">
      <div className="panel-head">
        <h2>待办事项</h2>
        <span className="panel-hint">
          改动会同步到 Obsidian；两端同时修改时按条目合并（后改的赢）
        </span>
      </div>

      {error && <div className="form-error">{error}</div>}
      {notice && <div className="admin-flash">{notice}</div>}
      {legacy && (
        <div className="admin-flash">
          待办数据还是旧格式（条目没有 id），暂不能在线编辑——在 Obsidian 里同步一次即可自动升级
        </div>
      )}

      {missing ? (
        <div className="empty">还没有待办数据——需要插件升级到 2.8.0 并在 Obsidian 里同步一次</div>
      ) : (
        <>
          <div className="todo-toolbar">
            <input
              className="todo-new-input"
              value={newText}
              placeholder="添加待办，回车确认"
              disabled={saving}
              onChange={(e) => setNewText(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter" && !e.nativeEvent.isComposing) addTodo();
              }}
            />
            <input
              type="date"
              value={newDate}
              disabled={saving}
              onChange={(e) => setNewDate(e.target.value)}
            />
            <button className="primary" disabled={saving || !newText.trim()} onClick={addTodo}>
              添加
            </button>
            <label className="todo-filter">
              <input
                type="checkbox"
                checked={onlyPending}
                onChange={(e) => setOnlyPending(e.target.checked)}
              />
              只看未完成
            </label>
          </div>

          {days.length === 0 ? (
            <div className="empty">{onlyPending ? "没有未完成的待办" : "还没有待办"}</div>
          ) : (
            <div className="todo-days">
              {days.map(({ date, items, total, done }) => {
                const isCollapsed = collapsed.has(date);
                return (
                  <div className="todo-day" key={date}>
                    <div className="todo-day-head">
                      <button
                        className="todo-collapse"
                        onClick={() => toggleCollapse(date)}
                        aria-expanded={!isCollapsed}
                        title={isCollapsed ? "展开" : "收起"}
                      >
                        {isCollapsed ? "▸" : "▾"}
                      </button>
                      <span className="todo-date">{date}</span>
                      <span className="todo-progress">
                        {done}/{total} 已完成
                      </span>
                      <button
                        className="todo-carryover"
                        disabled={saving}
                        onClick={() => carryOver(date)}
                        title="把该日未完成的待办挪到今天（已是今天则挪到明天）"
                      >
                        顺延未完成
                      </button>
                    </div>
                    {!isCollapsed && (
                      <ul className="todo-list">
                        {items.map((item) => (
                          <li key={item.id} className={item.done ? "todo-item done" : "todo-item"}>
                            <button
                              className={item.done ? "todo-box checked" : "todo-box"}
                              disabled={saving}
                              onClick={() => toggleTodo(date, item.id)}
                              aria-label={item.done ? "标记为未完成" : "标记为已完成"}
                            />
                            {editingId === item.id ? (
                              <input
                                className="todo-edit-input"
                                value={editText}
                                autoFocus
                                onChange={(e) => setEditText(e.target.value)}
                                onKeyDown={(e) => {
                                  if (e.nativeEvent.isComposing) return;
                                  if (e.key === "Enter") saveEdit(date, item.id);
                                  if (e.key === "Escape") setEditingId(null);
                                }}
                                onBlur={() => saveEdit(date, item.id)}
                              />
                            ) : (
                              <span
                                className="todo-text"
                                title="双击编辑"
                                onDoubleClick={() => {
                                  setEditingId(item.id);
                                  setEditText(item.text);
                                }}
                              >
                                {item.text}
                              </span>
                            )}
                            <button
                              className="todo-del"
                              disabled={saving}
                              onClick={() => removeTodo(date, item.id, item.text)}
                              title="删除"
                            >
                              ×
                            </button>
                          </li>
                        ))}
                      </ul>
                    )}
                  </div>
                );
              })}
            </div>
          )}
        </>
      )}
    </div>
  );
}

function tomorrowIso(): string {
  const d = new Date();
  d.setDate(d.getDate() + 1);
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${d.getFullYear()}-${m}-${day}`;
}

import { useEffect, useMemo, useRef, useState } from "react";
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

  // 筛选与折叠
  /**
   * 日期只有一个控件，两件事都由它决定：列表只看这一天，新待办也落在这一天。
   * 默认今天 —— 也就是「默认只显示今天的待办」。
   */
  const [filterDate, setFilterDate] = useState(todayIso);
  /** 取消日期筛选，把其它日期一并列出来（新待办的落点仍是上面的日期） */
  const [allDates, setAllDates] = useState(false);
  const [onlyPending, setOnlyPending] = useState(false);
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  /** 低频区（日期 + 两个筛选开关）默认收起，收起时只留一行摘要 */
  const [filtersOpen, setFiltersOpen] = useState(false);
  /** 同步说明默认藏进 ? 里，不占首屏 */
  const [helpOpen, setHelpOpen] = useState(false);
  const helpRef = useRef<HTMLDivElement>(null);
  /** 触摸设备上被点亮的那一行（删除键随之显形）；鼠标设备靠 hover，用不到 */
  const [revealedId, setRevealedId] = useState<string | null>(null);
  const canHover = useHoverCapable();

  const today = todayIso();

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

  /**
   * 同步说明的收起：点面板以外任何位置、或按 Esc 都关掉。
   * 只靠按钮再点一次太别扭，触摸设备尤其容易「打开后关不掉」。
   */
  useEffect(() => {
    if (!helpOpen) return;
    const onPointerDown = (e: PointerEvent) => {
      if (!helpRef.current?.contains(e.target as Node)) setHelpOpen(false);
    };
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") setHelpOpen(false);
    };
    document.addEventListener("pointerdown", onPointerDown);
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("pointerdown", onPointerDown);
      document.removeEventListener("keydown", onKeyDown);
    };
  }, [helpOpen]);

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
    if (!text || !filterDate) return;
    mutate((todos) => {
      (todos[filterDate] ??= []).push({
        id: newId(),
        text,
        done: false,
        updatedAt: Date.now(),
      });
    }, filterDate === today ? "已添加" : `已添加到 ${filterDate}`);
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
  const allDays = useMemo(() => {
    const todos = snapshot?.todos ?? {};
    return Object.keys(todos)
      .sort()
      .reverse()
      .map((date) => {
        const all = (todos[date] ?? []).filter((item) => !item.deleted);
        // 未完成排在已完成上面；组内保持原顺序（Array.sort 是稳定的）
        const visible = (onlyPending ? all.filter((item) => !item.done) : all)
          .slice()
          .sort((a, b) => Number(a.done) - Number(b.done));
        const done = all.filter((item) => item.done).length;
        // pending 用整天的数据算（不受只看未完成影响），决定要不要显示「顺延」
        return { date, items: visible, total: all.length, done, pending: all.length - done };
      })
      .filter((day) => day.total > 0 && day.items.length > 0);
  }, [snapshot, onlyPending]);

  const days = useMemo(
    () => (allDates ? allDays : allDays.filter((day) => day.date === filterDate)),
    [allDays, allDates, filterDate],
  );

  /** 被日期筛选挡住的其它日期，用来给出「还有多少没显示」的出口 */
  const hidden = useMemo(() => {
    if (allDates) return { days: 0, items: 0 };
    const rest = allDays.filter((day) => day.date !== filterDate);
    return { days: rest.length, items: rest.reduce((n, day) => n + day.items.length, 0) };
  }, [allDays, allDates, filterDate]);

  const toggleCollapse = (date: string) =>
    setCollapsed((cur) => {
      const next = new Set(cur);
      if (next.has(date)) next.delete(date);
      else next.add(date);
      return next;
    });

  const emptyText = onlyPending
    ? "没有未完成的待办"
    : hidden.days > 0
      ? filterDate === today
        ? "今天没有待办"
        : `${filterDate} 没有待办`
      : "还没有待办";

  /**
   * 筛选状态直接写进「筛选」按钮的文字里（收起也能看见当前范围），
   * 不再单独摆一个统计标签——那会让人分不清它属于列表还是筛选。
   */
  const filterLabel = [
    allDates ? "全部日期" : filterDate === today ? "" : filterDate.slice(5),
    onlyPending ? "未完成" : "",
  ]
    .filter(Boolean)
    .join(" · ");

  /** 日期与落点一起复位：视图回到今天，新待办也落回今天 */
  const resetDate = () => {
    setFilterDate(today);
    setAllDates(false);
  };

  if (loading) {
    return (
      <div className="panel">
        <div className="empty">加载中…</div>
      </div>
    );
  }

  return (
    <div className="panel">
      <div className="todo-head">
        <h2 className="todo-title">待办事项</h2>
        <div className={helpOpen ? "todo-help todo-help-open" : "todo-help"} ref={helpRef}>
          <button
            type="button"
            className="todo-help-btn"
            aria-expanded={helpOpen}
            aria-label="同步说明"
            title="同步说明"
            onClick={() => setHelpOpen((v) => !v)}
          >
            ?
          </button>
          <p className="todo-help-text">
            改动会同步到 Obsidian；两端同时修改时按条目合并（后改的赢）。双击待办文字可编辑，
            悬停（手机点一下）该行会出现删除键。
          </p>
        </div>
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
          <div className="todo-actions">
            <div className="todo-add">
              <input
                className="todo-new-input"
                value={newText}
                placeholder="添加待办"
                disabled={saving}
                onChange={(e) => setNewText(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter" && !e.nativeEvent.isComposing) addTodo();
                }}
              />
              <button className="primary" disabled={saving || !newText.trim()} onClick={addTodo}>
                添加
              </button>
            </div>

            <button
              type="button"
              className={filtersOpen ? "todo-filter-toggle open" : "todo-filter-toggle"}
              aria-expanded={filtersOpen}
              onClick={() => setFiltersOpen((v) => !v)}
            >
              <FunnelIcon />
              <span>筛选{filterLabel && ` · ${filterLabel}`}</span>
              <span className="todo-caret">{filtersOpen ? "▴" : "▾"}</span>
            </button>

            {filtersOpen && (
              <div className="todo-filters">
                <label className={allDates ? "todo-field off" : "todo-field"}>
                  <span>日期</span>
                  <input
                    type="date"
                    value={filterDate}
                    disabled={saving || allDates}
                    onChange={(e) => setFilterDate(e.target.value || today)}
                  />
                </label>
                <label className="todo-filter">
                  <input
                    type="checkbox"
                    checked={allDates}
                    onChange={(e) => {
                      const on = e.target.checked;
                      setAllDates(on);
                      // 关掉日期筛选时把日期一并复位，不留「看不见的落点」
                      if (on) setFilterDate(today);
                    }}
                  />
                  全部日期
                </label>
                <label className="todo-filter">
                  <input
                    type="checkbox"
                    checked={onlyPending}
                    onChange={(e) => setOnlyPending(e.target.checked)}
                  />
                  只看未完成
                </label>
                {(filterDate !== today || allDates) && (
                  <button type="button" className="todo-reset" onClick={resetDate}>
                    回到今天
                  </button>
                )}
              </div>
            )}
          </div>

          {days.length === 0 ? (
            <div className="empty">{emptyText}</div>
          ) : (
            <div className="todo-days">
              {days.map(({ date, items, total, done, pending }) => {
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
                      <span className="todo-progress" aria-label={`${done}/${total} 已完成`}>
                        <span className="todo-progress-track">
                          <span
                            className="todo-progress-fill"
                            style={{ width: `${Math.round((done / total) * 100)}%` }}
                          />
                        </span>
                        <span className="todo-progress-text">
                          {done}/{total}
                        </span>
                      </span>
                      {pending > 0 && (
                        <button
                          className="todo-carryover"
                          disabled={saving}
                          onClick={() => carryOver(date)}
                          title="把该日未完成的待办挪到今天（已是今天则挪到明天）"
                        >
                          顺延
                        </button>
                      )}
                    </div>
                    {!isCollapsed && (
                      <ul className="todo-list">
                        {items.map((item) => (
                          <li
                            key={item.id}
                            className={[
                              "todo-item",
                              item.done ? "done" : "",
                              revealedId === item.id ? "revealed" : "",
                            ]
                              .filter(Boolean)
                              .join(" ")}
                            // 触摸设备没有 hover：点这一行把它自己的删除键亮出来
                            onClick={
                              canHover
                                ? undefined
                                : () => setRevealedId((cur) => (cur === item.id ? null : item.id))
                            }
                          >
                            <button
                              className={item.done ? "todo-box checked" : "todo-box"}
                              disabled={saving}
                              onClick={(e) => {
                                e.stopPropagation(); // 别把「点亮删除键」也一起触发
                                toggleTodo(date, item.id);
                              }}
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
                              onClick={(e) => {
                                e.stopPropagation();
                                removeTodo(date, item.id, item.text);
                              }}
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

          {hidden.days > 0 && (
            <div className="todo-hidden">
              <span>
                另有 {hidden.days} 天（{hidden.items} 项）已隐藏
              </span>
              <button className="todo-hidden-show" onClick={() => setAllDates(true)}>
                显示全部
              </button>
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

/** 漏斗图标：用描边画法（实心在 11px 下会糊成一个三角形）；跟着按钮文字颜色走 */
function FunnelIcon() {
  return (
    <svg
      viewBox="0 0 24 24"
      width="13"
      height="13"
      fill="none"
      stroke="currentColor"
      strokeWidth="2.2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      <polygon points="22 3 2 3 10 12.46 10 19 14 21 14 12.46 22 3" />
    </svg>
  );
}

/**
 * 指针是否带悬停能力。触摸设备没有 hover，删除键只能靠「点一下该行」显形，
 * 因此这里要跟着媒体查询走（系统外接鼠标后也能实时切换）。
 */
function useHoverCapable(): boolean {
  const query = "(hover: hover) and (pointer: fine)";
  const [canHover, setCanHover] = useState(() => window.matchMedia(query).matches);

  useEffect(() => {
    const mq = window.matchMedia(query);
    const onChange = () => setCanHover(mq.matches);
    mq.addEventListener("change", onChange);
    return () => mq.removeEventListener("change", onChange);
  }, []);

  return canHover;
}

/** 与后端 DTO 一一对应（仅前端用到的字段） */

export interface TokenPair {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
}

export interface VaultInfo {
  id: number;
  name: string;
  version: number;
  createdAt: string;
}

/** 当前用户资料（/api/v1/me 响应）。昵称与邮箱未设置时为 null */
export interface UserInfo {
  id: number;
  username: string;
  nickName: string | null;
  email: string | null;
  /** USER / ADMIN；前端据此决定是否显示管理入口 */
  role: string;
  createdAt: string;
}

/** 管理员视角的用户（比自己的资料多出角色、状态与统计） */
export interface AdminUser {
  id: number;
  username: string;
  nickName: string | null;
  email: string | null;
  role: string;
  /** 1=正常，0=已禁用 */
  status: number;
  createdAt: string;
  vaultCount: number;
  recordCount: number;
}

/** 管理员视角的审计日志（比自己的多一个 username） */
export interface AdminAuditLog {
  id: number;
  userId: number | null;
  username: string | null;
  vaultId: number | null;
  action: string;
  detail: string;
  ip: string;
  createdAt: string;
}

export interface DailyRecord {
  id: number;
  path: string;
  content: string;
  version: number;
  updatedAt: string;
}

export interface DateCount {
  date: string;
  count: number;
}

/** 周记元数据（日历周记列打点用；正文按 path 另取） */
export interface WeeklyRecord {
  week: string;
  path: string;
  updatedAt: string;
}

/**
 * 待办条目。与插件 todos.ts 的 TodoItem、云端快照保持一致：
 * id 用于双向同步时按条目对齐，updatedAt 决定取哪一侧的版本，
 * deleted 是墓碑（删除不物理移除，否则另一侧合并会把它当新增复活）。
 */
export interface TodoItem {
  id: string;
  text: string;
  done: boolean;
  updatedAt: number;
  deleted?: boolean;
}

/** 待办快照（云端 daily-sync-todos.json 的完整内容） */
export interface TodoSnapshot {
  version: number;
  updatedAt: string;
  todos: Record<string, TodoItem[]>;
}

/** 审计日志条目（M5）：action 为事件类型字符串，中文标签在 AuditLogs 里映射 */
export interface AuditLog {
  id: number;
  userId: number | null;
  vaultId: number | null;
  action: string;
  detail: string;
  ip: string;
  createdAt: string;
}

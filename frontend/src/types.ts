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

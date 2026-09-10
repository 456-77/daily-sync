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

export interface TokenInfo {
  id: number;
  name: string;
  status: number;
  lastUsedAt: string | null;
  createdAt: string;
}

export interface TokenCreated {
  id: number;
  name: string;
  /** 明文令牌只在签发响应出现一次 */
  token: string;
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

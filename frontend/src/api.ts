import type { TokenPair } from "./types";

/**
 * API 客户端：自动带 accessToken；收到 401 时先尝试用 refreshToken 换新
 * （一次性轮换），刷新失败则清空登录态并广播 ds-logout 事件。
 */

const AUTH_KEY = "daily-sync-auth";

interface AuthState {
  accessToken: string;
  refreshToken: string;
  username: string;
}

let auth: AuthState | null = loadAuth();

function loadAuth(): AuthState | null {
  try {
    const parsed = JSON.parse(localStorage.getItem(AUTH_KEY) ?? "null");
    if (parsed && typeof parsed.accessToken === "string") return parsed;
  } catch {
    // 损坏的存储当未登录处理
  }
  return null;
}

export function getAuth(): AuthState | null {
  return auth;
}

export function setAuth(next: AuthState | null): void {
  auth = next;
  if (next) localStorage.setItem(AUTH_KEY, JSON.stringify(next));
  else localStorage.removeItem(AUTH_KEY);
}

/** 登录成功后保存令牌对与用户名 */
export function saveLogin(pair: TokenPair, username: string): void {
  setAuth({ accessToken: pair.accessToken, refreshToken: pair.refreshToken, username });
}

export class ApiError extends Error {
  constructor(
    public code: number,
    message: string,
  ) {
    super(message);
  }
}

async function tryRefresh(): Promise<boolean> {
  if (!auth) return false;
  try {
    const res = await fetch("/api/v1/auth/refresh", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ refreshToken: auth.refreshToken }),
    });
    const json = await res.json();
    if (!res.ok || json.code !== 0) return false;
    setAuth({ ...json.data, username: auth.username });
    return true;
  } catch {
    return false;
  }
}

async function request<T>(method: string, path: string, body?: unknown, allowRefresh = true): Promise<T> {
  const headers: Record<string, string> = {};
  if (auth) headers.Authorization = `Bearer ${auth.accessToken}`;
  if (body !== undefined) headers["Content-Type"] = "application/json";
  const res = await fetch(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const json = await res.json().catch(() => undefined);

  if (res.status === 401 && allowRefresh && auth && !path.startsWith("/api/v1/auth/")) {
    if (await tryRefresh()) return request<T>(method, path, body, false);
    setAuth(null);
    window.dispatchEvent(new Event("ds-logout"));
    throw new ApiError(401, "登录已过期，请重新登录");
  }
  if (!res.ok || !json || json.code !== 0) {
    throw new ApiError(res.status, json?.message ?? `请求失败（HTTP ${res.status}）`);
  }
  return json.data as T;
}

export const api = {
  get: <T>(path: string) => request<T>("GET", path),
  post: <T>(path: string, body?: unknown) => request<T>("POST", path, body),
  del: <T>(path: string) => request<T>("DELETE", path),
};

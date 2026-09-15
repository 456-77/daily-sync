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

/** 单次 fetch（带令牌）。401 且允许续期时刷新令牌后重试一次，仍失败则登出 */
async function rawFetch(method: string, path: string, body: unknown, allowRefresh = true): Promise<Response> {
  const headers: Record<string, string> = {};
  if (auth) headers.Authorization = `Bearer ${auth.accessToken}`;
  if (body !== undefined) headers["Content-Type"] = "application/json";
  const res = await fetch(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });

  if (res.status === 401 && allowRefresh && auth && !path.startsWith("/api/v1/auth/")) {
    if (await tryRefresh()) return rawFetch(method, path, body, false);
    setAuth(null);
    window.dispatchEvent(new Event("ds-logout"));
    throw new ApiError(401, "登录已过期，请重新登录");
  }
  return res;
}

/** JSON 接口：统一解 ApiResponse 信封 */
async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await rawFetch(method, path, body);
  const json = await res.json().catch(() => undefined);
  if (!res.ok || !json || json.code !== 0) {
    throw new ApiError(res.status, json?.message ?? `请求失败（HTTP ${res.status}）`);
  }
  return json.data as T;
}

/**
 * 二进制接口（附件图片 / PDF）。
 *
 * 必须走带 Authorization 的 fetch 再转 objectURL：`<img src>` 发不出自定义请求头，
 * 把接口地址直接塞进 src 只会拿到 401。错误响应仍是 JSON 信封，这里尽力读出服务端消息
 * （如「附件不存在」），让占位能显示有用的原因而不是一句"加载失败"。
 */
async function requestBlob(path: string): Promise<Blob> {
  const res = await rawFetch("GET", path, undefined);
  if (!res.ok) {
    const json = await res.json().catch(() => undefined);
    throw new ApiError(res.status, json?.message ?? `附件加载失败（HTTP ${res.status}）`);
  }
  return res.blob();
}

export const api = {
  get: <T>(path: string) => request<T>("GET", path),
  post: <T>(path: string, body?: unknown) => request<T>("POST", path, body),
  del: <T>(path: string) => request<T>("DELETE", path),
  blob: (path: string) => requestBlob(path),
};

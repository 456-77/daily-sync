package com.dailysync.auth;

/**
 * 当前请求的登录用户信息（JWT 拦截器写入，请求结束必须 clear，
 * 防止 Tomcat 线程池复用把上一个用户串到下一个请求）。
 */
public final class UserContext {
    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> USERNAME = new ThreadLocal<>();

    private UserContext() {}

    public static void set(Long userId, String username) {
        USER_ID.set(userId);
        USERNAME.set(username);
    }

    public static Long userId() {
        return USER_ID.get();
    }

    public static String username() {
        return USERNAME.get();
    }

    /** 请求结束后必须清理，防止线程池复用导致串号 */
    public static void clear() {
        USER_ID.remove();
        USERNAME.remove();
    }
}

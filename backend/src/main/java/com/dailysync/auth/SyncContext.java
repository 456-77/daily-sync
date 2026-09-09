package com.dailysync.auth;

public final class SyncContext {
    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<Long> VAULT_ID = new ThreadLocal<>();

    private SyncContext() {}

    public static void set(Long userId, Long vaultId) {
        USER_ID.set(userId);
        VAULT_ID.set(vaultId);
    }

    public static Long userId() {
        return USER_ID.get();
    }

    public static Long vaultId() {
        return VAULT_ID.get();
    }

    /** 请求结束后必须清理，防止线程池复用导致串号 */
    public static void clear() {
        USER_ID.remove();
        VAULT_ID.remove();
    }
}

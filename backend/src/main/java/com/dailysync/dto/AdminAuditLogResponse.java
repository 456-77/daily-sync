package com.dailysync.dto;

import java.time.LocalDateTime;

/**
 * 管理员视角的审计日志：比用户看自己的多一个 username。
 * 日志可能属于已被删除的用户，那时的记录已随用户一起清理，
 * 但仍保留 username 为 null 的兜底（例如无法归属身份的事件）。
 */
public record AdminAuditLogResponse(Long id, Long userId, String username, Long vaultId,
                                    String action, String detail, String ip, LocalDateTime createdAt) {
}

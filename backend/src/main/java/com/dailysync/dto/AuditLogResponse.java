package com.dailysync.dto;

import java.time.LocalDateTime;

/**
 * 审计日志条目（查询响应）。action 为事件类型字符串（LOGIN_SUCCESS 等），
 * 中文标签由前端映射；detail 不含敏感值。
 */
public record AuditLogResponse(Long id, Long userId, Long vaultId, String action,
                               String detail, String ip, LocalDateTime createdAt) {
}

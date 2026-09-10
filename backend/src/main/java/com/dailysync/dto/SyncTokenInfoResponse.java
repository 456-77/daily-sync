package com.dailysync.dto;

import java.time.LocalDateTime;

/**
 * 同步令牌信息（列表项，不含明文）。
 *
 * @param status     1=正常，0=已撤销
 * @param lastUsedAt 最近一次成功调用时间（观察设备活跃度）
 */
public record SyncTokenInfoResponse(Long id, String name, int status,
                                    LocalDateTime lastUsedAt, LocalDateTime createdAt) {
}

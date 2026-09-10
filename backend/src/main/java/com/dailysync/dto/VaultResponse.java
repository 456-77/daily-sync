package com.dailysync.dto;

import java.time.LocalDateTime;

/**
 * 仓库信息。
 *
 * @param version 仓库当前版本号（拉取游标），客户端首次全量拉取用 since=0
 */
public record VaultResponse(Long id, String name, long version, LocalDateTime createdAt) {
}

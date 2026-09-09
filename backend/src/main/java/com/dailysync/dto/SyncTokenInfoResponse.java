package com.dailysync.dto;

import java.time.LocalDateTime;

public record SyncTokenInfoResponse(Long id, String name, int status,
                                    LocalDateTime lastUsedAt, LocalDateTime createdAt) {
}

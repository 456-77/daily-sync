package com.dailysync.dto;

import java.time.LocalDateTime;

public record SyncTokenCreatedResponse(Long id, String name, String token, LocalDateTime createdAt) {
}

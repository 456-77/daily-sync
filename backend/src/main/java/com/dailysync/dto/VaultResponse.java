package com.dailysync.dto;

import java.time.LocalDateTime;

public record VaultResponse(Long id, String name, long version, LocalDateTime createdAt) {
}

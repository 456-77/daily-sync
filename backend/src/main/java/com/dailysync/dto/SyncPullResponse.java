package com.dailysync.dto;

import java.time.LocalDateTime;
import java.util.List;

public record SyncPullResponse(long vaultVersion, boolean hasMore, List<Record> records) {

    public record Record(String path, String content, boolean deleted, long version,
                         LocalDateTime updatedAt) {
    }
}

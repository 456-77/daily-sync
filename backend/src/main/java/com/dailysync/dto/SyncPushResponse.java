package com.dailysync.dto;

import java.util.List;

public record SyncPushResponse(long vaultVersion, List<ItemResult> results) {

    public record ItemResult(String path, String status) {
    }
}

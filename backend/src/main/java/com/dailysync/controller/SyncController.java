package com.dailysync.controller;

import com.dailysync.auth.SyncContext;
import com.dailysync.common.ApiResponse;
import com.dailysync.dto.SyncPullResponse;
import com.dailysync.dto.SyncPushRequest;
import com.dailysync.dto.SyncPushResponse;
import com.dailysync.service.SyncService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/sync")
@RequiredArgsConstructor
public class SyncController {

    private final SyncService syncService;

    @PostMapping
    public ApiResponse<SyncPushResponse> push(@Valid @RequestBody SyncPushRequest req) {
        return ApiResponse.ok(syncService.push(SyncContext.vaultId(), req));
    }

    @GetMapping
    public ApiResponse<SyncPullResponse> pull(@RequestParam(name = "since", defaultValue = "0") long since,
                                              @RequestParam(name = "limit", defaultValue = "200") int limit) {
        int clamped = Math.min(Math.max(limit, 1), 500);
        return ApiResponse.ok(syncService.pull(SyncContext.vaultId(), since, clamped));
    }
}

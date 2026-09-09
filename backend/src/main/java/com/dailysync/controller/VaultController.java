package com.dailysync.controller;

import com.dailysync.auth.UserContext;
import com.dailysync.common.ApiResponse;
import com.dailysync.dto.CreateSyncTokenRequest;
import com.dailysync.dto.CreateVaultRequest;
import com.dailysync.dto.SyncTokenCreatedResponse;
import com.dailysync.dto.SyncTokenInfoResponse;
import com.dailysync.dto.VaultResponse;
import com.dailysync.service.VaultService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/vaults")
@RequiredArgsConstructor
public class VaultController {

    private final VaultService vaultService;

    @PostMapping
    public ApiResponse<VaultResponse> create(@Valid @RequestBody CreateVaultRequest req) {
        return ApiResponse.ok(vaultService.create(UserContext.userId(), req));
    }

    @GetMapping
    public ApiResponse<List<VaultResponse>> list() {
        return ApiResponse.ok(vaultService.list(UserContext.userId()));
    }

    @PostMapping("/{id}/tokens")
    public ApiResponse<SyncTokenCreatedResponse> issueToken(@PathVariable Long id,
                                                            @Valid @RequestBody CreateSyncTokenRequest req) {
        return ApiResponse.ok(vaultService.issueToken(UserContext.userId(), id, req));
    }

    @GetMapping("/{id}/tokens")
    public ApiResponse<List<SyncTokenInfoResponse>> listTokens(@PathVariable Long id) {
        return ApiResponse.ok(vaultService.listTokens(UserContext.userId(), id));
    }

    @DeleteMapping("/{id}/tokens/{tokenId}")
    public ApiResponse<Void> revokeToken(@PathVariable Long id, @PathVariable Long tokenId) {
        vaultService.revokeToken(UserContext.userId(), id, tokenId);
        return ApiResponse.ok(null);
    }
}

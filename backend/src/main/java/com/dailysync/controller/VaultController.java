package com.dailysync.controller;

import com.dailysync.auth.UserContext;
import com.dailysync.common.ApiResponse;
import com.dailysync.common.ClientIp;
import com.dailysync.dto.CreateSyncTokenRequest;
import com.dailysync.dto.CreateVaultRequest;
import com.dailysync.dto.SyncTokenCreatedResponse;
import com.dailysync.dto.SyncTokenInfoResponse;
import com.dailysync.dto.VaultResponse;
import com.dailysync.service.VaultService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 仓库与同步令牌管理接口（JWT 保护）。
 *
 * <p>仓库（vault）= 一个 Obsidian 库，记录按仓库隔离；同一用户可建多个仓库
 * （如「个人库」「工作库」），名称在用户内唯一。
 * 同步令牌（dst_ 前缀）绑定仓库供插件调用 {@link SyncController}，
 * 明文只在签发响应里出现一次，库里只存哈希。
 */
@RestController
@RequestMapping("/api/v1/vaults")
@RequiredArgsConstructor
public class VaultController {

    private final VaultService vaultService;
    private final ClientIp clientIp;

    /**
     * 创建仓库。新仓库 version=0（拉取游标起点）。
     *
     * <p>错误：400 仓库名为空或超 64 字符；409 同名仓库已存在。
     */
    @PostMapping
    public ApiResponse<VaultResponse> create(@Valid @RequestBody CreateVaultRequest req,
                                             HttpServletRequest request) {
        return ApiResponse.ok(vaultService.create(UserContext.userId(), req, clientIp.of(request)));
    }

    /** 列出当前用户的所有仓库（按 id 升序），含各自最新 version。 */
    @GetMapping
    public ApiResponse<List<VaultResponse>> list() {
        return ApiResponse.ok(vaultService.list(UserContext.userId()));
    }

    /**
     * 为指定仓库签发同步令牌。name 为备注（如设备名「我的电脑」），最长 64 字符，可空。
     *
     * <p>{@code data.token} 是明文令牌的唯一一次返回（dst_ + 64 位十六进制，共 68 字符），
     * 客户端必须立即保存；之后任何接口都无法再次获取明文。
     * 一个仓库可签多枚（一台设备一枚），撤销单枚不影响其他设备。
     *
     * <p>错误：404 仓库不存在（含不属于当前用户的情况，不区分两者）。
     */
    @PostMapping("/{id}/tokens")
    public ApiResponse<SyncTokenCreatedResponse> issueToken(@PathVariable Long id,
                                                            @Valid @RequestBody CreateSyncTokenRequest req,
                                                            HttpServletRequest request) {
        return ApiResponse.ok(vaultService.issueToken(UserContext.userId(), id, req, clientIp.of(request)));
    }

    /**
     * 列出指定仓库的所有令牌（按 id 升序），含 status（1 正常 / 0 已撤销）与 lastUsedAt，
     * 用于观察哪些设备还在活跃。不含明文。
     *
     * <p>错误：404 仓库不存在。
     */
    @GetMapping("/{id}/tokens")
    public ApiResponse<List<SyncTokenInfoResponse>> listTokens(@PathVariable Long id) {
        return ApiResponse.ok(vaultService.listTokens(UserContext.userId(), id));
    }

    /**
     * 撤销令牌，立即生效（该设备下次同步收到 401）。不可恢复，需要则重新签发。
     *
     * <p>错误：404 仓库或令牌不存在。
     */
    @DeleteMapping("/{id}/tokens/{tokenId}")
    public ApiResponse<Void> revokeToken(@PathVariable Long id, @PathVariable Long tokenId,
                                         HttpServletRequest request) {
        vaultService.revokeToken(UserContext.userId(), id, tokenId, clientIp.of(request));
        return ApiResponse.ok(null);
    }
}

package com.dailysync.controller;

import com.dailysync.auth.UserContext;
import com.dailysync.common.ApiResponse;
import com.dailysync.dto.VaultResponse;
import com.dailysync.service.VaultService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 仓库查询接口（JWT 保护）。M5.1 起仓库只能由插件同步按 Obsidian 仓库名自动创建
 * （见 {@link SyncController}），这里只提供列表查询，没有创建/删除入口；
 * 同步令牌体系已随本版本退役（插件改用账号密码登录换 accessToken）。
 */
@RestController
@RequestMapping("/api/v1/vaults")
@RequiredArgsConstructor
public class VaultController {

    private final VaultService vaultService;

    /** 列出当前用户的所有仓库（按 id 升序），含各自最新 version（拉取游标）。 */
    @GetMapping
    public ApiResponse<List<VaultResponse>> list() {
        return ApiResponse.ok(vaultService.list(UserContext.userId()));
    }
}

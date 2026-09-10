package com.dailysync.controller;

import com.dailysync.auth.UserContext;
import com.dailysync.common.ApiResponse;
import com.dailysync.dto.SyncPullResponse;
import com.dailysync.dto.SyncPushRequest;
import com.dailysync.dto.SyncPushResponse;
import com.dailysync.service.SyncService;
import com.dailysync.service.VaultService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 数据同步接口（JWT 保护，与 Web 端同一套 accessToken）。
 *
 * <p>插件用账号密码调 {@link AuthController} 的 login 换取 accessToken（refreshToken
 * 30 天轮换续期），此后每个同步请求携带 {@code Authorization: Bearer <token>}。
 * 目标仓库由查询参数 {@code vault} 指定（插件填 Obsidian 仓库名），
 * 该用户下不存在时<b>自动创建</b>——仓库只能由同步产生，Web 端不提供手动创建。
 */
@RestController
@RequestMapping("/api/v1/sync")
@RequiredArgsConstructor
public class SyncController {

    private final SyncService syncService;
    private final VaultService vaultService;

    /**
     * 批量推送记录（幂等）。
     *
     * <p>限制：单批 ≤200 条；单条 content ≤1MB；path ≤255 且必须以 .md 结尾、
     * 为库内相对路径（不以 / 开头、无反斜杠、无 . / .. 段）；同一路径单批只能出现一次。
     * {@code deleted=true} 为墓碑删除（content 可省），重推同路径内容即复活。
     *
     * <p>语义：整批逐条与云端比对内容哈希，全都没变则不推进仓库版本（no-op），
     * 响应中对应条目 status=unchanged；有实际变更才 version+1 并返回 status=updated。
     * 客户端据此可安全重试（网络重发不会产生重复变更）。
     *
     * <p>错误：400 参数校验失败 / 路径不合法 / 单批出现重复路径 / vault 缺失或超 64 字符。
     */
    @PostMapping
    public ApiResponse<SyncPushResponse> push(@RequestParam("vault") String vault,
                                              @Valid @RequestBody SyncPushRequest req) {
        Long vaultId = vaultService.findOrCreateVault(UserContext.userId(), vault).getId();
        return ApiResponse.ok(syncService.push(vaultId, req));
    }

    /**
     * 增量拉取：返回 version 大于 since 的记录（按 version、id 升序）。
     *
     * <p>参数：vault 为目标仓库名（不存在时自动创建并返回空列表）；
     * since 为上次已同步到的仓库版本号（首次全量拉取传 0）；
     * limit 自动收敛到 1~500（默认 200）。删除的记录以墓碑形式下发（deleted=true）。
     *
     * <p>翻页规则：hasMore=false 时下次 since 用响应里的 vaultVersion；
     * hasMore=true 时下次 since 用最后一条记录的 version-1，客户端按 path 去重
     * （会重复收到上页尾部，属预期）。单批推送上限 200 ≤ 拉取 limit 上限 500，
     * 同一版本组必然一页装下，不会死循环。
     */
    @GetMapping
    public ApiResponse<SyncPullResponse> pull(@RequestParam("vault") String vault,
                                              @RequestParam(name = "since", defaultValue = "0") long since,
                                              @RequestParam(name = "limit", defaultValue = "200") int limit) {
        Long vaultId = vaultService.findOrCreateVault(UserContext.userId(), vault).getId();
        int clamped = Math.min(Math.max(limit, 1), 500);
        return ApiResponse.ok(syncService.pull(vaultId, since, clamped));
    }
}

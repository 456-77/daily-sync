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

/**
 * 数据同步接口（同步令牌鉴权，不走 JWT）。
 *
 * <p>请求头携带 {@code X-Sync-Token: dst_...}（在 {@link VaultController} 签发），
 * 令牌绑定的仓库即本次同步的目标仓库。无效 / 已撤销令牌统一返回
 * 401「同步令牌无效或已撤销」（不区分原因，防探测）。
 */
@RestController
@RequestMapping("/api/v1/sync")
@RequiredArgsConstructor
public class SyncController {

    private final SyncService syncService;

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
     * <p>错误：400 参数校验失败 / 路径不合法 / 单批出现重复路径。
     */
    @PostMapping
    public ApiResponse<SyncPushResponse> push(@Valid @RequestBody SyncPushRequest req) {
        return ApiResponse.ok(syncService.push(SyncContext.vaultId(), req));
    }

    /**
     * 增量拉取：返回 version 大于 since 的记录（按 version、id 升序）。
     *
     * <p>参数：since 为上次已同步到的仓库版本号（首次全量拉取传 0）；
     * limit 自动收敛到 1~500（默认 200）。删除的记录以墓碑形式下发（deleted=true）。
     *
     * <p>翻页规则：hasMore=false 时下次 since 用响应里的 vaultVersion；
     * hasMore=true 时下次 since 用最后一条记录的 version-1，客户端按 path 去重
     * （会重复收到上页尾部，属预期）。单批推送上限 200 ≤ 拉取 limit 上限 500，
     * 同一版本组必然一页装下，不会死循环。
     */
    @GetMapping
    public ApiResponse<SyncPullResponse> pull(@RequestParam(name = "since", defaultValue = "0") long since,
                                              @RequestParam(name = "limit", defaultValue = "200") int limit) {
        int clamped = Math.min(Math.max(limit, 1), 500);
        return ApiResponse.ok(syncService.pull(SyncContext.vaultId(), since, clamped));
    }
}

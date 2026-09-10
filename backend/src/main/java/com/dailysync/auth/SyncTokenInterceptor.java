package com.dailysync.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dailysync.common.ApiResponse;
import com.dailysync.common.ClientIp;
import com.dailysync.common.HashUtil;
import com.dailysync.entity.SyncToken;
import com.dailysync.entity.User;
import com.dailysync.entity.Vault;
import com.dailysync.mapper.SyncTokenMapper;
import com.dailysync.mapper.UserMapper;
import com.dailysync.mapper.VaultMapper;
import com.dailysync.service.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/**
 * 同步令牌拦截器：保护 /api/v1/sync（不走 JWT）。
 * 校验 X-Sync-Token 头（哈希查表），顺带确认所属仓库存在、账号未禁用，
 * 成功后把 userId/vaultId 放入 {@link SyncContext} 并刷新 last_used_at。
 * 三次查询（令牌→仓库→账号）在个人规模下无所谓，正确性优先。
 *
 * <p>审计（M5）：只有「查得到这枚令牌」的失败才记 SYNC_AUTH_FAIL（已撤销令牌被复用、
 * 仓库已删、账号被禁——都是令牌主人该警惕的信号，且能归属到 user_id）。
 * 乱猜的随机令牌查表即空，属纯噪音：限流器已经挡住频率，不写审计防刷库。
 */
@Component
@RequiredArgsConstructor
public class SyncTokenInterceptor implements HandlerInterceptor {

    private final SyncTokenMapper syncTokenMapper;
    private final VaultMapper vaultMapper;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;
    private final ClientIp clientIp;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        String token = request.getHeader("X-Sync-Token");
        // 各种失败一律同一句话，不给探测者区分"令牌不存在/已撤销"的机会
        if (token == null || token.isBlank()) {
            return reject(response);
        }
        String ip = clientIp.of(request);
        SyncToken stored = syncTokenMapper.selectOne(Wrappers.<SyncToken>lambdaQuery()
                .eq(SyncToken::getTokenHash, HashUtil.sha256Hex(token)));
        if (stored == null || stored.getStatus() != 1) {
            if (stored != null) {
                // 多查一次仓库只为把审计归属到主人：该路径只在异常时走到，代价可忽略
                Vault owner = vaultMapper.selectById(stored.getVaultId());
                auditSyncFail(owner == null ? null : owner.getUserId(), stored.getVaultId(),
                        "已撤销的令牌仍被使用（令牌 #" + stored.getId() + "）", ip);
            }
            return reject(response);
        }
        Vault vault = vaultMapper.selectById(stored.getVaultId());
        if (vault == null) {
            auditSyncFail(null, stored.getVaultId(), "令牌 #" + stored.getId() + " 对应的仓库不存在", ip);
            return reject(response);
        }
        User user = userMapper.selectById(vault.getUserId());
        if (user == null || user.getStatus() != 1) {
            auditSyncFail(vault.getUserId(), vault.getId(), "令牌 #" + stored.getId() + " 的账号被禁用或已删除", ip);
            return reject(response);
        }
        SyncContext.set(user.getId(), vault.getId());
        syncTokenMapper.update(null, Wrappers.<SyncToken>lambdaUpdate()
                .eq(SyncToken::getId, stored.getId())
                .set(SyncToken::getLastUsedAt, LocalDateTime.now()));
        return true;
    }

    /** SYNC_AUTH_FAIL 的便捷封装：账号已删时 userId 传 null，此类行仅库内可见。 */
    private void auditSyncFail(Long userId, Long vaultId, String detail, String ip) {
        auditService.record(userId, vaultId, AuditService.Action.SYNC_AUTH_FAIL, detail, ip);
    }

    private boolean reject(HttpServletResponse response) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        byte[] body = objectMapper.writeValueAsBytes(ApiResponse.error(401, "同步令牌无效或已撤销"));
        response.getOutputStream().write(body);
        response.setContentLength(body.length);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        return false;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        SyncContext.clear();
    }
}

package com.dailysync.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dailysync.common.ApiResponse;
import com.dailysync.common.HashUtil;
import com.dailysync.entity.SyncToken;
import com.dailysync.entity.User;
import com.dailysync.entity.Vault;
import com.dailysync.mapper.SyncTokenMapper;
import com.dailysync.mapper.UserMapper;
import com.dailysync.mapper.VaultMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class SyncTokenInterceptor implements HandlerInterceptor {

    private final SyncTokenMapper syncTokenMapper;
    private final VaultMapper vaultMapper;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;

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
        SyncToken stored = syncTokenMapper.selectOne(Wrappers.<SyncToken>lambdaQuery()
                .eq(SyncToken::getTokenHash, HashUtil.sha256Hex(token)));
        if (stored == null || stored.getStatus() != 1) {
            return reject(response);
        }
        Vault vault = vaultMapper.selectById(stored.getVaultId());
        if (vault == null) {
            return reject(response);
        }
        User user = userMapper.selectById(vault.getUserId());
        if (user == null || user.getStatus() != 1) {
            return reject(response);
        }
        SyncContext.set(user.getId(), vault.getId());
        syncTokenMapper.update(null, Wrappers.<SyncToken>lambdaUpdate()
                .eq(SyncToken::getId, stored.getId())
                .set(SyncToken::getLastUsedAt, LocalDateTime.now()));
        return true;
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

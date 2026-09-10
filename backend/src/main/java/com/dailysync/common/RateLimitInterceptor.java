package com.dailysync.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * 限流拦截器：挂在两个最容易被刷的入口——
 *
 * <ul>
 *   <li>/api/v1/auth/**（登录/注册）：按客户端 IP，防密码暴力破解（默认 10 次/分钟）。
 *       插件登录也走这里，多设备同 IP 下 10 次/分钟仍绰绰有余</li>
 *   <li>/api/v1/sync：按客户端 IP（默认 120 次/分钟）。M5.1 起同步走 JWT，
 *       无效 JWT 在 AuthInterceptor 就被纯 HMAC 校验拒掉（不查库），
 *       这里只为保护后续的仓库解析与同步查询不被洪水打穿</li>
 * </ul>
 *
 * <p>超限返回 429 + Retry-After 头。注册顺序在鉴权拦截器之前（见
 * {@link com.dailysync.config.WebMvcConfig}），被拒请求不会打到数据库。
 */
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiter rateLimiter;
    private final ClientIp clientIp;
    private final ObjectMapper objectMapper;

    /** 总开关：本机联调想关闭时设 RATE_LIMIT_ENABLED=false */
    @Value("${daily-sync.rate-limit.enabled:true}")
    private boolean enabled;

    @Value("${daily-sync.rate-limit.auth-per-minute:10}")
    private int authPerMinute;

    @Value("${daily-sync.rate-limit.sync-per-minute:120}")
    private int syncPerMinute;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!enabled || !(handler instanceof HandlerMethod)) {
            return true;
        }
        String path = request.getRequestURI();
        boolean authApi = path.startsWith("/api/v1/auth/");
        String key = (authApi ? "auth:" : "sync:") + clientIp.of(request);
        int limit = authApi ? authPerMinute : syncPerMinute;

        int retryAfterSec = rateLimiter.tryAcquire(key, limit, 60);
        if (retryAfterSec == 0) {
            return true;
        }
        response.setStatus(429);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSec));
        byte[] body = objectMapper.writeValueAsBytes(ApiResponse.error(429, "请求过于频繁，请稍后再试"));
        response.getOutputStream().write(body);
        response.setContentLength(body.length);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        return false;
    }
}

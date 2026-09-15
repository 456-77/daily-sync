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
import java.util.regex.Pattern;

/**
 * 限流拦截器：挂在三个最容易被刷的入口，各按客户端 IP 分桶——
 *
 * <ul>
 *   <li>/api/v1/auth/**（登录/注册）：防密码暴力破解（默认 10 次/分钟）。
 *       插件登录也走这里，多设备同 IP 下 10 次/分钟仍绰绰有余</li>
 *   <li>/api/v1/sync：正文同步（默认 120 次/分钟）。M5.1 起同步走 JWT，
 *       无效 JWT 在 AuthInterceptor 就被纯 HMAC 校验拒掉（不查库），
 *       这里只为保护后续的仓库解析与同步查询不被洪水打穿</li>
 *   <li>附件上传/下载（默认 600 次/分钟）：单独一桶。附件是一项一个请求，
 *       若与正文共用 120/分钟的桶，一台新设备首次同步几十张图就会把桶打满，
 *       随后真正的正文推送反而被 429 挡下——同步失败的原因还很难看出来</li>
 * </ul>
 *
 * <p>超限返回 429 + Retry-After 头。注册顺序在鉴权拦截器之前（见
 * {@link com.dailysync.config.WebMvcConfig}），被拒请求不会打到数据库。
 */
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    /** 网页端的附件下载入口 /api/v1/vaults/{id}/attachments（{id} 是数字主键） */
    private static final Pattern VAULT_ATTACHMENT = Pattern.compile("^/api/v1/vaults/\\d+/attachments$");

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

    @Value("${daily-sync.rate-limit.attachment-per-minute:600}")
    private int attachmentPerMinute;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!enabled || !(handler instanceof HandlerMethod)) {
            return true;
        }
        String path = request.getRequestURI();
        boolean authApi = path.startsWith("/api/v1/auth/");
        boolean attachmentApi = path.startsWith("/api/v1/sync/attachments") || VAULT_ATTACHMENT.matcher(path).matches();
        String bucket = authApi ? "auth:" : attachmentApi ? "att:" : "sync:";
        int limit = authApi ? authPerMinute : attachmentApi ? attachmentPerMinute : syncPerMinute;

        int retryAfterSec = rateLimiter.tryAcquire(bucket + clientIp.of(request), limit, 60);
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

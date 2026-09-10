package com.dailysync.config;

import com.dailysync.auth.AuthInterceptor;
import com.dailysync.auth.SyncTokenInterceptor;
import com.dailysync.common.RateLimitInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 拦截器挂载（按注册顺序执行）：
 *
 * <ol>
 *   <li>RateLimitInterceptor —— /api/v1/auth/**（按 IP）与 /api/v1/sync（按令牌哈希）
 *       的限流，排在最前，被拒请求不再消耗数据库；</li>
 *   <li>AuthInterceptor —— /api/v1/** 默认走 JWT，仅排除两处：
 *       /api/v1/auth/**（登录注册，无鉴权）与 /api/v1/sync（改走 X-Sync-Token）；</li>
 *   <li>SyncTokenInterceptor —— 接管 /api/v1/sync 的同步令牌校验。</li>
 * </ol>
 *
 * 后两个拦截器都会在请求结束后清理 ThreadLocal 上下文。
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;
    private final AuthInterceptor authInterceptor;
    private final SyncTokenInterceptor syncTokenInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/v1/auth/**")
                .addPathPatterns("/api/v1/sync");
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns("/api/v1/auth/**")
                // 同步接口走 X-Sync-Token 鉴权，不走 JWT
                .excludePathPatterns("/api/v1/sync");
        registry.addInterceptor(syncTokenInterceptor)
                .addPathPatterns("/api/v1/sync");
    }
}

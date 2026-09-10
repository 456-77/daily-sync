package com.dailysync.config;

import com.dailysync.auth.AuthInterceptor;
import com.dailysync.auth.SyncTokenInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 拦截器挂载：/api/v1/** 默认走 JWT（AuthInterceptor），仅排除两处——
 * /api/v1/auth/**（登录注册，无鉴权）与 /api/v1/sync（改走 X-Sync-Token，
 * 由 SyncTokenInterceptor 接管）。两个拦截器都会在请求结束后清理 ThreadLocal 上下文。
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final SyncTokenInterceptor syncTokenInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns("/api/v1/auth/**")
                // 同步接口走 X-Sync-Token 鉴权，不走 JWT
                .excludePathPatterns("/api/v1/sync");
        registry.addInterceptor(syncTokenInterceptor)
                .addPathPatterns("/api/v1/sync");
    }
}

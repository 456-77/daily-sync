package com.dailysync.config;

import com.dailysync.auth.AuthInterceptor;
import com.dailysync.common.RateLimitInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 拦截器挂载（按注册顺序执行）：
 *
 * <ol>
 *   <li>RateLimitInterceptor —— /api/v1/auth/**（按 IP）与 /api/v1/sync（按 IP）
 *       的限流，排在最前，被拒请求不再消耗数据库；</li>
 *   <li>AuthInterceptor —— /api/v1/** 全部走 JWT（含 /api/v1/sync：M5.1 起插件
 *       用账号密码登录换 accessToken，不再有独立的同步令牌），仅排除
 *       /api/v1/auth/**（登录注册，无鉴权）。请求结束清理 ThreadLocal 上下文。</li>
 * </ol>
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;
    private final AuthInterceptor authInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/v1/auth/**")
                .addPathPatterns("/api/v1/sync");
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns("/api/v1/auth/**");
    }
}

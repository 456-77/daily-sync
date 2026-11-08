package com.dailysync.config;

import com.dailysync.auth.AuthInterceptor;
import com.dailysync.common.RateLimitInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 拦截器挂载（按注册顺序执行）：
 *
 * <ol>
 *   <li>RateLimitInterceptor —— /api/v1/auth/**（按 IP）、/api/v1/sync（按 IP）
 *       以及附件上传下载（/api/v1/sync/attachments、/api/v1/vaults/*&#47;attachments，
 *       网页端文件列表 /api/v1/vaults/*&#47;files 与缩略图是同一页面的流量，同归附件桶；
 *       M9 起安装包下载 /api/v1/downloads/file 同为大文件入口，也归附件桶），
 *       排在最前，被拒请求不再消耗数据库。
 *       注意路径模式是**精确匹配**（不是前缀），所以附件这几个入口必须显式列出，
 *       否则它们完全不受限流保护；</li>
 *   <li>AuthInterceptor —— /api/v1/** 全部走 JWT（含 /api/v1/sync：M5.1 起插件
 *       用账号密码登录换 accessToken，不再有独立的同步令牌），仅排除
 *       /api/v1/auth/**（登录注册，无鉴权）与 /api/v1/downloads/**（M9 安装包
 *       分发：门户页与更新检查都是匿名场景，文件本身也在 GitHub 公开存在）。
 *       请求结束清理 ThreadLocal 上下文。</li>
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
                .addPathPatterns("/api/v1/sync")
                .addPathPatterns("/api/v1/sync/attachments")
                .addPathPatterns("/api/v1/vaults/*/attachments")
                .addPathPatterns("/api/v1/vaults/*/files")
                .addPathPatterns("/api/v1/downloads/file");
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns("/api/v1/auth/**")
                .excludePathPatterns("/api/v1/downloads/**");
    }

    /**
     * M9：站点根让给 quick-note 门户页，网页端 SPA 挪到 /app 子路径。
     * Spring Boot 的 welcome page 只对根路径自动补 index.html，子目录必须
     * 显式转发；/app（无尾斜杠）先重定向到 /app/ 保持地址规范。
     *
     * <p>M11：OmoFun 存档页同样需要显式转发（/omofun/ → static/omofun/index.html）；
     * 页面本身是公开静态资源，数据接口 /api/v1/omofun/** 由 AuthInterceptor 要求 ADMIN。
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/app", "/app/");
        registry.addViewController("/app/").setViewName("forward:/app/index.html");
        registry.addRedirectViewController("/omofun", "/omofun/");
        registry.addViewController("/omofun/").setViewName("forward:/omofun/index.html");
    }
}

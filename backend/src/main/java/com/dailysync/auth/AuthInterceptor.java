package com.dailysync.auth;

import com.dailysync.common.ApiResponse;
import com.dailysync.entity.User;
import com.dailysync.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * JWT 鉴权拦截器：保护 /api/v1/**（auth 除外，见 {@link com.dailysync.config.WebMvcConfig}）。
 *
 * <p>验签通过后**再查一次库**，而不是只信令牌里的信息：一是让「禁用账号」立即生效
 * （无状态 JWT 一旦签发就管不了，否则要等 accessToken 自然过期才有反应），
 * 二是取到当前角色用于管理员接口的鉴权。users 表很小且走主键，这点开销可忽略。
 *
 * <p>访问控制：/api/v1/admin/** 需要 ADMIN 角色，其余接口只要求账号正常。
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private static final String ADMIN_PREFIX = "/api/v1/admin/";
    private static final String ROLE_ADMIN = "ADMIN";

    private final JwtUtil jwtUtil;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // 放行非 Controller 请求（静态资源、异常转发等）
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return reject(response, 401, "未登录或令牌已失效");
        }

        Long userId;
        try {
            Claims claims = jwtUtil.parse(header.substring(7));
            userId = Long.valueOf(claims.getSubject());
        } catch (JwtException | IllegalArgumentException e) {
            return reject(response, 401, "未登录或令牌已失效");
        }

        User user = userMapper.selectById(userId);
        if (user == null) {
            return reject(response, 401, "未登录或令牌已失效");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            return reject(response, 403, "账号已被禁用");
        }
        UserContext.set(user.getId(), user.getUsername(), user.getRole());

        if (isAdminPath(request) && !ROLE_ADMIN.equals(user.getRole())) {
            return reject(response, 403, "需要管理员权限");
        }
        return true;
    }

    /** 管理员路径用 URI 前缀判定：比引入自定义注解 + 反射解析轻得多，够用 */
    private boolean isAdminPath(HttpServletRequest request) {
        return request.getRequestURI().startsWith(ADMIN_PREFIX);
    }

    private boolean reject(HttpServletResponse response, int code, String message) throws Exception {
        response.setStatus(code);
        response.setContentType("application/json;charset=UTF-8");
        byte[] body = objectMapper.writeValueAsBytes(ApiResponse.error(code, message));
        response.getOutputStream().write(body);
        response.setContentLength(body.length);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        return false;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }
}

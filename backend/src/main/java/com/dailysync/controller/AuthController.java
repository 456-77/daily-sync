package com.dailysync.controller;

import com.dailysync.common.ApiResponse;
import com.dailysync.common.ClientIp;
import com.dailysync.dto.*;
import com.dailysync.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 认证接口（无需登录）。IP 会随请求传入服务层，用于审计日志与限流。
 *
 * <p>签发的 accessToken（JWT，默认 120 分钟有效）用于调用 /api/v1/** 下的用户接口，
 * 通过 {@code Authorization: Bearer <token>} 头携带；refreshToken 用于到期前换取新令牌对，
 * 一次性使用（轮换，用旧换新后旧 refresh 立即作废）。
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final ClientIp clientIp;

    /**
     * 注册新用户并直接返回令牌对（注册即登录）。
     *
     * <p>用户名 3-32 位（字母/数字/下划线），密码 8-64 位。
     * 服务端配置了非空 invite-code 时必须携带正确的邀请码，否则开放注册。
     *
     * <p>错误：400 参数校验失败 / 邀请码错误；409 用户名已存在。
     */
    @PostMapping("/register")
    public ApiResponse<TokenResponse> register(@Valid @RequestBody RegisterRequest req,
                                               HttpServletRequest request) {
        return ApiResponse.ok(authService.register(req, clientIp.of(request)));
    }

    /**
     * 登录，返回 accessToken / refreshToken / expiresIn（秒）。
     * 成败都会记审计日志（失败含尝试的用户名，便于发现暴力破解）。
     *
     * <p>错误：400 参数校验失败；401 用户名或密码错误。
     */
    @PostMapping("/login")
    public ApiResponse<TokenResponse> login(@Valid @RequestBody LoginRequest req,
                                            HttpServletRequest request) {
        return ApiResponse.ok(authService.login(req, clientIp.of(request)));
    }

    /**
     * 用 refreshToken 换取新令牌对（轮换：旧 refresh 一次性作废）。
     * 客户端应在 accessToken 过期前（或收到 401 后）调用本接口续期。
     *
     * <p>错误：401 refresh token 无效或已过期（需重新登录）。
     */
    @PostMapping("/refresh")
    public ApiResponse<TokenResponse> refresh(@Valid @RequestBody RefreshRequest req) {
        return ApiResponse.ok(authService.refresh(req));
    }

    /**
     * 门户免登录票据（M9）：凭据校验与登录完全同口径（同话术、同审计），通过即
     * 签发一张一次性、2 分钟有效的票据。quick-note 用本机保存的账号密码调它，
     * 再把票据拼进门户页 URL 的 #fragment（fragment 不进服务器日志）带浏览器
     * 完成免登录——浏览器侧由门户页 JS 调 {@link #exchangeTicket} 换正式令牌。
     *
     * <p>错误：401 用户名或密码错误；403 账号被禁用。
     */
    @PostMapping("/portal-ticket")
    public ApiResponse<String> portalTicket(@Valid @RequestBody LoginRequest req,
                                            HttpServletRequest request) {
        return ApiResponse.ok(authService.issuePortalTicket(req, clientIp.of(request)));
    }

    /**
     * 用门户票据换正式令牌对（一次性，票据 2 分钟内有效）。门户页 JS 在浏览器里
     * 调用，成功后把响应按登录态同款结构写进 localStorage 完成免登录。
     *
     * <p>错误：401 票据无效 / 已过期 / 已使用（统一一句话，不区分原因）。
     */
    @PostMapping("/exchange-ticket")
    public ApiResponse<PortalSessionResponse> exchangeTicket(@Valid @RequestBody TicketRequest req) {
        return ApiResponse.ok(authService.exchangeTicket(req));
    }
}

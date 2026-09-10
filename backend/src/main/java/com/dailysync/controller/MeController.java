package com.dailysync.controller;

import com.dailysync.auth.UserContext;
import com.dailysync.common.ApiResponse;
import com.dailysync.common.ClientIp;
import com.dailysync.dto.ChangePasswordRequest;
import com.dailysync.dto.UpdateProfileRequest;
import com.dailysync.dto.UserInfoResponse;
import com.dailysync.service.ProfileService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 当前登录用户（JWT 保护）：资料查询与修改、修改密码。
 * 客户端也用 GET /api/v1/me 验证令牌是否仍然有效。
 */
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
public class MeController {

    private final ProfileService profileService;
    private final ClientIp clientIp;

    /** 当前用户资料（未设置的昵称/邮箱为 null）。错误：401 用户不存在。 */
    @GetMapping
    public ApiResponse<UserInfoResponse> me() {
        return ApiResponse.ok(profileService.getProfile(UserContext.userId()));
    }

    /**
     * 更新资料（用户名 / 昵称 / 邮箱），昵称与邮箱留空即清空。
     *
     * <p>错误：400 参数校验失败；409 用户名或邮箱已被占用；401 用户不存在。
     */
    @PostMapping("/profile")
    public ApiResponse<UserInfoResponse> updateProfile(@Valid @RequestBody UpdateProfileRequest req,
                                                       HttpServletRequest request) {
        return ApiResponse.ok(profileService.updateProfile(
                UserContext.userId(), req, clientIp.of(request)));
    }

    /**
     * 修改密码。成功后该用户全部 refresh token 作废，所有设备都需要重新登录。
     *
     * <p>错误：400 原密码不正确 / 新旧密码相同 / 新密码不合法；401 用户不存在。
     */
    @PostMapping("/password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest req,
                                            HttpServletRequest request) {
        profileService.changePassword(UserContext.userId(), req, clientIp.of(request));
        return ApiResponse.ok(null);
    }
}

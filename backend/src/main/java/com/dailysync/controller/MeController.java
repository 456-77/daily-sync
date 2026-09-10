package com.dailysync.controller;

import com.dailysync.auth.UserContext;
import com.dailysync.common.ApiResponse;
import com.dailysync.common.BizException;
import com.dailysync.dto.UserInfoResponse;
import com.dailysync.entity.User;
import com.dailysync.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前登录用户信息接口（JWT 保护），用于客户端验证令牌有效性 / 展示用户名。
 */
@RestController
@RequiredArgsConstructor
public class MeController {

    private final UserMapper userMapper;

    /** 返回当前 accessToken 对应用户的 id、用户名、注册时间。错误：401 用户不存在（已被删除）。 */
    @GetMapping("/api/v1/me")
    public ApiResponse<UserInfoResponse> me() {
        User user = userMapper.selectById(UserContext.userId());
        if (user == null) {
            throw new BizException(HttpStatus.UNAUTHORIZED, "用户不存在");
        }
        return ApiResponse.ok(new UserInfoResponse(user.getId(), user.getUsername(), user.getCreatedAt()));
    }
}

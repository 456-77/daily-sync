package com.dailysync.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
/** 登录/注册/刷新的令牌对响应。 */
public class TokenResponse {
    private String accessToken;
    private String refreshToken;
    /** access token 有效期（秒），前端用它提前刷新 */
    private long expiresIn;
}

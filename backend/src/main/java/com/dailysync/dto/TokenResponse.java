package com.dailysync.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class TokenResponse {
    private String accessToken;
    private String refreshToken;
    /** access token 有效期（秒），前端用它提前刷新 */
    private long expiresIn;
}

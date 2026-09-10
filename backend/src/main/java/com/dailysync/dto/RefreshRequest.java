package com.dailysync.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
/** 刷新令牌请求。 */
public class RefreshRequest {
    @NotBlank
    private String refreshToken;
}

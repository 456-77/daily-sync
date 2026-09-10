package com.dailysync.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
/** 登录请求。 */
public class LoginRequest {
    @NotBlank
    private String username;

    @NotBlank
    private String password;
}

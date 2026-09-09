package com.dailysync.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {
    @NotBlank
    @Pattern(regexp = "^[a-zA-Z0-9_]{3,32}$", message = "用户名只能包含字母、数字、下划线，长度 3-32")
    private String username;

    @NotBlank
    @Size(min = 8, max = 64, message = "密码长度需要 8-64 位")
    private String password;

    /** 邀请码：服务端配置了邀请码时必填 */
    @Size(max = 64)
    private String inviteCode;
}

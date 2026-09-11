package com.dailysync.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 管理员重置用户密码请求（不需要原密码）。 */
@Data
public class ResetPasswordRequest {

    @NotBlank
    @Size(min = 8, max = 64, message = "密码长度需要 8-64 位")
    private String newPassword;
}

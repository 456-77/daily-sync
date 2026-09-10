package com.dailysync.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 修改密码请求。新密码规则与注册一致（8-64 位）。 */
@Data
public class ChangePasswordRequest {

    @NotBlank
    private String oldPassword;

    @NotBlank
    @Size(min = 8, max = 64, message = "密码长度需要 8-64 位")
    private String newPassword;
}

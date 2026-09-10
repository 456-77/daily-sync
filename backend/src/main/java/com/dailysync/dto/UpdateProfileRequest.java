package com.dailysync.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新个人资料请求。用户名必填且沿用注册时的规则；
 * 昵称与邮箱留空表示清空（服务端统一存 NULL）。
 */
@Data
public class UpdateProfileRequest {

    @NotBlank
    @Pattern(regexp = "^[a-zA-Z0-9_]{3,32}$", message = "用户名只能包含字母、数字、下划线，长度 3-32")
    private String username;

    @Size(max = 32, message = "昵称最长 32 个字符")
    private String nickName;

    @Email(message = "邮箱格式不正确")
    @Size(max = 128, message = "邮箱最长 128 个字符")
    private String email;
}

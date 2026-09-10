package com.dailysync.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

/** 当前用户信息（/api/v1/me 系列接口响应）。昵称与邮箱未设置时为 null。 */
@Data
@AllArgsConstructor
public class UserInfoResponse {
    private Long id;
    private String username;
    private String nickName;
    private String email;
    private LocalDateTime createdAt;
}

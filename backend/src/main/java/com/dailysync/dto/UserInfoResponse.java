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
    /** USER / ADMIN；前端据此决定是否显示管理入口，真正的拦截在服务端 */
    private String role;
    private LocalDateTime createdAt;
}

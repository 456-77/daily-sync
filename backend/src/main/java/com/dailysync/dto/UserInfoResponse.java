package com.dailysync.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
/** 当前用户信息（/api/v1/me 响应）。 */
public class UserInfoResponse {
    private Long id;
    private String username;
    private LocalDateTime createdAt;
}

package com.dailysync.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class UserInfoResponse {
    private Long id;
    private String username;
    private LocalDateTime createdAt;
}

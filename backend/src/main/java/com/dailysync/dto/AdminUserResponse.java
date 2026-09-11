package com.dailysync.dto;

import java.time.LocalDateTime;

/** 管理员视角的用户信息（比自己的资料多出角色、状态与仓库/记录统计）。 */
public record AdminUserResponse(Long id, String username, String nickName, String email,
                                String role, Integer status, LocalDateTime createdAt,
                                long vaultCount, long recordCount) {
}

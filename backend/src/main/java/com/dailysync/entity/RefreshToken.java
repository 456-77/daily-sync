package com.dailysync.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 刷新令牌（refresh_tokens 表）：用于换取新令牌对，一次性（轮换）——
 * 每次刷新成功即删除旧行，被盗用的旧 token 立即失效。
 */
@Data
@TableName("refresh_tokens")
public class RefreshToken {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    /** 明文 token 的 SHA-256（十六进制），不存明文 */
    private String tokenHash;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
}

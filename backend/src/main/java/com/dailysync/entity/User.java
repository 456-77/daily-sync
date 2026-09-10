package com.dailysync.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 用户（users 表）。 */
@Data
@TableName("users")
public class User {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    /** BCrypt 哈希，不存明文 */
    private String passwordHash;
    /** 1=正常，0=已禁用（登录与令牌校验都会拒绝） */
    private Integer status;
    private LocalDateTime createdAt;
}

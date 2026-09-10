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
    /** 昵称，仅用于展示；未设置时为 NULL */
    private String nickName;
    /** 邮箱；未设置时为 NULL，填了则要求全局唯一 */
    private String email;
    /** BCrypt 哈希，不存明文 */
    private String passwordHash;
    /** 1=正常，0=已禁用（登录与令牌校验都会拒绝） */
    private Integer status;
    private LocalDateTime createdAt;
}

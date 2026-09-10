package com.dailysync.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 仓库（vaults 表）：一个 Obsidian 库。
 * 记录按仓库隔离，同一用户可建多个仓库（用户内名称唯一）。
 */
@Data
@TableName("vaults")
public class Vault {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String name;
    /** 仓库级单调递增版本号，兼作拉取游标：推送产生实际变更时 +1，客户端以它做增量拉取 */
    private Long version;
    private LocalDateTime createdAt;
}

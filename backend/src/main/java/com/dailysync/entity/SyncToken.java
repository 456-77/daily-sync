package com.dailysync.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sync_tokens")
public class SyncToken {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long vaultId;
    private String name;
    private String tokenHash;
    private Integer status;
    private LocalDateTime lastUsedAt;
    private LocalDateTime createdAt;
}

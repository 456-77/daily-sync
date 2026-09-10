package com.dailysync.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 同步令牌（sync_tokens 表）：绑定一个仓库，供插件调用 /api/v1/sync。
 * 明文（dst_ + 64 位十六进制）只在签发响应里出现一次，库里只存 SHA-256 哈希。
 */
@Data
@TableName("sync_tokens")
public class SyncToken {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long vaultId;
    /** 备注名（一般填设备名，如「我的电脑」），纯展示用 */
    private String name;
    /** 明文令牌的 SHA-256（十六进制） */
    private String tokenHash;
    /** 1=正常，0=已撤销（校验时立即失效，不可恢复） */
    private Integer status;
    /** 最近一次成功调用时间，用于观察哪些设备还活跃 */
    private LocalDateTime lastUsedAt;
    private LocalDateTime createdAt;
}

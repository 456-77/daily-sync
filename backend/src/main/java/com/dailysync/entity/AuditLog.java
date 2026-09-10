package com.dailysync.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计日志（audit_logs 表）：安全相关事件流水，只增不改。
 * 记录「谁（user_id）在哪个仓库（vault_id）于何时从哪个 IP 做了什么（action + detail）」，
 * 不落任何令牌 / 密码的明文或哈希。
 */
@Data
@TableName("audit_logs")
public class AuditLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 操作者；登录失败等无法确认身份的事件为 NULL */
    private Long userId;
    /** 事件关联的仓库；与仓库无关的事件为 NULL */
    private Long vaultId;
    /** 事件类型（AuditService.Action 的 name()） */
    private String action;
    /** 人读摘要（≤255 字符，由服务端裁剪；不含敏感值） */
    private String detail;
    /** 发起方 IP（behind-proxy 开启时为 X-Forwarded-For 首段） */
    private String ip;
    private LocalDateTime createdAt;
}

package com.dailysync.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dailysync.dto.AuditLogResponse;
import com.dailysync.entity.AuditLog;
import com.dailysync.mapper.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 审计日志：安全相关事件的记录与查询。
 *
 * <p>记录是「尽力而为」——审计写入失败只打服务日志，绝不让它影响主业务
 * （登录不能因为记不了日志而失败）。事件类型见 {@link Action}，
 * 库里只存枚举 name()，前端负责翻译成中文标签。
 *
 * <p>查询按用户隔离（user_id = 当前登录用户），新→旧按 id 游标翻页。
 * 无法归属到用户的行（user_id 为 NULL）不出现在任何人的列表里，只能查库看。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    /** detail 列宽度，超长截断（VARCHAR(255)） */
    private static final int DETAIL_MAX = 255;

    /**
     * 事件类型；新增事件时在此追加（name 落库，label 仅便于读日志）。
     * M5.1 起同步令牌体系退役，TOKEN_ISSUE / TOKEN_REVOKE / SYNC_AUTH_FAIL
     * 不再产生新事件（同步鉴权失败即 JWT 过期，走通用 401，不审计）。
     */
    public enum Action {
        REGISTER("注册"),
        LOGIN_SUCCESS("登录成功"),
        LOGIN_FAIL("登录失败"),
        VAULT_CREATE("创建仓库"),
        PROFILE_UPDATE("更新资料"),
        PASSWORD_CHANGE("修改密码"),
        USER_STATUS_CHANGE("变更用户状态"),
        USER_RESET_PASSWORD("重置用户密码"),
        USER_DELETE("删除用户");

        public final String label;

        Action(String label) {
            this.label = label;
        }
    }

    private final AuditLogMapper auditLogMapper;

    /**
     * 记一条审计日志（异步语义上 best-effort：失败仅告警，不打断主流程）。
     *
     * @param userId 操作者，无法确认身份时传 null
     * @param vaultId 事件关联仓库，无关时传 null
     * @param detail  人读摘要；不含令牌/密码等敏感值，超长会被裁剪
     * @param ip      发起方 IP
     */
    public void record(Long userId, Long vaultId, Action action, String detail, String ip) {
        try {
            AuditLog row = new AuditLog();
            row.setUserId(userId);
            row.setVaultId(vaultId);
            row.setAction(action.name());
            row.setDetail(detail == null ? "" : detail.length() > DETAIL_MAX
                    ? detail.substring(0, DETAIL_MAX) : detail);
            row.setIp(ip == null ? "" : ip);
            // 时间戳由应用侧显式写入：云上 MySQL 容器时区是 UTC，依赖 DB 的
            // CURRENT_TIMESTAMP 默认值会存成 UTC，前端按本地读会差 8 小时
            row.setCreatedAt(LocalDateTime.now());
            auditLogMapper.insert(row);
        } catch (Exception e) {
            log.warn("审计日志写入失败 action={} detail={}", action, detail, e);
        }
    }

    /**
     * 查询当前用户的审计日志（新→旧）。
     *
     * @param beforeId 游标：只取 id 小于它的行（首页不传）；相比 OFFSET 翻页，
     *                 不断有新日志写入时不会重页漏页
     * @param limit    每页条数，收敛到 1~100（默认 50）
     */
    public List<AuditLogResponse> list(Long userId, Long beforeId, int limit) {
        int clamped = Math.min(Math.max(limit, 1), 100);
        return auditLogMapper.selectList(Wrappers.<AuditLog>lambdaQuery()
                        .eq(AuditLog::getUserId, userId)
                        .lt(beforeId != null, AuditLog::getId, beforeId)
                        .orderByDesc(AuditLog::getId)
                        .last("LIMIT " + clamped)).stream()
                .map(r -> new AuditLogResponse(r.getId(), r.getUserId(), r.getVaultId(),
                        r.getAction(), r.getDetail(), r.getIp(), r.getCreatedAt()))
                .toList();
    }
}

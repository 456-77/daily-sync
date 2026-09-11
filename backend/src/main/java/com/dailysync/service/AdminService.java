package com.dailysync.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dailysync.common.BizException;
import com.dailysync.dto.AdminAuditLogResponse;
import com.dailysync.dto.AdminUserResponse;
import com.dailysync.entity.AuditLog;
import com.dailysync.entity.DailyRecord;
import com.dailysync.entity.RefreshToken;
import com.dailysync.entity.User;
import com.dailysync.entity.Vault;
import com.dailysync.mapper.AuditLogMapper;
import com.dailysync.mapper.DailyRecordMapper;
import com.dailysync.mapper.RefreshTokenMapper;
import com.dailysync.mapper.UserMapper;
import com.dailysync.mapper.VaultMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 管理员功能：用户列表与用户管理、全量审计日志。
 *
 * <p>角色鉴权由 {@link com.dailysync.auth.AuthInterceptor} 在 /api/v1/admin/** 上统一完成，
 * 这里只做业务约束：目标用户必须存在，且**不允许对自己执行禁用/重置/删除**——
 * 否则管理员一个手滑就把自己锁在门外了。
 *
 * <p>所有写操作都会留下审计记录；删除用户时连同其审计一起清掉，
 * 避免日志里留下指向不存在用户的悬空记录。
 */
@Service
@RequiredArgsConstructor
public class AdminService {

    /** 管理员一页最多取多少条日志 */
    private static final int LOG_PAGE_MAX = 200;

    private final UserMapper userMapper;
    private final VaultMapper vaultMapper;
    private final DailyRecordMapper recordMapper;
    private final RefreshTokenMapper refreshTokenMapper;
    private final AuditLogMapper auditLogMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    /** 用户列表（含仓库数与记录数） */
    public List<AdminUserResponse> listUsers() {
        return userMapper.selectAllWithStats().stream()
                .map(this::toUserResponse)
                .toList();
    }

    /**
     * 启用/禁用用户。禁用后无需等令牌过期：拦截器每次请求都会查状态，
     * 下一个请求就会被 403 挡掉。
     */
    @Transactional
    public void updateStatus(Long operatorId, Long targetId, int status, String ip) {
        User target = requireTargetNotSelf(operatorId, targetId);
        target.setStatus(status);
        userMapper.updateById(target);
        auditService.record(operatorId, null, AuditService.Action.USER_STATUS_CHANGE,
                "目标用户: " + target.getUsername() + " -> " + (status == 1 ? "启用" : "禁用"), ip);
    }

    /** 重置密码：不需要原密码；改完作废该用户全部 refresh token，逼其重新登录。 */
    @Transactional
    public void resetPassword(Long operatorId, Long targetId, String newPassword, String ip) {
        User target = requireTargetNotSelf(operatorId, targetId);
        target.setPasswordHash(passwordEncoder.encode(newPassword));
        userMapper.updateById(target);
        refreshTokenMapper.delete(Wrappers.<RefreshToken>lambdaQuery()
                .eq(RefreshToken::getUserId, targetId));
        auditService.record(operatorId, null, AuditService.Action.USER_RESET_PASSWORD,
                "目标用户: " + target.getUsername(), ip);
    }

    /** 删除用户，连同其仓库、记录、登录态与审计日志一并清理。 */
    @Transactional
    public void deleteUser(Long operatorId, Long targetId, String ip) {
        User target = requireTargetNotSelf(operatorId, targetId);

        List<Long> vaultIds = vaultMapper.selectList(Wrappers.<Vault>lambdaQuery()
                        .eq(Vault::getUserId, targetId)).stream()
                .map(Vault::getId)
                .toList();
        if (!vaultIds.isEmpty()) {
            recordMapper.delete(Wrappers.<DailyRecord>lambdaQuery()
                    .in(DailyRecord::getVaultId, vaultIds));
            vaultMapper.delete(Wrappers.<Vault>lambdaQuery().eq(Vault::getUserId, targetId));
        }
        refreshTokenMapper.delete(Wrappers.<RefreshToken>lambdaQuery()
                .eq(RefreshToken::getUserId, targetId));
        auditLogMapper.delete(Wrappers.<AuditLog>lambdaQuery()
                .eq(AuditLog::getUserId, targetId));
        userMapper.deleteById(targetId);

        auditService.record(operatorId, null, AuditService.Action.USER_DELETE,
                "目标用户: " + target.getUsername() + "（id " + targetId + "）", ip);
    }

    /** 全量审计日志（新→旧），把 userId 翻成用户名便于阅读 */
    public List<AdminAuditLogResponse> listAllLogs(Long beforeId, int limit) {
        int clamped = Math.min(Math.max(limit, 1), LOG_PAGE_MAX);
        Map<Long, String> nameById = userMapper.selectList(null).stream()
                .collect(Collectors.toMap(User::getId, User::getUsername, (a, b) -> a));

        return auditLogMapper.selectList(Wrappers.<AuditLog>lambdaQuery()
                        .lt(beforeId != null, AuditLog::getId, beforeId)
                        .orderByDesc(AuditLog::getId)
                        .last("LIMIT " + clamped)).stream()
                .map(r -> new AdminAuditLogResponse(r.getId(), r.getUserId(),
                        r.getUserId() == null ? null : nameById.get(r.getUserId()),
                        r.getVaultId(), r.getAction(), r.getDetail(), r.getIp(), r.getCreatedAt()))
                .toList();
    }

    /** 目标用户必须存在，且不能是操作者自己 */
    private User requireTargetNotSelf(Long operatorId, Long targetId) {
        if (operatorId.equals(targetId)) {
            throw new BizException(HttpStatus.BAD_REQUEST, "不能对自己执行该操作");
        }
        User target = userMapper.selectById(targetId);
        if (target == null) {
            throw new BizException(HttpStatus.NOT_FOUND, "用户不存在");
        }
        return target;
    }

    /** 统计查询返回的是 Map（别名已写成 camelCase），这里手动落成 DTO */
    private AdminUserResponse toUserResponse(Map<String, Object> row) {
        return new AdminUserResponse(
                ((Number) row.get("id")).longValue(),
                (String) row.get("username"),
                (String) row.get("nickName"),
                (String) row.get("email"),
                (String) row.get("role"),
                row.get("status") == null ? null : ((Number) row.get("status")).intValue(),
                (LocalDateTime) row.get("createdAt"),
                ((Number) row.get("vaultCount")).longValue(),
                ((Number) row.get("recordCount")).longValue());
    }
}

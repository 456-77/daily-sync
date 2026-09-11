package com.dailysync.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dailysync.common.BizException;
import com.dailysync.dto.ChangePasswordRequest;
import com.dailysync.dto.UpdateProfileRequest;
import com.dailysync.dto.UserInfoResponse;
import com.dailysync.entity.RefreshToken;
import com.dailysync.entity.User;
import com.dailysync.mapper.RefreshTokenMapper;
import com.dailysync.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 个人资料与密码。
 *
 * <p>用户名与邮箱都要求全局唯一（邮箱允许多个 NULL，即未填写）；
 * 昵称无唯一性要求，仅用于展示。
 */
@Service
@RequiredArgsConstructor
public class ProfileService {

    private final UserMapper userMapper;
    private final RefreshTokenMapper refreshTokenMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    /** 当前用户资料 */
    public UserInfoResponse getProfile(Long userId) {
        return toResponse(requireUser(userId));
    }

    /**
     * 更新资料：用户名与邮箱做唯一性校验（排除自己），昵称/邮箱留空即清空。
     *
     * <p>错误：409 用户名或邮箱已被占用。
     */
    @Transactional
    public UserInfoResponse updateProfile(Long userId, UpdateProfileRequest req, String ip) {
        User user = requireUser(userId);
        String username = req.getUsername().trim();
        String nickName = trimToNull(req.getNickName());
        String email = trimToNull(req.getEmail());

        // 只有真的改了才占用一次查询；与自己相同视为 no-op
        if (!username.equals(user.getUsername())) {
            Long taken = userMapper.selectCount(Wrappers.<User>lambdaQuery()
                    .eq(User::getUsername, username)
                    .ne(User::getId, userId));
            if (taken != null && taken > 0) {
                throw new BizException(HttpStatus.CONFLICT, "用户名已被占用");
            }
        }
        if (email != null && !email.equals(user.getEmail())) {
            Long taken = userMapper.selectCount(Wrappers.<User>lambdaQuery()
                    .eq(User::getEmail, email)
                    .ne(User::getId, userId));
            if (taken != null && taken > 0) {
                throw new BizException(HttpStatus.CONFLICT, "邮箱已被占用");
            }
        }

        // 先更新内存对象（下面要拿它拼响应），再用 UpdateWrapper 显式写库：
        // MyBatis-Plus 的 updateById 默认忽略 null 字段，那样「把昵称/邮箱清空」
        // 会静默失败——响应体看着是 null，库里其实没改
        user.setUsername(username);
        user.setNickName(nickName);
        user.setEmail(email);
        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .eq(User::getId, userId)
                .set(User::getUsername, username)
                .set(User::getNickName, nickName)
                .set(User::getEmail, email));

        auditService.record(userId, null, AuditService.Action.PROFILE_UPDATE,
                "用户名: " + username, ip);
        return toResponse(user);
    }

    /**
     * 修改密码：校验原密码 → 换新哈希 → 作废该用户全部 refresh token。
     *
     * <p>为什么要作废全部：accessToken 是无状态 JWT，改密码并不会让它自动失效，
     * 只有把 refresh token 清掉，其他设备在 accessToken 到期后才无法续期。这里
     * 连当前设备一起作废，语义是「改完密码所有设备重新登录」，最干净也最安全。
     *
     * <p>原密码错误返回 400 而非 401：401 会触发前端的令牌刷新流程，
     * 那样「输错原密码」会被误判成登录过期，把人直接踢下线。
     */
    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest req, String ip) {
        User user = requireUser(userId);
        if (!passwordEncoder.matches(req.getOldPassword(), user.getPasswordHash())) {
            throw new BizException(HttpStatus.BAD_REQUEST, "原密码不正确");
        }
        if (req.getOldPassword().equals(req.getNewPassword())) {
            throw new BizException(HttpStatus.BAD_REQUEST, "新密码不能与原密码相同");
        }

        user.setPasswordHash(passwordEncoder.encode(req.getNewPassword()));
        userMapper.updateById(user);
        refreshTokenMapper.delete(Wrappers.<RefreshToken>lambdaQuery()
                .eq(RefreshToken::getUserId, userId));

        auditService.record(userId, null, AuditService.Action.PASSWORD_CHANGE, null, ip);
    }

    private User requireUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(HttpStatus.UNAUTHORIZED, "用户不存在");
        }
        return user;
    }

    private UserInfoResponse toResponse(User user) {
        return new UserInfoResponse(user.getId(), user.getUsername(), user.getNickName(),
                user.getEmail(), user.getRole(), user.getCreatedAt());
    }

    /** 空白一律按「未填」存 NULL，避免库里同时出现 '' 和 NULL 两种空值 */
    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

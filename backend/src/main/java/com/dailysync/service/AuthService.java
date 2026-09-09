package com.dailysync.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dailysync.auth.JwtUtil;
import com.dailysync.common.BizException;
import com.dailysync.dto.*;
import com.dailysync.entity.RefreshToken;
import com.dailysync.entity.User;
import com.dailysync.mapper.RefreshTokenMapper;
import com.dailysync.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final long REFRESH_TTL_DAYS = 30;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserMapper userMapper;
    private final RefreshTokenMapper refreshTokenMapper;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;

    @Value("${daily-sync.invite-code:}")
    private String inviteCode;

    @Transactional
    public TokenResponse register(RegisterRequest req) {
        if (!inviteCode.isBlank() && !inviteCode.equals(req.getInviteCode())) {
            throw new BizException(HttpStatus.BAD_REQUEST, "邀请码错误");
        }
        Long count = userMapper.selectCount(
                Wrappers.<User>lambdaQuery().eq(User::getUsername, req.getUsername()));
        if (count != null && count > 0) {
            throw new BizException(HttpStatus.CONFLICT, "用户名已被占用");
        }

        User user = new User();
        user.setUsername(req.getUsername());
        user.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        user.setStatus(1);
        userMapper.insert(user);

        return issueTokens(user);
    }

    public TokenResponse login(LoginRequest req) {
        User user = userMapper.selectOne(
                Wrappers.<User>lambdaQuery().eq(User::getUsername, req.getUsername()));
        // 用户不存在与密码错误返回同一句话，不给撞库者提示
        if (user == null || !passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            throw new BizException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        if (user.getStatus() != 1) {
            throw new BizException(HttpStatus.FORBIDDEN, "账号已被禁用");
        }
        return issueTokens(user);
    }

    /** 用 refresh token 换新令牌对；旧 refresh 一次性作废（轮换） */
    @Transactional
    public TokenResponse refresh(RefreshRequest req) {
        String hash = sha256Hex(req.getRefreshToken());
        RefreshToken stored = refreshTokenMapper.selectOne(
                Wrappers.<RefreshToken>lambdaQuery().eq(RefreshToken::getTokenHash, hash));
        if (stored == null || stored.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BizException(HttpStatus.UNAUTHORIZED, "refresh token 无效或已过期");
        }
        refreshTokenMapper.deleteById(stored.getId());

        User user = userMapper.selectById(stored.getUserId());
        if (user == null || user.getStatus() != 1) {
            throw new BizException(HttpStatus.UNAUTHORIZED, "账号不存在或已被禁用");
        }
        return issueTokens(user);
    }

    /** 签发 access + refresh：refresh 明文只出现这一次，库里只存哈希 */
    private TokenResponse issueTokens(User user) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String refreshToken = HexFormat.of().formatHex(bytes);

        RefreshToken entity = new RefreshToken();
        entity.setUserId(user.getId());
        entity.setTokenHash(sha256Hex(refreshToken));
        entity.setExpiresAt(LocalDateTime.now().plusDays(REFRESH_TTL_DAYS));
        refreshTokenMapper.insert(entity);

        return new TokenResponse(
                jwtUtil.createAccessToken(user.getId(), user.getUsername()),
                refreshToken,
                jwtUtil.accessTtlSeconds());
    }

    private String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}

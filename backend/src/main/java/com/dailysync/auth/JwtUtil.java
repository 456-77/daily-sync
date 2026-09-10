package com.dailysync.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * accessToken（JWT）签发与验签。HMAC-SHA 签名密钥来自配置 daily-sync.jwt-secret
 * （≥32 字节），有效期 daily-sync.jwt-access-ttl-minutes（默认 120 分钟）。
 */
@Component
public class JwtUtil {

    @Value("${daily-sync.jwt-secret}")
    private String secret;

    @Value("${daily-sync.jwt-access-ttl-minutes:120}")
    private long accessTtlMinutes;

    private SecretKey key() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String createAccessToken(Long userId, String username) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + accessTtlMinutes * 60_000);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key())
                .compact();
    }

    /** 解析并验签；过期或伪造会抛 JwtException，由拦截器统一转 401 */
    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key()).build().parseSignedClaims(token).getPayload();
    }

    public long accessTtlSeconds() {
        return accessTtlMinutes * 60;
    }
}

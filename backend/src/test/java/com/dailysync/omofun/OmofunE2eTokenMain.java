package com.dailysync.omofun;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Date;

/**
 * 本地 e2e 辅助：从真实库读一个可用 ADMIN，用与 JwtUtil 相同的密钥签一个 120 分钟
 * accessToken，避免为了测接口去猜/改用户密码。仅测试用，不打包进镜像。
 *
 * <p>用法（环境变量来自 daily-sync/.env）：
 * <pre>
 * DB_PASSWORD=... JWT_SECRET=... java -cp "classes;test-classes;deps" \
 *   com.dailysync.omofun.OmofunE2eTokenMain
 * </pre>
 */
public final class OmofunE2eTokenMain {

    private OmofunE2eTokenMain() {
    }

    public static void main(String[] args) throws Exception {
        String host = env("DB_HOST", "120.26.58.22");
        String port = env("DB_PORT", "3306");
        String user = env("DB_USER", "daily_sync");
        String password = System.getenv("DB_PASSWORD");
        String secret = System.getenv("JWT_SECRET");
        if (password == null || secret == null) {
            System.err.println("需要 DB_PASSWORD / JWT_SECRET 环境变量（source ../.env）");
            System.exit(2);
        }
        String url = "jdbc:mysql://" + host + ":" + port + "/daily_sync"
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai"
                + "&allowPublicKeyRetrieval=true&useSSL=false";
        try (Connection connection = DriverManager.getConnection(url, user, password);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, username, role FROM users "
                             + "WHERE role='ADMIN' AND status=1 ORDER BY id LIMIT 1");
             ResultSet rs = statement.executeQuery()) {
            if (!rs.next()) {
                System.err.println("没有可用的 ADMIN 用户");
                System.exit(3);
            }
            long id = rs.getLong("id");
            String username = rs.getString("username");
            Date now = new Date();
            String token = Jwts.builder()
                    .subject(String.valueOf(id))
                    .claim("username", username)
                    .issuedAt(now)
                    .expiration(new Date(now.getTime() + 120 * 60_000L))
                    .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
                    .compact();
            System.out.println("ADMIN id=" + id + " username=" + username);
            System.out.println(token);
        }
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}

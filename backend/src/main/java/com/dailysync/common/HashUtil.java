package com.dailysync.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * SHA-256 工具（十六进制输出，按 UTF-8 字节）。
 * 同步令牌、refresh token 的存储哈希与记录内容比对的幂等哈希都用它，
 * 插件端 sync.ts 的 sha256Hex 与此结果保持一致。
 */
public final class HashUtil {

    private HashUtil() {}

    public static String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}

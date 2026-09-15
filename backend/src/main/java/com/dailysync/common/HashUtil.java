package com.dailysync.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 工具（十六进制输出）。
 * 同步令牌、refresh token 的存储哈希、记录内容比对的幂等哈希，
 * 以及附件的磁盘文件名（内容寻址）都用它；
 * 插件端 sync.ts 的 sha256Hex 与此结果保持一致。
 */
public final class HashUtil {

    private HashUtil() {}

    public static String sha256Hex(String input) {
        return sha256Hex(input.getBytes(StandardCharsets.UTF_8));
    }

    /** 二进制内容（附件字节）的哈希。 */
    public static String sha256Hex(byte[] input) {
        MessageDigest digest = sha256Digest();
        digest.update(input);
        return hex(digest.digest());
    }

    /**
     * 取一个可增量喂数据的摘要器。
     * 附件边读流边 update，不能先把整个文件读进内存——容器堆只有约 290M。
     */
    public static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 摘要结果转十六进制小写串。 */
    public static String hex(byte[] digest) {
        return HexFormat.of().formatHex(digest);
    }
}
